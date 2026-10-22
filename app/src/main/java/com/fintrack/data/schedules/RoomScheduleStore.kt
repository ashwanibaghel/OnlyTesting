package com.fintrack.data.schedules

import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.ReserveBucket
import com.fintrack.domain.schedules.*
import com.fintrack.engine.*
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal class RoomScheduleStore(private val db: FinTrackDatabase, private val clock: Clock) {
    private val dao get() = db.scheduleDao()
    private fun m(value: Long) = Money(value, INR)
    private suspend fun today(book: String) = LocalDate.now(clock.withZone(db.planningDao().profile(book)?.timezone?.let(ZoneId::of) ?: clock.zone))
    private fun amount(draft: TransactionDraft): Money = when (draft) {
        is TransactionDraft.Income -> draft.amount
        is TransactionDraft.Expense -> draft.amount
        is TransactionDraft.Transfer -> draft.amount + draft.fee
    }

    suspend fun rules(book: String): List<ScheduledRule> {
        val exceptions = dao.exceptions(book).groupBy { it.ruleId }
        return dao.rules(book).map { ScheduledRule(it.id, it.name, TemplateCodec.decode(it.template),
            Recurrence(LocalDate.parse(it.startDate), Frequency.valueOf(it.frequency), it.interval,
                it.endDate?.let(LocalDate::parse), exceptions[it.id].orEmpty().map { entry -> LocalDate.parse(entry.date) }.toSet()),
            it.mandatory, it.stopDate?.let(LocalDate::parse)) }
    }

    private suspend fun resolved(book: String): Map<Pair<String, LocalDate>, ScheduleOccurrenceEntity> {
        val records = dao.occurrences(book).associateBy { it.id }.toMutableMap()
        for (event in dao.resolutions(book)) {
            val previous = records.getValue(event.occurrenceId)
            records[event.occurrenceId] = previous.copy(status = event.status,
                journalId = if (event.status == "CONFIRMED") event.journalId else null,
                confirmedTemplate = event.confirmedTemplate ?: previous.confirmedTemplate)
        }
        return records.values.associateBy { it.ruleId to LocalDate.parse(it.scheduledDate) }
    }

    private fun dates(rule: ScheduledRule, from: LocalDate, through: LocalDate): List<LocalDate> {
        val last = minOf(through, rule.stoppedFrom?.minusDays(1) ?: through)
        if (last < from || last < rule.recurrence.start) return emptyList()
        val result = mutableListOf<LocalDate>()
        var start = maxOf(from, rule.recurrence.start)
        require(ChronoUnit.DAYS.between(start, last) <= 36_600) { "Review recurring history in a range of at most 100 years" }
        while (start <= last) {
            val end = minOf(last, start.plusDays(minOf(3660, ChronoUnit.DAYS.between(start, last))))
            result += occurrences(rule.recurrence, start, end)
            if (end == last) break
            start = end.plusDays(1)
        }
        return result
    }

    suspend fun read(book: String, asOf: LocalDate, through: LocalDate, buckets: List<ReserveBucket>): ScheduleSnapshot {
        require(through >= asOf && ChronoUnit.DAYS.between(asOf, through) <= 3660) { "Choose a planning horizon up to ten years" }
        val rules = rules(book)
        val records = resolved(book)
        val liquidAccounts = db.ledgerDao().accounts(book).filter { it.kind != AccountKind.INVESTMENT.name }.map { it.id }.toSet()
        val pending = mutableListOf<ScheduledOccurrence>()
        val seen = mutableSetOf<Pair<String, LocalDate>>()
        fun add(rule: ScheduledRule, date: LocalDate, record: ScheduleOccurrenceEntity?) {
            if (!seen.add(rule.id to date)) return
            require(pending.size < 100_000) { "Too many unresolved planned occurrences; narrow the planning range or resolve old schedules" }
            val template = record?.confirmedTemplate?.let(TemplateCodec::decode) ?: rule.template
            val accountId = when (template) {
                is TransactionDraft.Income -> template.accountId
                is TransactionDraft.Expense -> template.accountId
                else -> error("Unsupported recurring template")
            }
            pending += ScheduledOccurrence("${rule.id}@$date", rule.id, rule.name, date, template,
                rule.mandatory, record?.let { OccurrenceStatus.valueOf(it.status) } ?: OccurrenceStatus.AWAITING_CONFIRMATION,
                record?.journalId, m(0), m(0), accountId in liquidAccounts)
        }
        for (rule in rules) {
            for (date in dates(rule, rule.recurrence.start, through)) {
                val record = records[rule.id to date]
                if (record != null && record.status != "REOPENED" && date < asOf) continue
                add(rule, date, record)
            }
        }
        val indexed = rules.associateBy { it.id }
        for ((key, record) in records) if (key.second <= through && (key.second >= asOf || record.status == "REOPENED")) {
            add(indexed.getValue(key.first), key.second, record)
        }
        val obligations = obligations(pending, buckets, asOf)
        val cover = coverObligations(obligations, buckets.associate { it.id to it.funded }, INR).associateBy { it.id }
        val byOccurrence = cover.values.groupBy { it.id.substringBefore(':') }
        val result = pending.sortedWith(compareBy<ScheduledOccurrence> { it.scheduledDate }.thenBy { it.key }).map {
            it.copy(covered = sumMoney(byOccurrence[it.key].orEmpty().map { value -> value.covered }, INR),
                uncovered = sumMoney(byOccurrence[it.key].orEmpty().map { value -> value.uncovered }, INR))
        }
        val revision = requireNotNull(db.ledgerDao().book(book)).revision
        return ScheduleSnapshot(revision, asOf, through, rules, result, sumMoney(cover.values.map { it.uncovered }, INR))
    }

    fun obligations(items: List<ScheduledOccurrence>, buckets: List<ReserveBucket>, asOf: LocalDate): List<Obligation> =
        items.filter { it.status in listOf(OccurrenceStatus.AWAITING_CONFIRMATION, OccurrenceStatus.REOPENED) &&
            it.affectsLiquidCash && it.mandatory && it.template is TransactionDraft.Expense }
            .flatMap { item ->
                val draft = item.template as TransactionDraft.Expense
                draft.splits.groupBy { it.category }.map { (category, splits) ->
                    val fundingMonth = YearMonth.from(maxOf(asOf, item.scheduledDate))
                    val bucket = buckets.firstOrNull { !it.archived && it.category == category && it.month == fundingMonth }
                    // Length-prefix category to avoid collisions in otherwise valid free-text labels.
                    Obligation("${item.key}:${category.length}:$category", bucket?.id, sumMoney(splits.map { it.amount }, INR))
                }
            }

    suspend fun define(command: LedgerCommand, rule: ScheduledRule) {
        require(rule.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && rule.name.isNotBlank() && rule.name == rule.name.trim() && rule.name.length <= 100) { "Invalid schedule identity or name" }
        require(rule.stoppedFrom == null && rule.template.date == rule.recurrence.start) { "A new schedule must use its original anchor date" }
        val end = rule.recurrence.end
        require(rule.recurrence.start.year in 1..9999 && (end == null || end.year in 1..9999)) { "Unsupported schedule calendar range" }
        require(rule.template !is TransactionDraft.Transfer) { "Recurring rules support income and expenses; investment contributions remain manual" }
        require(rule.template.memo.length <= 500 && rule.recurrence.exceptions.size <= 3660) { "Oversized schedule" }
        occurrences(rule.recurrence, rule.recurrence.start, rule.recurrence.start)
        val accounts = db.ledgerDao().accounts(command.bookId)
        when (val draft = rule.template) {
            is TransactionDraft.Income -> {
                require(draft.amount.currency == INR && draft.amount.minor > 0 && draft.category.isNotBlank() && draft.category == draft.category.trim() && draft.category.length <= 100) { "Invalid scheduled income" }
                require(accounts.any { it.id == draft.accountId && !it.archived }) { "Scheduled account not available" }
            }
            is TransactionDraft.Expense -> {
                require(draft.amount.currency == INR && draft.amount.minor > 0 && draft.splits.size in 1..100) { "Invalid scheduled expense" }
                require(draft.splits.all { it.amount.currency == INR && it.amount.minor > 0 && it.category.isNotBlank() && it.category == it.category.trim() && it.category.length <= 100 }) { "Invalid scheduled splits" }
                require(sumMoney(draft.splits.map { it.amount }, INR) == draft.amount) { "Scheduled splits must equal the parent amount" }
                require(accounts.any { it.id == draft.accountId && !it.archived }) { "Scheduled account not available" }
            }
            else -> error("Unsupported recurring template")
        }
        dao.insertRule(ScheduledRuleEntity(rule.id, command.bookId, command.id, rule.name, TemplateCodec.encode(rule.template),
            rule.recurrence.start.toString(), rule.recurrence.frequency.name, rule.recurrence.interval,
            rule.recurrence.end?.toString(), rule.mandatory, null))
        dao.insertExceptions(rule.recurrence.exceptions.sorted().map { ScheduleExceptionEntity(rule.id, it.toString()) })
    }

    suspend fun replace(command: LedgerCommand, action: ScheduleAction.Replace) {
        val start = action.replacement.recurrence.start
        require(action.ruleId != action.replacement.id) { "Replacement must retain the old rule and use a new identity" }
        require(resolved(command.bookId).values.none {
            it.ruleId == action.ruleId && LocalDate.parse(it.scheduledDate) >= start
        }) { "Start after existing reviewed occurrences so replacement cannot duplicate or resurrect their decisions" }
        // These mutations share the caller's transaction: an invalid replacement cannot leave the old plan stopped.
        stop(command, ScheduleAction.Stop(action.ruleId, start, action.reason))
        define(command, action.replacement)
    }

    suspend fun stop(command: LedgerCommand, action: ScheduleAction.Stop) {
        require(action.reason.isNotBlank() && action.reason.length <= 500 && action.effectiveFrom >= today(command.bookId)) { "Stopping requires a reason and cannot erase past due obligations" }
        val rule = requireNotNull(rules(command.bookId).firstOrNull { it.id == action.ruleId }) { "Schedule not found" }
        require(rule.stoppedFrom == null || action.effectiveFrom <= rule.stoppedFrom) { "A stopped rule cannot be extended; create a new rule" }
        check(dao.stopRule(command.bookId, rule.id, action.effectiveFrom.toString()) == 1)
    }

    suspend fun confirmationDraft(command: LedgerCommand, action: ScheduleAction.Confirm): TransactionDraft {
        val rule = available(command.bookId, action.ruleId, action.scheduledDate)
        require(action.actualDate <= today(command.bookId)) { "A manual confirmation must describe actual activity, never a future payment" }
        val actual = action.actualAmount
        if (actual != null) require(actual.currency == INR && actual.minor > 0) { "Confirmed amount must be positive INR" }
        val previous = resolved(command.bookId)[rule.id to action.scheduledDate]
        return when (val template = previous?.confirmedTemplate?.let(TemplateCodec::decode) ?: rule.template) {
            is TransactionDraft.Income -> template.copy(amount = actual ?: template.amount, date = action.actualDate, clearing = action.clearing)
            is TransactionDraft.Expense -> {
                require(actual == null || template.splits.size == 1 || actual == template.amount) { "Multi-split variable bills require an explicit split correction after confirmation" }
                template.copy(amount = actual ?: template.amount, date = action.actualDate, clearing = action.clearing,
                    splits = if (actual != null && template.splits.size == 1) listOf(template.splits.single().copy(amount = actual)) else template.splits)
            }
            else -> error("Unsupported schedule kind")
        }
    }

    private suspend fun available(book: String, ruleId: String, date: LocalDate): ScheduledRule {
        val rule = requireNotNull(rules(book).firstOrNull { it.id == ruleId }) { "Schedule not found" }
        val previous = resolved(book)[ruleId to date]
        if (previous == null) require(dates(rule, date, date).singleOrNull() == date) { "Not an active scheduled occurrence" }
        else require(previous.status == "REOPENED") { "Occurrence already confirmed or skipped" }
        return rule
    }

    suspend fun recordConfirmation(command: LedgerCommand, action: ScheduleAction.Confirm, journal: String, draft: TransactionDraft) {
        val previous = resolved(command.bookId)[action.ruleId to action.scheduledDate]
        if (previous == null) dao.insertOccurrence(ScheduleOccurrenceEntity("${action.ruleId}@${action.scheduledDate}", action.ruleId,
            action.scheduledDate.toString(), command.id, "CONFIRMED", journal, "Manually confirmed actual activity", TemplateCodec.encode(draft)))
        else dao.insertResolution(OccurrenceResolutionEntity(command.id, previous.id, command.id, "CONFIRMED", journal,
            TemplateCodec.encode(draft), "Manually reconfirmed returned payment"))
    }

    suspend fun skip(command: LedgerCommand, action: ScheduleAction.Skip) {
        available(command.bookId, action.ruleId, action.scheduledDate)
        require(action.reason.isNotBlank() && action.reason.length <= 500) { "Skipping requires a reason" }
        val previous = resolved(command.bookId)[action.ruleId to action.scheduledDate]
        if (previous == null) dao.insertOccurrence(ScheduleOccurrenceEntity("${action.ruleId}@${action.scheduledDate}", action.ruleId,
            action.scheduledDate.toString(), command.id, "SKIPPED", null, action.reason, null))
        else dao.insertResolution(OccurrenceResolutionEntity(command.id, previous.id, command.id, "SKIPPED", null, null, action.reason))
    }

    suspend fun reopen(command: LedgerCommand, action: ScheduleAction.Reopen) {
        require(action.reason.isNotBlank() && action.reason.length <= 500) { "Reopening requires a reason" }
        val previous = requireNotNull(resolved(command.bookId)[action.ruleId to action.scheduledDate]) { "Occurrence not found" }
        require(previous.status == "SKIPPED") { "Only a skipped occurrence can be reopened manually" }
        dao.insertResolution(OccurrenceResolutionEntity(command.id, previous.id, command.id, "REOPENED", null, null, action.reason))
    }

    suspend fun linkExisting(command: LedgerCommand, action: ScheduleAction.LinkExisting, ledger: LedgerSnapshot) {
        val rule = available(command.bookId, action.ruleId, action.scheduledDate)
        require(action.reason.isNotBlank() && action.reason.length <= 500) { "Link an occurrence with an explicit reason" }
        val entry = requireNotNull(ledger.history.firstOrNull { it.id == action.journalId && it.reversedBy == null }) { "Existing transaction not available or already reversed" }
        require(entry.kind in listOf("INCOME", "EXPENSE")) { "Link a real recorded payment/income; early payment is allowed" }
        require(resolved(command.bookId).values.none { it.status == "CONFIRMED" && it.journalId == entry.id }) { "Existing transaction already belongs to another occurrence" }
        val asset = entry.legs.single { it.role == LedgerRole.ASSET }
        val recorded = requireNotNull(db.ledgerDao().journal(command.bookId, entry.id))
        val clearing = Clearing.valueOf(recorded.clearing)
        val draft = if (entry.kind == "INCOME") {
            require(rule.template is TransactionDraft.Income) { "An expense schedule cannot be settled by income" }
            val category = entry.legs.single { it.role == LedgerRole.INCOME }.label
            TransactionDraft.Income(asset.accountId, asset.amount, category, entry.effectiveDate, clearing, entry.memo)
        } else {
            require(rule.template is TransactionDraft.Expense) { "An income schedule cannot be settled by an expense" }
            TransactionDraft.Expense(asset.accountId, -asset.amount, entry.legs.filter { it.role == LedgerRole.EXPENSE }.map { Split(it.label, it.amount) }, entry.effectiveDate, clearing, entry.memo)
        }
        // The actual journal and funded consumption already exist; linking must never post or consume again.
        recordConfirmation(command, ScheduleAction.Confirm(rule.id, action.scheduledDate, entry.effectiveDate, clearing), entry.id, draft)
    }

    suspend fun resolveActualChange(command: LedgerCommand, originalJournal: String, reversalJournal: String,
        replacementJournal: String? = null, replacement: TransactionDraft? = null) {
        val previous = resolved(command.bookId).values.firstOrNull { it.status == "CONFIRMED" && it.journalId == originalJournal } ?: return
        val originalTemplate = previous.confirmedTemplate?.let(TemplateCodec::decode)
        val sameKind = (originalTemplate is TransactionDraft.Expense && replacement is TransactionDraft.Expense) ||
            (originalTemplate is TransactionDraft.Income && replacement is TransactionDraft.Income)
        dao.insertResolution(OccurrenceResolutionEntity(command.id, previous.id, command.id,
            if (sameKind) "CONFIRMED" else "REOPENED", if (sameKind) replacementJournal else reversalJournal,
            if (sameKind) TemplateCodec.encode(requireNotNull(replacement)) else null,
            if (sameKind) "Occurrence follows immutable reverse-and-replace correction" else "Actual payment reversed; review or skip the reopened obligation"))
    }

    suspend fun validateInvestment(command: LedgerCommand, action: ScheduleAction.Invest, allowArchivedReserve: Boolean = false) {
        val bucket = requireNotNull(db.planningDao().buckets(command.bookId).firstOrNull { it.id == action.bucketId && (allowArchivedReserve || !it.archived) && it.kind == "INVESTMENT" }) { "Investment reserve not available" }
        val accounts = db.ledgerDao().accounts(command.bookId)
        require(accounts.any { it.id == action.transfer.sourceId && it.kind != "INVESTMENT" && !it.archived } &&
            accounts.any { it.id == action.transfer.destinationId && it.kind == "INVESTMENT" && !it.archived }) { "Contribution must move liquid money to an owned investment account" }
        require(bucket.protected && action.transfer.amount.currency == INR && action.transfer.amount.minor > 0) { "Invalid funded contribution" }
    }

    suspend fun recordInvestment(command: LedgerCommand, action: ScheduleAction.Invest, journal: String) {
        dao.insertContribution(InvestmentContributionEntity(journal, action.bucketId, action.transfer.destinationId,
            action.transfer.amount.minor, action.transfer.date.toString(), null))
    }

    suspend fun reverseContribution(book: String, original: String, reversal: String, date: LocalDate) {
        val contribution = dao.contributions(book).firstOrNull { it.journalId == original && it.reversalOf == null } ?: return
        dao.insertContribution(contribution.copy(journalId = reversal, minor = Math.negateExact(contribution.minor),
            date = date.toString(), reversalOf = original))
    }

    suspend fun correctedInvestment(book: String, original: String, replacement: TransactionDraft): ScheduleAction.Invest? {
        if (replacement !is TransactionDraft.Transfer) return null
        val previous = dao.contributions(book).firstOrNull { it.journalId == original && it.reversalOf == null } ?: return null
        val accounts = db.ledgerDao().accounts(book)
        if (accounts.firstOrNull { it.id == replacement.sourceId }?.kind == "INVESTMENT" ||
            accounts.firstOrNull { it.id == replacement.destinationId }?.kind != "INVESTMENT") return null
        return ScheduleAction.Invest(previous.bucketId, replacement)
    }

    suspend fun validateContributions(book: String, funded: Map<String, Money>) {
        for ((bucket, entries) in dao.contributions(book).groupBy { it.bucketId }) {
            val contributed = sumMoney(entries.map { m(it.minor) }, INR)
            require(contributed.minor >= 0) { "Returned contributions exceed actual contributions" }
            contributed + (funded[bucket] ?: m(0)) // Progress must remain exactly representable.
        }
    }

    suspend fun weeklyReview(command: LedgerCommand, action: ScheduleAction.WeeklyReview) {
        require(action.weekOf.dayOfWeek == java.time.DayOfWeek.MONDAY && action.weekOf <= today(command.bookId) && action.note.length <= 2000) { "Review a real week beginning Monday; note is limited to 2000 characters" }
        dao.insertReview(WeeklyReviewEntity(command.id, command.bookId, command.id, action.weekOf.toString(), clock.millis(), action.note,
            Math.addExact(command.expectedRevision, 1)))
    }
}
