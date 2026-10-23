package com.fintrack.data.ledger

import androidx.room.withTransaction
import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.PlanningAction
import com.fintrack.data.planning.RoomPlanningStore
import com.fintrack.data.schedules.RoomScheduleStore
import com.fintrack.domain.schedules.ScheduleAction
import java.time.YearMonth
import com.fintrack.engine.Money
import com.fintrack.engine.Posting
import com.fintrack.engine.sumMoney
import com.fintrack.engine.validateJournal
import java.math.BigInteger
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLedgerRepository(private val db: FinTrackDatabase, private val clock: Clock) : LedgerRepository {
    private val dao get() = db.ledgerDao()
    private val planning = RoomPlanningStore(db, clock)
    private val schedules = RoomScheduleStore(db, clock)
    private fun id() = UUID.randomUUID().toString()
    private fun money(value: Long) = Money(value, INR)
    private fun identifier(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid identifier" } }
    private fun label(value: String) { require(value.isNotBlank() && value == value.trim() && value.length <= 100) { "Name/category must be trimmed and 1..100 characters" } }
    private suspend fun date(book: String, value: LocalDate) {
        val zone = db.planningDao().profile(book)?.timezone?.let(ZoneId::of) ?: clock.zone
        require(value.year in 1..9999 && !value.isAfter(LocalDate.now(clock.withZone(zone)))) { "Actual entries cannot have future or unsupported dates in the book timezone" }
    }
    private fun reason(value: String) { require(value.isNotBlank() && value.length <= 500) { "A correction/reconciliation reason is required (1..500 characters)" } }
    private fun currency(value: Money) { require(value.currency == INR) { "This book supports INR with two minor digits only" } }

    override suspend fun initializeBook(bookId: String, currency: com.fintrack.engine.CurrencySpec) {
        identifier(bookId)
        require(currency == INR) { "Unsupported book currency" }
        db.withTransaction {
            if (dao.book(bookId) == null) dao.insertBook(BookEntity(bookId, "local-owner", "INR", 2, 0, clock.millis()))
        }
    }

    override suspend fun execute(command: LedgerCommand): CommandReceipt {
        val action = command.action.detached()
        val input = command.copy(action = action)
        identifier(input.id); identifier(input.bookId)
        require(input.expectedRevision >= 0) { "Invalid expected revision" }
        val hash = fingerprint(input)
        return db.withTransaction {
            dao.command(input.id)?.let {
                require(it.bookId == input.bookId && it.fingerprint == hash) { "Command key already used with a different payload" }
                return@withTransaction it.receipt()
            }
            val book = requireNotNull(dao.book(input.bookId)) { "Book does not exist" }
            require(book.currencyCode == "INR" && book.exponent == 2) { "Unsupported stored currency" }
            check(book.revision == input.expectedRevision) { "Stale financial state. Refresh and review before resubmitting" }
            val next = Math.addExact(book.revision, 1)
            val primary = when (action) {
                is LedgerAction.Setup -> input.bookId
                is LedgerAction.Schedule -> when (val operation = action.operation) {
                    is ScheduleAction.Define -> operation.rule.id
                    is ScheduleAction.Replace -> operation.replacement.id
                    is ScheduleAction.Stop -> operation.ruleId
                    is ScheduleAction.LinkExisting -> operation.journalId
                    is ScheduleAction.Unlink -> operation.journalId
                    else -> id()
                }
                is LedgerAction.Plan -> when (val operation = action.operation) {
                    is PlanningAction.CreateReserve -> operation.id
                    is PlanningAction.SetBudget -> operation.id
                    is PlanningAction.SetGoal -> operation.id
                    else -> input.id
                }
                is LedgerAction.CreateAccount -> action.accountId
                is LedgerAction.Archive -> action.accountId
                is LedgerAction.Clear -> action.entryId
                is LedgerAction.Reconcile -> input.id
                else -> id()
            }
            val secondary = when {
                action is LedgerAction.Correct -> id()
                action is LedgerAction.Schedule && action.operation is ScheduleAction.Replace -> action.operation.ruleId
                else -> null
            }
            val receipt = CommandEntity(input.id, input.bookId, hash, primary, secondary, next)
            dao.insertCommand(receipt)
            val explanation = when (action) {
                is LedgerAction.Schedule -> when (val operation = action.operation) {
                    is ScheduleAction.Stop -> operation.reason
                    is ScheduleAction.Replace -> operation.reason
                    is ScheduleAction.Skip -> operation.reason
                    is ScheduleAction.Reopen -> operation.reason
                    is ScheduleAction.LinkExisting -> operation.reason
                    is ScheduleAction.Unlink -> operation.reason
                    else -> ""
                }
                is LedgerAction.Plan -> (action.operation as? PlanningAction.Move)?.reason.orEmpty()
                is LedgerAction.Reverse -> action.reason
                is LedgerAction.Correct -> action.reason
                is LedgerAction.Reconcile -> action.reason
                else -> ""
            }
            val auditAction = when (action) {
                is LedgerAction.Plan -> "Planning.${action.operation.javaClass.simpleName}"
                is LedgerAction.Schedule -> "Schedule.${action.operation.javaClass.simpleName}"
                else -> action.javaClass.simpleName
            }
            dao.insertAudit(AuditEntity(input.id, auditAction, explanation, clock.millis()))
            when (action) {
                is LedgerAction.Setup -> {
                    val current = snapshotInside(input.bookId)
                    require(current.history.isEmpty() && db.planningDao().profile(input.bookId)?.setupComplete != true) { "Existing financial books must be kept; configure their profile without resetting balances" }
                    require(action.profile.setupComplete && action.newAccounts.size + action.existingOpenings.size in 1..1000) { "Complete setup with 1..1000 explicitly declared account balances" }
                    require(action.newAccounts.map { it.accountId }.toSet().size == action.newAccounts.size &&
                        action.existingOpenings.map { it.accountId }.toSet().size == action.existingOpenings.size) { "Duplicate account in setup" }
                    require(current.accounts.map { it.id }.toSet() == action.existingOpenings.map { it.accountId }.toSet()) { "Declare every existing metadata-only account balance explicitly" }
                    require(action.newAccounts.none { added -> current.accounts.any { it.id == added.accountId } }) { "Existing account identity must be retained" }
                    // Persist the selected zone before validating actual opening dates; the transaction hides intermediate setup state.
                    planning.apply(input, action.profile.copy(setupComplete = false), current)
                    for (declaration in action.existingOpenings) {
                        val existing = current.accounts.single { it.id == declaration.accountId }
                        if (existing.archived) check(dao.archive(input.bookId, existing.id, false) == 1)
                        opening(input, existing.id, declaration.balance, declaration.date)
                        if (existing.archived) check(dao.archive(input.bookId, existing.id, true) == 1)
                    }
                    action.newAccounts.forEach { createAccount(input, it) }
                    planning.apply(input, action.profile, snapshotInside(input.bookId))
                }
                is LedgerAction.Schedule -> when (val operation = action.operation) {
                    is ScheduleAction.Define -> schedules.define(input, operation.rule)
                    is ScheduleAction.Replace -> schedules.replace(input, operation)
                    is ScheduleAction.Stop -> schedules.stop(input, operation)
                    is ScheduleAction.Skip -> schedules.skip(input, operation)
                    is ScheduleAction.Reopen -> schedules.reopen(input, operation)
                    is ScheduleAction.LinkExisting -> schedules.linkExisting(input, operation, snapshotInside(input.bookId))
                    is ScheduleAction.Unlink -> schedules.unlink(input, operation)
                    is ScheduleAction.WeeklyReview -> schedules.weeklyReview(input, operation)
                    is ScheduleAction.Confirm -> {
                        val draft = schedules.confirmationDraft(input, operation)
                        postDraft(input, primary, draft)
                        val zone = db.planningDao().profile(input.bookId)?.timezone?.let(ZoneId::of) ?: clock.zone
                        // Late bills use the live period; early payments can consume their funded future envelope.
                        planning.consumeDraft(input, primary, draft, YearMonth.from(maxOf(LocalDate.now(clock.withZone(zone)), operation.scheduledDate)))
                        schedules.recordConfirmation(input, operation, primary, draft)
                    }
                    is ScheduleAction.Invest -> {
                        schedules.validateInvestment(input, operation)
                        postDraft(input, primary, operation.transfer)
                        planning.consumeInvestment(input, primary, operation.bucketId, operation.transfer.amount, operation.transfer.date)
                        planning.consumeDraft(input, primary, operation.transfer) // Only fee category, never principal.
                        schedules.recordInvestment(input, operation, primary)
                    }
                }
                is LedgerAction.Plan -> planning.apply(input, action.operation, snapshotInside(input.bookId))
                is LedgerAction.CreateAccount -> {
                    createAccount(input, action)
                }
                is LedgerAction.Post -> postDraft(input, primary, action.draft)
                is LedgerAction.Archive -> {
                    account(input.bookId, action.accountId, allowArchived = true)
                    check(dao.archive(input.bookId, action.accountId, action.archived) == 1)
                }
                is LedgerAction.Reverse -> reverse(input, primary, action.entryId, action.date, action.reason)
                is LedgerAction.Correct -> {
                    reverse(input, requireNotNull(secondary), action.entryId, action.replacement.date, action.reason)
                    postDraft(input, primary, action.replacement, action.reason)
                }
                is LedgerAction.Clear -> {
                    date(input.bookId, action.date)
                    val original = original(input.bookId, action.entryId)
                    require(original.clearedDate == null && original.kind != "REVERSAL" && original.reversedBy == null) { "Entry cannot be cleared" }
                    require(!action.date.isBefore(original.effectiveDate)) { "Clearing predates transaction" }
                    dao.insertClearing(ClearingEntity(action.entryId, input.id, action.date.toString()))
                }
                is LedgerAction.Reconcile -> {
                    reason(action.reason); date(input.bookId, action.statementDate)
                    account(input.bookId, action.accountId)
                    val preview = reconciliation(input.bookId, action.accountId, action.statementDate, action.statement, action.basis)
                    val adjustment = if (preview.difference.minor != 0L) id() else null
                    if (adjustment != null) {
                        val equity = control(input.bookId, LedgerRole.EQUITY, "Reconciliation equity")
                        postJournal(input, adjustment, "ADJUSTMENT", action.statementDate, Clearing.CLEARED,
                            "Statement reconciliation", action.reason, null,
                            listOf(Posting(action.accountId, preview.difference), Posting(equity, -preview.difference)))
                    }
                    dao.insertReconciliation(ReconciliationEntity(input.id, action.accountId, action.statementDate.toString(),
                        action.basis.name, action.statement.minor, preview.ledger.minor, preview.difference.minor, adjustment))
                }
            }
            // Validate all final derived positions exactly; any overflow rolls back every inserted row.
            planning.afterLedger(input, primary, secondary)
            when (action) {
                is LedgerAction.Reverse -> {
                    schedules.reverseContribution(input.bookId, action.entryId, primary, action.date)
                    schedules.resolveActualChange(input, action.entryId, primary)
                }
                is LedgerAction.Correct -> {
                    schedules.reverseContribution(input.bookId, action.entryId, requireNotNull(secondary), action.replacement.date)
                    schedules.correctedInvestment(input.bookId, action.entryId, action.replacement)?.let { corrected ->
                        schedules.validateInvestment(input, corrected, allowArchivedReserve = true)
                        planning.consumeCorrectedInvestment(input, primary, corrected.bucketId, corrected.transfer.amount, corrected.transfer.date)
                        schedules.recordInvestment(input, corrected, primary)
                    }
                    schedules.resolveActualChange(input, action.entryId, requireNotNull(secondary), primary, action.replacement)
                }
                else -> Unit
            }
            schedules.validateContributions(input.bookId, planning.balances(input.bookId))
            val final = snapshotInside(input.bookId)
            val zone = db.planningDao().profile(input.bookId)?.timezone?.let(ZoneId::of) ?: clock.zone
            // A valid individual amount must not commit a combination that makes current planning unreadable.
            planning.read(final, input.bookId, LocalDate.now(clock.withZone(zone)))
            check(dao.advance(input.bookId, book.revision, next) == 1) { "Stale book revision" }
            receipt.receipt()
        }
    }

    private fun CommandEntity.receipt() = CommandReceipt(id, primaryId, secondaryId, revision)

    private suspend fun createAccount(command: LedgerCommand, action: LedgerAction.CreateAccount) {
        identifier(action.accountId); label(action.name); currency(action.opening); date(command.bookId, action.date)
        dao.insertAccount(AccountEntity(action.accountId, action.name, "INR", action.kind.name, false, command.bookId))
        dao.insertLedgerAccount(LedgerAccountEntity(action.accountId, command.bookId, action.accountId, "ASSET", action.name))
        opening(command, action.accountId, action.opening, action.date)
    }

    private suspend fun opening(command: LedgerCommand, accountId: String, balance: Money, at: LocalDate) {
        currency(balance); date(command.bookId, at)
        if (balance.minor != 0L) {
            val equity = control(command.bookId, LedgerRole.EQUITY, "Opening equity")
            postJournal(command, id(), "OPENING", at, Clearing.CLEARED, "Opening balance", "", null,
                listOf(Posting(accountId, balance), Posting(equity, -balance)))
        }
    }

    private suspend fun account(book: String, id: String, allowArchived: Boolean = false): AccountEntity {
        identifier(id)
        val account = requireNotNull(dao.accounts(book).firstOrNull { it.id == id }) { "Account is missing or belongs to another book" }
        require(allowArchived || !account.archived) { "Account is archived. Explicitly unarchive before posting" }
        require(account.currencyCode == "INR") { "Account currency mismatch" }
        return account
    }

    private suspend fun control(book: String, role: LedgerRole, name: String): String {
        label(name)
        val existing = dao.ledgerAccounts(book).firstOrNull { it.role == role.name && it.name == name }
        if (existing != null) return existing.id
        val newId = id()
        dao.insertLedgerAccount(LedgerAccountEntity(newId, book, null, role.name, name))
        return newId
    }

    private suspend fun postDraft(command: LedgerCommand, entryId: String, draft: TransactionDraft, reason: String = "") {
        date(command.bookId, draft.date)
        require(draft.memo.length <= 500) { "Memo is too long" }
        val legs = mutableListOf<Posting>()
        val kind = when (draft) {
            is TransactionDraft.Income -> {
                currency(draft.amount); require(draft.amount.minor > 0) { "Income must be positive" }
                account(command.bookId, draft.accountId)
                legs += Posting(draft.accountId, draft.amount)
                legs += Posting(control(command.bookId, LedgerRole.INCOME, draft.category), -draft.amount)
                "INCOME"
            }
            is TransactionDraft.Expense -> {
                currency(draft.amount); require(draft.amount.minor > 0) { "Expense must be positive" }
                require(draft.splits.size in 1..100) { "Expense needs 1..100 splits" }
                account(command.bookId, draft.accountId)
                draft.splits.forEach { currency(it.amount); label(it.category); require(it.amount.minor > 0) { "Split must be positive" } }
                require(sumMoney(draft.splits.map { it.amount }, INR) == draft.amount) { "Splits must equal the expense exactly" }
                legs += Posting(draft.accountId, -draft.amount)
                draft.splits.forEach { legs += Posting(control(command.bookId, LedgerRole.EXPENSE, it.category), it.amount) }
                "EXPENSE"
            }
            is TransactionDraft.Transfer -> {
                currency(draft.amount); currency(draft.fee)
                require(draft.sourceId != draft.destinationId) { "Transfer accounts must differ" }
                require(draft.amount.minor > 0 && draft.fee.minor >= 0) { "Invalid transfer amount or fee" }
                account(command.bookId, draft.sourceId); account(command.bookId, draft.destinationId)
                legs += Posting(draft.sourceId, -(draft.amount + draft.fee))
                legs += Posting(draft.destinationId, draft.amount)
                if (draft.fee.minor > 0) legs += Posting(control(command.bookId, LedgerRole.EXPENSE, "Transfer fees"), draft.fee)
                "TRANSFER"
            }
        }
        postJournal(command, entryId, kind, draft.date, draft.clearing, draft.memo, reason, null, legs)
    }

    private suspend fun postJournal(command: LedgerCommand, id: String, kind: String, date: LocalDate,
        clearing: Clearing, memo: String, reason: String, reversalOf: String?, legs: List<Posting>) {
        validateJournal(legs)
        // Keep gross positive journal totals representable in SQLite's signed integer SUM.
        val gross = legs.filter { it.amount.minor > 0 }.fold(BigInteger.ZERO) { sum, leg -> sum + BigInteger.valueOf(leg.amount.minor) }
        require(gross <= BigInteger.valueOf(Long.MAX_VALUE)) { "Gross journal amount exceeds signed 64-bit range" }
        dao.insertJournal(JournalEntity(id, command.bookId, command.id, kind, date.toString(), clock.millis(), clearing.name, memo, reason, reversalOf))
        dao.insertPostings(legs.map { PostingEntity(this.id(), id, it.accountId, it.amount.minor) })
        dao.seal(JournalSealEntity(id))
    }

    private suspend fun original(book: String, entryId: String): HistoryEntry =
        requireNotNull(snapshotInside(book).history.firstOrNull { it.id == entryId }) { "Transaction is missing or belongs to another book" }

    private suspend fun reverse(command: LedgerCommand, id: String, originalId: String, at: LocalDate, why: String) {
        reason(why); date(command.bookId, at)
        val entry = original(command.bookId, originalId)
        require(entry.kind != "REVERSAL" && entry.reversedBy == null) { "Entry already reversed or is itself a reversal" }
        require(!at.isBefore(entry.effectiveDate) && (entry.clearedDate == null || !at.isBefore(entry.clearedDate))) { "Reversal predates original or clearing" }
        entry.legs.filter { it.role == LedgerRole.ASSET }.forEach { account(command.bookId, it.accountId) }
        postJournal(command, id, "REVERSAL", at, if (entry.clearedDate != null) Clearing.CLEARED else Clearing.PENDING,
            "Reversal of ${entry.id}", why, entry.id, entry.legs.map { Posting(it.accountId, -it.amount) })
    }

    override suspend fun snapshot(bookId: String): LedgerSnapshot = db.withTransaction { snapshotInside(bookId) }

    private suspend fun snapshotInside(bookId: String): LedgerSnapshot {
        val book = requireNotNull(dao.book(bookId)) { "Book does not exist" }
        val journals = dao.journals(bookId)
        val legs = dao.postings(bookId).groupBy { it.entryId }
        val controls = dao.ledgerAccounts(bookId).associateBy { it.id }
        val clearing = dao.clearings(bookId).associateBy { it.entryId }
        val reversed = journals.filter { it.reversalOf != null }.associateBy { it.reversalOf }
        val history = journals.map { journal ->
            val date = LocalDate.parse(journal.effectiveDate)
            HistoryEntry(journal.id, journal.kind, date, journal.createdAt, journal.memo, journal.reason, journal.reversalOf,
                reversed[journal.id]?.id, if (journal.clearing == "CLEARED") date else clearing[journal.id]?.let { LocalDate.parse(it.clearedDate) },
                legs[journal.id].orEmpty().map { leg ->
                    val ledger = controls.getValue(leg.ledgerAccountId)
                    HistoryLeg(ledger.id, ledger.name, LedgerRole.valueOf(ledger.role), money(leg.minor))
                })
        }
        // Also validate control account totals, not only displayed asset balances.
        history.flatMap { it.legs }.groupBy { it.accountId }.values.forEach { sumMoney(it.map { leg -> leg.amount }, INR) }
        val accounts = dao.accounts(bookId).map { account ->
            val accountLegs = history.flatMap { entry -> entry.legs.filter { it.accountId == account.id } }
            val clearedLegs = history.filter { it.clearedDate != null }.flatMap { entry -> entry.legs.filter { it.accountId == account.id } }
            AccountBalance(account.id, account.name, AccountKind.valueOf(account.kind), account.archived,
                sumMoney(accountLegs.map { it.amount }, INR), sumMoney(clearedLegs.map { it.amount }, INR))
        }
        val income = sumMoney(history.flatMap { it.legs }.filter { it.role == LedgerRole.INCOME }.map { -it.amount }, INR)
        val expense = sumMoney(history.flatMap { it.legs }.filter { it.role == LedgerRole.EXPENSE }.map { it.amount }, INR)
        return LedgerSnapshot(book.revision, accounts, history, sumMoney(accounts.filter { it.liquid }.map { it.posted }, INR), income, expense)
    }

    override fun observe(bookId: String): Flow<LedgerSnapshot> = dao.revision(bookId).map { snapshot(bookId) }

    override suspend fun history(bookId: String, filter: HistoryFilter): List<HistoryEntry> = db.withTransaction {
        require(filter.from == null || filter.through == null || !filter.from.isAfter(filter.through)) { "Invalid history date range" }
        if (filter.accountId != null) account(bookId, filter.accountId, allowArchived = true)
        snapshotInside(bookId).history.filter { entry ->
            (filter.accountId == null || entry.legs.any { it.accountId == filter.accountId }) &&
                (filter.from == null || !entry.effectiveDate.isBefore(filter.from)) &&
                (filter.through == null || !entry.effectiveDate.isAfter(filter.through)) &&
                (filter.query.isBlank() || (listOf(entry.memo, entry.reason, entry.kind) + entry.legs.map { it.label })
                    .any { it.contains(filter.query, ignoreCase = true) })
        }.reversed()
    }

    override suspend fun reconcile(bookId: String, accountId: String, date: LocalDate,
        statement: Money, basis: BalanceBasis): ReconciliationPreview = db.withTransaction {
        reconciliation(bookId, accountId, date, statement, basis)
    }

    private suspend fun reconciliation(bookId: String, accountId: String, at: LocalDate,
        statement: Money, basis: BalanceBasis): ReconciliationPreview {
        currency(statement); date(bookId, at); account(bookId, accountId, allowArchived = true)
        val snapshot = snapshotInside(bookId)
        val entries = snapshot.history.filter { !it.effectiveDate.isAfter(at) }
        val posted = sumMoney(entries.flatMap { it.legs }.filter { it.accountId == accountId }.map { it.amount }, INR)
        val cleared = sumMoney(entries.filter { it.clearedDate != null && !it.clearedDate.isAfter(at) }
            .flatMap { it.legs }.filter { it.accountId == accountId }.map { it.amount }, INR)
        val ledger = if (basis == BalanceBasis.POSTED) posted else cleared
        return ReconciliationPreview(accountId, at, basis, statement, ledger, posted - cleared, statement - ledger, snapshot.revision)
    }
}
