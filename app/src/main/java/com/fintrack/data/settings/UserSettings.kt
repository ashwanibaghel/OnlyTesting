package com.fintrack.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Nonfinancial preferences only. Never store balances or financial reservations here. */
@Singleton
class UserSettings @Inject constructor(private val store: DataStore<Preferences>) {
    val hideAmounts: Flow<Boolean> = store.data.map { it[HIDE_AMOUNTS] ?: false }

    suspend fun setHideAmounts(hidden: Boolean) {
        store.edit { it[HIDE_AMOUNTS] = hidden }
    }

    private companion object {
        val HIDE_AMOUNTS = booleanPreferencesKey("hide_amounts")
    }
}
