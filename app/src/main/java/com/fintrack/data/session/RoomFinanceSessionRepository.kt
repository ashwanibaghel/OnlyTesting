package com.fintrack.data.session

import androidx.room.withTransaction
import com.fintrack.data.local.FinTrackDatabase
import com.fintrack.data.planning.RoomPlanningStore
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.PlanningAction
import com.fintrack.domain.schedules.PurchaseFunding
import com.fintrack.domain.schedules.ScheduleRepository
import com.fintrack.domain.session.*
import com.fintrack.engine.Money
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

class RoomFinanceSessionRepository(private val db: FinTrackDatabase,
    private val ledger: LedgerRepository, private val schedules: ScheduleRepository,
    private val clock: Clock, private val clockPulses: Flow<Unit> = minutePulses()) : FinanceSessionRepository {
    private val planning = RoomPlanningStore(db, clock)
    private suspend fun today(book: String): LocalDate = LocalDate.now(clock.withZone(
        db.planningDao().profile(book)?.timezone?.let(ZoneId::of) ?: clock.zone))

    override suspend fun snapshot(bookId: String): FinanceSession = db.withTransaction {
        val current = planning.read(ledger.snapshot(bookId), bookId, today(bookId))
        val mode = when {
            current.profile.setupComplete -> SetupMode.COMPLETE
            current.ledger.history.isNotEmpty() -> SetupMode.EXISTING_FINANCIAL_BOOK
            current.ledger.accounts.isNotEmpty() -> SetupMode.METADATA_ONLY
            else -> SetupMode.NEW_BOOK
        }
        val month = YearMonth.from(current.asOf)
        FinanceSession(current, mode, month.takeIf { current.budgets.any { budget -> !budget.closed && budget.month < month } })
    }

    override fun observe(bookId: String): Flow<FinanceSession> = merge(
        db.ledgerDao().revision(bookId).map { Unit }, clockPulses
    ).map { snapshot(bookId) }.distinctUntilChanged()

    override suspend fun prepareCurrentPeriod(bookId: String): CommandReceipt? = db.withTransaction {
        val current = snapshot(bookId)
        val through = current.pendingRolloverThrough ?: return@withTransaction null
        ledger.execute(LedgerCommand(UUID.randomUUID().toString(), bookId, current.revision,
            LedgerAction.Plan(PlanningAction.AdvanceMonth(through))))
    }

    override suspend fun forecast(bookId: String, horizonDays: Int) = db.withTransaction {
        require(horizonDays in 0..366) { "Projection horizon must be 0..366 days from the book's current date" }
        val start = today(bookId)
        schedules.forecast(bookId, start, start.plusDays(horizonDays.toLong()))
    }

    override suspend fun simulatePurchase(bookId: String, horizonDays: Int, date: LocalDate,
        amount: Money, funding: PurchaseFunding?) = db.withTransaction {
        require(horizonDays in 0..366) { "Projection horizon must be 0..366 days from the book's current date" }
        val start = today(bookId)
        schedules.simulatePurchase(bookId, start, start.plusDays(horizonDays.toLong()), date, amount, funding)
    }
}

private fun minutePulses(): Flow<Unit> = flow {
    while (true) { delay(60_000); emit(Unit) }
}
