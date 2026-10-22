package com.fintrack.data.planning

import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.*
import com.fintrack.engine.*
import com.fintrack.data.schedules.RoomScheduleStore
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Called only inside the owning financial command's Room transaction. */
internal class RoomPlanningStore(private val db: FinTrackDatabase, private val clock: Clock) {
    private val dao get() = db.planningDao()
    private fun m(value: Long) = Money(value, INR)
    private fun uid() = UUID.randomUUID().toString()
    private fun id(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid planning identifier" } }
    private fun label(value: String) { require(value.isNotBlank() && value == value.trim() && value.length <= 100) { "Name/category must be trimmed and 1..100 characters" } }
    private fun nonnegative(value: Money) { require(value.currency == INR && value.minor >= 0) { "Expected a nonnegative INR amount" } }
    private suspend fun today(book: String): LocalDate =
        LocalDate.now(clock.withZone(dao.profile(book)?.timezone?.let(ZoneId::of) ?: clock.zone))

    suspend fun balances(book: String): Map<String, Money> = dao.legs(book).filter { it.bucketId != null }
        .groupBy { requireNotNull(it.bucketId) }.mapValues { (_, legs) -> sumMoney(legs.map { m(it.minor) }, INR) }

    suspend fun validate(book: String) {
        val balances = balances(book)
        require(balances.values.all { it.minor >= 0 }) { "A funded reserve cannot be negative" }
        sumMoney(balances.values, INR)
    }

    suspend fun read(ledger: LedgerSnapshot, book: String, asOf: LocalDate): PlanningSnapshot {
        val profile = dao.profile(book)?.let { FinancialProfile(it.timezone, m(it.safetyBufferMinor),
            m(it.monthlyCapacityMinor), it.horizonDays, it.setupComplete) }
            ?: FinancialProfile(clock.zone.id, m(0), m(0), 30, false)
        val balances = balances(book)
        val buckets = dao.buckets(book).map { ReserveBucket(it.id, it.name, BucketKind.valueOf(it.kind),
            it.protected, balances[it.id] ?: m(0), it.category, it.month?.let(YearMonth::parse), it.archived) }
        val indexed = buckets.associateBy { it.id }
        val spending = ledger.history.flatMap { entry -> entry.legs.filter { it.role == LedgerRole.EXPENSE }
            .map { Triple(YearMonth.from(entry.effectiveDate), it.label, it.amount) } }
            .groupBy { it.first to it.second }.mapValues { (_, legs) -> sumMoney(legs.map { it.third }, INR) }
        val budgets = dao.budgets(book).map {
            val bucket = indexed.getValue(it.bucketId)
            val policy = RolloverPolicy.valueOf(it.rollover)
            val spent = spending[bucket.month to bucket.category] ?: m(0)
            MonthlyBudget(it.bucketId, requireNotNull(bucket.category), requireNotNull(bucket.month), m(it.limitMinor),
                m(it.carriedMinor), policy, it.closed, budgetPosition(m(it.limitMinor), m(it.carriedMinor), spent, policy), bucket.funded)
        }
        val contributions = db.scheduleDao().contributions(book).groupBy { it.bucketId }
        val journals = ledger.history.associateBy { it.id }
        val funding = dao.goals(book).map {
            val reserved = indexed.getValue(it.bucketId).funded
            val posted = sumMoney(contributions[it.bucketId].orEmpty().map { value -> m(value.minor) }, INR)
            val cleared = sumMoney(contributions[it.bucketId].orEmpty().filter { value -> journals[value.journalId]?.clearedDate != null }.map { value -> m(value.minor) }, INR)
            require(posted.minor >= 0) { "Contribution reversals exceed original contributions" }
            GoalFunding(it.bucketId, reserved, posted, cleared, reserved + posted)
        }.associateBy { it.bucketId }
        val goals = dao.goals(book).map { GoalPlan(it.bucketId, m(it.targetMinor), funding.getValue(it.bucketId).progress,
            LocalDate.parse(it.deadline), it.priority, m(it.monthlyMinor), it.anchorDay) }
        val reservations = reservationPosition(ledger.liquid, buckets.map { it.funded })
        val schedules = RoomScheduleStore(db, clock)
        val scheduled = schedules.read(book, asOf, asOf.plusDays(profile.obligationHorizonDays.toLong()), buckets)
        val obligations = schedules.obligations(scheduled.occurrences, buckets, asOf)
        return PlanningSnapshot(ledger, asOf, profile, buckets, budgets, goals,
            assessGoals(goals, asOf, profile.monthlyCapacity), reservations,
            safeToSpend(ledger.liquid, reservations.reserved, scheduled.uncoveredObligations, profile.safetyBuffer),
            funding.values.toList(), obligations, scheduled.uncoveredObligations)
    }

    fun diversion(snapshot: PlanningSnapshot, sourceId: String, destinationId: String?, amount: Money): DiversionReview {
        nonnegative(amount); require(amount.minor > 0 && sourceId != destinationId) { "Choose distinct reserves and a positive amount" }
        val source = requireNotNull(snapshot.buckets.firstOrNull { it.id == sourceId && !it.archived }) { "Source reserve not available" }
        if (destinationId != null) require(snapshot.buckets.any { it.id == destinationId && !it.archived }) { "Destination reserve not available" }
        require(amount.minor <= source.funded.minor) { "Amount exceeds funded reserve" }
        val changed = snapshot.goals.map { when (it.id) {
            sourceId -> it.copy(funded = it.funded - amount)
            destinationId -> it.copy(funded = it.funded + amount)
            else -> it
        } }
        val after = assessGoals(changed, snapshot.asOf, snapshot.profile.monthlyCapacity).assessments.associateBy { it.id }
        val impacts = snapshot.goalCollisions.assessments.filter { it != after.getValue(it.id) || it.id == sourceId || it.id == destinationId }.map { before ->
            val updated = after.getValue(before.id)
            GoalDiversionImpact(before.id, when (before.id) { sourceId -> amount; destinationId -> -amount; else -> m(0) },
                before.requiredMonthly, updated.requiredMonthly, before.projectedCompletion, updated.projectedCompletion)
        }
        val reservedAfter = if (destinationId == null) snapshot.reservations.reserved - amount else snapshot.reservations.reserved
        val changedFunding = snapshot.buckets.associate { it.id to when (it.id) {
            sourceId -> it.funded - amount
            destinationId -> it.funded + amount
            else -> it.funded
        } }
        val uncoveredAfter = sumMoney(coverObligations(snapshot.obligations, changedFunding, INR).map { it.uncovered }, INR)
        return DiversionReview(DiversionConsent(snapshot.ledger.revision, snapshot.asOf, sourceId, destinationId, amount, impacts),
            source.name, source.funded - amount, source.protected,
            safeToSpend(snapshot.ledger.liquid, reservedAfter, uncoveredAfter, snapshot.profile.safetyBuffer))
    }

    suspend fun apply(command: LedgerCommand, action: PlanningAction, ledger: LedgerSnapshot) {
        val book = command.bookId
        when (action) {
            is PlanningAction.CreateReserve -> {
                id(action.id); label(action.name)
                require(action.kind in listOf(BucketKind.SAVINGS, BucketKind.EMERGENCY, BucketKind.INVESTMENT)) { "Use budget/goal setup for this reserve type" }
                require(action.protected) { "Savings, emergency and investment reserves must be protected" }
                dao.insertBucket(ReserveBucketEntity(action.id, book, action.name, action.kind.name, true, null, null, false))
            }
            is PlanningAction.SetBudget -> setBudget(book, action)
            is PlanningAction.SetGoal -> {
                id(action.id); label(action.name); nonnegative(action.target); nonnegative(action.monthlyPlan)
                require(action.target.minor > 0 && action.deadline.year in 1..9999 && action.priority in 1..100 && action.anchorDay in 1..31) { "Invalid goal target, deadline, priority or contribution day" }
                val kind = if (action.investment) BucketKind.INVESTMENT else BucketKind.GOAL
                val existing = dao.buckets(book).firstOrNull { it.id == action.id }
                if (existing == null) dao.insertBucket(ReserveBucketEntity(action.id, book, action.name, kind.name, true, null, null, false))
                else {
                    require(existing.kind == kind.name && !existing.archived) { "Goal type cannot change" }
                    check(dao.updateBucket(existing.copy(name = action.name)) == 1)
                }
                val goal = FinancialGoalEntity(action.id, action.target.minor, action.deadline.toString(), action.priority, action.monthlyPlan.minor, action.anchorDay)
                if (dao.goals(book).any { it.bucketId == action.id }) check(dao.updateGoal(goal) == 1) else dao.insertGoal(goal)
            }
            is PlanningAction.Move -> {
                nonnegative(action.amount)
                require(action.amount.minor > 0 && action.sourceId != action.destinationId && action.reason.isNotBlank() && action.reason.length <= 500) { "A positive move and explicit reason are required" }
                val snapshot = read(ledger, book, today(book))
                val source = action.sourceId?.let { sourceId -> requireNotNull(snapshot.buckets.firstOrNull { it.id == sourceId && !it.archived }) { "Source reserve not available" } }
                if (action.destinationId != null) require(snapshot.buckets.any { it.id == action.destinationId && !it.archived }) { "Destination reserve not available" }
                require(action.amount.minor <= (source?.funded ?: snapshot.reservations.unassigned).minor) { "Not enough funded/unassigned money" }
                if (source?.protected == true) {
                    val expected = diversion(snapshot, source.id, action.destinationId, action.amount).consent
                    require(action.consent == expected) { "Protected money requires a current reviewed diversion confirmation" }
                }
                allocation(command, "MOVE", today(book), action.reason, action.sourceId, action.destinationId, action.amount)
            }
            is PlanningAction.Configure -> {
                ZoneId.of(action.timezone); nonnegative(action.safetyBuffer); nonnegative(action.monthlyCapacity)
                require(action.horizonDays in 1..366) { "Obligation horizon must be 1..366 days" }
                require(!action.setupComplete || ledger.accounts.isNotEmpty()) { "Create an account before completing setup" }
                val profile = FinancialProfileEntity(book, action.timezone, action.safetyBuffer.minor, action.monthlyCapacity.minor, action.horizonDays, action.setupComplete)
                if (dao.profile(book) == null) dao.insertProfile(profile) else check(dao.updateProfile(profile) == 1)
            }
            is PlanningAction.AdvanceMonth -> advanceMonth(command, action.through, ledger)
        }
        validate(book)
    }

    private suspend fun setBudget(book: String, action: PlanningAction.SetBudget) {
        id(action.id); label(action.category); nonnegative(action.limit)
        require(action.month.year in 1..9999) { "Unsupported budget month" }
        val existing = dao.buckets(book).firstOrNull { it.id == action.id }
        if (existing == null) dao.insertBucket(ReserveBucketEntity(action.id, book, action.category, "CATEGORY", false, action.category, action.month.toString(), false))
        else require(existing.kind == "CATEGORY" && existing.category == action.category && existing.month == action.month.toString() && !existing.archived) { "Budget category/month cannot change" }
        val previous = dao.budgets(book).firstOrNull { it.bucketId == action.id }
        require(previous?.closed != true) { "Closed budget cannot be edited" }
        val value = MonthlyBudgetEntity(action.id, action.limit.minor, previous?.carriedMinor ?: 0, action.rollover.name, false)
        if (previous == null) dao.insertBudget(value) else check(dao.updateBudget(value) == 1)
    }

    private suspend fun advanceMonth(command: LedgerCommand, through: YearMonth, ledger: LedgerSnapshot) {
        require(through.year in 1..9999 && through <= YearMonth.from(today(command.bookId))) { "Cannot close future budget periods in the book timezone" }
        var steps = 0
        while (true) {
            val snapshot = read(ledger, command.bookId, today(command.bookId))
            val previous = snapshot.budgets.filter { !it.closed && it.month < through }.minByOrNull { it.month } ?: break
            require(++steps <= 2400) { "Advance budgets in smaller date ranges" }
            val nextMonth = previous.month.plusMonths(1)
            var next = snapshot.budgets.firstOrNull { it.month == nextMonth && it.category == previous.category }
            if (next == null) {
                val newId = uid()
                setBudget(command.bookId, PlanningAction.SetBudget(newId, previous.category, nextMonth, previous.limit, previous.rollover))
                next = read(ledger, command.bookId, today(command.bookId)).budgets.single { it.bucketId == newId }
            }
            val nextEntity = dao.budgets(command.bookId).single { it.bucketId == next.bucketId }
            check(!nextEntity.closed) { "Cannot roll ownership into a closed budget" }
            check(dao.updateBudget(nextEntity.copy(carriedMinor = Math.addExact(nextEntity.carriedMinor, previous.position.carryForward.minor))) == 1)
            if (previous.funded.minor > 0) allocation(command, "ROLLOVER", nextMonth.atDay(1), "Explicit monthly rollover",
                previous.bucketId, if (previous.rollover == RolloverPolicy.POSITIVE_REMAINDER) next.bucketId else null, previous.funded)
            val old = dao.budgets(command.bookId).single { it.bucketId == previous.bucketId }
            check(dao.updateBudget(old.copy(closed = true)) == 1)
            val bucket = dao.buckets(command.bookId).single { it.id == previous.bucketId }
            check(dao.updateBucket(bucket.copy(archived = true)) == 1)
        }
    }

    /** Hooks run after actual postings and before the shared book revision advances. */
    suspend fun afterLedger(command: LedgerCommand, primary: String, secondary: String?) {
        when (val action = command.action) {
            is LedgerAction.Post -> consumeDraft(command, primary, action.draft)
            is LedgerAction.Reverse -> refund(command, action.entryId, primary, action.date)
            is LedgerAction.Correct -> {
                refund(command, action.entryId, requireNotNull(secondary), action.replacement.date)
                consumeDraft(command, primary, action.replacement)
            }
            else -> Unit
        }
        validate(command.bookId)
    }

    internal suspend fun consumeDraft(command: LedgerCommand, journal: String, draft: TransactionDraft, fundingMonth: YearMonth = YearMonth.from(draft.date)) {
        val splits = when (draft) {
            is TransactionDraft.Expense -> draft.splits
            is TransactionDraft.Transfer -> if (draft.fee.minor > 0) listOf(Split("Transfer fees", draft.fee)) else emptyList()
            else -> emptyList()
        }.groupBy { it.category }.mapValues { (_, parts) -> sumMoney(parts.map { it.amount }, INR) }
        for ((category, amount) in splits) {
            val bucket = dao.buckets(command.bookId).firstOrNull { it.category == category && it.month == fundingMonth.toString() && !it.archived } ?: continue
            val funded = balances(command.bookId)[bucket.id] ?: m(0)
            val consumed = m(minOf(funded.minor, amount.minor))
            if (consumed.minor > 0) allocation(command, "CONSUME", draft.date, "Funded category spending", bucket.id, null, consumed, journal)
        }
    }

    private suspend fun refund(command: LedgerCommand, originalId: String, reversalId: String, date: LocalDate) {
        val allLegs = dao.legs(command.bookId)
        for (entry in dao.linked(command.bookId, originalId).filter { it.kind in listOf("CONSUME", "INVEST_CONSUME") }) {
            for (leg in allLegs.filter { it.entryId == entry.id && it.bucketId != null && it.minor < 0 }) {
                val bucket = dao.buckets(command.bookId).single { it.id == leg.bucketId }
                val budget = dao.budgets(command.bookId).firstOrNull { it.bucketId == bucket.id }
                // Closed-period refunds become actual unassigned money; no old reservation is resurrected.
                val openCurrentOrFuture = bucket.month?.let(YearMonth::parse)?.let { it >= YearMonth.from(date) } == true
                if (!bucket.archived && budget?.closed != true && (openCurrentOrFuture || entry.kind == "INVEST_CONSUME")) {
                    allocation(command, "REFUND", date, "Restore original funded spending", null, bucket.id, -m(leg.minor), reversalId)
                }
            }
        }
    }

    internal suspend fun consumeInvestment(command: LedgerCommand, journal: String, bucketId: String, amount: Money, date: LocalDate) {
        val balance = balances(command.bookId)[bucketId] ?: m(0)
        require(amount.currency == INR && amount.minor > 0 && amount.minor <= balance.minor) { "Contribution exceeds funded investment reserve" }
        allocation(command, "INVEST_CONSUME", date, "Move funded ownership into tracked investment contribution", bucketId, null, amount, journal)
        validate(command.bookId)
    }

    internal suspend fun consumeCorrectedInvestment(command: LedgerCommand, journal: String, bucketId: String, amount: Money, date: LocalDate) {
        val available = balances(command.bookId)[bucketId] ?: m(0)
        val consumed = m(minOf(available.minor, amount.minor))
        if (consumed.minor > 0) allocation(command, "INVEST_CONSUME", date, "Funded portion of corrected actual contribution", bucketId, null, consumed, journal)
        validate(command.bookId)
    }

    private suspend fun allocation(command: LedgerCommand, kind: String, date: LocalDate, reason: String,
        source: String?, destination: String?, amount: Money, linkedJournal: String? = null) {
        require(source != destination && amount.minor > 0) { "Virtual movement must conserve positive ownership" }
        val entry = uid()
        dao.insertAllocation(AllocationJournalEntity(entry, command.bookId, command.id, kind, date.toString(), reason, clock.millis(), linkedJournal))
        dao.insertLegs(listOf(AllocationLegEntity(uid(), entry, source, Math.negateExact(amount.minor)),
            AllocationLegEntity(uid(), entry, destination, amount.minor)))
        dao.seal(AllocationSealEntity(entry))
    }
}
