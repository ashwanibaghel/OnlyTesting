package com.fintrack.data.reports

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.ledger.RoomLedgerRepository
import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.*
import com.fintrack.domain.reports.*
import com.fintrack.domain.schedules.*
import com.fintrack.engine.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
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
class RoomFinancialReportsRepositoryTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val first = today.withDayOfMonth(1)
    private val september = LocalDate.of(2026, 9, 1)
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var reports: RoomFinancialReportsRepository
    private fun uid() = UUID.randomUUID().toString()
    private fun m(value: Long) = Money(value, INR)
    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking { block() }
    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext(); file = "r-${uid().take(16)}.db"
        db = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
            .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
        ledger = RoomLedgerRepository(db, clock); reports = RoomFinancialReportsRepository(db, clock)
        ledger.initializeBook()
        send(LedgerAction.CreateAccount("bank", "Bank", AccountKind.BANK, m(1000), september))
        send(LedgerAction.CreateAccount("cash", "Cash", AccountKind.CASH, m(0), september))
        send(LedgerAction.CreateAccount("invest", "Investment", AccountKind.INVESTMENT, m(0), september))
    }
    @After fun teardown() { db.close(); context.deleteDatabase(file) }
    private suspend fun send(action: LedgerAction) = ledger.execute(LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision, action))
    private suspend fun post(draft: TransactionDraft) = send(LedgerAction.Post(draft))
    private suspend fun income(amount: Long, date: LocalDate = first, clearing: Clearing = Clearing.CLEARED) =
        post(TransactionDraft.Income("bank", m(amount), "Salary", date, clearing))
    private suspend fun expense(amount: Long, date: LocalDate = first, clearing: Clearing = Clearing.CLEARED) =
        post(TransactionDraft.Expense("bank", m(amount), listOf(Split("Food", m(amount))), date, clearing))
    private suspend fun report(from: LocalDate = first, through: LocalDate = today) = reports.report(DEFAULT_BOOK, ReportPeriod(from, through))
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }; throw AssertionError("Expected rejection")
    }
    private fun count(table: String) = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst()); it.getLong(0) }

    @Test fun openingBalancesAreEquityAndNotOperatingIncome() = scenario {
        val october = report(); assertEquals(m(0), october.posted.totals.income); assertNull(october.posted.totals.savingsRatio)
        assertEquals(m(1000), october.accounts.single { it.id == "bank" }.openingPosted)
        assertEquals(m(1000), october.assetTrend.last().postedAssets); assertEquals(0L, october.posted.journalCount)
        val start = report(september, september)
        assertEquals(m(1000), start.posted.totals.equityMovement); assertEquals(m(1000), start.posted.totals.trackedAssetMovement)
        assertEquals(m(0), start.posted.totals.income); assertEquals(m(0), start.accounts.single { it.id == "bank" }.openingPosted)
    }
    @Test fun splitsTransfersFeesAndInvestmentsConserveActualReports() = scenario {
        income(500)
        post(TransactionDraft.Expense("bank", m(100), listOf(Split("Food", m(60)), Split("Fun", m(40))), first.plusDays(1)))
        post(TransactionDraft.Transfer("bank", "cash", m(200), m(10), first.plusDays(2)))
        post(TransactionDraft.Transfer("bank", "invest", m(100), m(5), first.plusDays(3)))
        val result = report(); val total = result.posted.totals
        assertEquals(m(500), total.income); assertEquals(m(115), total.expense); assertEquals(m(385), total.saved)
        assertEquals(m(285), total.liquidMovement); assertEquals(m(385), total.trackedAssetMovement); assertEquals(m(0), total.equityMovement)
        assertEquals(SavingsRatio(m(385), m(500)), total.savingsRatio)
        assertEquals(mapOf("Salary" to m(500), "Food" to m(60), "Fun" to m(40), "Transfer fees" to m(15)), result.posted.categories.associate { it.category to it.amount })
        assertEquals(m(1285), result.assetTrend.last().postedLiquid); assertEquals(m(100), result.assetTrend.last().postedInvestment)
        assertEquals(m(1385), result.assetTrend.last().postedAssets); assertEquals(4L, result.posted.journalCount)
        assertEquals(result.posted.copy(basis = ReportBasis.CLEARED), result.cleared)
    }
    @Test fun clearingUsesItsActualDateRatherThanRewritingAnEarlierPeriod() = scenario {
        val original = income(300, september.plusDays(28), Clearing.PENDING)
        send(LedgerAction.Clear(original.primaryId, first.plusDays(9)))
        val sep = report(september, september.withDayOfMonth(30))
        assertEquals(m(300), sep.posted.totals.income); assertEquals(m(0), sep.cleared.totals.income)
        assertEquals(1L, sep.unresolvedPendingCount); assertEquals(m(1000), sep.assetTrend.last().clearedAssets)
        val oct = report(); assertEquals(m(0), oct.posted.totals.income); assertEquals(m(300), oct.cleared.totals.income)
        assertEquals(m(1300), oct.assetTrend[8].postedAssets); assertEquals(m(1000), oct.assetTrend[8].clearedAssets)
        assertEquals(m(1300), oct.assetTrend[9].clearedAssets); assertEquals(0L, oct.unresolvedPendingCount)
        assertEquals(0L, oct.posted.journalCount); assertEquals(1L, oct.cleared.journalCount)
    }
    @Test fun expenseRefundRetainsSignedCategoryAndOriginalPeriod() = scenario {
        val paid = expense(100, september.plusDays(9))
        send(LedgerAction.Reverse(paid.primaryId, first.plusDays(4), "Expense refunded"))
        assertEquals(m(100), report(september, september.withDayOfMonth(30)).posted.totals.expense)
        val oct = report(); assertEquals(m(-100), oct.posted.totals.expense); assertEquals(m(100), oct.posted.totals.saved)
        assertEquals(m(-100), oct.posted.categories.single().amount); assertEquals(m(900), oct.assetTrend[3].postedAssets)
        assertEquals(m(1000), oct.assetTrend[4].postedAssets); assertNull(oct.posted.totals.savingsRatio)
    }
    @Test fun correctionIncludesOriginalReversalAndReplacementExactlyOnce() = scenario {
        val paid = expense(100)
        send(LedgerAction.Correct(paid.primaryId, TransactionDraft.Expense("bank", m(70), listOf(Split("Food", m(70))), first.plusDays(3)), "Correct amount"))
        val result = report(); assertEquals(m(70), result.posted.totals.expense); assertEquals(3L, result.posted.journalCount)
        assertEquals(m(930), result.assetTrend.last().postedAssets); assertEquals(m(70), result.posted.categories.single().amount)
    }
    @Test fun reversedPendingEntriesDoNotRemainActionablePendingMoney() = scenario {
        val paid = expense(100, first, Clearing.PENDING)
        assertEquals(1L, report().unresolvedPendingCount)
        send(LedgerAction.Reverse(paid.primaryId, first.plusDays(2), "Pending payment cancelled"))
        assertEquals(1L, report(through = first.plusDays(1)).unresolvedPendingCount)
        val result = report(); assertEquals(0L, result.unresolvedPendingCount); assertEquals(m(0), result.posted.totals.expense)
        assertEquals(m(0), result.cleared.totals.expense); assertEquals(0L, result.cleared.journalCount)
        assertEquals(m(1000), result.assetTrend.last().postedAssets)
    }
    @Test fun reconciliationDifferenceIsEquityRatherThanFictitiousIncomeOrExpense() = scenario {
        send(LedgerAction.Reconcile("bank", first, m(800), BalanceBasis.POSTED, "Explicit statement adjustment"))
        val result = report(); assertEquals(m(-200), result.posted.totals.equityMovement)
        assertEquals(m(0), result.posted.totals.income); assertEquals(m(0), result.posted.totals.expense)
        assertEquals(m(800), result.assetTrend.last().postedAssets); assertTrue(result.posted.categories.isEmpty())
    }
    @Test fun virtualReservationsAndUnconfirmedSchedulesNeverBecomeActualReportMoney() = scenario {
        send(LedgerAction.Plan(PlanningAction.CreateReserve("save", "Savings", BucketKind.SAVINGS)))
        send(LedgerAction.Plan(PlanningAction.Move(null, "save", m(400), "Fund savings")))
        send(LedgerAction.Schedule(ScheduleAction.Define(ScheduledRule("salary", "Expected salary",
            TransactionDraft.Income("bank", m(900), "Salary", today.plusDays(1)), Recurrence(today.plusDays(1), Frequency.MONTHLY), false))))
        val result = report(); assertEquals(m(0), result.posted.totals.income); assertEquals(m(0), result.posted.totals.expense)
        assertEquals(m(1000), result.assetTrend.last().postedAssets); assertEquals(0L, result.posted.journalCount)
    }
    @Test fun fundedManualInvestmentIsTransferWithOnlyFeeAsExpense() = scenario {
        send(LedgerAction.Plan(PlanningAction.SetGoal("goal", "Investment goal", m(1000), today.plusYears(1), 1, m(100), 23, true)))
        send(LedgerAction.Plan(PlanningAction.Move(null, "goal", m(100), "Fund investment")))
        send(LedgerAction.Schedule(ScheduleAction.Invest("goal", TransactionDraft.Transfer("bank", "invest", m(100), m(5), first))))
        val result = report(); assertEquals(m(0), result.posted.totals.income); assertEquals(m(5), result.posted.totals.expense)
        assertEquals(m(-105), result.posted.totals.liquidMovement); assertEquals(m(-5), result.posted.totals.trackedAssetMovement)
        assertEquals(m(100), result.assetTrend.last().postedInvestment); assertEquals(m(995), result.assetTrend.last().postedAssets)
    }
    @Test fun archivedAndForeignAccountsNeverLoseOrLeakMoney() = scenario {
        send(LedgerAction.Archive("bank", true)); ledger.initializeBook("other")
        ledger.execute(LedgerCommand(uid(), "other", 0, LedgerAction.CreateAccount("foreign", "Foreign", AccountKind.BANK, m(777), first)))
        val result = report(); assertTrue(result.accounts.single { it.id == "bank" }.archived)
        assertEquals(m(1000), result.assetTrend.last().postedAssets); assertFalse(result.accounts.any { it.id == "foreign" })
        val other = reports.report("other", ReportPeriod(first, today)); assertEquals(m(777), other.assetTrend.last().postedAssets)
        assertEquals(1, other.accounts.size); assertEquals(m(0), other.posted.totals.income)
    }
    @Test fun monthlyAndDailyPointsIncludeGapsAndHistoricalBalances() = scenario {
        income(100, september.plusDays(9)); expense(20, first.plusDays(1))
        val result = report(september, today)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10)), result.posted.months.map { it.month })
        assertEquals(m(100), result.posted.months[0].totals.income); assertEquals(m(20), result.posted.months[1].totals.expense)
        assertEquals(53, result.assetTrend.size); assertEquals(m(1000), result.assetTrend[8].postedAssets)
        assertEquals(m(1100), result.assetTrend[9].postedAssets); assertEquals(m(1080), result.assetTrend.last().postedAssets)
        assertEquals(m(1100), report().accounts.single { it.id == "bank" }.openingPosted)
    }
    @Test fun checkedBigIntegerAggregationAllowsCancellationWithoutFloatingPoint() = scenario {
        val amount = Long.MAX_VALUE - 1000
        val original = income(amount); send(LedgerAction.Reverse(original.primaryId, first, "Cancel first receipt")); income(amount)
        val result = report()
        assertEquals(m(amount), result.posted.totals.income); assertEquals(m(Long.MAX_VALUE), result.assetTrend.last().postedAssets)
        assertEquals(m(Long.MAX_VALUE), result.accounts.single { it.id == "bank" }.closingPosted)
        assertEquals(SavingsRatio(m(amount), m(amount)), result.posted.totals.savingsRatio)
    }
    @Test fun outOfRangeHistoricalTotalsFailWithoutMutatingLiveHistory() = scenario {
        val amount = Long.MAX_VALUE - 1000
        val firstReceipt = income(amount); send(LedgerAction.Reverse(firstReceipt.primaryId, first.plusDays(1), "Cancel first"))
        val secondReceipt = income(amount); send(LedgerAction.Reverse(secondReceipt.primaryId, first.plusDays(1), "Cancel second"))
        val before = ledger.snapshot(); val commands = count("commands")
        assertTrue(rejects { report(first, first) } is ArithmeticException)
        assertEquals(before, ledger.snapshot()); assertEquals(commands, count("commands"))
        // A report containing that overflowing daily point must also reject, even if later reversals net to zero.
        assertTrue(rejects { report() } is ArithmeticException)
        assertEquals(m(0), report(today, today).posted.totals.income)
    }
    @Test fun invalidFutureAndOversizedPeriodsAreRejectedUsingBookTimezone() = scenario {
        for (period in listOf(ReportPeriod(today, first), ReportPeriod(today.minusDays(366), today),
            ReportPeriod(first, today.plusDays(1)), ReportPeriod(LocalDate.of(0, 1, 1), first))) {
            rejects { reports.report(DEFAULT_BOOK, period) }
        }
        rejects { reports.report("missing", ReportPeriod(first, today)) }; rejects { reports.report("' OR 1=1", ReportPeriod(first, today)) }
        send(LedgerAction.Plan(PlanningAction.Configure("Asia/Kolkata", m(0), m(0), 30, false)))
        val late = Clock.fixed(Instant.parse("2026-10-23T23:30:00Z"), ZoneOffset.UTC)
        assertEquals(today.plusDays(1), RoomFinancialReportsRepository(db, late).report(DEFAULT_BOOK, ReportPeriod(today, today.plusDays(1))).period.through)
        send(LedgerAction.Plan(PlanningAction.Configure("Pacific/Honolulu", m(0), m(0), 30, false)))
        rejects { RoomFinancialReportsRepository(db, late).report(DEFAULT_BOOK, ReportPeriod(today, today.plusDays(1))) }
    }
    @Test fun observationRefreshesRealRevisionAndReportsNeverWriteFinancialRows() = scenario {
        val firstResult = CompletableDeferred<FinancialReport>()
        val collecting = async { reports.observe(DEFAULT_BOOK, ReportPeriod(first, today)).onEach { firstResult.complete(it) }.take(2).toList() }
        try {
            val initial = withTimeout(10_000) { firstResult.await() }; val receipt = income(80)
            val values = withTimeout(10_000) { collecting.await() }
            assertEquals(initial, values.first()); assertEquals(receipt.revision, values.last().revision)
            assertEquals(m(80), values.last().posted.totals.income)
            val before = ledger.snapshot(); val counts = listOf("commands", "audit_events", "journals", "postings").map(::count)
            repeat(3) { assertEquals(values.last(), report()) }
            assertEquals(before, ledger.snapshot()); assertEquals(counts, listOf("commands", "audit_events", "journals", "postings").map(::count))
        } finally { collecting.cancelAndJoin() }
    }
    @Test fun emptyBookAndCancelledReadHaveNoFabricatedValuesOrWrites() = scenario {
        ledger.initializeBook("empty")
        val empty = reports.report("empty", ReportPeriod(first, today))
        assertEquals(0L, empty.revision); assertTrue(empty.accounts.isEmpty()); assertEquals(m(0), empty.posted.totals.income)
        assertTrue(empty.assetTrend.all { it.postedAssets == m(0) && it.clearedAssets == m(0) })
        val before = ledger.snapshot(); val cancelled = Job().apply { cancel() }
        assertTrue(rejects { withContext(cancelled) { report() } } is CancellationException)
        assertEquals(before, ledger.snapshot())
    }
}
