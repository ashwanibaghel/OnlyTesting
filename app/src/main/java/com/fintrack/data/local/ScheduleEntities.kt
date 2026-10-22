package com.fintrack.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_rules", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"]),
    ForeignKey(entity = CommandEntity::class, parentColumns = ["id"], childColumns = ["commandId"]),
], indices = [Index("bookId"), Index("commandId")])
data class ScheduledRuleEntity(@PrimaryKey val id: String, val bookId: String, val commandId: String,
    val name: String, val template: String, val startDate: String, val frequency: String,
    val interval: Int, val endDate: String?, val mandatory: Boolean, val stopDate: String?)

@Entity(tableName = "schedule_exceptions", primaryKeys = ["ruleId", "date"], foreignKeys = [
    ForeignKey(entity = ScheduledRuleEntity::class, parentColumns = ["id"], childColumns = ["ruleId"]),
])
data class ScheduleExceptionEntity(val ruleId: String, val date: String)

@Entity(tableName = "schedule_occurrences", foreignKeys = [
    ForeignKey(entity = ScheduledRuleEntity::class, parentColumns = ["id"], childColumns = ["ruleId"]),
    ForeignKey(entity = CommandEntity::class, parentColumns = ["id"], childColumns = ["commandId"]),
    ForeignKey(entity = JournalEntity::class, parentColumns = ["id"], childColumns = ["journalId"]),
], indices = [Index(value = ["ruleId", "scheduledDate"], unique = true), Index("commandId"), Index(value = ["journalId"], unique = true)])
data class ScheduleOccurrenceEntity(@PrimaryKey val id: String, val ruleId: String, val scheduledDate: String,
    val commandId: String, val status: String, val journalId: String?, val reason: String, val confirmedTemplate: String?)

@Entity(tableName = "occurrence_resolutions", foreignKeys = [
    ForeignKey(entity = ScheduleOccurrenceEntity::class, parentColumns = ["id"], childColumns = ["occurrenceId"]),
    ForeignKey(entity = CommandEntity::class, parentColumns = ["id"], childColumns = ["commandId"]),
    ForeignKey(entity = JournalEntity::class, parentColumns = ["id"], childColumns = ["journalId"]),
], indices = [Index("occurrenceId"), Index(value = ["commandId"], unique = true), Index("journalId")])
data class OccurrenceResolutionEntity(@PrimaryKey val id: String, val occurrenceId: String,
    val commandId: String, val status: String, val journalId: String?, val confirmedTemplate: String?, val reason: String)

@Entity(tableName = "investment_contributions", foreignKeys = [
    ForeignKey(entity = JournalEntity::class, parentColumns = ["id"], childColumns = ["journalId"]),
    ForeignKey(entity = ReserveBucketEntity::class, parentColumns = ["id"], childColumns = ["bucketId"]),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["investmentAccountId"]),
    ForeignKey(entity = JournalEntity::class, parentColumns = ["id"], childColumns = ["reversalOf"]),
], indices = [Index("bucketId"), Index("investmentAccountId"), Index(value = ["reversalOf"], unique = true)])
data class InvestmentContributionEntity(@PrimaryKey val journalId: String, val bucketId: String,
    val investmentAccountId: String, val minor: Long, val date: String, val reversalOf: String?)

@Entity(tableName = "weekly_reviews", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"]),
    ForeignKey(entity = CommandEntity::class, parentColumns = ["id"], childColumns = ["commandId"]),
], indices = [Index(value = ["bookId", "weekOf"], unique = true), Index("commandId")])
data class WeeklyReviewEntity(@PrimaryKey val id: String, val bookId: String, val commandId: String,
    val weekOf: String, val reviewedAt: Long, val note: String, val revision: Long)
