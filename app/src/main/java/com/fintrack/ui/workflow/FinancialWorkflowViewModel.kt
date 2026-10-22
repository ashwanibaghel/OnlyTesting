package com.fintrack.ui.workflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.PlanningAction
import com.fintrack.domain.planning.PlanningRepository
import com.fintrack.domain.session.FinanceSession
import com.fintrack.domain.session.FinanceSessionRepository
import com.fintrack.engine.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A form instance identity, retained while editing; separate instances get separate keys. */
data class FinancialOperation(val key: String) {
    init { require(key.matches(Regex("[A-Za-z0-9_-]{1,100}"))) }
    companion object { fun newForm() = FinancialOperation(UUID.randomUUID().toString()) }
}
data class ReviewedFinancialCommand(val token: String, val operation: FinancialOperation,
    val inputVersion: Long, val asOf: LocalDate, val command: LedgerCommand)
data class FailedFinancialCommand(val operation: FinancialOperation, val command: LedgerCommand)
data class FinancialWorkflowState(val session: FinanceSession? = null,
    val operation: FinancialOperation? = null, val busy: Boolean = false,
    val review: ReviewedFinancialCommand? = null, val retry: FailedFinancialCommand? = null,
    val commandError: String? = null, val storageError: String? = null,
    val periodError: String? = null, val saved: Map<String, CommandReceipt> = emptyMap())

/** Shared financial form behavior; no consumer layout or design decisions are embedded here. */
@HiltViewModel
class FinancialWorkflowViewModel @Inject constructor(private val ledger: LedgerRepository,
    private val sessions: FinanceSessionRepository, private val planning: PlanningRepository) : ViewModel() {
    private val mutable = MutableStateFlow(FinancialWorkflowState())
    val state = mutable.asStateFlow()
    private var inputVersion = 0L
    private var observation: Job? = null

    init { reload() }

    fun reload() {
        observation?.cancel()
        observation = viewModelScope.launch {
            try {
                ledger.initializeBook()
                sessions.observe(DEFAULT_BOOK).collect { current ->
                    acceptSession(current)
                    if (current.pendingRolloverThrough != null) {
                        try { sessions.prepareCurrentPeriod(DEFAULT_BOOK); mutable.update { it.copy(periodError = null) } }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            mutable.update { it.copy(periodError = error.message ?: "Monthly preparation failed") }
                        }
                    } else mutable.update { it.copy(periodError = null) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // An observer failure cannot release an independent write's submission lock.
                mutable.update { it.copy(storageError = error.message ?: "Storage is unavailable", review = null) }
            }
        }
    }

    /** Call synchronously on every relevant input edit and operation change. */
    fun edited(operation: FinancialOperation) {
        inputVersion = Math.addExact(inputVersion, 1)
        mutable.update { it.copy(operation = operation, review = null, retry = null, commandError = null) }
    }

    fun prepare(operation: FinancialOperation, action: (FinanceSession) -> LedgerAction) {
        if (mutable.value.busy || operation.key in mutable.value.saved) return
        edited(operation)
        try {
            val current = ready()
            publishReview(operation, inputVersion, current, action(current).detached())
        } catch (error: Exception) { fail(error, inputVersion) }
    }

    /** Protected diversion always obtains recomputed consequences from the actual repository. */
    fun prepareDiversion(operation: FinancialOperation, sourceId: String, destinationId: String?, amount: Money, reason: String) {
        if (mutable.value.busy || operation.key in mutable.value.saved) return
        edited(operation)
        val version = inputVersion
        val current = try { ready() } catch (error: Exception) { fail(error, version); return }
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = planning.previewDiversion(DEFAULT_BOOK, sourceId, destinationId, amount, current.today)
                if (result.consent.revision == current.revision && result.consent.asOf == current.today)
                    publishReview(operation, version, current, LedgerAction.Plan(PlanningAction.Move(sourceId, destinationId, amount, reason, result.consent)).detached())
            } catch (error: Exception) { fail(error, version) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private fun ready(): FinanceSession {
        check(mutable.value.storageError == null) { "Refresh storage before reviewing a financial operation" }
        val current = requireNotNull(mutable.value.session) { "Storage is not ready" }
        check(current.pendingRolloverThrough == null) { "Complete configured monthly preparation before reviewing" }
        return current
    }

    private fun publishReview(operation: FinancialOperation, version: Long, current: FinanceSession, action: LedgerAction) {
        val latest = mutable.value
        if (version != inputVersion || latest.operation != operation || latest.storageError != null ||
            latest.session?.revision != current.revision || latest.session?.today != current.today) return
        // Retaining the form key across recreation prevents a changed payload from silently posting twice.
        val command = LedgerCommand(operation.key, DEFAULT_BOOK, current.revision, action)
        mutable.update { it.copy(review = ReviewedFinancialCommand(UUID.randomUUID().toString(), operation, version, current.today, command)) }
    }

    fun confirm(operation: FinancialOperation, reviewToken: String) {
        if (mutable.value.busy) return
        val review = mutable.value.review ?: return
        if (review.operation != operation || review.token != reviewToken || review.inputVersion != inputVersion ||
            mutable.value.operation != operation || mutable.value.session?.revision != review.command.expectedRevision ||
            mutable.value.session?.today != review.asOf || mutable.value.storageError != null) return
        execute(operation, review.command, review.asOf)
    }

    fun retry(operation: FinancialOperation, commandId: String) {
        if (mutable.value.busy || mutable.value.operation != operation) return
        val failed = mutable.value.retry?.takeIf { it.operation == operation && it.command.id == commandId } ?: return
        // Keep original ID, payload and expected revision, including after a lost acknowledgement.
        execute(operation, failed.command)
    }

    private fun acceptSession(current: FinanceSession) {
        mutable.update {
            if (it.session != null && it.session.revision > current.revision) it
            else it.copy(session = current, storageError = null,
                review = it.review?.takeIf { review -> review.command.expectedRevision == current.revision && review.asOf == current.today })
        }
    }

    private fun execute(operation: FinancialOperation, command: LedgerCommand, reviewedOn: LocalDate? = null) {
        if (command.id in mutable.value.saved) return
        val version = inputVersion
        mutable.update { it.copy(busy = true, review = null, retry = null, commandError = null) }
        viewModelScope.launch {
            var attempted = false
            try {
                if (reviewedOn != null) {
                    val fresh = sessions.snapshot(DEFAULT_BOOK)
                    acceptSession(fresh)
                    check(fresh.revision == command.expectedRevision && fresh.today == reviewedOn && fresh.pendingRolloverThrough == null) {
                        "Financial state or book date changed; refresh and review again"
                    }
                }
                attempted = true
                val receipt = ledger.execute(command)
                // Save the successful receipt before any refresh that could fail.
                mutable.update { it.copy(saved = it.saved + (command.id to receipt)) }
                val current = sessions.snapshot(DEFAULT_BOOK)
                acceptSession(current)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (command.id in mutable.value.saved) mutable.update { it.copy(storageError = error.message ?: "Saved; refresh storage") }
                else fail(error, version, if (attempted) FailedFinancialCommand(operation, command) else null)
            }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private fun fail(error: Exception, version: Long, failed: FailedFinancialCommand? = null) {
        if (error is CancellationException) throw error
        if (version != inputVersion) return
        mutable.update { it.copy(commandError = error.message ?: "Financial operation failed",
            review = null, retry = failed?.takeUnless { value -> value.command.id in it.saved }) }
    }
}
