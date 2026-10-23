package com.fintrack.data.ledger

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.local.*
import com.fintrack.data.planning.RoomPlanningRepository
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.PlanningAction
import com.fintrack.domain.schedules.*
import com.fintrack.engine.*
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
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
class RoomFinancialReviewRepositoryTest {
    private val instant = AtomicReference(Instant.parse("2026-10-23T12:00:00Z"))
    private val clock: Clock = object : Clock() {
        override fun instant(): Instant = instant.get()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = object : Clock() {
            override fun instant(): Instant = instant.get()
            override fun getZone(): ZoneId = zone
            override fun withZone(zone: ZoneId): Clock = this@RoomFinancialReviewRepositoryTest.clock.withZone(zone)
        }
    }
    private val today = LocalDate.of(2026, 10, 23)
    private val month = YearMonth.from(today)
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var reviews: RoomFinancialReviewRepository
    private fun m(value: Long) = Money(value, INR)
    private fun uid() = UUID.randomUUID().toString()
    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000, block) }
    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext(); file = "p-${uid().take(16)}.db"
        db = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
            .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
        ledger = RoomLedgerRepository(db, clock); reviews = RoomFinancialReviewRepository(db, ledger, clock)
        ledger.initializeBook()
        send(LedgerAction.CreateAccount("bank", "Bank", AccountKind.BANK, m(1000), today.minusMonths(2)))
        send(LedgerAction.CreateAccount("cash", "Cash", AccountKind.CASH, m(0), today))
    }
    @After fun teardown() { db.close(); context.deleteDatabase(file) }
    private suspend fun command(action: LedgerAction, book: String = DEFAULT_BOOK) = LedgerCommand(uid(), book, ledger.snapshot(book).revision, action)
    private suspend fun send(action: LedgerAction) = ledger.execute(command(action))
    private suspend fun plan(action: PlanningAction) = send(LedgerAction.Plan(action))
    private fun income(amount: Long = 100) = LedgerAction.Post(TransactionDraft.Income("bank", m(amount), "Salary", today))
    private fun expense(amount: Long = 100, splits: List<Split> = listOf(Split("Food", m(amount)))) =
        LedgerAction.Post(TransactionDraft.Expense("bank", m(amount), splits, today))
    private fun count(table: String) = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst()); it.getLong(0) }
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block() } catch (_: Exception) { return }
        throw AssertionError("Expected rejection")
    }

    @Test fun incomePreviewRollsBackEverythingAndExactApplyPostsOnce() = scenario {
        val before = ledger.snapshot(); val audits = count("audit_events"); val request = command(income())
        val review = reviews.preview(request)
        assertNull(review.alreadySaved); assertEquals(m(1000), review.before.liquid); assertEquals(m(1100), review.after.liquid)
        assertEquals(before, ledger.snapshot()); assertEquals(audits, count("audit_events")); assertNull(db.ledgerDao().command(request.id))
        val receipt = reviews.apply(review)
        assertEquals(before.revision + 1, receipt.revision); assertEquals(m(1100), ledger.snapshot().liquid)
        assertEquals(receipt, reviews.apply(review)); assertEquals(before.history.size + 1, ledger.snapshot().history.size)
    }
    @Test fun fundedExpensePreviewUsesActualAtomicReserveConsumption() = scenario {
        plan(PlanningAction.SetBudget("food", "Food", month, m(500)))
        plan(PlanningAction.Move(null, "food", m(300), "Fund food")); val before = ledger.snapshot()
        val review = reviews.preview(command(expense()))
        assertEquals(m(900), review.after.liquid); assertEquals(m(200), review.after.reservations.reserved)
        assertEquals(review.before.safeToSpend, review.after.safeToSpend); assertEquals(before, ledger.snapshot())
        reviews.apply(review)
        assertEquals(m(200), RoomPlanningRepository(db, ledger, clock).snapshot(DEFAULT_BOOK, today).reservations.reserved)
    }
    @Test fun transferPreviewIncludesFeeWithoutTreatingPrincipalAsExpense() = scenario {
        val before = ledger.snapshot()
        val review = reviews.preview(command(LedgerAction.Post(TransactionDraft.Transfer("bank", "cash", m(200), m(10), today))))
        assertEquals(m(990), review.after.liquid); assertEquals(m(10), review.after.expense)
        assertEquals(m(200), review.after.accounts.single { it.id == "cash" }.posted); assertEquals(before, ledger.snapshot())
        reviews.apply(review); assertEquals(m(990), ledger.snapshot().liquid)
    }
    @Test fun protectedDiversionPreviewKeepsExactConsentAndGoalConsequences() = scenario {
        plan(PlanningAction.SetGoal("goal", "Goal", m(900), today.plusMonths(3), 1, m(300), 23))
        plan(PlanningAction.Move(null, "goal", m(400), "Fund goal"))
        val planning = RoomPlanningRepository(db, ledger, clock)
        val consent = planning.previewDiversion(DEFAULT_BOOK, "goal", null, m(100), today).consent
        val before = planning.snapshot(DEFAULT_BOOK, today)
        val review = reviews.preview(command(LedgerAction.Plan(PlanningAction.Move("goal", null, m(100), "Reviewed use", consent))))
        assertEquals(m(300), review.after.goalFunding.single().progress)
        assertEquals(before, planning.snapshot(DEFAULT_BOOK, today)); reviews.apply(review)
        assertEquals(review.after.goalCollisions, planning.snapshot(DEFAULT_BOOK, today).goalCollisions)
    }
    @Test fun invalidPreviewCannotLeaveActualMetadataCommandOrAudit() = scenario {
        val before = ledger.snapshot(); val audits = count("audit_events")
        rejects { reviews.preview(command(expense(100, listOf(Split("Food", m(99)))))) }
        assertEquals(before, ledger.snapshot()); assertEquals(audits, count("audit_events"))
    }
    @Test fun setupPreviewUsesSelectedZoneAndCommitsDeclaredEquityOnlyOnApply() = scenario {
        ledger.initializeBook("new-book")
        val action = LedgerAction.Setup(listOf(LedgerAction.CreateAccount("new-bank", "New bank", AccountKind.BANK, m(500), today.plusDays(1))),
            emptyList(), PlanningAction.Configure("Pacific/Kiritimati", m(50), m(0), 30, true))
        val review = reviews.preview(command(action, "new-book"))
        assertEquals(today.plusDays(1), review.after.asOf); assertEquals(m(500), review.after.liquid)
        assertTrue(ledger.snapshot("new-book").accounts.isEmpty()); assertNull(db.planningDao().profile("new-book"))
        reviews.apply(review); assertEquals(m(500), ledger.snapshot("new-book").liquid)
        assertEquals(m(0), ledger.snapshot("new-book").income)
    }
    @Test fun rolloverGeneratedIdsDoNotCauseFalseOutcomeMismatch() = scenario {
        plan(PlanningAction.SetBudget("old-food", "Food", month.minusMonths(1), m(100), RolloverPolicy.POSITIVE_REMAINDER))
        plan(PlanningAction.Move(null, "old-food", m(50), "Old funding"))
        val before = ledger.snapshot(); val buckets = count("reserve_buckets")
        val review = reviews.preview(command(LedgerAction.Plan(PlanningAction.AdvanceMonth(month))))
        assertEquals(m(50), review.after.budgets.single { it.month == month }.funded)
        assertEquals(buckets, count("reserve_buckets")); assertEquals(before, ledger.snapshot())
        reviews.apply(review); assertEquals(buckets + 1, count("reserve_buckets"))
    }
    @Test fun correctionPreviewUsesReverseAndReplaceButPreservesOriginalUntilApply() = scenario {
        val original = send(expense()); val before = ledger.snapshot()
        val review = reviews.preview(command(LedgerAction.Correct(original.primaryId, expense(150).draft, "Correct bill")))
        assertEquals(m(850), review.after.liquid); assertEquals(before.history.size + 2, review.after.journalCount)
        assertEquals(before, ledger.snapshot()); val receipt = reviews.apply(review)
        assertNotNull(receipt.secondaryId); assertNotNull(ledger.snapshot().history.single { it.id == original.primaryId }.reversedBy)
    }
    @Test fun reconciliationPreviewDoesNotMisclassifyEquityAsExpense() = scenario {
        val review = reviews.preview(command(LedgerAction.Reconcile("bank", today, m(900), BalanceBasis.POSTED, "Actual statement")))
        assertEquals(m(900), review.after.liquid); assertEquals(m(0), review.after.expense)
        assertEquals(m(1000), ledger.snapshot().liquid); reviews.apply(review)
        assertEquals(m(900), ledger.snapshot().liquid); assertEquals(m(0), ledger.snapshot().expense)
    }
    @Test fun clearingPreviewChangesOnlyReviewedClearedPositions() = scenario {
        val pending = send(LedgerAction.Post(TransactionDraft.Income("bank", m(100), "Salary", today, Clearing.PENDING)))
        val review = reviews.preview(command(LedgerAction.Clear(pending.primaryId, today)))
        assertEquals(m(1000), review.before.accounts.single { it.id == "bank" }.cleared)
        assertEquals(m(1100), review.after.accounts.single { it.id == "bank" }.cleared)
        assertEquals(review.before.liquid, review.after.liquid)
        assertNull(ledger.snapshot().history.single { it.id == pending.primaryId }.clearedDate)
        reviews.apply(review); assertEquals(today, ledger.snapshot().history.single { it.id == pending.primaryId }.clearedDate)
    }
    @Test fun changedCommandValuesCannotUseOldReviewFingerprint() = scenario {
        val review = reviews.preview(command(income())); val before = ledger.snapshot()
        rejects { reviews.apply(review.copy(command = review.command.copy(action = income(200)))) }
        assertEquals(before, ledger.snapshot())
    }
    @Test fun newFinancialRevisionRejectsReviewWithoutRebasingOrPosting() = scenario {
        val review = reviews.preview(command(income())); send(expense(50)); val before = ledger.snapshot()
        rejects { reviews.apply(review) }; assertEquals(before, ledger.snapshot())
    }
    @Test fun bookDateChangeRejectsUncommittedReviewWithoutFinancialWrite() = scenario {
        val review = reviews.preview(command(income())); val before = ledger.snapshot()
        instant.set(Instant.parse("2026-10-24T12:00:00Z"))
        rejects { reviews.apply(review) }; assertEquals(before, ledger.snapshot())
    }
    @Test fun lostApplyAcknowledgementReturnsExistingReceiptOnExactRetry() = scenario {
        val review = reviews.preview(command(income())); val before = ledger.snapshot()
        var receipt: CommandReceipt? = null
        rejects { receipt = reviews.apply(review); error("Response lost after completed transaction") }
        send(expense(50)); instant.set(Instant.parse("2026-10-24T12:00:00Z"))
        assertEquals(receipt, reviews.apply(review)); assertEquals(before.history.size + 2, ledger.snapshot().history.size)
        assertEquals(m(1050), ledger.snapshot().liquid)
    }
    @Test fun callerOwnedSplitsCannotChangeCapturedCommandAfterPreview() = scenario {
        val splits = mutableListOf(Split("Food", m(100)))
        val review = reviews.preview(command(expense(100, splits))); splits[0] = Split("Other", m(100))
        reviews.apply(review)
        assertEquals("Food", ledger.snapshot().history.last().legs.single { it.role == LedgerRole.EXPENSE }.label)
    }
    @Test fun incorrectExpectedOutcomeRollsBackActualWriteAuditAndReceipt() = scenario {
        val review = reviews.preview(command(income())); val before = ledger.snapshot(); val audits = count("audit_events")
        rejects { reviews.apply(review.copy(after = review.after.copy(liquid = m(999)))) }
        assertEquals(before, ledger.snapshot()); assertEquals(audits, count("audit_events")); assertNull(db.ledgerDao().command(review.command.id))
        reviews.apply(review); assertEquals(m(1100), ledger.snapshot().liquid)
    }
    @Test fun previewOfSavedCommandReportsExistingReceiptWithoutAnotherEffect() = scenario {
        val command = command(income()); val receipt = ledger.execute(command); val before = ledger.snapshot()
        val review = reviews.preview(command)
        assertEquals(receipt, review.alreadySaved); assertEquals(review.before, review.after)
        assertEquals(before, ledger.snapshot()); assertEquals(receipt, reviews.apply(review))
    }
    @Test fun reusedSavedKeyWithDifferentPayloadCannotPreviewNewMoney() = scenario {
        val command = command(income()); ledger.execute(command); val before = ledger.snapshot()
        rejects { reviews.preview(command.copy(action = income(200))) }; assertEquals(before, ledger.snapshot())
    }
    @Test fun cancelledPreviewRollsBackEvenAfterActualNestedExecuteSucceeded() = scenario {
        val before = ledger.snapshot(); val request = command(income()); val started = CompletableDeferred<Unit>()
        val suspended = object : LedgerRepository by ledger {
            override suspend fun execute(command: LedgerCommand): CommandReceipt {
                ledger.execute(command); started.complete(Unit); awaitCancellation()
            }
        }
        val candidate = RoomFinancialReviewRepository(db, suspended, clock)
        val job = launch { candidate.preview(request) }; started.await(); job.cancelAndJoin()
        assertEquals(before, ledger.snapshot()); assertNull(db.ledgerDao().command(request.id))
        ledger.execute(request); assertEquals(m(1100), ledger.snapshot().liquid)
    }
    @Test fun observersNeverReceiveRolledBackPreviewJournalIds() = scenario {
        val before = ledger.snapshot(); val initial = CompletableDeferred<Unit>(); val changed = CompletableDeferred<LedgerSnapshot>()
        val states = mutableListOf<LedgerSnapshot>()
        val collector = launch { ledger.observe().collect { value ->
            states += value; initial.complete(Unit); if (value.revision > before.revision) changed.complete(value)
        } }
        try {
            initial.await(); val review = reviews.preview(command(income()))
            assertFalse(changed.isCompleted); assertEquals(before, ledger.snapshot())
            reviews.apply(review); val actual = ledger.snapshot()
            assertEquals(actual, changed.await()); assertTrue(states.all { it == before || it == actual })
        } finally { collector.cancelAndJoin() }
    }
    @Test fun scheduleConfirmationPreviewRollsBackDecisionAndKeepsCashCoverageDisjoint() = scenario {
        val rule = ScheduledRule("bill", "Bill", expense().draft, Recurrence(today, Frequency.MONTHLY), true)
        send(LedgerAction.Schedule(ScheduleAction.Define(rule)))
        val before = ledger.snapshot()
        val review = reviews.preview(command(LedgerAction.Schedule(ScheduleAction.Confirm("bill", today, today, Clearing.PENDING))))
        assertEquals(m(100), review.before.uncoveredObligations); assertEquals(m(0), review.after.uncoveredObligations)
        assertEquals(m(900), review.after.liquid); assertEquals(review.before.safeToSpend, review.after.safeToSpend)
        assertEquals(0L, count("schedule_occurrences")); assertEquals(before, ledger.snapshot())
        reviews.apply(review); assertEquals(1L, count("schedule_occurrences")); assertEquals(m(900), ledger.snapshot().liquid)
    }
    @Test fun investmentPreviewUsesActualCostAndReserveHooksWithoutDoubleProgress() = scenario {
        send(LedgerAction.CreateAccount("invest", "Investments", AccountKind.INVESTMENT, m(0), today))
        plan(PlanningAction.SetGoal("retire", "Retirement", m(1000), today.plusYears(1), 1, m(100), 23, investment = true))
        plan(PlanningAction.Move(null, "retire", m(500), "Fund investment"))
        val before = ledger.snapshot()
        val review = reviews.preview(command(LedgerAction.Schedule(ScheduleAction.Invest("retire",
            TransactionDraft.Transfer("bank", "invest", m(300), m(0), today)))))
        assertEquals(m(700), review.after.liquid); assertEquals(m(200), review.after.reservations.reserved)
        assertEquals(m(300), review.after.goalFunding.single().postedContributions)
        assertEquals(m(500), review.after.goalFunding.single().progress)
        assertEquals(review.before.safeToSpend, review.after.safeToSpend)
        assertEquals(0L, count("investment_contributions")); assertEquals(before, ledger.snapshot())
        reviews.apply(review); assertEquals(1L, count("investment_contributions"))
    }
    @Test fun replacementPreviewUsesRealRulesAndCommitsOnlyAfterConfirmation() = scenario {
        send(LedgerAction.Schedule(ScheduleAction.Define(ScheduledRule("old", "Old bill", expense().draft, Recurrence(today, Frequency.MONTHLY), true))))
        val replacement = ScheduledRule("new", "New bill", expense(150).draft, Recurrence(today, Frequency.MONTHLY), true)
        val review = reviews.preview(command(LedgerAction.Schedule(ScheduleAction.Replace("old", replacement, "New tariff"))))
        assertEquals(m(100), review.before.uncoveredObligations); assertEquals(m(150), review.after.uncoveredObligations)
        assertEquals(1L, count("scheduled_rules")); assertNull(db.scheduleDao().rules(DEFAULT_BOOK).single().stopDate)
        reviews.apply(review); assertEquals(2L, count("scheduled_rules"))
    }
    @Test fun failedApplyAfterNestedWriteRollsBackBeforeAnyAcknowledgement() = scenario {
        val review = reviews.preview(command(income())); val before = ledger.snapshot(); val audits = count("audit_events")
        val interrupted = object : LedgerRepository by ledger {
            override suspend fun execute(command: LedgerCommand): CommandReceipt {
                ledger.execute(command); error("Synthetic interruption before outer transaction completion")
            }
        }
        val candidate = RoomFinancialReviewRepository(db, interrupted, clock)
        rejects { candidate.apply(review) }
        assertEquals(before, ledger.snapshot()); assertEquals(audits, count("audit_events")); assertNull(db.ledgerDao().command(review.command.id))
        reviews.apply(review); assertEquals(m(1100), ledger.snapshot().liquid)
    }
}
