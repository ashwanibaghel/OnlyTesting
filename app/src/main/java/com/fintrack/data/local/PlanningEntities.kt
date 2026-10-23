package com.fintrack.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "financial_profiles", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"]),
])
data class FinancialProfileEntity(@PrimaryKey val bookId: String, val timezone: String,
    val safetyBufferMinor: Long, val monthlyCapacityMinor: Long, val horizonDays: Int, val setupComplete: Boolean)

@Entity(tableName = "reserve_buckets", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"]),
], indices = [Index("bookId"), Index(value = ["bookId", "category", "month"], unique = true)])
data class ReserveBucketEntity(@PrimaryKey val id: String, val bookId: String, val name: String,
    val kind: String, val protected: Boolean, val category: String?, val month: String?, val archived: Boolean)

@Entity(tableName = "monthly_budgets", foreignKeys = [
    ForeignKey(entity = ReserveBucketEntity::class, parentColumns = ["id"], childColumns = ["bucketId"]),
])
data class MonthlyBudgetEntity(@PrimaryKey val bucketId: String, val limitMinor: Long,
    val carriedMinor: Long, val rollover: String, val closed: Boolean)

@Entity(tableName = "financial_goals", foreignKeys = [
    ForeignKey(entity = ReserveBucketEntity::class, parentColumns = ["id"], childColumns = ["bucketId"]),
])
data class FinancialGoalEntity(@PrimaryKey val bucketId: String, val targetMinor: Long, val deadline: String,
    val priority: Int, val monthlyMinor: Long, val anchorDay: Int)

@Entity(tableName = "allocation_journals", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"]),
    ForeignKey(entity = CommandEntity::class, parentColumns = ["id"], childColumns = ["commandId"]),
    ForeignKey(entity = JournalEntity::class, parentColumns = ["id"], childColumns = ["linkedJournalId"]),
], indices = [Index("bookId"), Index("commandId"), Index("linkedJournalId")])
data class AllocationJournalEntity(@PrimaryKey val id: String, val bookId: String, val commandId: String,
    val kind: String, val effectiveDate: String, val reason: String, val createdAt: Long, val linkedJournalId: String?)

@Entity(tableName = "allocation_legs", foreignKeys = [
    ForeignKey(entity = AllocationJournalEntity::class, parentColumns = ["id"], childColumns = ["entryId"]),
    ForeignKey(entity = ReserveBucketEntity::class, parentColumns = ["id"], childColumns = ["bucketId"]),
], indices = [Index("entryId"), Index("bucketId")])
data class AllocationLegEntity(@PrimaryKey val id: String, val entryId: String, val bucketId: String?, val minor: Long)

@Entity(tableName = "allocation_seals", foreignKeys = [
    ForeignKey(entity = AllocationJournalEntity::class, parentColumns = ["id"], childColumns = ["entryId"]),
])
data class AllocationSealEntity(@PrimaryKey val entryId: String)
