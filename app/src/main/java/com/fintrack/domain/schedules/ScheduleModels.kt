package com.fintrack.domain.schedules

import com.fintrack.domain.ledger.Clearing
import com.fintrack.domain.ledger.TransactionDraft
import com.fintrack.domain.planning.DiversionReview
import com.fintrack.engine.*
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

data class ScheduledRule(val id: String, val name: String, val template: TransactionDraft,
    val recurrence: Recurrence, val mandatory: Boolean, val stoppedFrom: LocalDate? = null)
enum class OccurrenceStatus { AWAITING_CONFIRMATION, REOPENED, CONFIRMED, SKIPPED }
data class ScheduledOccurrence(val key: String, val ruleId: String, val name: String, val scheduledDate: LocalDate,
    val template: TransactionDraft, val mandatory: Boolean, val status: OccurrenceStatus,
    val journalId: String?, val covered: Money, val uncovered: Money)
data class ScheduleSnapshot(val revision: Long, val asOf: LocalDate, val through: LocalDate,
    val rules: List<ScheduledRule>, val occurrences: List<ScheduledOccurrence>, val uncoveredObligations: Money)
data class CashFlowProjection(val revision: Long, val asOf: LocalDate, val through: LocalDate,
    val events: List<CashEvent>, val cash: ForecastResult, val currentSafeToSpend: SafeToSpend,
    val assumptions: List<String>, val timeline: List<ForecastDay>)
data class PurchaseFunding(val bucketId: String, val amount: Money)
data class PurchaseImpact(val revision: Long, val amount: Money, val date: LocalDate,
    val before: CashFlowProjection, val after: ForecastResult, val safeToSpendFromCurrentFundsAfterPurchase: SafeToSpend,
    val diversion: DiversionReview?, val warnings: List<String>)

sealed interface ScheduleAction {
    data class Define(val rule: ScheduledRule) : ScheduleAction
    data class Stop(val ruleId: String, val effectiveFrom: LocalDate, val reason: String) : ScheduleAction
    data class Confirm(val ruleId: String, val scheduledDate: LocalDate,
        val actualDate: LocalDate, val clearing: Clearing, val actualAmount: Money? = null) : ScheduleAction
    data class Skip(val ruleId: String, val scheduledDate: LocalDate, val reason: String) : ScheduleAction
    data class Reopen(val ruleId: String, val scheduledDate: LocalDate, val reason: String) : ScheduleAction
    data class LinkExisting(val ruleId: String, val scheduledDate: LocalDate,
        val journalId: String, val reason: String) : ScheduleAction
    data class Invest(val bucketId: String, val transfer: TransactionDraft.Transfer) : ScheduleAction
    data class WeeklyReview(val weekOf: LocalDate, val note: String) : ScheduleAction
}

interface ScheduleRepository {
    suspend fun snapshot(bookId: String, asOf: LocalDate, through: LocalDate): ScheduleSnapshot
    fun observe(bookId: String, asOf: LocalDate, through: LocalDate): Flow<ScheduleSnapshot>
    suspend fun forecast(bookId: String, asOf: LocalDate, through: LocalDate): CashFlowProjection
    suspend fun simulatePurchase(bookId: String, asOf: LocalDate, through: LocalDate,
        date: LocalDate, amount: Money, funding: PurchaseFunding? = null): PurchaseImpact
    suspend fun contributions(bookId: String): List<InvestmentContribution>
    suspend fun weeklyReviews(bookId: String): List<WeeklyReviewRecord>
}

data class InvestmentContribution(val journalId: String, val bucketId: String, val investmentAccountId: String,
    val amount: Money, val date: LocalDate, val reversalOf: String?, val clearedDate: LocalDate?)
data class WeeklyReviewRecord(val weekOf: LocalDate, val reviewedAt: Long, val note: String, val revision: Long)
