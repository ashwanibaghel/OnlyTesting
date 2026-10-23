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
import com.fintrack.data.local.FinTrackMigrations
import com.fintrack.data.ledger.RoomLedgerRepository
import com.fintrack.data.ledger.RoomFinancialReviewRepository
import com.fintrack.domain.ledger.FinancialReviewRepository
import com.fintrack.domain.ledger.HistoryRepository
import com.fintrack.domain.reports.FinancialReportsRepository
import com.fintrack.data.reports.RoomFinancialReportsRepository
import com.fintrack.data.ledger.RoomHistoryRepository
import com.fintrack.domain.ledger.LedgerRepository
import com.fintrack.domain.planning.PlanningRepository
import com.fintrack.data.planning.RoomPlanningRepository
import com.fintrack.domain.schedules.ScheduleRepository
import com.fintrack.data.schedules.RoomScheduleRepository
import com.fintrack.data.session.RoomFinanceSessionRepository
import com.fintrack.domain.session.FinanceSessionRepository
import com.fintrack.domain.session.FinancialOverviewRepository
import com.fintrack.data.session.RoomFinancialOverviewRepository
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
            .addMigrations(*FinTrackMigrations.all)
            .addCallback(LedgerSchema.callback)
            .build()

    @Provides
    @Singleton
    fun ledger(database: FinTrackDatabase): LedgerRepository = RoomLedgerRepository(database, Clock.systemDefaultZone())

    @Provides
    @Singleton
    fun financialReviews(database: FinTrackDatabase, ledger: LedgerRepository): FinancialReviewRepository =
        RoomFinancialReviewRepository(database, ledger, Clock.systemDefaultZone())

    @Provides
    @Singleton
    fun history(database: FinTrackDatabase): HistoryRepository = RoomHistoryRepository(database)

    @Provides
    @Singleton
    fun reports(database: FinTrackDatabase): FinancialReportsRepository = RoomFinancialReportsRepository(database, Clock.systemDefaultZone())

    @Provides
    @Singleton
    fun planning(database: FinTrackDatabase, ledger: LedgerRepository): PlanningRepository =
        RoomPlanningRepository(database, ledger, Clock.systemDefaultZone())

    @Provides
    @Singleton
    fun schedules(database: FinTrackDatabase, ledger: LedgerRepository): ScheduleRepository =
        RoomScheduleRepository(database, ledger, Clock.systemDefaultZone())

    @Provides
    @Singleton
    fun session(database: FinTrackDatabase, ledger: LedgerRepository,
        schedules: ScheduleRepository): FinanceSessionRepository =
        RoomFinanceSessionRepository(database, ledger, schedules, Clock.systemDefaultZone())

    @Provides
    @Singleton
    fun overview(database: FinTrackDatabase, session: FinanceSessionRepository, reports: FinancialReportsRepository,
        history: HistoryRepository): FinancialOverviewRepository =
        RoomFinancialOverviewRepository(database, session, reports, history, Clock.systemDefaultZone())

    @Provides
    fun accounts(database: FinTrackDatabase): AccountDao = database.accountDao()

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsStore
}
