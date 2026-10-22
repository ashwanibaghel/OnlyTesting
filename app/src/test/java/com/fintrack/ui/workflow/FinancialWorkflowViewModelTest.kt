package com.fintrack.ui.workflow

import androidx.lifecycle.ViewModelStore
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.*
import com.fintrack.domain.schedules.*
import com.fintrack.domain.session.*
import com.fintrack.engine.*
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinancialWorkflowViewModelTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val first = FinancialOperation("first-form")
    private val second = FinancialOperation("second-form")
    private val store = ViewModelStore()
    private val sessions = TestSessions()
    private val ledger = TestLedger(sessions)
    private val planning = TestPlanning(sessions)
    private lateinit var vm: FinancialWorkflowViewModel
    private fun m(value: Long) = Money(value, INR)
    private fun income(amount: Long = 100) = LedgerAction.Post(TransactionDraft.Income("bank", m(amount), "Salary", today))
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        vm = FinancialWorkflowViewModel(ledger, sessions, planning); store.put("workflow", vm)
    }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun TestScope.review(operation: FinancialOperation = first, amount: Long = 100): ReviewedFinancialCommand {
        runCurrent(); vm.prepare(operation) { income(amount) }; return requireNotNull(vm.state.value.review)
    }
    private fun TestScope.failWrite(operation: FinancialOperation = first): FailedFinancialCommand {
        val review = review(operation); ledger.failExecute = true
        vm.confirm(operation, review.token); runCurrent(); return requireNotNull(vm.state.value.retry)
    }

    @Test fun failedWriteThenInvalidInputCannotRetryPreviousOperation() = runTest {
        val failed = failWrite()
        vm.prepare(second) { Money.parse("invalid", INR); income() }
        assertNotNull(vm.state.value.commandError); assertNull(vm.state.value.retry)
        vm.retry(first, failed.command.id); runCurrent(); assertEquals(1, ledger.attempts.size)
    }
    @Test fun failedWriteThenFailedDiversionPreviewDoesNotExposeOldRetry() = runTest {
        val failed = failWrite(); planning.failPreview = true
        vm.prepareDiversion(second, "goal", null, m(50), "New diversion"); runCurrent()
        assertNotNull(vm.state.value.commandError); assertNull(vm.state.value.retry); assertNull(vm.state.value.review)
        vm.retry(first, failed.command.id); runCurrent(); assertEquals(1, ledger.attempts.size)
    }
    @Test fun editedFormRejectsOldReviewEvenIfValuesAreChangedBack() = runTest {
        val old = review(); vm.edited(first); vm.prepare(first) { income() }
        val current = vm.state.value.review!!
        vm.confirm(first, old.token); runCurrent(); assertTrue(ledger.attempts.isEmpty())
        vm.confirm(first, current.token); runCurrent(); assertEquals(1, ledger.postings)
    }
    @Test fun switchingFormRejectsPreviousReviewAndRetryCallbacks() = runTest {
        val failed = failWrite(); ledger.failExecute = false
        val current = review(second)
        vm.retry(first, failed.command.id); vm.confirm(first, current.token); runCurrent()
        assertEquals(1, ledger.attempts.size)
        vm.confirm(second, current.token); runCurrent(); assertEquals(1, ledger.postings)
    }
    @Test fun successfulRetryAfterLostAcknowledgementUsesOriginalCommandAndPostsOnce() = runTest {
        val reviewed = review(); ledger.failAcknowledgementOnce = true
        vm.confirm(first, reviewed.token); runCurrent()
        val failed = vm.state.value.retry!!
        assertEquals(reviewed.command, failed.command); assertEquals(1, ledger.postings)
        vm.retry(first, failed.command.id); vm.retry(first, failed.command.id); runCurrent()
        assertEquals(2, ledger.attempts.size); assertEquals(ledger.attempts[0], ledger.attempts[1])
        assertEquals(1, ledger.postings); assertNull(vm.state.value.retry); assertNotNull(vm.state.value.saved[first.key])
    }
    @Test fun rapidConfirmCallbacksConsumeReviewBeforeCoroutineStarts() = runTest {
        val reviewed = review(); vm.confirm(first, reviewed.token); vm.confirm(first, reviewed.token)
        assertTrue(vm.state.value.busy); assertNull(vm.state.value.review)
        runCurrent(); assertEquals(1, ledger.attempts.size); assertEquals(1, ledger.postings)
    }
    @Test fun successfulFormCannotBePreparedAgainAfterUnrelatedOperation() = runTest {
        val one = review(); vm.confirm(first, one.token); runCurrent()
        val two = review(second); vm.confirm(second, two.token); runCurrent()
        vm.prepare(first) { income() }; runCurrent()
        assertNull(vm.state.value.review); assertEquals(2, ledger.postings)
    }
    @Test fun acknowledgedSuccessWithRefreshFailureRetainsReceiptAndPreventsResubmission() = runTest {
        val reviewed = review(); sessions.failSnapshotWhenPosted = true
        vm.confirm(first, reviewed.token); runCurrent()
        assertNotNull(vm.state.value.saved[first.key]); assertNull(vm.state.value.retry)
        assertNotNull(vm.state.value.storageError)
        vm.prepare(first) { income() }; vm.confirm(first, reviewed.token); vm.retry(first, first.key); runCurrent()
        assertEquals(1, ledger.attempts.size); assertEquals(1, ledger.postings)
    }
    @Test fun changedBookRevisionInvalidatesReview() = runTest {
        val reviewed = review(); sessions.revise(1); runCurrent()
        assertNull(vm.state.value.review); vm.confirm(first, reviewed.token); runCurrent()
        assertTrue(ledger.attempts.isEmpty())
    }
    @Test fun changedBookDateInvalidatesReviewWithoutFinancialRevision() = runTest {
        val reviewed = review(); sessions.redate(today.plusDays(1)); runCurrent()
        assertNull(vm.state.value.review); vm.confirm(first, reviewed.token); runCurrent()
        assertTrue(ledger.attempts.isEmpty())
    }
    @Test fun freshPreflightRejectsUnobservedDateChangeWithoutRetryableFinancialCommand() = runTest {
        val reviewed = review(); sessions.redate(today.plusDays(1), publish = false)
        vm.confirm(first, reviewed.token); runCurrent()
        assertTrue(ledger.attempts.isEmpty()); assertNull(vm.state.value.retry)
        assertNotNull(vm.state.value.commandError)
    }
    @Test fun freshPreflightRejectsUnobservedRevisionInsteadOfRebasingUserCommand() = runTest {
        val reviewed = review(); sessions.revise(2, publish = false)
        vm.confirm(first, reviewed.token); runCurrent()
        assertTrue(ledger.attempts.isEmpty()); assertNull(vm.state.value.retry)
        assertEquals(2L, vm.state.value.session!!.revision)
    }
    @Test fun lateDiversionPreviewCannotRestoreReviewAfterEditing() = runTest {
        runCurrent(); planning.previewGate = CompletableDeferred()
        vm.prepareDiversion(first, "goal", null, m(50), "Travel"); runCurrent()
        assertTrue(vm.state.value.busy); vm.edited(second)
        planning.previewGate!!.complete(Unit); runCurrent()
        assertNull(vm.state.value.review); assertNull(vm.state.value.retry); assertFalse(vm.state.value.busy)
    }
    @Test fun diversionReviewUsesExactConsentValuesAndRevision() = runTest {
        runCurrent(); vm.prepareDiversion(first, "goal", "other", m(50), "Reviewed diversion"); runCurrent()
        val reviewed = vm.state.value.review!!
        val action = (reviewed.command.action as LedgerAction.Plan).operation as PlanningAction.Move
        assertEquals(m(50), action.amount); assertEquals("goal", action.sourceId); assertEquals("other", action.destinationId)
        assertEquals(0L, action.consent!!.revision); assertEquals(today, action.consent.asOf)
        vm.confirm(first, reviewed.token); runCurrent()
        assertEquals(reviewed.command, ledger.attempts.single())
    }
    @Test fun observerFailureCannotUnlockWriteOrExposePreviousRetry() = runTest {
        val reviewed = review(); ledger.executeGate = CompletableDeferred()
        vm.confirm(first, reviewed.token); runCurrent(); sessions.failObservation(); runCurrent()
        assertTrue(vm.state.value.busy); assertNotNull(vm.state.value.storageError)
        vm.prepare(second) { income() }; vm.confirm(first, reviewed.token); runCurrent()
        assertEquals(1, ledger.attempts.size)
        ledger.executeGate!!.complete(Unit); runCurrent(); assertFalse(vm.state.value.busy); assertEquals(1, ledger.postings)
    }
    @Test fun lateWriteFailureAfterEditingCannotOfferOldOperationAsRetry() = runTest {
        val reviewed = review(); ledger.executeGate = CompletableDeferred(); ledger.failExecute = true
        vm.confirm(first, reviewed.token); runCurrent(); vm.edited(second)
        ledger.executeGate!!.complete(Unit); runCurrent()
        assertNull(vm.state.value.retry); assertNull(vm.state.value.commandError); assertFalse(vm.state.value.busy)
    }
    @Test fun mutableExpenseSplitsAreCapturedAtReviewTime() = runTest {
        runCurrent(); val splits = mutableListOf(Split("Food", m(100)))
        vm.prepare(first) { LedgerAction.Post(TransactionDraft.Expense("bank", m(100), splits, today)) }
        val reviewed = vm.state.value.review!!; splits[0] = Split("Other", m(100))
        vm.confirm(first, reviewed.token); runCurrent()
        val draft = (ledger.attempts.single().action as LedgerAction.Post).draft as TransactionDraft.Expense
        assertEquals("Food", draft.splits.single().category)
    }
    @Test fun mutableScheduleExceptionsAreCapturedAtReviewTime() = runTest {
        runCurrent(); val exceptions = mutableSetOf(today.plusMonths(1))
        vm.prepare(first) { LedgerAction.Schedule(ScheduleAction.Define(ScheduledRule("rule", "Income",
            (income().draft as TransactionDraft.Income), Recurrence(today, Frequency.MONTHLY, exceptions = exceptions), true))) }
        val reviewed = vm.state.value.review!!; exceptions.clear()
        vm.confirm(first, reviewed.token); runCurrent()
        val action = (ledger.attempts.single().action as LedgerAction.Schedule).operation as ScheduleAction.Define
        assertEquals(setOf(today.plusMonths(1)), action.rule.recurrence.exceptions)
    }
    @Test fun olderObserverEmissionCannotRegressAcceptedFinancialRevision() = runTest {
        review(); sessions.revise(3); runCurrent(); sessions.publish(session(1)); runCurrent()
        assertEquals(3L, vm.state.value.session!!.revision)
    }
    @Test fun retainedFormIdentityAcrossViewModelRecreationCannotPostTwice() = runTest {
        val reviewed = review(); ledger.failAcknowledgementOnce = true
        vm.confirm(first, reviewed.token); runCurrent(); assertEquals(1, ledger.postings)
        store.clear(); vm = FinancialWorkflowViewModel(ledger, sessions, planning); store.put("recreated", vm)
        val again = review(); assertEquals(first.key, again.command.id)
        vm.confirm(first, again.token); runCurrent()
        assertEquals(1, ledger.postings); assertNotNull(vm.state.value.commandError)
    }
    @Test fun observerStorageFailurePreventsReviewUntilReloadSucceeds() = runTest {
        runCurrent(); sessions.failObservation(); runCurrent()
        vm.prepare(first) { income() }
        assertNull(vm.state.value.review); assertNull(vm.state.value.retry)
        assertNotNull(vm.state.value.storageError)
        sessions.publish(sessions.current); vm.reload(); runCurrent(); vm.prepare(first) { income() }
        assertNotNull(vm.state.value.review); assertNull(vm.state.value.storageError)
    }
    @Test fun pendingPeriodIsPreparedBeforeNewFinancialReview() = runTest {
        runCurrent(); sessions.pendingPeriod(); runCurrent()
        assertEquals(1, sessions.preparations); assertNull(vm.state.value.session!!.pendingRolloverThrough)
        vm.prepare(first) { income() }; assertNotNull(vm.state.value.review)
        assertEquals(1L, vm.state.value.review!!.command.expectedRevision); assertEquals(0, ledger.postings)
    }
    @Test fun failedPeriodPreparationRetainsObservationAndPreventsUnpreparedReview() = runTest {
        runCurrent(); sessions.failPreparation = true; sessions.pendingPeriod(); runCurrent()
        assertNotNull(vm.state.value.periodError); assertNull(vm.state.value.storageError)
        vm.prepare(first) { income() }; assertNull(vm.state.value.review); assertNull(vm.state.value.retry)
        sessions.failPreparation = false; vm.reload(); runCurrent()
        assertNull(vm.state.value.periodError); assertNull(vm.state.value.session!!.pendingRolloverThrough)
        vm.prepare(first) { income() }; assertNotNull(vm.state.value.review)
    }
    @Test fun preflightReadFailureDoesNotExposeAnUnattemptedFinancialCommandForRetry() = runTest {
        val reviewed = review(); sessions.failAllSnapshots = true
        vm.confirm(first, reviewed.token); runCurrent()
        assertTrue(ledger.attempts.isEmpty()); assertNull(vm.state.value.retry); assertNotNull(vm.state.value.commandError)
    }

    private fun session(revision: Long = 0, date: LocalDate = today): FinanceSession {
        val zero = m(0); val actual = LedgerSnapshot(revision, emptyList(), emptyList(), m(1000), zero, zero)
        return FinanceSession(PlanningSnapshot(actual, date, FinancialProfile("UTC", zero, zero, 30, true),
            emptyList(), emptyList(), emptyList(), assessGoals(emptyList(), date, zero),
            reservationPosition(m(1000), emptyList()), safeToSpend(m(1000), zero, zero, zero), emptyList(), emptyList(), zero), SetupMode.COMPLETE, null)
    }
    private inner class TestSessions : FinanceSessionRepository {
        var current = session()
        private val events = MutableStateFlow<FinanceSession?>(current)
        var posted = false
        var failSnapshotWhenPosted = false
        var failAllSnapshots = false
        var failPreparation = false
        var preparations = 0
        fun publish(value: FinanceSession) { events.value = value }
        fun failObservation() { events.value = null }
        fun revise(revision: Long, publish: Boolean = true) { current = current.copy(planning = current.planning.copy(ledger = current.planning.ledger.copy(revision = revision))); if (publish) publish(current) }
        fun redate(date: LocalDate, publish: Boolean = true) { current = current.copy(planning = current.planning.copy(asOf = date)); if (publish) publish(current) }
        fun pendingPeriod() { current = current.copy(pendingRolloverThrough = YearMonth.from(today)); publish(current) }
        override suspend fun snapshot(bookId: String): FinanceSession { check(!failAllSnapshots && !(posted && failSnapshotWhenPosted)) { "Refresh failed" }; return current }
        override fun observe(bookId: String) = events.map { requireNotNull(it) { "Observation failed" } }
        override suspend fun prepareCurrentPeriod(bookId: String): CommandReceipt? {
            check(!failPreparation) { "Period preparation failed" }; preparations++
            current = current.copy(pendingRolloverThrough = null); revise(current.revision + 1)
            return CommandReceipt("rollover", "rollover", null, current.revision)
        }
        override suspend fun forecast(bookId: String, horizonDays: Int): CashFlowProjection = error("Not called")
        override suspend fun simulatePurchase(bookId: String, horizonDays: Int, date: LocalDate, amount: Money, funding: PurchaseFunding?): PurchaseImpact = error("Not called")
    }
    private inner class TestLedger(private val sessions: TestSessions) : LedgerRepository {
        val attempts = mutableListOf<LedgerCommand>()
        private val completed = mutableMapOf<String, Pair<LedgerCommand, CommandReceipt>>()
        var postings = 0
        var failExecute = false
        var failAcknowledgementOnce = false
        var executeGate: CompletableDeferred<Unit>? = null
        override suspend fun initializeBook(bookId: String, currency: CurrencySpec) = Unit
        override suspend fun execute(command: LedgerCommand): CommandReceipt {
            attempts += command; executeGate?.await(); check(!failExecute) { "Write failed" }
            completed[command.id]?.let { require(it.first == command) { "Command key already used with a different payload" }; return it.second }
            check(command.expectedRevision == sessions.current.revision) { "Stale state" }
            val receipt = CommandReceipt(command.id, "entry-${command.id}", null, command.expectedRevision + 1)
            completed[command.id] = command to receipt; postings++; sessions.posted = true
            sessions.revise(receipt.revision, publish = false)
            if (failAcknowledgementOnce) { failAcknowledgementOnce = false; error("Acknowledgement lost") }
            return receipt
        }
        override suspend fun snapshot(bookId: String) = sessions.current.planning.ledger
        override fun observe(bookId: String) = flowOf(sessions.current.planning.ledger)
        override suspend fun history(bookId: String, filter: HistoryFilter): List<HistoryEntry> = error("Not called")
        override suspend fun reconcile(bookId: String, accountId: String, date: LocalDate, statement: Money, basis: BalanceBasis): ReconciliationPreview = error("Not called")
    }
    private inner class TestPlanning(private val sessions: TestSessions) : PlanningRepository {
        var failPreview = false
        var previewGate: CompletableDeferred<Unit>? = null
        override suspend fun snapshot(bookId: String, asOf: LocalDate) = sessions.current.planning
        override fun observe(bookId: String, asOf: LocalDate) = flowOf(sessions.current.planning)
        override suspend fun previewDiversion(bookId: String, sourceId: String, destinationId: String?, amount: Money, asOf: LocalDate): DiversionReview {
            previewGate?.await(); check(!failPreview) { "Preview failed" }
            return DiversionReview(DiversionConsent(sessions.current.revision, asOf, sourceId, destinationId, amount, emptyList()),
                "Goal", m(50), true, safeToSpend(m(1000), m(50), m(0), m(0)))
        }
    }
}
