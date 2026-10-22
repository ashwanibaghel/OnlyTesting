package com.fintrack.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.domain.ledger.*
import com.fintrack.engine.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReconciliationInputs(val account: String, val date: String, val amount: String, val basis: BalanceBasis)
data class ReviewedReconciliation(val id: String, val inputs: ReconciliationInputs, val preview: ReconciliationPreview)
data class LedgerUiState(val snapshot: LedgerSnapshot? = null, val busy: Boolean = false,
    val error: String? = null, val review: ReviewedReconciliation? = null,
    val retryCommand: LedgerCommand? = null, val savedCommandIds: Set<String> = emptySet())

@HiltViewModel
class LedgerViewModel @Inject constructor(private val repository: LedgerRepository) : ViewModel() {
    private val mutable = MutableStateFlow(LedgerUiState())
    val state = mutable.asStateFlow()
    private var operationVersion = 0L

    init {
        viewModelScope.launch {
            try {
                repository.initializeBook()
                repository.observe().collect { snapshot -> mutable.update {
                    it.copy(snapshot = snapshot, review = it.review?.takeIf { review -> review.preview.revision == snapshot.revision })
                } }
            } catch (error: Exception) { failure(error, releaseBusy = false) }
        }
    }

    /** Called synchronously by edit callbacks, before an old button callback can be used. */
    fun inputsChanged(invalidateReview: Boolean = true) {
        operationVersion++
        mutable.update { it.copy(error = null, retryCommand = null, review = if (invalidateReview) null else it.review) }
    }

    fun submit(key: String? = null, action: () -> LedgerAction) {
        if (mutable.value.busy || key in mutable.value.savedCommandIds) return
        val previous = mutable.value.retryCommand
        inputsChanged()
        try {
            val revision = requireNotNull(mutable.value.snapshot) { "Storage is not ready" }.revision
            val requested = action()
            // A repeated click on an unchanged failed form must retain its original revision and ID.
            val command = previous?.takeIf { key != null && it.id == key && it.action == requested }
                ?: LedgerCommand(key ?: UUID.randomUUID().toString(), DEFAULT_BOOK, revision, requested)
            execute(command)
        } catch (error: Exception) { failure(error) }
    }

    fun retry(commandId: String) {
        val command = mutable.value.retryCommand?.takeIf { it.id == commandId } ?: return
        if (mutable.value.busy) return
        inputsChanged()
        execute(command)
    }

    private fun execute(command: LedgerCommand) {
        if (mutable.value.busy) return
        val version = operationVersion
        // Consume the review immediately: two Apply callbacks cannot create two command IDs.
        mutable.update { it.copy(busy = true, error = null, review = null, retryCommand = null) }
        viewModelScope.launch {
            try {
                repository.execute(command)
                val snapshot = repository.snapshot()
                mutable.update { it.copy(snapshot = snapshot, busy = false, error = null,
                    savedCommandIds = it.savedCommandIds + command.id) }
            } catch (error: Exception) { failure(error, command, version) }
        }
    }

    fun review(inputs: ReconciliationInputs) {
        if (mutable.value.busy) return
        inputsChanged()
        val version = operationVersion
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = repository.reconcile(DEFAULT_BOOK, inputs.account, LocalDate.parse(inputs.date),
                    Money.parse(inputs.amount, INR), inputs.basis)
                mutable.update { it.copy(busy = false, review = if (version == operationVersion &&
                    it.snapshot?.revision == result.revision) ReviewedReconciliation(UUID.randomUUID().toString(), inputs, result) else null) }
            } catch (error: Exception) { failure(error, version = version) }
        }
    }

    fun applyReview(reviewId: String, inputs: ReconciliationInputs, reason: String) {
        if (mutable.value.busy) return
        val reviewed = mutable.value.review ?: return
        if (reviewed.id != reviewId || reviewed.inputs != inputs ||
            reviewed.preview.revision != mutable.value.snapshot?.revision) {
            inputsChanged()
            return
        }
        val preview = reviewed.preview
        inputsChanged()
        execute(LedgerCommand(UUID.randomUUID().toString(), DEFAULT_BOOK, preview.revision,
            LedgerAction.Reconcile(preview.accountId, preview.date, preview.statement, preview.basis, reason)))
    }

    private fun failure(error: Exception, command: LedgerCommand? = null, version: Long = operationVersion,
        releaseBusy: Boolean = true) {
        if (error is CancellationException) throw error
        mutable.update { it.copy(busy = if (releaseBusy) false else it.busy,
            error = if (version == operationVersion) error.message ?: "Operation failed. Check the ledger before starting a new submission." else null,
            retryCommand = command?.takeIf { version == operationVersion }) }
    }
}
