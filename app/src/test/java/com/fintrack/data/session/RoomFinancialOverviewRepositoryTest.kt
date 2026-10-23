package com.fintrack.data.session

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.ledger.RoomHistoryRepository
import com.fintrack.data.ledger.RoomLedgerRepository
import com.fintrack.data.local.*
import com.fintrack.data.reports.RoomFinancialReportsRepository
import com.fintrack.data.schedules.RoomScheduleRepository
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.*
import com.fintrack.domain.reports.*
import com.fintrack.domain.schedules.*
import com.fintrack.domain.session.*
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
class RoomFinancialOverviewRepositoryTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val initialClock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var clock: Clock
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var sessions: RoomFinanceSessionRepository
    private lateinit var reports: RoomFinancialReportsRepository
    private lateinit var history: RoomHistoryRepository
    private lateinit var overview: RoomFinancialOverviewRepository
    private fun uid() = UUID.randomUUID().toString()
    private fun m(value: Long) = Money(value, INR)
    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking { block() }
    private fun repositories(nextClock: Clock, pulses: Flow<Unit> = emptyFlow()) {
        clock = nextClock; ledger = RoomLedgerRepository(db, clock)
        val schedules = RoomScheduleRepository(db, ledger, clock)
        sessions = RoomFinanceSessionRepository(db, ledger, schedules, clock, emptyFlow())
        reports = RoomFinancialReportsRepository(db, clock); history = RoomHistoryRepository(db)
        overview = RoomFinancialOverviewRepository(db, sessions, reports, history, clock, pulses)
    }
    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext(); file = "o-${uid().take(16)}.db"
        db = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
            .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
        repositories(initialClock); ledger.initializeBook()
    }
    @After fun teardown() { db.close(); context.deleteDatabase(file) }
    private suspend fun send(action: LedgerAction) = ledger.execute(LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision, action))
    private suspend fun plan(action: PlanningAction) = send(LedgerAction.Plan(action))
    private suspend fun bank() = send(LedgerAction.Setup(listOf(LedgerAction.CreateAccount("bank", "Bank", AccountKind.BANK,
        m(1000), today.withMonth(9).withDayOfMonth(1))), emptyList(), PlanningAction.Configure("UTC", m(0), m(200), 30, true)))
    private suspend fun expense(amount: Long, clearing: Clearing = Clearing.CLEARED) = send(LedgerAction.Post(
        TransactionDraft.Expense("bank", m(amount), listOf(Split("Food", m(amount))), today, clearing)))
    private suspend fun snapshot() = overview.snapshot(DEFAULT_BOOK)
    private suspend fun goal() = plan(PlanningAction.SetGoal("goal", "Protected goal", m(900), today.plusMonths(3), 1, m(100), 23))
    private fun count(table: String) = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst()); it.getLong(0) }
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }; throw AssertionError("Expected rejection")
    }
    private fun consistent(value: FinancialOverview) {
        assertTrue(listOf(value.monthly.revision, value.weekly.revision, value.recent.revision, value.pending.revision,
            value.schedules.revision).all { it == value.revision })
        assertEquals(value.today, value.monthly.period.through); assertEquals(value.today, value.weekly.period.through)
        assertEquals(value.today, value.schedules.asOf)
    }

    @Test fun emptyBookRequiresSetupAndDoesNotInventFinancialActivity() = scenario {
        val value = snapshot(); consistent(value)
        assertEquals(0L, value.revision); assertEquals(SetupMode.NEW_BOOK, value.session.setupMode)
        assertTrue(value.recent.entries.isEmpty()); assertTrue(value.pending.entries.isEmpty())
        assertEquals(m(0), value.monthly.posted.totals.income); assertEquals(m(0), value.session.planning.safeToSpend.available)
        assertTrue(value.attention.any { it is FinancialAttention.SetupNeeded }); assertTrue(value.attention.any { it is FinancialAttention.WeeklyReviewDue })
        assertEquals(0L, count("commands")); assertEquals(0L, count("weekly_reviews")); assertEquals(0L, count("journals"))
    }
    @Test fun actualPendingPlannedAndFundedValuesRemainSeparate() = scenario {
        bank(); expense(100, Clearing.PENDING); goal(); plan(PlanningAction.Move(null, "goal", m(200), "Fund goal"))
        send(LedgerAction.Schedule(ScheduleAction.Define(ScheduledRule("bill", "Upcoming bill",
            TransactionDraft.Expense("bank", m(100), listOf(Split("Food", m(100))), today.plusDays(1)),
            Recurrence(today.plusDays(1), Frequency.MONTHLY), true))))
        val value = snapshot(); consistent(value)
        assertEquals(SetupMode.COMPLETE, value.session.setupMode); assertEquals(m(900), value.session.planning.ledger.liquid)
        assertEquals(m(200), value.session.planning.reservations.reserved); assertEquals(m(100), value.session.planning.uncoveredObligations)
        assertEquals(m(600), value.session.planning.safeToSpend.available)
        assertEquals(m(100), value.monthly.posted.totals.expense); assertEquals(m(0), value.monthly.cleared.totals.expense)
        assertEquals(1L, value.monthly.unresolvedPendingCount); assertEquals(1, value.pending.entries.size)
        assertEquals(m(200), value.session.planning.goalFunding.single().progress)
        assertEquals(1, value.schedules.occurrences.size); assertEquals(OccurrenceStatus.AWAITING_CONFIRMATION, value.schedules.occurrences.single().status)
        assertTrue(value.attention.contains(FinancialAttention.PendingClearing(1)))
        assertTrue(value.attention.any { it is FinancialAttention.UncoveredObligations && it.amount == m(100) })
    }
    @Test fun currentBudgetOverrunUsesOverspentAndNotClampedRemaining() = scenario {
        bank(); plan(PlanningAction.SetBudget("food", "Food", YearMonth.from(today), m(20)))
        plan(PlanningAction.Move(null, "food", m(10), "Fund budget")); expense(25)
        val value = snapshot(); consistent(value)
        assertEquals(m(0), value.session.planning.budgets.single().position.remaining)
        assertTrue(value.attention.contains(FinancialAttention.BudgetOverrun("food", "Food", m(5))))
        assertEquals(m(25), value.monthly.posted.totals.expense); assertEquals(m(0), value.session.planning.reservations.reserved)
    }
    @Test fun observedFinancialChangesRecalculateBackingAndGoalWarnings() = scenario {
        bank(); goal(); plan(PlanningAction.Move(null, "goal", m(800), "Fund protected goal"))
        val events = Channel<FinancialOverview>(Channel.UNLIMITED)
        val collecting = launch { overview.observe(DEFAULT_BOOK).collect { events.send(it) } }
        suspend fun next(revision: Long): FinancialOverview = withTimeout(10_000) {
            var value = events.receive()
            while (value.revision < revision) value = events.receive()
            assertEquals(revision, value.revision); value
        }
        try {
            val start = next(ledger.snapshot().revision); assertEquals(m(800), start.session.planning.goalFunding.single().progress)
            val spent = expense(500); val unsupported = next(spent.revision); consistent(unsupported)
            assertEquals(m(500), unsupported.session.planning.goalFunding.single().progress)
            assertTrue(unsupported.attention.contains(FinancialAttention.ReservationShortfall(m(300))))
            assertTrue(unsupported.attention.contains(FinancialAttention.GoalBackingReview(listOf("goal"))))
            val receipt = send(LedgerAction.Post(TransactionDraft.Income("bank", m(400), "Salary", today)))
            val restored = next(receipt.revision); consistent(restored)
            assertEquals(m(800), restored.session.planning.goalFunding.single().progress)
            assertFalse(restored.attention.any { it is FinancialAttention.ReservationShortfall || it is FinancialAttention.GoalBackingReview })
        } finally { collecting.cancelAndJoin(); events.close() }
    }
    @Test fun weeklyCompletionRetainsItsRevisionAfterLaterChanges() = scenario {
        bank(); val week = today.minusDays(4)
        val receipt = send(LedgerAction.Schedule(ScheduleAction.WeeklyReview(week, "Reviewed actual money")))
        val recorded = snapshot(); consistent(recorded)
        assertEquals(receipt.revision, recorded.review.recorded!!.revision); assertFalse(recorded.review.changedSinceReview)
        assertFalse(recorded.attention.any { it is FinancialAttention.WeeklyReviewDue || it is FinancialAttention.ChangedSinceReview })
        expense(10)
        val changed = snapshot(); assertTrue(changed.review.changedSinceReview)
        assertEquals(receipt.revision, changed.review.recorded!!.revision)
        assertTrue(changed.attention.contains(FinancialAttention.ChangedSinceReview(receipt.revision, changed.revision)))
        assertEquals(1L, count("weekly_reviews")); assertEquals(m(10), changed.weekly.posted.totals.expense)
    }
    @Test fun timezoneMidnightRefreshesReportsWarningsAndPendingRollover() = scenario {
        bank(); plan(PlanningAction.Configure("Asia/Kolkata", m(0), m(200), 30, true))
        plan(PlanningAction.SetBudget("food", "Food", YearMonth.from(today), m(100)))
        plan(PlanningAction.SetGoal("due", "Due goal", m(100), today.withDayOfMonth(31), 1, m(0), 31))
        val moving = MovingClock(Instant.parse("2026-10-31T18:29:00Z")); val pulses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        repositories(moving, pulses)
        val events = Channel<FinancialOverview>(Channel.UNLIMITED)
        val collecting = launch { overview.observe(DEFAULT_BOOK).collect { events.send(it) } }
        try {
            val before = withTimeout(10_000) { events.receive() }
            assertEquals(LocalDate.of(2026, 10, 31), before.today); assertNull(before.session.pendingRolloverThrough)
            assertFalse(before.attention.any { it is FinancialAttention.MissedGoalDeadline })
            withTimeout(10_000) { pulses.subscriptionCount.first { it > 0 } }
            moving.set(Instant.parse("2026-10-31T18:31:00Z")); pulses.emit(Unit)
            val after = withTimeout(10_000) { events.receive() }; consistent(after)
            assertEquals(LocalDate.of(2026, 11, 1), after.today); assertEquals(before.revision, after.revision)
            assertEquals(LocalDate.of(2026, 11, 1), after.monthly.period.from)
            assertEquals(YearMonth.of(2026, 11), after.session.pendingRolloverThrough)
            assertTrue(after.attention.contains(FinancialAttention.MissedGoalDeadline("due")))
            assertFalse(after.session.planning.budgets.single().closed)
        } finally { collecting.cancelAndJoin(); events.close() }
    }
    @Test fun composedReadsAreReadOnlyAndCannotLeakAnotherBook() = scenario {
        bank(); ledger.initializeBook("other")
        ledger.execute(LedgerCommand(uid(), "other", 0, LedgerAction.CreateAccount("foreign", "Foreign", AccountKind.CASH, m(777), today)))
        val before = ledger.snapshot(); val tables = listOf("commands", "audit_events", "journals", "allocation_journals", "weekly_reviews")
        val counts = tables.map(::count); val first = snapshot()
        repeat(2) { assertEquals(first, snapshot()) }
        val other = overview.snapshot("other"); consistent(other)
        assertEquals(m(777), other.session.planning.ledger.liquid); assertEquals(m(1000), first.session.planning.ledger.liquid)
        assertEquals("other", other.monthly.bookId); assertFalse(first.session.planning.ledger.accounts.any { it.id == "foreign" })
        assertEquals(before, ledger.snapshot()); assertEquals(counts, tables.map(::count))
    }
    @Test fun failedSourceDoesNotPublishFabricatedZeroMoneyOrWriteData() = scenario {
        bank(); val before = ledger.snapshot(); val counts = count("commands")
        val failing = object : FinancialReportsRepository by reports {
            override suspend fun report(bookId: String, period: ReportPeriod): FinancialReport = error("Synthetic read failure")
        }
        val service = RoomFinancialOverviewRepository(db, sessions, failing, history, clock, emptyFlow())
        assertEquals("Synthetic read failure", rejects { service.snapshot(DEFAULT_BOOK) }.message)
        assertEquals(before, ledger.snapshot()); assertEquals(counts, count("commands")); assertEquals(m(1000), snapshot().session.planning.ledger.liquid)
    }
    @Test fun mixedSourceRevisionIsRejectedBeforePublishingOverview() = scenario {
        bank(); val before = ledger.snapshot()
        val alterations: List<(FinancialReport) -> FinancialReport> = listOf(
            { it.copy(revision = it.revision + 1) },
            { it.copy(period = it.period.copy(through = it.period.through.minusDays(1))) }
        )
        for (alter in alterations) {
            val inconsistent = object : FinancialReportsRepository by reports {
                override suspend fun report(bookId: String, period: ReportPeriod) = alter(reports.report(bookId, period))
            }
            val service = RoomFinancialOverviewRepository(db, sessions, inconsistent, history, clock, emptyFlow())
            assertTrue(rejects { service.snapshot(DEFAULT_BOOK) } is IllegalStateException)
        }
        assertEquals(before, ledger.snapshot())
    }
}

private class MovingClock private constructor(private val value: AtomicReference<Instant>, private val currentZone: ZoneId) : Clock() {
    constructor(initial: Instant) : this(AtomicReference(initial), ZoneOffset.UTC)
    override fun getZone(): ZoneId = currentZone
    override fun withZone(zone: ZoneId): Clock = MovingClock(value, zone)
    override fun instant(): Instant = value.get()
    fun set(instant: Instant) { value.set(instant) }
}
