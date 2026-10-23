package com.fintrack.domain.ledger

import com.fintrack.domain.planning.FinancialProfile
import com.fintrack.domain.planning.GoalFunding
import com.fintrack.domain.planning.InvestmentBackingPosition
import com.fintrack.engine.*
import java.time.LocalDate
import java.time.YearMonth

/** Category identities use category/month rather than a prospective generated database ID. */
data class ReviewedReservePosition(val identity: String, val name: String, val kind: String,
    val protected: Boolean, val funded: Money, val archived: Boolean, val backing: ClaimBacking)
data class ReviewedBudgetPosition(val category: String, val month: YearMonth,
    val limit: Money, val carried: Money, val rollover: RolloverPolicy, val closed: Boolean,
    val position: BudgetPosition, val funded: Money, val backing: ClaimBacking)
data class ReviewedFinancialPosition(val revision: Long, val asOf: LocalDate,
    val accounts: List<AccountBalance>, val liquid: Money, val income: Money, val expense: Money,
    val journalCount: Int, val profile: FinancialProfile, val reserves: List<ReviewedReservePosition>,
    val budgets: List<ReviewedBudgetPosition>, val reservations: ReservationPosition,
    val safeToSpend: SafeToSpend, val uncoveredObligations: Money,
    val goalFunding: List<GoalFunding>, val goalCollisions: GoalCollisions,
    val goalFundingAssumptions: List<String> = emptyList(), val investmentBacking: List<InvestmentBackingPosition> = emptyList())

/** Prospective outcome only; it contains no pretend journal IDs or success receipts. */
data class FinancialCommandReview(val command: LedgerCommand, val fingerprint: String,
    val before: ReviewedFinancialPosition, val after: ReviewedFinancialPosition,
    val alreadySaved: CommandReceipt?)

interface FinancialReviewRepository {
    suspend fun preview(command: LedgerCommand): FinancialCommandReview
    /** Revalidates the exact command, current book date/revision and prospective outcome atomically. */
    suspend fun apply(review: FinancialCommandReview): CommandReceipt
}
