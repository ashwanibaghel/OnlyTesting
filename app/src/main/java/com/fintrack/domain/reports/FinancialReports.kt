package com.fintrack.domain.reports

import com.fintrack.domain.ledger.AccountKind
import com.fintrack.domain.ledger.LedgerRole
import com.fintrack.engine.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow

data class ReportPeriod(val from: LocalDate, val through: LocalDate)
/** Posted uses effective dates; cleared uses actual clearing dates, including later clearing events. */
enum class ReportBasis { POSTED, CLEARED }
/** Keep the exact ratio. A presentation layer can round it explicitly; zero/nonpositive income has no savings rate. */
data class SavingsRatio(val saved: Money, val income: Money)
data class OperatingTotals(val income: Money, val expense: Money, val saved: Money,
    val liquidMovement: Money, val trackedAssetMovement: Money, val equityMovement: Money) {
    val savingsRatio: SavingsRatio? get() = income.takeIf { it.minor > 0 }?.let { SavingsRatio(saved, it) }
}
data class CategoryTotal(val role: LedgerRole, val category: String, val amount: Money)
data class MonthReport(val month: YearMonth, val totals: OperatingTotals)
data class RecognizedReport(val basis: ReportBasis, val totals: OperatingTotals,
    val categories: List<CategoryTotal>, val months: List<MonthReport>, val journalCount: Long)
data class ReportAccount(val id: String, val name: String, val kind: AccountKind, val archived: Boolean,
    val openingPosted: Money, val openingCleared: Money, val closingPosted: Money, val closingCleared: Money)
/** Tracked investment balances are manual book values, never live market prices or total personal net worth. */
data class AssetTrendPoint(val date: LocalDate, val postedLiquid: Money, val clearedLiquid: Money,
    val postedInvestment: Money, val clearedInvestment: Money, val postedAssets: Money, val clearedAssets: Money)
data class FinancialReport(val bookId: String, val revision: Long, val period: ReportPeriod,
    val posted: RecognizedReport, val cleared: RecognizedReport, val accounts: List<ReportAccount>,
    val assetTrend: List<AssetTrendPoint>, val unresolvedPendingCount: Long, val assumptions: List<String>)

interface FinancialReportsRepository {
    /** Inclusive, actual-only period of at most 366 days, ending no later than the book's current day. */
    suspend fun report(bookId: String, period: ReportPeriod): FinancialReport
    fun observe(bookId: String, period: ReportPeriod): Flow<FinancialReport>
}
