package com.fintrack.data.session

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.ledger.RoomLedgerRepository
import com.fintrack.data.local.*
import com.fintrack.data.schedules.RoomScheduleRepository
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.PlanningAction
import com.fintrack.domain.schedules.*
import com.fintrack.domain.session.SetupMode
import com.fintrack.engine.*
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomFinanceSessionRepositoryTest {
    private val now = AtomicReference(Instant.parse("2026-10-23T12:00:00Z"))
    private val clock: Clock = object : Clock() {
        override fun instant(): Instant = now.get()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = object : Clock() {
            override fun instant(): Instant = now.get()
            override fun getZone(): ZoneId = zone
            override fun withZone(zone: ZoneId): Clock = this@RoomFinanceSessionRepositoryTest.clock.withZone(zone)
        }
    }
    private val today = LocalDate.of(2026, 10, 23)
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var session: RoomFinanceSessionRepository
    private lateinit var pulses: Channel<Unit>
    private fun m(value: Long) = Money(value, INR)
    private fun uid() = UUID.randomUUID().toString()
    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000, block) }
    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext(); file = "f-${uid().take(16)}.db"
        db = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
            .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
        ledger = RoomLedgerRepository(db, clock); ledger.initializeBook(); pulses = Channel(Channel.UNLIMITED)
        session = RoomFinanceSessionRepository(db, ledger, RoomScheduleRepository(db, ledger, clock), clock, pulses.receiveAsFlow())
    }
    @After fun teardown() { pulses.close(); db.close(); context.deleteDatabase(file) }
    private suspend fun send(action: LedgerAction) = ledger.execute(LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision, action))
    private suspend fun plan(action: PlanningAction) = send(LedgerAction.Plan(action))
    private suspend fun account(amount: Long = 1000) = send(LedgerAction.CreateAccount("bank", "Bank", AccountKind.BANK, m(amount), today))
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block() } catch (_: Exception) { return }
        throw AssertionError("Expected rejection")
    }

    @Test fun setupModesPreserveExistingAccountsAndPostedHistory() = scenario {
        assertEquals(SetupMode.NEW_BOOK, session.snapshot(DEFAULT_BOOK).setupMode)
        account(0); assertEquals(SetupMode.METADATA_ONLY, session.snapshot(DEFAULT_BOOK).setupMode)
        send(LedgerAction.Post(TransactionDraft.Income("bank", m(100), "Salary", today)))
        assertEquals(SetupMode.EXISTING_FINANCIAL_BOOK, session.snapshot(DEFAULT_BOOK).setupMode)
        plan(PlanningAction.Configure("UTC", m(0), m(0), 30, true))
        assertEquals(SetupMode.COMPLETE, session.snapshot(DEFAULT_BOOK).setupMode)
        assertEquals(m(100), session.snapshot(DEFAULT_BOOK).planning.ledger.liquid)
    }
    @Test fun currentForecastUsesBookDateAndActualOpeningWithoutPostingIncome() = scenario {
        account(); plan(PlanningAction.Configure("Pacific/Kiritimati", m(0), m(0), 30, true))
        val bookDate = today.plusDays(1)
        send(LedgerAction.Schedule(ScheduleAction.Define(ScheduledRule("pay", "Pay",
            TransactionDraft.Income("bank", m(500), "Salary", bookDate), Recurrence(bookDate, Frequency.MONTHLY), true))))
        val before = ledger.snapshot(); val result = session.forecast(DEFAULT_BOOK, 30)
        assertEquals(bookDate, result.asOf); assertEquals(bookDate.plusDays(30), result.through)
        assertEquals(m(1000), result.currentSafeToSpend.available)
        assertTrue(result.events.any { it.date == bookDate && it.delta == m(500) })
        assertEquals(before, ledger.snapshot())
    }
    @Test fun purchaseCannotUsePastDateAgainstCurrentActualOpening() = scenario {
        account(); val before = ledger.snapshot()
        rejects { session.simulatePurchase(DEFAULT_BOOK, 30, today.minusDays(1), m(100)) }
        rejects { session.forecast(DEFAULT_BOOK, -1) }; rejects { session.forecast(DEFAULT_BOOK, 367) }
        rejects { session.simulatePurchase(DEFAULT_BOOK, 367, today, m(100)) }
        assertEquals(before, ledger.snapshot())
    }
    @Test fun configuredRolloverIsAtomicAuditedAndRepeatedPreparationDoesNothing() = scenario {
        account(); val month = YearMonth.from(today)
        plan(PlanningAction.SetBudget("food", "Food", month.minusMonths(1), m(500), RolloverPolicy.POSITIVE_REMAINDER))
        plan(PlanningAction.Move(null, "food", m(300), "Fund last month"))
        val before = session.snapshot(DEFAULT_BOOK)
        assertEquals(month, before.pendingRolloverThrough)
        val receipt = session.prepareCurrentPeriod(DEFAULT_BOOK)!!
        val after = session.snapshot(DEFAULT_BOOK)
        assertEquals(before.revision + 1, receipt.revision); assertNull(after.pendingRolloverThrough)
        assertEquals(before.planning.ledger.liquid, after.planning.ledger.liquid)
        assertEquals(m(300), after.planning.budgets.single { it.month == month }.funded)
        assertNull(session.prepareCurrentPeriod(DEFAULT_BOOK)); assertEquals(after, session.snapshot(DEFAULT_BOOK))
        assertEquals("Planning.AdvanceMonth", db.openHelper.writableDatabase.query(
            "SELECT action FROM audit_events WHERE commandId = ?", arrayOf(receipt.commandId)).use { check(it.moveToFirst()); it.getString(0) })
    }
    @Test fun futureBudgetIsNotRolledForwardOrSilentlyFunded() = scenario {
        account(); plan(PlanningAction.SetBudget("future", "Food", YearMonth.from(today).plusMonths(1), m(500)))
        val before = session.snapshot(DEFAULT_BOOK)
        assertNull(before.pendingRolloverThrough); assertNull(session.prepareCurrentPeriod(DEFAULT_BOOK))
        assertEquals(before, session.snapshot(DEFAULT_BOOK)); assertEquals(m(0), before.planning.reservations.reserved)
    }
    @Test fun clockPulseUpdatesGoalWarningsWithoutAnyFinancialWrite() = scenario {
        account(); plan(PlanningAction.SetGoal("goal", "Goal", m(500), today, 1, m(500), 23))
        val initial = CompletableDeferred<Unit>()
        val collection = async { session.observe(DEFAULT_BOOK).onEach { initial.complete(Unit) }.take(2).toList() }
        initial.await(); val revision = ledger.snapshot().revision
        now.set(Instant.parse("2026-10-24T12:00:00Z")); pulses.send(Unit)
        val values = collection.await()
        assertEquals(today, values.first().today); assertEquals(today.plusDays(1), values.last().today)
        assertEquals(revision, values.last().revision)
        assertNotEquals(values.first().planning.goalCollisions, values.last().planning.goalCollisions)
        assertEquals(revision, ledger.snapshot().revision)
    }
    @Test fun financialRevisionRefreshesActualMoneyAndFundedGoalWarningsTogether() = scenario {
        account(); plan(PlanningAction.SetGoal("goal", "Goal", m(500), today.plusMonths(1), 1, m(500), 23))
        val initial = CompletableDeferred<Unit>()
        val collection = async { session.observe(DEFAULT_BOOK).onEach { initial.complete(Unit) }.take(2).toList() }
        initial.await(); plan(PlanningAction.Move(null, "goal", m(400), "Fund goal"))
        val values = collection.await()
        assertEquals(m(0), values.first().planning.goalFunding.single().progress)
        assertEquals(m(400), values.last().planning.goalFunding.single().progress)
        assertEquals(m(600), values.last().planning.safeToSpend.available)
        assertEquals(values.first().revision + 1, values.last().revision)
        assertEquals(values.first().planning.ledger.accounts, values.last().planning.ledger.accounts)
    }
    @Test fun concurrentPeriodPreparationProducesOneRolloverCommand() = scenario {
        account(); plan(PlanningAction.SetBudget("food", "Food", YearMonth.from(today).minusMonths(1), m(500)))
        val revision = ledger.snapshot().revision
        val results = listOf(async { session.prepareCurrentPeriod(DEFAULT_BOOK) }, async { session.prepareCurrentPeriod(DEFAULT_BOOK) }).awaitAll()
        assertEquals(1, results.count { it != null }); assertEquals(revision + 1, ledger.snapshot().revision)
    }
}
