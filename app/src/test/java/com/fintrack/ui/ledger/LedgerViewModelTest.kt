package com.fintrack.ui.ledger

import androidx.lifecycle.ViewModelStore
import com.fintrack.domain.ledger.*
import com.fintrack.engine.CurrencySpec
import com.fintrack.engine.Money
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModelTest {
    private val store = ViewModelStore()
    private val repository = TestRepository()
    private lateinit var vm: LedgerViewModel
    private val inputs = ReconciliationInputs("bank", "2026-10-23", "12.34", BalanceBasis.CLEARED)
    private fun income() = LedgerAction.Post(TransactionDraft.Income("bank", Money(100, INR), "Salary", LocalDate.parse(inputs.date)))

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        vm = LedgerViewModel(repository)
        store.put("ledger", vm)
    }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    private fun TestScope.failSubmission(key: String = "income"): LedgerCommand {
        runCurrent()
        repository.failExecute = true
        vm.submit(key) { income() }
        runCurrent()
        return requireNotNull(vm.state.value.retryCommand)
    }

    @Test fun failedSubmissionThenInvalidInputCannotRetryPreviousCommand() = runTest {
        val failed = failSubmission()
        vm.submit("invalid") { Money.parse("not-money", INR); income() }
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.retryCommand)
        vm.retry(failed.id)
        runCurrent()
        assertEquals(1, repository.attempts.size)
    }

    @Test fun failedSubmissionThenFailedPreviewCannotRetryPreviousCommand() = runTest {
        val failed = failSubmission()
        repository.failPreview = true
        vm.review(inputs)
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.review)
        assertNull(vm.state.value.retryCommand)
        vm.retry(failed.id)
        assertEquals(1, repository.attempts.size)
    }

    @Test fun failedSubmissionThenInvalidPreviewCannotRetryPreviousCommand() = runTest {
        val failed = failSubmission()
        vm.review(inputs.copy(date = "bad-date"))
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.retryCommand)
        vm.retry(failed.id)
        assertEquals(1, repository.attempts.size)
    }

    @Test fun editingOrSwitchingOperationDiscardsRetryAndRejectsOldCallback() = runTest {
        val failed = failSubmission()
        vm.inputsChanged()
        assertNull(vm.state.value.retryCommand)
        assertNull(vm.state.value.error)
        vm.retry(failed.id)
        runCurrent()
        assertEquals(1, repository.attempts.size)
    }

    @Test fun oldRetryCallbackCannotExecuteNewFailedOperation() = runTest {
        val first = failSubmission()
        val second = failSubmission("another-income")
        vm.retry(first.id)
        runCurrent()
        assertEquals(second, vm.state.value.retryCommand)
        assertEquals(2, repository.attempts.size)
    }

    @Test fun retryAfterLostAcknowledgementUsesExactCommandAndPostsOnce() = runTest {
        runCurrent()
        repository.failAfterCommit = true
        vm.submit("income") { income() }
        runCurrent()
        val failed = requireNotNull(vm.state.value.retryCommand)
        vm.retry(failed.id)
        vm.retry(failed.id)
        runCurrent()
        assertEquals(listOf(failed, failed), repository.attempts)
        assertEquals(1, repository.committed.size)
        assertEquals(100L, vm.state.value.snapshot!!.income.minor)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.retryCommand)
    }

    @Test fun repeatedSubmitAfterRefreshFailureRetainsOriginalRevision() = runTest {
        runCurrent()
        repository.failSnapshot = true
        vm.submit("income") { income() }
        runCurrent()
        val failed = requireNotNull(vm.state.value.retryCommand)
        assertTrue(repository.snapshots.value.revision > failed.expectedRevision)
        vm.submit("income") { income() }
        runCurrent()
        assertEquals(listOf(failed, failed), repository.attempts)
        assertEquals(1, repository.committed.size)
    }

    @Test fun unrelatedSuccessDoesNotReenablePreviouslySavedForm() = runTest {
        runCurrent()
        vm.submit("income") { income() }
        runCurrent()
        vm.submit("archive") { LedgerAction.Archive("bank", true) }
        runCurrent()
        vm.submit("income") { fail("Saved form must not be evaluated"); income() }
        runCurrent()
        assertEquals(2, repository.attempts.size)
        assertEquals(setOf("income", "archive"), vm.state.value.savedCommandIds)
    }

    @Test fun rapidSubmissionsAreBlockedBeforeCoroutineStarts() = runTest {
        runCurrent()
        vm.submit("first") { income() }
        vm.submit("second") { income() }
        runCurrent()
        assertEquals(1, repository.attempts.size)
    }

    @Test fun observationFailureCannotUnlockAnInFlightSubmission() = runTest {
        runCurrent()
        repository.executeGate = CompletableDeferred()
        vm.submit("first") { income() }
        runCurrent()
        repository.failObservation = true
        repository.snapshots.value = repository.snapshots.value.copy(revision = 8)
        runCurrent()
        assertTrue(vm.state.value.busy)
        assertNull(vm.state.value.retryCommand)
        vm.submit("second") { income() }
        repository.executeGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, repository.attempts.size)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
    }

    private fun TestScope.rejectChangedReview(changed: ReconciliationInputs) {
        runCurrent()
        vm.review(inputs)
        runCurrent()
        val reviewed = requireNotNull(vm.state.value.review)
        vm.applyReview(reviewed.id, changed, "Statement checked")
        runCurrent()
        assertNull(vm.state.value.review)
        assertTrue(repository.attempts.isEmpty())
    }

    @Test fun changingReviewedAccountPreventsApply() = runTest { rejectChangedReview(inputs.copy(account = "cash")) }
    @Test fun changingReviewedDatePreventsApply() = runTest { rejectChangedReview(inputs.copy(date = "2026-10-22")) }
    @Test fun changingReviewedBalancePreventsApply() = runTest { rejectChangedReview(inputs.copy(amount = "56.78")) }
    @Test fun changingReviewedBasisPreventsApply() = runTest { rejectChangedReview(inputs.copy(basis = BalanceBasis.POSTED)) }

    @Test fun editInvalidatesPreviewEvenIfFieldsAreChangedBack() = runTest {
        runCurrent()
        vm.review(inputs)
        runCurrent()
        val reviewed = requireNotNull(vm.state.value.review)
        vm.inputsChanged()
        vm.applyReview(reviewed.id, inputs, "Statement checked")
        runCurrent()
        assertNull(vm.state.value.review)
        assertTrue(repository.attempts.isEmpty())
    }

    @Test fun latePreviewCannotRestoreReviewAfterEdit() = runTest {
        runCurrent()
        repository.previewGate = CompletableDeferred()
        vm.review(inputs)
        runCurrent()
        assertTrue(vm.state.value.busy)
        vm.inputsChanged()
        repository.previewGate!!.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.review)
    }

    @Test fun bookRevisionChangeInvalidatesReview() = runTest {
        runCurrent()
        vm.review(inputs)
        runCurrent()
        val reviewed = requireNotNull(vm.state.value.review)
        repository.snapshots.value = repository.snapshots.value.copy(revision = 8)
        runCurrent()
        assertNull(vm.state.value.review)
        vm.applyReview(reviewed.id, inputs, "Statement checked")
        assertTrue(repository.attempts.isEmpty())
    }

    @Test fun applyUsesExactReviewedValuesAndRevisionAndConsumesReviewOnce() = runTest {
        runCurrent()
        vm.review(inputs)
        runCurrent()
        val reviewed = requireNotNull(vm.state.value.review)
        vm.inputsChanged(invalidateReview = false) // A reason edit does not change the calculation.
        vm.applyReview(reviewed.id, inputs, "Statement checked")
        vm.applyReview(reviewed.id, inputs, "Statement checked")
        runCurrent()
        vm.applyReview(reviewed.id, inputs, "Statement checked")
        runCurrent()
        val command = repository.attempts.single()
        assertEquals(reviewed.preview.revision, command.expectedRevision)
        assertEquals(LedgerAction.Reconcile("bank", LocalDate.parse(inputs.date), Money(1234, INR),
            BalanceBasis.CLEARED, "Statement checked"), command.action)
        assertNull(vm.state.value.review)
    }

    @Test fun failedApplyCanOnlyRetryItsOriginalCommand() = runTest {
        runCurrent()
        vm.review(inputs)
        runCurrent()
        val reviewed = requireNotNull(vm.state.value.review)
        repository.failAfterCommit = true
        vm.applyReview(reviewed.id, inputs, "Statement checked")
        runCurrent()
        val failed = requireNotNull(vm.state.value.retryCommand)
        vm.applyReview(reviewed.id, inputs, "Different reason")
        assertNull(vm.state.value.review)
        vm.retry(failed.id)
        runCurrent()
        assertEquals(listOf(failed, failed), repository.attempts)
        assertEquals(1, repository.committed.size)
    }

    private class TestRepository : LedgerRepository {
        val snapshots = MutableStateFlow(LedgerSnapshot(7, emptyList(), emptyList(), Money(0, INR), Money(0, INR), Money(0, INR)))
        val attempts = mutableListOf<LedgerCommand>()
        val committed = mutableMapOf<String, LedgerCommand>()
        var failExecute = false
        var failAfterCommit = false
        var failSnapshot = false
        var failPreview = false
        var failObservation = false
        var executeGate: CompletableDeferred<Unit>? = null
        var previewGate: CompletableDeferred<Unit>? = null
        override suspend fun initializeBook(bookId: String, currency: CurrencySpec) = Unit
        override fun observe(bookId: String) = snapshots.transform {
            if (failObservation) error("Observation unavailable")
            emit(it)
        }
        override suspend fun snapshot(bookId: String): LedgerSnapshot {
            if (failSnapshot) { failSnapshot = false; error("Refresh unavailable") }
            return snapshots.value
        }
        override suspend fun history(bookId: String, filter: HistoryFilter) = emptyList<HistoryEntry>()
        override suspend fun execute(command: LedgerCommand): CommandReceipt {
            attempts += command
            executeGate?.await()
            if (failExecute) { failExecute = false; error("Submission unavailable") }
            if (committed.putIfAbsent(command.id, command) == null) {
                snapshots.value = snapshots.value.copy(revision = snapshots.value.revision + 1,
                    income = Money(snapshots.value.income.minor + if (command.action is LedgerAction.Post) 100 else 0, INR))
            } else check(committed[command.id] == command) { "Command changed on retry" }
            if (failAfterCommit) { failAfterCommit = false; error("Acknowledgement lost") }
            return CommandReceipt(command.id, command.id, null, snapshots.value.revision)
        }
        override suspend fun reconcile(bookId: String, accountId: String, date: LocalDate, statement: Money,
            basis: BalanceBasis): ReconciliationPreview {
            previewGate?.await()
            if (failPreview) error("Preview unavailable")
            return ReconciliationPreview(accountId, date, basis, statement, Money(0, INR), Money(0, INR), statement, snapshots.value.revision)
        }
    }
}
