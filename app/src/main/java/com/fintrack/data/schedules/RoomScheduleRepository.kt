package com.fintrack.data.schedules

import androidx.room.withTransaction
import com.fintrack.data.local.FinTrackDatabase
import com.fintrack.data.planning.RoomPlanningStore
import com.fintrack.domain.ledger.INR
import com.fintrack.domain.ledger.LedgerRepository
import com.fintrack.domain.schedules.*
import com.fintrack.engine.*
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.map

class RoomScheduleRepository(private val db: FinTrackDatabase, private val ledger: LedgerRepository,
    clock: Clock) : ScheduleRepository {
    private val store = RoomScheduleStore(db, clock)
    private val planning = RoomPlanningStore(db, clock)
    private fun m(value: Long) = Money(value, INR)

    override suspend fun snapshot(bookId: String, asOf: LocalDate, through: LocalDate) = db.withTransaction {
        val current = planning.read(ledger.snapshot(bookId), bookId, asOf)
        store.read(bookId, asOf, through, current.buckets)
    }
    override fun observe(bookId: String, asOf: LocalDate, through: LocalDate) =
        db.ledgerDao().revision(bookId).map { snapshot(bookId, asOf, through) }

    override suspend fun forecast(bookId: String, asOf: LocalDate, through: LocalDate): CashFlowProjection = db.withTransaction {
        val current = planning.read(ledger.snapshot(bookId), bookId, asOf)
        val scheduled = store.read(bookId, asOf, through, current.buckets)
        val events = scheduled.occurrences.filter { it.affectsLiquidCash && it.status in listOf(OccurrenceStatus.AWAITING_CONFIRMATION, OccurrenceStatus.REOPENED) }.mapNotNull { item ->
            val date = maxOf(asOf, item.scheduledDate)
            when (val draft = item.template) {
                is com.fintrack.domain.ledger.TransactionDraft.Income ->
                    if (item.scheduledDate < asOf) null else CashEvent(item.key, date, draft.amount)
                is com.fintrack.domain.ledger.TransactionDraft.Expense -> CashEvent(item.key, date, -draft.amount)
                else -> null
            }
        }
        val assumptions = listOf(
            "Opening money is actual posted liquid assets; manually confirmed pending activity is already included once.",
            "Planned activity directly in non-liquid investment accounts is excluded from liquid cash events and liquid obligation coverage; it remains separately identified schedule activity.",
            "Unconfirmed scheduled income is assumed only on future/current due dates and is not guaranteed or current spendable money.",
            "Unpaid scheduled expenses are assumed on their due date; overdue expenses occur at the projection start. Overdue income is excluded.",
            "Outflows precede inflows on the same day. No market returns, trading profits, taxes, new fees or unscheduled activity are assumed.",
            "Cash projection is separate from funded reserves. Current Safe-to-Spend uses the configured ${current.profile.obligationHorizonDays}-day obligation horizon."
        )
        CashFlowProjection(current.ledger.revision, asOf, through, events,
            com.fintrack.engine.forecast(asOf, through, current.ledger.liquid, events, assumptions), current.safeToSpend, assumptions,
            forecastTimeline(asOf, through, current.ledger.liquid, events, assumptions))
    }

    override suspend fun simulatePurchase(bookId: String, asOf: LocalDate, through: LocalDate,
        date: LocalDate, amount: Money, funding: PurchaseFunding?): PurchaseImpact = db.withTransaction {
        require(amount.currency == INR && amount.minor > 0 && date >= asOf && date <= through) { "Choose a positive INR purchase within the forecast horizon" }
        val current = planning.read(ledger.snapshot(bookId), bookId, asOf)
        val before = forecast(bookId, asOf, through)
        val diversion = funding?.let {
            require(it.amount.currency == INR && it.amount.minor > 0 && it.amount.minor <= amount.minor) { "Reserve funding must be positive and no greater than the purchase" }
            planning.diversion(current, it.bucketId, null, it.amount)
        }
        val after = com.fintrack.engine.forecast(asOf, through, current.ledger.liquid,
            before.events + CashEvent("purchase-simulation", date, -amount), before.assumptions + "One hypothetical purchase is included; no data is posted.")
        val balances = current.buckets.associate { it.id to if (it.id == funding?.bucketId) it.funded - funding.amount else it.funded }
        val uncovered = sumMoney(coverObligations(current.obligations, balances, INR).map { it.uncovered }, INR)
        val reserved = current.reservations.reserved - (funding?.amount ?: m(0))
        val fromCurrent = safeToSpend(current.ledger.liquid - amount, reserved, uncovered, current.profile.safetyBuffer)
        val warnings = buildList {
            if (fromCurrent.deficit.minor > 0) add("Paying from today's actual funds would leave a ${fromCurrent.deficit.toDecimal().toPlainString()} INR commitment/safety deficit.")
            if (after.firstShortfall != null) add("Projected liquid money first becomes negative on ${after.firstShortfall}.")
            if (diversion?.protected == true) add("This scenario uses protected ${diversion.sourceName}; goal impacts require separate reviewed confirmation before any real diversion.")
            add("Simulation only. Future income is uncertain; projected cash does not increase current funded progress or Safe-to-Spend.")
        }
        PurchaseImpact(current.ledger.revision, amount, date, before, after, fromCurrent, diversion, warnings)
    }

    override suspend fun contributions(bookId: String) = db.withTransaction {
        val history = ledger.snapshot(bookId).history.associateBy { it.id }
        db.scheduleDao().contributions(bookId).map { InvestmentContribution(it.journalId, it.bucketId,
            it.investmentAccountId, m(it.minor), LocalDate.parse(it.date), it.reversalOf, history[it.journalId]?.clearedDate) }
    }
    override suspend fun weeklyReviews(bookId: String) = db.withTransaction {
        db.scheduleDao().reviews(bookId).map { WeeklyReviewRecord(LocalDate.parse(it.weekOf), it.reviewedAt, it.note, it.revision) }
    }
}
