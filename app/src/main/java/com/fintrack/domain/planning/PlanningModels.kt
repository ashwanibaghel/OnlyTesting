package com.fintrack.domain.planning

import com.fintrack.domain.ledger.LedgerSnapshot
import com.fintrack.engine.*
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow

enum class BucketKind { CATEGORY, SAVINGS, EMERGENCY, GOAL, INVESTMENT }
data class ReserveBucket(val id: String, val name: String, val kind: BucketKind, val protected: Boolean,
    val funded: Money, val category: String?, val month: YearMonth?, val archived: Boolean)
data class MonthlyBudget(val bucketId: String, val category: String, val month: YearMonth,
    val limit: Money, val carried: Money, val rollover: RolloverPolicy, val closed: Boolean,
    val position: BudgetPosition, val funded: Money)
data class FinancialProfile(val timezone: String, val safetyBuffer: Money, val monthlyCapacity: Money,
    val obligationHorizonDays: Int, val setupComplete: Boolean)
data class GoalFunding(val bucketId: String, val reserved: Money, val postedContributions: Money,
    val clearedContributions: Money, val progress: Money)
data class PlanningSnapshot(val ledger: LedgerSnapshot, val asOf: LocalDate, val profile: FinancialProfile,
    val buckets: List<ReserveBucket>, val budgets: List<MonthlyBudget>, val goals: List<GoalPlan>,
    val goalCollisions: GoalCollisions, val reservations: ReservationPosition, val safeToSpend: SafeToSpend,
    val goalFunding: List<GoalFunding>, val obligations: List<Obligation>, val uncoveredObligations: Money)
data class GoalDiversionImpact(val id: String, val lostFunded: Money, val requiredBefore: Money?,
    val requiredAfter: Money?, val completionBefore: LocalDate?, val completionAfter: LocalDate?)
data class DiversionConsent(val revision: Long, val asOf: LocalDate, val sourceId: String,
    val destinationId: String?, val amount: Money, val goalImpacts: List<GoalDiversionImpact>)
data class DiversionReview(val consent: DiversionConsent, val sourceName: String,
    val remainingSource: Money, val protected: Boolean, val safeToSpendAfter: SafeToSpend)

sealed interface PlanningAction {
    data class CreateReserve(val id: String, val name: String, val kind: BucketKind,
        val protected: Boolean = true) : PlanningAction
    data class SetBudget(val id: String, val category: String, val month: YearMonth,
        val limit: Money, val rollover: RolloverPolicy = RolloverPolicy.OFF) : PlanningAction
    data class SetGoal(val id: String, val name: String, val target: Money, val deadline: LocalDate,
        val priority: Int, val monthlyPlan: Money, val anchorDay: Int = 1,
        val investment: Boolean = false) : PlanningAction
    data class Move(val sourceId: String?, val destinationId: String?, val amount: Money,
        val reason: String, val consent: DiversionConsent? = null) : PlanningAction
    data class AdvanceMonth(val through: YearMonth) : PlanningAction
    data class Configure(val timezone: String, val safetyBuffer: Money, val monthlyCapacity: Money,
        val horizonDays: Int, val setupComplete: Boolean) : PlanningAction
}

interface PlanningRepository {
    suspend fun snapshot(bookId: String, asOf: LocalDate): PlanningSnapshot
    fun observe(bookId: String, asOf: LocalDate): Flow<PlanningSnapshot>
    suspend fun previewDiversion(bookId: String, sourceId: String, destinationId: String?,
        amount: Money, asOf: LocalDate): DiversionReview
}
