package com.fintrack.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.fintrack.data.local.AccountDao
import com.fintrack.data.local.FinTrackDatabase
import com.fintrack.data.local.LedgerSchema
import com.fintrack.data.local.PlanningSchema
import com.fintrack.data.ledger.RoomLedgerRepository
import com.fintrack.domain.ledger.LedgerRepository
import com.fintrack.domain.planning.PlanningRepository
import com.fintrack.data.planning.RoomPlanningRepository
import java.time.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "user_settings")

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): FinTrackDatabase =
        Room.databaseBuilder(context, FinTrackDatabase::class.java, "fintrack.db")
            .addMigrations(LedgerSchema.migration1To2, PlanningSchema.migration2To3)
            .addCallback(LedgerSchema.callback)
            .build()

    @Provides
    @Singleton
    fun ledger(database: FinTrackDatabase): LedgerRepository = RoomLedgerRepository(database, Clock.systemDefaultZone())

    @Provides
    @Singleton
    fun planning(database: FinTrackDatabase, ledger: LedgerRepository): PlanningRepository =
        RoomPlanningRepository(database, ledger, Clock.systemDefaultZone())

    @Provides
    fun accounts(database: FinTrackDatabase): AccountDao = database.accountDao()

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsStore
}
