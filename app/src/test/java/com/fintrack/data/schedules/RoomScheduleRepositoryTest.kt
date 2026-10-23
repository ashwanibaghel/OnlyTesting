package com.fintrack.data.schedules

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.ledger.RoomLedgerRepository
import com.fintrack.data.local.*
import com.fintrack.data.planning.RoomPlanningRepository
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.*
import com.fintrack.domain.schedules.*
import com.fintrack.engine.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
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
class RoomScheduleRepositoryTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val month = YearMonth.from(today)
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var planning: RoomPlanningRepository
    private lateinit var schedules: RoomScheduleRepository
    private fun m(value: Long) = Money(value, INR)
    private fun uid() = UUID.randomUUID().toString()
    private fun scenario(block: suspend () -> Unit) = runBlocking { block() }
    private fun open() = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
        .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
    private fun repositories() {
        ledger = RoomLedgerRepository(db, clock); planning = RoomPlanningRepository(db, ledger, clock)
        schedules = RoomScheduleRepository(db, ledger, clock)
    }
    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric includes the method name in its directory; leave room for native SQLite's -journal/-wal sidecars on Windows.
        file = "s-${uid().replace("-", "").take(16)}.db"
        db = open(); repositories()
        ledger.initializeBook()
        send(LedgerAction.CreateAccount("bank", "Bank", AccountKind.BANK, m(1000), today.minusMonths(2)))
        send(LedgerAction.CreateAccount("invest", "Investments", AccountKind.INVESTMENT, m(0), today))
    }
    @After fun teardown() { db.close(); context.deleteDatabase(file) }
    private suspend fun command(action: LedgerAction) = LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision, action)
    private suspend fun send(action: LedgerAction) = ledger.execute(command(action))
    private suspend fun schedule(action: ScheduleAction) = send(LedgerAction.Schedule(action))
    private suspend fun plan(action: PlanningAction) = send(LedgerAction.Plan(action))
    private suspend fun snapshot() = planning.snapshot(DEFAULT_BOOK, today)
    private suspend fun occurrences(through: LocalDate = today.plusDays(30)) = schedules.snapshot(DEFAULT_BOOK, today, through)
    private suspend fun rule(id: String = "bill", amount: Long = 100, start: LocalDate = today,
        frequency: Frequency = Frequency.MONTHLY, income: Boolean = false, mandatory: Boolean = true, end: LocalDate? = null) {
        val draft = if (income) TransactionDraft.Income("bank", m(amount), "Salary", start)
            else TransactionDraft.Expense("bank", m(amount), listOf(Split("Food", m(amount))), start)
        schedule(ScheduleAction.Define(ScheduledRule(id, id, draft, Recurrence(start, frequency, end = end), mandatory)))
    }
    private suspend fun fundedCategory(amount: Long) {
        plan(PlanningAction.SetBudget("food", "Food", month, m(500)))
        plan(PlanningAction.Move(null, "food", m(amount), "Fund current category"))
    }
    private suspend fun investment(amount: Long = 500) {
        plan(PlanningAction.SetGoal("retire", "Retirement", m(1000), today.plusYears(1), 1, m(100), 23, investment = true))
        plan(PlanningAction.Move(null, "retire", m(amount), "Investment funding"))
    }
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }; throw AssertionError("Expected rejection")
    }
    private fun count(table: String) = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst()); it.getLong(0) }

    private fun replacement(id: String = "new-bill", start: LocalDate = today, amount: Long = 150) =
        ScheduledRule(id, "Updated bill", TransactionDraft.Expense("bank", m(amount), listOf(Split("Food", m(amount))), start),
            Recurrence(start, Frequency.MONTHLY), true)

    @Test fun derivedObligationOverflowRollsBackNewRuleAndAudit() = scenario {
        rule("max-a", Long.MAX_VALUE); val before = snapshot(); val audits = count("audit_events")
        rejects { rule("max-b", Long.MAX_VALUE) }
        assertEquals(before, snapshot()); assertEquals(audits, count("audit_events")); assertEquals(1L, count("scheduled_rules"))
    }
    private suspend fun nonLiquidRule(id: String, income: Boolean = false) {
        val draft = if (income) TransactionDraft.Income("invest", m(100), "Interest", today)
            else TransactionDraft.Expense("invest", m(100), listOf(Split("Food", m(100))), today)
        schedule(ScheduleAction.Define(ScheduledRule(id, id, draft, Recurrence(today, Frequency.MONTHLY), true)))
    }
    @Test fun nonLiquidIncomeDoesNotInflateProjectedLiquidCash() = scenario {
        nonLiquidRule("interest", income = true); val before = ledger.snapshot()
        val result = schedules.forecast(DEFAULT_BOOK, today, today)
        assertEquals(m(1000), result.cash.closing); assertTrue(result.events.isEmpty())
        assertFalse(occurrences(today).occurrences.single().affectsLiquidCash)
        assertEquals(before, ledger.snapshot())
    }
    @Test fun nonLiquidExpenseDoesNotDeductLiquidCashOrUncoveredCashObligations() = scenario {
        nonLiquidRule("asset-fee"); val before = ledger.snapshot()
        assertEquals(m(0), snapshot().uncoveredObligations); assertEquals(m(1000), snapshot().safeToSpend.available)
        assertEquals(m(1000), schedules.forecast(DEFAULT_BOOK, today, today).cash.closing)
        assertEquals(before, ledger.snapshot())
    }
    @Test fun nonLiquidBillCannotConsumeCashCoverageNeededByLiquidBill() = scenario {
        fundedCategory(100); nonLiquidRule("a-asset-fee"); rule("z-bank-bill")
        val result = occurrences(today)
        assertEquals(m(0), snapshot().uncoveredObligations)
        assertEquals(m(100), result.occurrences.single { it.ruleId == "z-bank-bill" }.covered)
        assertEquals(m(0), result.occurrences.single { it.ruleId == "a-asset-fee" }.covered)
        assertFalse(result.occurrences.single { it.ruleId == "a-asset-fee" }.affectsLiquidCash)
        assertTrue(result.occurrences.single { it.ruleId == "z-bank-bill" }.affectsLiquidCash)
    }
    @Test fun nonLiquidConfirmationRemainsActualWithoutInventingLiquidMovement() = scenario {
        nonLiquidRule("asset-fee"); val projection = schedules.forecast(DEFAULT_BOOK, today, today)
        schedule(ScheduleAction.Confirm("asset-fee", today, today, Clearing.PENDING))
        assertEquals(m(1000), projection.cash.closing)
        assertEquals(m(1000), ledger.snapshot().liquid); assertEquals(m(-100), ledger.snapshot().accounts.single { it.id == "invest" }.posted)
        assertEquals(m(100), ledger.snapshot().expense); assertEquals(m(0), snapshot().uncoveredObligations)
        assertEquals(m(1000), schedules.forecast(DEFAULT_BOOK, today, today).cash.closing)
    }

    @Test fun replacementKeepsOldOverdueBillsAndChangesOnlyFuturePlan() = scenario {
        rule(start = today.minusMonths(1)); val before = ledger.snapshot()
        val receipt = schedule(ScheduleAction.Replace("bill", replacement(), "New amount from today"))
        val result = occurrences(); assertEquals(2, result.rules.size)
        assertEquals(today, result.rules.single { it.id == "bill" }.stoppedFrom)
        assertEquals(m(100), result.occurrences.single { it.ruleId == "bill" }.template.let { (it as TransactionDraft.Expense).amount })
        assertEquals(today.minusMonths(1), result.occurrences.single { it.ruleId == "bill" }.scheduledDate)
        assertTrue(result.occurrences.filter { it.ruleId == "new-bill" }.all { (it.template as TransactionDraft.Expense).amount == m(150) })
        assertEquals(before.accounts, ledger.snapshot().accounts); assertEquals(before.history, ledger.snapshot().history)
        assertEquals(before.revision + 1, receipt.revision); assertEquals("new-bill", receipt.primaryId); assertEquals("bill", receipt.secondaryId)
    }
    @Test fun invalidReplacementRollsBackStopCommandAndAudit() = scenario {
        rule(); val before = occurrences(); val revision = ledger.snapshot().revision; val audits = count("audit_events")
        rejects { schedule(ScheduleAction.Replace("bill", replacement().copy(name = " "), "Invalid replacement")) }
        assertEquals(before, occurrences()); assertEquals(revision, ledger.snapshot().revision); assertEquals(audits, count("audit_events"))
    }
    @Test fun replacementRetryReturnsReceiptWithoutExtraRuleOrPosting() = scenario {
        rule(); val request = command(LedgerAction.Schedule(ScheduleAction.Replace("bill", replacement(), "Updated bill")))
        val receipt = ledger.execute(request); val before = snapshot()
        assertEquals(receipt, ledger.execute(request)); assertEquals(before, snapshot()); assertEquals(2L, count("scheduled_rules"))
        val audits = count("audit_events")
        rejects { schedule(ScheduleAction.Replace("bill", replacement(), "Duplicate replacement")) }
        assertEquals(before, snapshot()); assertEquals(audits, count("audit_events"))
    }
    @Test fun replacementCannotDuplicateAnEarlyConfirmedFutureBill() = scenario {
        rule(start = today.plusDays(1)); schedule(ScheduleAction.Confirm("bill", today.plusDays(1), today, Clearing.CLEARED))
        val before = occurrences(); val actual = ledger.snapshot()
        rejects { schedule(ScheduleAction.Replace("bill", replacement(start = today.plusDays(1)), "Changed after prepayment")) }
        assertEquals(before, occurrences()); assertEquals(actual, ledger.snapshot())
    }
    @Test fun replacementCannotResurrectSkippedOrReopenedFutureDecisions() = scenario {
        rule(start = today.plusDays(1)); schedule(ScheduleAction.Skip("bill", today.plusDays(1), "Cancelled bill"))
        val before = occurrences()
        rejects { schedule(ScheduleAction.Replace("bill", replacement(start = today.plusDays(1)), "New price")) }
        assertEquals(before, occurrences())
        schedule(ScheduleAction.Reopen("bill", today.plusDays(1), "Mistaken skip")); val reopened = occurrences()
        rejects { schedule(ScheduleAction.Replace("bill", replacement(start = today.plusDays(1)), "New price")) }
        assertEquals(reopened, occurrences())
    }
    @Test fun replacementPreservesEarlierConfirmationAndCannotRewritePastPlan() = scenario {
        rule(); val paid = schedule(ScheduleAction.Confirm("bill", today, today, Clearing.PENDING))
        val history = ledger.snapshot().history
        rejects { schedule(ScheduleAction.Replace("bill", replacement(start = today.minusDays(1)), "Backdated replacement")) }
        schedule(ScheduleAction.Replace("bill", replacement(start = today.plusDays(1)), "Changed tomorrow"))
        val result = occurrences(); assertEquals(paid.primaryId, result.occurrences.single { it.ruleId == "bill" }.journalId)
        assertEquals(OccurrenceStatus.CONFIRMED, result.occurrences.single { it.ruleId == "bill" }.status)
        assertEquals(history, ledger.snapshot().history)
    }
    @Test fun replacementCannotReuseIdentityOrExtendAnAlreadyStoppedPlan() = scenario {
        rule(); rejects { schedule(ScheduleAction.Replace("bill", replacement("bill"), "Reuse identity")) }
        schedule(ScheduleAction.Stop("bill", today, "Stopped")); val before = occurrences()
        rejects { schedule(ScheduleAction.Replace("bill", replacement(start = today.plusDays(1)), "Extend stopped rule")) }
        assertEquals(before, occurrences())
    }
    @Test fun replacementPersistsAcrossReopenAndChangedPayloadCannotReuseKey() = scenario {
        rule(); val request = command(LedgerAction.Schedule(ScheduleAction.Replace("bill", replacement(), "Updated price")))
        val receipt = ledger.execute(request); val before = occurrences()
        db.close(); db = open(); repositories()
        assertEquals(before, occurrences()); assertEquals(receipt, ledger.execute(request))
        rejects { ledger.execute(request.copy(action = LedgerAction.Schedule(ScheduleAction.Replace("bill", replacement(amount = 200), "Updated price")))) }
        assertEquals(before, occurrences())
    }

    @Test fun definingSchedulesNeverPostsExpectedMoneyOrChangesActualBalances() = scenario {
        val before = ledger.snapshot(); rule(); rule("salary", 500, income = true)
        assertEquals(before.accounts, ledger.snapshot().accounts); assertEquals(before.history, ledger.snapshot().history)
        assertEquals(2, occurrences().rules.size); assertEquals(m(1000), ledger.snapshot().liquid)
    }
    @Test fun uncoveredMandatoryBillsReduceAvailabilityButFutureSalaryDoesNotIncreaseIt() = scenario {
        rule(); rule("salary", 500, today.plusDays(1), income = true)
        assertEquals(m(100), snapshot().uncoveredObligations); assertEquals(m(900), snapshot().safeToSpend.available)
        val income = occurrences().occurrences.single { it.ruleId == "salary" }
        assertEquals(OccurrenceStatus.AWAITING_CONFIRMATION, income.status); assertEquals(m(0), income.uncovered)
    }
    @Test fun oneFundedReserveCoversSeveralBillsOnlyOnce() = scenario {
        fundedCategory(150); rule("a"); rule("b")
        val bills = occurrences(today).occurrences
        assertEquals(m(150), sumMoney(bills.map { it.covered }, INR)); assertEquals(m(50), sumMoney(bills.map { it.uncovered }, INR))
        assertEquals(m(50), snapshot().uncoveredObligations); assertEquals(m(800), snapshot().safeToSpend.available)
    }
    @Test fun confirmingFundedBillConsumesActualAndReservationAndRemovesOnlyItsObligation() = scenario {
        fundedCategory(150); rule("a"); rule("b")
        val before = snapshot().safeToSpend
        schedule(ScheduleAction.Confirm("a", today, today, Clearing.PENDING))
        val after = snapshot()
        assertEquals(m(900), after.ledger.liquid); assertEquals(m(50), after.reservations.reserved)
        assertEquals(m(50), after.uncoveredObligations); assertEquals(before, after.safeToSpend)
        assertNull(after.ledger.history.last().clearedDate)
        assertEquals(OccurrenceStatus.CONFIRMED, occurrences(today).occurrences.single { it.ruleId == "a" }.status)
    }
    @Test fun pendingConfirmedExpenseIsNotDeductedAgainAsFutureCashOrObligation() = scenario {
        rule(); schedule(ScheduleAction.Confirm("bill", today, today, Clearing.PENDING))
        assertEquals(m(0), snapshot().uncoveredObligations)
        val forecast = schedules.forecast(DEFAULT_BOOK, today, today)
        assertTrue(forecast.events.isEmpty()); assertEquals(m(900), forecast.cash.closing)
    }
    @Test fun incomeRequiresManualConfirmationAndFutureActualDateIsRejected() = scenario {
        rule("salary", 500, today.plusDays(1), income = true)
        rejects { schedule(ScheduleAction.Confirm("salary", today.plusDays(1), today.plusDays(1), Clearing.CLEARED)) }
        assertEquals(m(1000), ledger.snapshot().liquid)
        rule("dueSalary", 500, income = true)
        schedule(ScheduleAction.Confirm("dueSalary", today, today, Clearing.CLEARED))
        assertEquals(m(1500), ledger.snapshot().liquid); assertEquals(m(500), ledger.snapshot().income)
    }
    @Test fun earlyActualPaymentConsumesFutureFundingAndReversalRestoresIt() = scenario {
        val due = month.plusMonths(1).atDay(1)
        plan(PlanningAction.SetBudget("future-food", "Food", month.plusMonths(1), m(500)))
        plan(PlanningAction.Move(null, "future-food", m(100), "Fund upcoming period"))
        rule(start = due)
        val before = snapshot().safeToSpend
        val paid = schedule(ScheduleAction.Confirm("bill", due, today, Clearing.CLEARED))
        assertEquals(m(900), ledger.snapshot().liquid); assertEquals(m(0), snapshot().reservations.reserved)
        assertEquals(before, snapshot().safeToSpend)
        send(LedgerAction.Reverse(paid.primaryId, today, "Early payment returned"))
        assertEquals(m(100), snapshot().reservations.reserved); assertEquals(before, snapshot().safeToSpend)
    }
    @Test fun existingEarlyPaymentCanSettleFutureBillWithoutProjectedDuplicate() = scenario {
        val due = month.plusMonths(1).atDay(1); rule(start = due)
        val actual = send(LedgerAction.Post(TransactionDraft.Expense("bank", m(100), listOf(Split("Food", m(100))), today)))
        schedule(ScheduleAction.LinkExisting("bill", due, actual.primaryId, "Paid in advance"))
        val result = schedules.forecast(DEFAULT_BOOK, today, due)
        assertTrue(result.events.isEmpty()); assertEquals(m(900), result.cash.closing)
        assertEquals(m(0), snapshot().uncoveredObligations)
    }
    @Test fun duplicateConfirmationWithSameOrDifferentCommandIdCannotPostTwice() = scenario {
        rule(); val command = command(LedgerAction.Schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED)))
        val receipt = ledger.execute(command); assertEquals(receipt, ledger.execute(command))
        rejects { schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED)) }
        assertEquals(m(900), ledger.snapshot().liquid); assertEquals(1L, count("schedule_occurrences"))
    }
    @Test fun twoConcurrentConfirmationsOnlyCommitOneActualPayment() = scenario {
        rule(); val revision = ledger.snapshot().revision
        val results = coroutineScope { (1..2).map { async(Dispatchers.IO) { runCatching {
            ledger.execute(LedgerCommand(uid(), DEFAULT_BOOK, revision, LedgerAction.Schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED))))
        } } }.awaitAll() }
        assertEquals(1, results.count { it.isSuccess }); assertEquals(m(900), ledger.snapshot().liquid)
    }
    @Test fun skippedOccurrenceIsAuditedAndNeverPosted() = scenario {
        rule(); schedule(ScheduleAction.Skip("bill", today, "Bill cancelled"))
        assertEquals(m(1000), ledger.snapshot().liquid); assertEquals(m(0), snapshot().uncoveredObligations)
        assertEquals(OccurrenceStatus.SKIPPED, occurrences(today).occurrences.single().status)
        rejects { schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED)) }
    }
    @Test fun skippedBillCanBeReopenedWithoutErasingItsOriginalDecision() = scenario {
        rule(); schedule(ScheduleAction.Skip("bill", today, "Mistaken skip"))
        schedule(ScheduleAction.Reopen("bill", today, "Still needs payment"))
        assertEquals(m(100), snapshot().uncoveredObligations)
        schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED))
        assertEquals(1L, count("schedule_occurrences")); assertEquals(2L, count("occurrence_resolutions"))
        assertEquals(m(900), ledger.snapshot().liquid)
    }
    @Test fun returningAndThenCancellingBillIsAnExplicitAuditedDecision() = scenario {
        rule(); val receipt = schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED))
        send(LedgerAction.Reverse(receipt.primaryId, today, "Payment refunded"))
        assertEquals(m(100), snapshot().uncoveredObligations)
        schedule(ScheduleAction.Skip("bill", today, "Subscription fully cancelled"))
        assertEquals(m(0), snapshot().uncoveredObligations); assertEquals(m(1000), ledger.snapshot().liquid)
    }
    @Test fun repeatedDstLocalHourCannotConfirmOneOccurrenceTwice() = scenario {
        plan(PlanningAction.Configure("America/New_York", m(0), m(0), 30, true))
        val due = LocalDate.of(2026, 11, 1)
        rule(start = due)
        ledger = RoomLedgerRepository(db, Clock.fixed(Instant.parse("2026-11-01T05:30:00Z"), ZoneOffset.UTC))
        schedule(ScheduleAction.Confirm("bill", due, due, Clearing.CLEARED))
        ledger = RoomLedgerRepository(db, Clock.fixed(Instant.parse("2026-11-01T06:30:00Z"), ZoneOffset.UTC))
        rejects { schedule(ScheduleAction.Confirm("bill", due, due, Clearing.CLEARED)) }
        assertEquals(m(900), ledger.snapshot().liquid); assertEquals(1L, count("schedule_occurrences"))
    }
    @Test fun scheduleStopPreservesPastMissedBillsAndActualHistory() = scenario {
        rule(start = today.minusDays(1), frequency = Frequency.DAILY)
        schedule(ScheduleAction.Stop("bill", today, "Stopped future plan"))
        assertEquals(listOf(today.minusDays(1)), occurrences(today.plusDays(2)).occurrences.map { it.scheduledDate })
        rejects { schedule(ScheduleAction.Stop("bill", today.minusDays(1), "Would erase past due")) }
    }
    @Test fun aFutureScheduleCanBeStoppedBeforeItsFirstOccurrence() = scenario {
        rule(start = today.plusDays(10)); schedule(ScheduleAction.Stop("bill", today, "No longer planned"))
        assertTrue(occurrences().occurrences.isEmpty()); assertEquals(m(1000), ledger.snapshot().liquid)
    }
    @Test fun latePastPeriodBillUsesCurrentFundingWithoutDoubleCounting() = scenario {
        fundedCategory(100); rule(start = month.minusMonths(1).atDay(28), end = month.minusMonths(1).atDay(28))
        val before = snapshot()
        assertEquals(m(0), before.uncoveredObligations)
        schedule(ScheduleAction.Confirm("bill", month.minusMonths(1).atDay(28), today, Clearing.CLEARED))
        val after = snapshot()
        assertEquals(m(0), after.reservations.reserved); assertEquals(before.safeToSpend, after.safeToSpend)
    }
    @Test fun splitSchedulesAllocateCoverageAcrossMatchingCategoriesExactly() = scenario {
        fundedCategory(50)
        val draft = TransactionDraft.Expense("bank", m(200), listOf(Split("Food", m(100)), Split("Travel", m(100))), today)
        schedule(ScheduleAction.Define(ScheduledRule("split", "Split bill", draft, Recurrence(today, Frequency.MONTHLY), true)))
        val item = occurrences(today).occurrences.single()
        assertEquals(m(50), item.covered); assertEquals(m(150), item.uncovered)
        schedule(ScheduleAction.Confirm("split", today, today, Clearing.CLEARED))
        assertEquals(m(0), snapshot().reservations.reserved); assertEquals(m(800), ledger.snapshot().liquid)
    }
    @Test fun variableSingleCategoryBillUsesConfirmedActualAmount() = scenario {
        rule(); schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED, m(120)))
        assertEquals(m(880), ledger.snapshot().liquid); assertEquals(m(120), ledger.snapshot().expense)
    }
    @Test fun linkingAnAlreadyRecordedBillDoesNotPostOrConsumeFundingAgain() = scenario {
        fundedCategory(100); rule()
        val actual = send(LedgerAction.Post(TransactionDraft.Expense("bank", m(100), listOf(Split("Food", m(100))), today)))
        val before = ledger.snapshot(); val allocations = count("allocation_journals")
        schedule(ScheduleAction.LinkExisting("bill", today, actual.primaryId, "This transaction paid this bill"))
        assertEquals(before.history, ledger.snapshot().history); assertEquals(before.accounts, ledger.snapshot().accounts)
        assertEquals(allocations, count("allocation_journals")); assertEquals(m(0), snapshot().uncoveredObligations)
        assertEquals(m(900), snapshot().safeToSpend.available)
    }
    @Test fun oneExistingPaymentCannotSettleTwoDifferentOccurrences() = scenario {
        rule("a"); rule("b")
        val actual = send(LedgerAction.Post(TransactionDraft.Expense("bank", m(100), listOf(Split("Food", m(100))), today)))
        schedule(ScheduleAction.LinkExisting("a", today, actual.primaryId, "Matched bill"))
        rejects { schedule(ScheduleAction.LinkExisting("b", today, actual.primaryId, "Would count payment twice")) }
        assertEquals(m(100), snapshot().uncoveredObligations); assertEquals(m(900), ledger.snapshot().liquid)
    }
    @Test fun reversingALinkedExistingPaymentReopensItsOccurrence() = scenario {
        rule()
        val actual = send(LedgerAction.Post(TransactionDraft.Expense("bank", m(120), listOf(Split("Food", m(120))), today)))
        schedule(ScheduleAction.LinkExisting("bill", today, actual.primaryId, "Recorded earlier"))
        send(LedgerAction.Reverse(actual.primaryId, today, "Returned payment"))
        assertEquals(m(120), snapshot().uncoveredObligations)
        schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED))
        assertEquals(m(880), ledger.snapshot().liquid)
    }
    @Test fun reversingConfirmedBillReopensItsKnownObligationAndAllowsOneReconfirmation() = scenario {
        rule(); val receipt = schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED, m(120)))
        send(LedgerAction.Reverse(receipt.primaryId, today, "Payment returned"))
        assertEquals(m(120), snapshot().uncoveredObligations)
        assertEquals(m(880), snapshot().safeToSpend.available)
        schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED))
        assertEquals(m(880), ledger.snapshot().liquid)
        rejects { schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED)) }
    }
    @Test fun correctingConfirmedPaymentRetainsCurrentJournalAndKnownBillAmount() = scenario {
        rule(); val receipt = schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED))
        val corrected = send(LedgerAction.Correct(receipt.primaryId, TransactionDraft.Expense("bank", m(150), listOf(Split("Food", m(150))), today), "Actual bill was higher"))
        assertEquals(corrected.primaryId, occurrences(today).occurrences.single().journalId)
        send(LedgerAction.Reverse(corrected.primaryId, today, "Corrected payment returned"))
        assertEquals(m(150), snapshot().uncoveredObligations)
    }
    @Test fun failedOccurrenceInsertRollsBackActualPostingAndFundedConsumption() = scenario {
        fundedCategory(100); rule(); val before = snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_occurrence_failure BEFORE INSERT ON schedule_occurrences BEGIN SELECT RAISE(ABORT,'Interrupted occurrence'); END")
        rejects { schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED)) }
        assertEquals(before, snapshot())
    }
    @Test fun forecastSeparatesProjectedSalaryAndProcessesSameDayOutflowBeforeInflow() = scenario {
        rule("expense", 1200); rule("salary", 1000, income = true)
        val result = schedules.forecast(DEFAULT_BOOK, today, today)
        assertEquals(m(800), result.cash.closing); assertEquals(m(-200), result.cash.minimum)
        assertEquals(today, result.cash.firstShortfall); assertEquals(m(1000), ledger.snapshot().liquid)
        assertTrue(result.assumptions.isNotEmpty())
    }
    @Test fun overdueIncomeIsExcludedWhileOverdueExpenseIsProjectedAtStart() = scenario {
        rule("bill", 100, today.minusDays(1)); rule("salary", 500, today.minusDays(1), income = true)
        val result = schedules.forecast(DEFAULT_BOOK, today, today)
        assertEquals(1, result.events.size); assertEquals(today, result.events.single().date)
        assertEquals(m(900), result.cash.closing)
    }
    @Test fun purchaseSimulationNeverWritesAndCanShowReviewedProtectedGoalConsequences() = scenario {
        investment(); val before = snapshot(); val commands = count("commands")
        val result = schedules.simulatePurchase(DEFAULT_BOOK, today, today.plusDays(30), today, m(600), PurchaseFunding("retire", m(200)))
        assertEquals(m(400), result.after.closing)
        assertNotNull(result.diversion); assertTrue(result.diversion!!.consent.goalImpacts.isNotEmpty())
        assertEquals(before, snapshot()); assertEquals(commands, count("commands"))
    }
    @Test fun releasingReserveCoverageForBillsDoesNotArtificiallyIncreaseSafeToSpend() = scenario {
        fundedCategory(100); rule(); val before = snapshot().safeToSpend
        plan(PlanningAction.Move("food", null, m(100), "Release category funding"))
        assertEquals(m(100), snapshot().uncoveredObligations); assertEquals(before, snapshot().safeToSpend)
    }
    @Test fun investmentTransferConsumesReserveAndCreditsProgressExactlyOnce() = scenario {
        investment(); val before = snapshot()
        val command = command(LedgerAction.Schedule(ScheduleAction.Invest("retire", TransactionDraft.Transfer("bank", "invest", m(300), date = today))))
        val receipt = ledger.execute(command); assertEquals(receipt, ledger.execute(command))
        val after = snapshot(); val funding = after.goalFunding.single()
        assertEquals(m(700), after.ledger.liquid); assertEquals(m(200), funding.reserved)
        assertEquals(m(300), funding.postedContributions); assertEquals(m(500), funding.progress)
        assertEquals(before.safeToSpend, after.safeToSpend)
        assertEquals(m(0), after.ledger.income); assertEquals(m(0), after.ledger.expense)
        assertEquals(1, schedules.contributions(DEFAULT_BOOK).size)
    }
    @Test fun pendingInvestmentContributionIsDistinctFromClearedContribution() = scenario {
        investment(); schedule(ScheduleAction.Invest("retire", TransactionDraft.Transfer("bank", "invest", m(300), date = today, clearing = Clearing.PENDING)))
        val progress = snapshot().goalFunding.single()
        assertEquals(m(300), progress.postedContributions); assertEquals(m(0), progress.clearedContributions)
        assertNull(schedules.contributions(DEFAULT_BOOK).single().clearedDate)
    }
    @Test fun investmentReversalRestoresFundedOwnershipWithoutDoublingProgress() = scenario {
        investment(); val receipt = schedule(ScheduleAction.Invest("retire", TransactionDraft.Transfer("bank", "invest", m(300), date = today)))
        send(LedgerAction.Reverse(receipt.primaryId, today, "Contribution returned"))
        val after = snapshot(); val funding = after.goalFunding.single()
        assertEquals(m(1000), after.ledger.liquid); assertEquals(m(500), funding.reserved)
        assertEquals(m(0), funding.postedContributions); assertEquals(m(500), funding.progress)
        assertEquals(2, schedules.contributions(DEFAULT_BOOK).size)
    }
    @Test fun correctedInvestmentRetainsGoalLinkAndConsumesOnlyItsCurrentFundedPortion() = scenario {
        investment(); val receipt = schedule(ScheduleAction.Invest("retire", TransactionDraft.Transfer("bank", "invest", m(300), date = today)))
        val corrected = send(LedgerAction.Correct(receipt.primaryId, TransactionDraft.Transfer("bank", "invest", m(400), date = today), "Actual contribution corrected"))
        val after = snapshot(); val progress = after.goalFunding.single()
        assertEquals(m(100), progress.reserved); assertEquals(m(400), progress.postedContributions)
        assertEquals(m(500), progress.progress); assertEquals(m(500), after.safeToSpend.available)
        assertTrue(schedules.contributions(DEFAULT_BOOK).any { it.journalId == corrected.primaryId && it.bucketId == "retire" })
        send(LedgerAction.Reverse(corrected.primaryId, today, "Corrected contribution returned"))
        assertEquals(m(1000), snapshot().ledger.liquid); assertEquals(m(500), snapshot().goalFunding.single().reserved)
    }
    @Test fun contributionCorrectionCanRecordAnActualUnfundedRemainderWithoutNegativeReserve() = scenario {
        investment(); val receipt = schedule(ScheduleAction.Invest("retire", TransactionDraft.Transfer("bank", "invest", m(300), date = today)))
        send(LedgerAction.Correct(receipt.primaryId, TransactionDraft.Transfer("bank", "invest", m(600), date = today), "Actual amount includes unassigned money"))
        val after = snapshot(); val progress = after.goalFunding.single()
        assertEquals(m(0), progress.reserved); assertEquals(m(600), progress.postedContributions)
        assertEquals(m(600), progress.progress); assertEquals(m(400), after.safeToSpend.available)
    }
    @Test fun goalProgressOverflowRollsBackOnlyTheAttemptedNewFunding() = scenario {
        send(LedgerAction.Post(TransactionDraft.Income("bank", m(Long.MAX_VALUE - 1000), "Salary", today)))
        plan(PlanningAction.SetGoal("retire", "Retirement", m(Long.MAX_VALUE), today.plusYears(1), 1, m(100), 23, investment = true))
        plan(PlanningAction.Move(null, "retire", m(500), "Initial reserve"))
        schedule(ScheduleAction.Invest("retire", TransactionDraft.Transfer("bank", "invest", m(500), date = today)))
        plan(PlanningAction.Move(null, "retire", m(Long.MAX_VALUE - 500), "Fund remainder"))
        send(LedgerAction.Post(TransactionDraft.Income("bank", m(1), "Salary", today)))
        val before = snapshot()
        rejects { plan(PlanningAction.Move(null, "retire", m(1), "Would overflow total eligible goal progress")) }
        assertEquals(before, snapshot())
    }
    @Test fun insufficientInvestmentFundingRollsBackTransfer() = scenario {
        investment(100); val before = snapshot()
        rejects { schedule(ScheduleAction.Invest("retire", TransactionDraft.Transfer("bank", "invest", m(101), date = today))) }
        assertEquals(before, snapshot()); assertEquals(0L, count("investment_contributions"))
    }
    @Test fun weeklyReviewPersistsOnceAndDoesNotMutateMoney() = scenario {
        val before = ledger.snapshot()
        val command = command(LedgerAction.Schedule(ScheduleAction.WeeklyReview(LocalDate.of(2026, 10, 19), "Reviewed accounts and upcoming bills")))
        val receipt = ledger.execute(command); assertEquals(receipt, ledger.execute(command))
        assertEquals(before.accounts, ledger.snapshot().accounts); assertEquals(before.history, ledger.snapshot().history)
        assertEquals(1, schedules.weeklyReviews(DEFAULT_BOOK).size)
        rejects { schedule(ScheduleAction.WeeklyReview(LocalDate.of(2026, 10, 19), "Duplicate")) }
    }
    @Test fun scheduleOccurrenceAndContributionHistoryAreImmutableAfterReopen() = scenario {
        rule(); schedule(ScheduleAction.Confirm("bill", today, today, Clearing.CLEARED))
        val before = occurrences(today); db.close(); db = open(); repositories()
        assertEquals(before, occurrences(today))
        rejects { db.openHelper.writableDatabase.execSQL("UPDATE schedule_occurrences SET status='SKIPPED'") }
        rejects { db.openHelper.writableDatabase.execSQL("DELETE FROM scheduled_rules") }
        rejects { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO schedule_occurrences SELECT * FROM schedule_occurrences") }
    }
    @Test fun v4MetadataGuardsRejectNegativeAmountsWithoutChangingProfiles() = scenario {
        fundedCategory(100); val before = snapshot()
        rejects { db.openHelper.writableDatabase.execSQL("UPDATE monthly_budgets SET limitMinor=-1") }
        plan(PlanningAction.Configure("UTC", m(0), m(0), 30, true))
        rejects { db.openHelper.writableDatabase.execSQL("UPDATE financial_profiles SET safetyBufferMinor=-1") }
        assertEquals(before.reservations, snapshot().reservations)
    }
    @Test fun v3MigrationPreservesAllExistingFinancialAndVirtualRows() = scenario {
        fundedCategory(300); val before = snapshot()
        val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("com.fintrack.data.local.FinTrackDatabase/3.json"))
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("database")
        val entities = source.getJSONArray("entities")
        data class Rows(val table: String, val columns: List<String>, val rows: List<List<Any?>>)
        val captured = (0 until entities.length()).map { index ->
            val table = entities.getJSONObject(index).getString("tableName")
            db.openHelper.writableDatabase.query("SELECT * FROM `$table`").use { cursor ->
                val data = mutableListOf<List<Any?>>()
                while (cursor.moveToNext()) data += (0 until cursor.columnCount).map { col -> when (cursor.getType(col)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(col)
                    android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(col)
                    else -> error("Unexpected legacy financial data type")
                } }
                Rows(table, cursor.columnNames.toList(), data)
            }
        }
        db.close(); context.deleteDatabase(file)
        context.openOrCreateDatabase(file, 0, null).use { old ->
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices -> for (j in 0 until indices.length())
                    old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val queries = source.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            for (table in captured) for (row in table.rows) old.execSQL(
                "INSERT INTO `${table.table}` (${table.columns.joinToString(",") { "`$it`" }}) VALUES (${row.joinToString(",") { "?" }})", row.toTypedArray())
            old.version = 3
        }
        db = open(); repositories(); assertEquals(before, snapshot())
        rule(); assertEquals(1, occurrences().rules.size)
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
}
