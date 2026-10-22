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

data class LedgerUiState(val snapshot: LedgerSnapshot? = null, val busy: Boolean = false,
    val error: String? = null, val review: ReconciliationPreview? = null, val savedCommandId: String? = null)

@HiltViewModel
class LedgerViewModel @Inject constructor(private val repository: LedgerRepository) : ViewModel() {
    private val mutable = MutableStateFlow(LedgerUiState())
    val state = mutable.asStateFlow()
    private var retryCommand: LedgerCommand? = null

    init {
        viewModelScope.launch {
            try {
                repository.initializeBook()
                repository.observe().collect { snapshot -> mutable.update { it.copy(snapshot = snapshot) } }
            } catch (error: Exception) { failure(error) }
        }
    }

    fun submit(key: String? = null, action: () -> LedgerAction) {
        if (mutable.value.busy) return
        if (key != null && mutable.value.savedCommandId == key) return
        try {
            val revision = requireNotNull(mutable.value.snapshot) { "Storage is not ready" }.revision
            execute(LedgerCommand(key ?: UUID.randomUUID().toString(), DEFAULT_BOOK, revision, action()))
        } catch (error: Exception) { failure(error) }
    }

    fun retry() { retryCommand?.let { execute(it) } }

    private fun execute(command: LedgerCommand) {
        if (mutable.value.busy) return
        retryCommand = command
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                repository.execute(command)
                val snapshot = repository.snapshot()
                retryCommand = null
                mutable.update { it.copy(snapshot = snapshot, busy = false, review = null, savedCommandId = command.id) }
            } catch (error: Exception) { failure(error) }
        }
    }

    fun review(account: String, date: String, amount: String, basis: BalanceBasis) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null, review = null) }
        viewModelScope.launch {
            try {
                val result = repository.reconcile(DEFAULT_BOOK, account, LocalDate.parse(date), Money.parse(amount, INR), basis)
                mutable.update { it.copy(review = result, busy = false) }
            } catch (error: Exception) { failure(error) }
        }
    }

    fun applyReview(reason: String) {
        val preview = mutable.value.review ?: return
        execute(LedgerCommand(UUID.randomUUID().toString(), DEFAULT_BOOK, preview.revision,
            LedgerAction.Reconcile(preview.accountId, preview.date, preview.statement, preview.basis, reason)))
    }

    private fun failure(error: Exception) {
        if (error is CancellationException) throw error
        mutable.update { it.copy(busy = false, error = error.message ?: "Operation failed. No partial financial change was saved.") }
    }
}
