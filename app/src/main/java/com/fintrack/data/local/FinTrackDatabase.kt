package com.fintrack.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [BookEntity::class, AccountEntity::class, LedgerAccountEntity::class,
    CommandEntity::class, JournalEntity::class, PostingEntity::class, JournalSealEntity::class,
    ClearingEntity::class, AuditEntity::class, ReconciliationEntity::class,
    FinancialProfileEntity::class, ReserveBucketEntity::class, MonthlyBudgetEntity::class,
    FinancialGoalEntity::class, AllocationJournalEntity::class, AllocationLegEntity::class,
    AllocationSealEntity::class], version = 3, exportSchema = true)
abstract class FinTrackDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    internal abstract fun ledgerDao(): LedgerDao
    internal abstract fun planningDao(): PlanningDao
}
