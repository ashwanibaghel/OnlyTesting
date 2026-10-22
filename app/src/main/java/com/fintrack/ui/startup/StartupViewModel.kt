package com.fintrack.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.data.local.AccountDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class StorageState { CHECKING, READY, FAILED }

@HiltViewModel
class StartupViewModel @Inject constructor(accounts: AccountDao) : ViewModel() {
    val storageState: StateFlow<StorageState> = accounts.observeCount()
        .map { StorageState.READY }
        .catch { emit(StorageState.FAILED) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageState.CHECKING)
}
