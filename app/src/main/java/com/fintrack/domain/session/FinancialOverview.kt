package com.fintrack.domain.session

import com.fintrack.domain.ledger.HistoryPage
import com.fintrack.domain.reports.FinancialReport
import com.fintrack.domain.schedules.ScheduleSnapshot
import com.fintrack.domain.schedules.WeeklyReviewRecord
import com.fintrack.engine.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow

/** Attention amounts can overlap; they must never be summed as separate financial liabilities. */
sealed interface FinancialAttention {
    data class SetupNeeded(val mode: SetupMode) : FinancialAttention
    data class PendingRollover(val through: YearMonth) : FinancialAttention
    data class PendingClearing(val count: Long) : FinancialAttention
    data class BudgetOverrun(val bucketId: String, val category: String, val amount: Money) : FinancialAttention
    data class ReservationShortfall(val amount: Money) : FinancialAttention
    data class UncoveredObligations(val amount: Money, val through: LocalDate) : FinancialAttention
    data class SafetyDeficit(val amount: Money) : FinancialAttention
    data class GoalBackingReview(val bucketIds: List<String>) : FinancialAttention
    data class GoalCollision(val monthlyShortage: Money) : FinancialAttention
    data class MissedGoalDeadline(val bucketId: String) : FinancialAttention
    data class WeeklyReviewDue(val weekOf: LocalDate) : FinancialAttention
    data class ChangedSinceReview(val reviewedRevision: Long, val currentRevision: Long) : FinancialAttention
}
data class WeeklyReviewStatus(val weekOf: LocalDate, val recorded: WeeklyReviewRecord?, val changedSinceReview: Boolean)
/** One dated/revision-consistent read model; contains no layout, visual approval or synthetic values. */
data class FinancialOverview(val session: FinanceSession, val monthly: FinancialReport,
    val weekly: FinancialReport, val recent: HistoryPage, val pending: HistoryPage,
    val schedules: ScheduleSnapshot, val review: WeeklyReviewStatus, val attention: List<FinancialAttention>) {
    val revision: Long get() = session.revision
    val today: LocalDate get() = session.today
}
interface FinancialOverviewRepository {
    suspend fun snapshot(bookId: String): FinancialOverview
    fun observe(bookId: String): Flow<FinancialOverview>
}
