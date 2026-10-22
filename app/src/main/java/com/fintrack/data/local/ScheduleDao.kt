package com.fintrack.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface ScheduleDao {
    @Insert suspend fun insertRule(value: ScheduledRuleEntity)
    @Insert suspend fun insertExceptions(value: List<ScheduleExceptionEntity>)
    @Query("SELECT * FROM scheduled_rules WHERE bookId = :book ORDER BY startDate,id") suspend fun rules(book: String): List<ScheduledRuleEntity>
    @Query("SELECT e.* FROM schedule_exceptions e JOIN scheduled_rules r ON r.id = e.ruleId WHERE r.bookId = :book") suspend fun exceptions(book: String): List<ScheduleExceptionEntity>
    @Query("UPDATE scheduled_rules SET stopDate = :from WHERE id = :id AND bookId = :book") suspend fun stopRule(book: String, id: String, from: String): Int
    @Insert suspend fun insertOccurrence(value: ScheduleOccurrenceEntity)
    @Query("SELECT o.* FROM schedule_occurrences o JOIN scheduled_rules r ON r.id = o.ruleId WHERE r.bookId = :book") suspend fun occurrences(book: String): List<ScheduleOccurrenceEntity>
    @Insert suspend fun insertResolution(value: OccurrenceResolutionEntity)
    @Query("SELECT e.* FROM occurrence_resolutions e JOIN schedule_occurrences o ON o.id = e.occurrenceId JOIN scheduled_rules r ON r.id = o.ruleId JOIN commands c ON c.id = e.commandId WHERE r.bookId = :book ORDER BY c.revision,e.id")
    suspend fun resolutions(book: String): List<OccurrenceResolutionEntity>
    @Insert suspend fun insertContribution(value: InvestmentContributionEntity)
    @Query("SELECT c.* FROM investment_contributions c JOIN reserve_buckets r ON r.id = c.bucketId WHERE r.bookId = :book") suspend fun contributions(book: String): List<InvestmentContributionEntity>
    @Insert suspend fun insertReview(value: WeeklyReviewEntity)
    @Query("SELECT * FROM weekly_reviews WHERE bookId = :book ORDER BY weekOf DESC") suspend fun reviews(book: String): List<WeeklyReviewEntity>
}
