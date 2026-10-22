package com.fintrack.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface PlanningDao {
    @Query("SELECT * FROM financial_profiles WHERE bookId = :book") suspend fun profile(book: String): FinancialProfileEntity?
    @Insert suspend fun insertProfile(value: FinancialProfileEntity)
    @Update suspend fun updateProfile(value: FinancialProfileEntity): Int
    @Query("SELECT * FROM reserve_buckets WHERE bookId = :book ORDER BY name,id") suspend fun buckets(book: String): List<ReserveBucketEntity>
    @Insert suspend fun insertBucket(value: ReserveBucketEntity)
    @Update suspend fun updateBucket(value: ReserveBucketEntity): Int
    @Query("SELECT b.* FROM monthly_budgets b JOIN reserve_buckets r ON r.id = b.bucketId WHERE r.bookId = :book")
    suspend fun budgets(book: String): List<MonthlyBudgetEntity>
    @Insert suspend fun insertBudget(value: MonthlyBudgetEntity)
    @Update suspend fun updateBudget(value: MonthlyBudgetEntity): Int
    @Query("SELECT g.* FROM financial_goals g JOIN reserve_buckets r ON r.id = g.bucketId WHERE r.bookId = :book")
    suspend fun goals(book: String): List<FinancialGoalEntity>
    @Insert suspend fun insertGoal(value: FinancialGoalEntity)
    @Update suspend fun updateGoal(value: FinancialGoalEntity): Int
    @Insert suspend fun insertAllocation(value: AllocationJournalEntity)
    @Insert suspend fun insertLegs(value: List<AllocationLegEntity>)
    @Insert suspend fun seal(value: AllocationSealEntity)
    @Query("SELECT l.* FROM allocation_legs l JOIN allocation_journals j ON j.id = l.entryId JOIN allocation_seals s ON s.entryId = j.id WHERE j.bookId = :book")
    suspend fun legs(book: String): List<AllocationLegEntity>
    @Query("SELECT j.* FROM allocation_journals j JOIN allocation_seals s ON s.entryId = j.id WHERE j.bookId = :book AND j.linkedJournalId = :journal")
    suspend fun linked(book: String, journal: String): List<AllocationJournalEntity>
}
