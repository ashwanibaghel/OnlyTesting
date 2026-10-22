package com.fintrack.data.planning

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.ledger.RoomLedgerRepository
import com.fintrack.data.ledger.fingerprint
import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.*
import com.fintrack.engine.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
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
class RoomPlanningRepositoryTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val month = YearMonth.from(today)
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var planning: RoomPlanningRepository
    private fun m(value: Long) = Money(value, INR)
    private fun uid() = UUID.randomUUID().toString()
    private fun scenario(block: suspend () -> Unit) = runBlocking { block() }
    private fun open() = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
        .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
    private fun repositories() { ledger = RoomLedgerRepository(db, clock); planning = RoomPlanningRepository(db, ledger, clock) }
    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext(); file = "planning-${uid()}.db"
        db = open(); repositories(); ledger.initializeBook()
        send(LedgerAction.CreateAccount("bank", "Bank", AccountKind.BANK, m(1000), today.minusMonths(2)))
    }
    @After fun teardown() { db.close(); context.deleteDatabase(file) }
    private suspend fun command(action: LedgerAction, key: String = uid()) =
        LedgerCommand(key, DEFAULT_BOOK, ledger.snapshot().revision, action)
    private suspend fun send(action: LedgerAction) = ledger.execute(command(action))
    private suspend fun plan(action: PlanningAction) = send(LedgerAction.Plan(action))
    private suspend fun snapshot() = planning.snapshot(DEFAULT_BOOK, today)
    private suspend fun budget(id: String = "food", category: String = "Food", period: YearMonth = month,
        rollover: RolloverPolicy = RolloverPolicy.OFF) = plan(PlanningAction.SetBudget(id, category, period, m(500), rollover))
    private suspend fun move(to: String?, amount: Long, from: String? = null, consent: DiversionConsent? = null) =
        plan(PlanningAction.Move(from, to, m(amount), "Explicit allocation", consent))
    private suspend fun goal(id: String = "goal", priority: Int = 1) =
        plan(PlanningAction.SetGoal(id, id, m(900), today.plusMonths(3), priority, m(300), 23))
    private fun expense(amount: Long = 100, at: LocalDate = today, splits: List<Split> = listOf(Split("Food", m(amount)))) =
        LedgerAction.Post(TransactionDraft.Expense("bank", m(amount), splits, at))
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected rejection")
    }
    private fun count(table: String) = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst()); it.getLong(0) }

    @Test fun derivedGoalOverflowRollsBackNewGoalAndAudit() = scenario {
        plan(PlanningAction.SetGoal("max-a", "First goal", m(Long.MAX_VALUE), today, 1, m(0), today.dayOfMonth))
        val before = snapshot(); val audits = count("audit_events")
        rejects { plan(PlanningAction.SetGoal("max-b", "Second goal", m(Long.MAX_VALUE), today, 2, m(0), today.dayOfMonth)) }
        assertEquals(before, snapshot()); assertEquals(audits, count("audit_events")); assertEquals(1L, count("financial_goals"))
    }
    @Test fun derivedSafeToSpendOverflowRollsBackActualExpenseAndAudit() = scenario {
        plan(PlanningAction.Configure("UTC", m(Long.MAX_VALUE), m(0), 30, true))
        val before = snapshot(); val audits = count("audit_events")
        rejects { send(expense(2000)) }
        assertEquals(before, snapshot()); assertEquals(audits, count("audit_events"))
    }
    @Test fun derivedBudgetAllowanceOverflowRollsBackLimitChange() = scenario {
        plan(PlanningAction.SetBudget("old", "Food", month.minusMonths(1), m(1), RolloverPolicy.POSITIVE_REMAINDER))
        plan(PlanningAction.AdvanceMonth(month)); val before = snapshot()
        val current = before.budgets.single { it.month == month }
        assertEquals(m(1), current.carried)
        rejects { plan(PlanningAction.SetBudget(current.bucketId, "Food", month, m(Long.MAX_VALUE), current.rollover)) }
        assertEquals(before, snapshot())
    }
    @Test fun derivedRefundBudgetOverflowRollsBackNewBudget() = scenario {
        val receipt = send(expense(1, today.minusMonths(1)))
        send(LedgerAction.Reverse(receipt.primaryId, today, "Actual refund this month"))
        val before = snapshot(); val audits = count("audit_events")
        rejects { plan(PlanningAction.SetBudget("overflow", "Food", month, m(Long.MAX_VALUE))) }
        assertEquals(before, snapshot()); assertEquals(audits, count("audit_events"))
    }

    @Test fun fundingReservesChangesOwnershipWithoutChangingActualAccounts() = scenario {
        budget(); val before = ledger.snapshot(); move("food", 300)
        val after = snapshot()
        assertEquals(before.accounts, after.ledger.accounts); assertEquals(before.history, after.ledger.history)
        assertEquals(m(300), after.reservations.reserved); assertEquals(m(700), after.reservations.unassigned)
        assertEquals(m(700), after.safeToSpend.available)
    }
    @Test fun budgetTargetIsNeverTreatedAsFundedMoney() = scenario {
        budget(); val result = snapshot()
        assertEquals(m(500), result.budgets.single().limit); assertEquals(m(0), result.budgets.single().funded)
        assertEquals(m(1000), result.safeToSpend.available)
    }
    @Test fun overfundingRollsBackCommandAndAudit() = scenario {
        budget(); val before = snapshot(); val audits = count("audit_events")
        rejects { move("food", 1001) }
        assertEquals(before, snapshot()); assertEquals(audits, count("audit_events")); assertEquals(0, count("allocation_journals").toInt())
    }
    @Test fun fundedSpendingAtomicallyReducesActualAndMatchingReserve() = scenario {
        budget(); move("food", 300); send(expense(100))
        val result = snapshot()
        assertEquals(m(900), result.ledger.liquid); assertEquals(m(200), result.reservations.reserved)
        assertEquals(m(700), result.safeToSpend.available); assertEquals(m(100), result.budgets.single().position.spent)
    }
    @Test fun partiallyFundedExpenseConsumesOnlyItsFundedPortion() = scenario {
        budget(); move("food", 50); send(expense(100))
        assertEquals(m(0), snapshot().reservations.reserved); assertEquals(m(900), snapshot().safeToSpend.available)
    }
    @Test fun splitExpenseConsumesEachMatchingCategoryOnce() = scenario {
        budget(); budget("travel", "Travel"); move("food", 100); move("travel", 200)
        send(expense(150, splits = listOf(Split("Food", m(20)), Split("Food", m(30)), Split("Travel", m(100)))))
        val result = snapshot(); assertEquals(m(150), result.reservations.reserved)
        assertEquals(m(50), result.buckets.single { it.id == "food" }.funded)
        assertEquals(m(100), result.buckets.single { it.id == "travel" }.funded)
    }
    @Test fun transferFeeConsumesBudgetButPrincipalDoesNotBecomeExpense() = scenario {
        send(LedgerAction.CreateAccount("cash", "Cash", AccountKind.CASH, m(0), today))
        budget("fees", "Transfer fees"); move("fees", 100)
        send(LedgerAction.Post(TransactionDraft.Transfer("bank", "cash", m(200), m(30), today)))
        assertEquals(m(970), snapshot().ledger.liquid); assertEquals(m(30), snapshot().ledger.expense)
        assertEquals(m(70), snapshot().reservations.reserved)
    }
    @Test fun samePeriodReversalRestoresExactlyOriginalConsumedFunding() = scenario {
        budget(); move("food", 50); val receipt = send(expense(100))
        send(LedgerAction.Reverse(receipt.primaryId, today, "Refund"))
        assertEquals(m(1000), snapshot().ledger.liquid); assertEquals(m(50), snapshot().reservations.reserved)
        assertEquals(m(0), snapshot().ledger.expense)
        val before = snapshot(); rejects { send(LedgerAction.Reverse(receipt.primaryId, today, "Duplicate refund")) }; assertEquals(before, snapshot())
    }
    @Test fun correctionMovesFundingBetweenCategoriesAndRollsBackAsOneCommand() = scenario {
        budget(); budget("travel", "Travel"); move("food", 100); move("travel", 100)
        val receipt = send(expense(100))
        val before = snapshot()
        rejects { send(LedgerAction.Correct(receipt.primaryId, TransactionDraft.Expense("missing", m(100), listOf(Split("Travel", m(100))), today), "Wrong account")) }
        assertEquals(before, snapshot())
        send(LedgerAction.Correct(receipt.primaryId, TransactionDraft.Expense("bank", m(100), listOf(Split("Travel", m(100))), today), "Correct category"))
        assertEquals(m(100), snapshot().buckets.single { it.id == "food" }.funded)
        assertEquals(m(0), snapshot().buckets.single { it.id == "travel" }.funded)
        assertEquals(m(900), snapshot().ledger.liquid)
    }
    @Test fun allocationFailureRollsBackActualExpenseAndRevision() = scenario {
        budget(); move("food", 100); val before = snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_failure BEFORE INSERT ON allocation_legs BEGIN SELECT RAISE(ABORT,'Synthetic interruption'); END")
        rejects { send(expense(100)) }
        assertEquals(before, snapshot())
    }
    @Test fun rolloverOffReleasesRemainingFundingWithoutChangingActualCash() = scenario {
        budget(period = month.minusMonths(1)); move("food", 300)
        send(expense(200, month.minusMonths(1).atDay(20)))
        val liquid = snapshot().ledger.liquid
        plan(PlanningAction.AdvanceMonth(month))
        val result = snapshot(); assertEquals(liquid, result.ledger.liquid)
        assertEquals(m(0), result.reservations.reserved)
        assertEquals(m(0), result.budgets.single { it.month == month }.carried)
        assertTrue(result.budgets.single { it.bucketId == "food" }.closed)
    }
    @Test fun rolloverOnCarriesPermissionAndActualFundingSeparately() = scenario {
        budget(period = month.minusMonths(1), rollover = RolloverPolicy.POSITIVE_REMAINDER); move("food", 300)
        send(expense(200, month.minusMonths(1).atDay(20)))
        plan(PlanningAction.AdvanceMonth(month))
        val current = snapshot().budgets.single { it.month == month }
        assertEquals(m(300), current.carried); assertEquals(m(100), current.funded)
        assertEquals(m(800), current.position.allowance)
        val before = snapshot(); plan(PlanningAction.AdvanceMonth(month))
        assertEquals(before.budgets, snapshot().budgets); assertEquals(before.reservations, snapshot().reservations)
    }
    @Test fun closedPeriodRefundDoesNotResurrectOldReservation() = scenario {
        budget(period = month.minusMonths(1)); move("food", 300)
        val receipt = send(expense(200, month.minusMonths(1).atDay(20)))
        plan(PlanningAction.AdvanceMonth(month))
        send(LedgerAction.Reverse(receipt.primaryId, today, "Closed period refund"))
        assertEquals(m(1000), snapshot().ledger.liquid); assertEquals(m(0), snapshot().reservations.reserved)
        assertEquals(m(1000), snapshot().reservations.unassigned)
    }
    @Test fun protectedSavingsAndEmergencyFundingPersistAcrossMonths() = scenario {
        plan(PlanningAction.CreateReserve("saving", "Savings", BucketKind.SAVINGS))
        plan(PlanningAction.CreateReserve("emergency", "Emergency", BucketKind.EMERGENCY))
        move("saving", 300); move("emergency", 300); plan(PlanningAction.AdvanceMonth(month))
        assertEquals(m(600), snapshot().reservations.reserved)
        rejects { move(null, 1, "saving") }; assertEquals(m(600), snapshot().reservations.reserved)
    }
    @Test fun goalDiversionRequiresExactReviewAndRecalculatesRequirements() = scenario {
        goal(); move("goal", 600)
        val review = planning.previewDiversion(DEFAULT_BOOK, "goal", null, m(300), today)
        assertTrue(review.protected); assertEquals(m(300), review.remainingSource)
        val impact = review.consent.goalImpacts.single()
        assertTrue(impact.requiredAfter!!.minor > impact.requiredBefore!!.minor)
        rejects { move(null, 200, "goal", review.consent) }
        move(null, 300, "goal", review.consent)
        assertEquals(m(300), snapshot().buckets.single().funded)
        assertEquals(impact.requiredAfter, snapshot().goalCollisions.assessments.single().requiredMonthly)
    }
    @Test fun staleDiversionReviewIsRejectedAfterAnyFinancialChange() = scenario {
        goal(); move("goal", 500)
        val review = planning.previewDiversion(DEFAULT_BOOK, "goal", null, m(100), today)
        send(LedgerAction.Post(TransactionDraft.Income("bank", m(10), "Salary", today)))
        val before = snapshot(); rejects { move(null, 100, "goal", review.consent) }; assertEquals(before, snapshot())
    }
    @Test fun reserveToReserveMoveConservesTotalAndActualCash() = scenario {
        goal("a"); goal("b", 2); move("a", 500)
        val before = snapshot()
        val review = planning.previewDiversion(DEFAULT_BOOK, "a", "b", m(200), today)
        move("b", 200, "a", review.consent)
        assertEquals(before.reservations, snapshot().reservations)
        assertEquals(before.ledger.accounts, snapshot().ledger.accounts)
        assertEquals(before.ledger.history, snapshot().ledger.history)
        assertEquals(m(300), snapshot().buckets.single { it.id == "a" }.funded)
        assertEquals(m(200), snapshot().buckets.single { it.id == "b" }.funded)
    }
    @Test fun lostAcknowledgementRetryDoesNotDuplicateAllocation() = scenario {
        budget(); val command = command(LedgerAction.Plan(PlanningAction.Move(null, "food", m(100), "Funding")))
        val receipt = ledger.execute(command)
        send(LedgerAction.Post(TransactionDraft.Income("bank", m(10), "Salary", today)))
        assertEquals(receipt, ledger.execute(command)); assertEquals(1L, count("allocation_journals"))
        assertEquals(m(100), snapshot().reservations.reserved)
        rejects { ledger.execute(command.copy(action = LedgerAction.Plan(PlanningAction.Move(null, "food", m(200), "Changed")))) }
    }
    @Test fun concurrentFundingAtOneRevisionCommitsOnlyOne() = scenario {
        budget(); val revision = ledger.snapshot().revision
        val results = coroutineScope { (1..2).map { async(Dispatchers.IO) {
            runCatching { ledger.execute(LedgerCommand(uid(), DEFAULT_BOOK, revision,
                LedgerAction.Plan(PlanningAction.Move(null, "food", m(700), "Concurrent funding")))) }
        } }.awaitAll() }
        assertEquals(1, results.count { it.isSuccess }); assertEquals(m(700), snapshot().reservations.reserved)
    }
    @Test fun metadataAndImmutableVirtualLedgerPersistAfterReopen() = scenario {
        goal(); move("goal", 200); val before = snapshot()
        db.close(); db = open(); repositories()
        assertEquals(before, snapshot())
        rejects { db.openHelper.writableDatabase.execSQL("UPDATE allocation_legs SET minor = 1") }
        rejects { db.openHelper.writableDatabase.execSQL("DELETE FROM allocation_journals") }
        rejects { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO allocation_seals SELECT * FROM allocation_seals") }
        assertEquals(before, snapshot())
    }
    @Test fun unsupportedOrCrossBookFundingDoesNotChangeData() = scenario {
        budget(); val before = snapshot()
        rejects { move("missing", 10) }
        rejects { plan(PlanningAction.Move(null, "food", Money(10, CurrencySpec("USD", 2)), "Wrong currency")) }
        ledger.initializeBook("other")
        rejects { ledger.execute(LedgerCommand(uid(), "other", 0, LedgerAction.Plan(PlanningAction.Move(null, "food", m(10), "Cross book")))) }
        assertEquals(before, snapshot())
    }
    @Test fun profileBufferIsDisjointAndProjectedCapacityIsNotCurrentMoney() = scenario {
        plan(PlanningAction.Configure("Asia/Kolkata", m(100), m(10000), 30, true))
        goal(); move("goal", 200)
        assertEquals(m(700), snapshot().safeToSpend.available)
        assertEquals("Asia/Kolkata", snapshot().profile.timezone)
        assertTrue(snapshot().profile.setupComplete)
    }
    @Test fun actualDatesAndDiversionConsentUseBookTimezoneAcrossUtcMidnight() = scenario {
        plan(PlanningAction.Configure("Pacific/Kiritimati", m(0), m(300), 30, true))
        send(LedgerAction.Post(TransactionDraft.Income("bank", m(100), "Salary", today.plusDays(1))))
        goal(); move("goal", 300)
        val staleDate = planning.previewDiversion(DEFAULT_BOOK, "goal", null, m(100), today)
        rejects { move(null, 100, "goal", staleDate.consent) }
        val review = planning.previewDiversion(DEFAULT_BOOK, "goal", null, m(100), today.plusDays(1))
        move(null, 100, "goal", review.consent)
        assertEquals(m(200), snapshot().reservations.reserved)
    }
    @Test fun actualFutureDateInBookTimezoneIsRejectedEvenIfUtcDateAllowsIt() = scenario {
        val boundary = Clock.fixed(Instant.parse("2026-10-23T00:30:00Z"), ZoneOffset.UTC)
        ledger = RoomLedgerRepository(db, boundary)
        plan(PlanningAction.Configure("Pacific/Honolulu", m(0), m(0), 30, true))
        val before = ledger.snapshot()
        rejects { send(LedgerAction.Post(TransactionDraft.Income("bank", m(100), "Salary", today))) }
        assertEquals(before, ledger.snapshot())
        send(LedgerAction.Post(TransactionDraft.Income("bank", m(100), "Salary", today.minusDays(1))))
    }
    @Test fun rolloverCannotCloseFutureBookMonthAtUtcMonthBoundary() = scenario {
        ledger = RoomLedgerRepository(db, Clock.fixed(Instant.parse("2026-11-01T00:30:00Z"), ZoneOffset.UTC))
        plan(PlanningAction.Configure("Pacific/Honolulu", m(0), m(0), 30, true))
        budget(); val before = ledger.snapshot()
        rejects { plan(PlanningAction.AdvanceMonth(month.plusMonths(1))) }
        assertEquals(before, ledger.snapshot())
    }
    @Test fun reconciliationCanExposeReserveDeficitWithoutSilentlyDivertingSavings() = scenario {
        goal(); move("goal", 800)
        send(LedgerAction.Reconcile("bank", today, m(500), BalanceBasis.POSTED, "Actual statement"))
        assertEquals(m(800), snapshot().reservations.reserved)
        assertEquals(m(300), snapshot().reservations.deficit); assertEquals(m(300), snapshot().safeToSpend.deficit)
    }
    @Test fun v2MigrationPreservesFinancialHistoryAndOriginalCommandFingerprint() = scenario {
        db.close(); context.deleteDatabase(file)
        val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("com.fintrack.data.local.FinTrackDatabase/2.json"))
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("database")
        val original = LedgerCommand("legacy-command", DEFAULT_BOOK, 0,
            LedgerAction.CreateAccount("bank", "Bank", AccountKind.BANK, m(1000), today))
        context.openOrCreateDatabase(file, 0, null).use { old ->
            val entities = source.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val queries = source.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO books VALUES ('personal','local-owner','INR',2,1,0)")
            old.execSQL("INSERT INTO accounts VALUES ('bank','Bank','INR','BANK',0,'personal')")
            old.execSQL("INSERT INTO ledger_accounts VALUES ('bank','personal','bank','ASSET','Bank')")
            old.execSQL("INSERT INTO ledger_accounts VALUES ('equity','personal',NULL,'EQUITY','Opening equity')")
            old.execSQL("INSERT INTO commands VALUES ('legacy-command','personal',?,'bank',NULL,1)", arrayOf(fingerprint(original)))
            old.execSQL("INSERT INTO audit_events VALUES ('legacy-command','CreateAccount','',0)")
            old.execSQL("INSERT INTO journals VALUES ('opening','personal','legacy-command','OPENING',?,0,'CLEARED','Opening balance','',NULL)", arrayOf(today.toString()))
            old.execSQL("INSERT INTO postings VALUES ('leg1','opening','bank',1000)")
            old.execSQL("INSERT INTO postings VALUES ('leg2','opening','equity',-1000)")
            old.execSQL("INSERT INTO journal_seals VALUES ('opening')")
            old.version = 2
        }
        db = open(); repositories()
        assertEquals(m(1000), ledger.snapshot().liquid); assertEquals("opening", ledger.snapshot().history.single().id)
        assertTrue(snapshot().buckets.isEmpty()); assertEquals(1L, ledger.execute(original).revision)
        budget(); move("food", 300); assertEquals(m(300), snapshot().reservations.reserved)
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        rejects { db.openHelper.writableDatabase.execSQL("DELETE FROM postings") }
    }
}
