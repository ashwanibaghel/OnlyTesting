package com.fintrack.domain.session

import com.fintrack.domain.ledger.CommandReceipt
import com.fintrack.domain.planning.PlanningSnapshot
import com.fintrack.domain.schedules.CashFlowProjection
import com.fintrack.domain.schedules.PurchaseFunding
import com.fintrack.domain.schedules.PurchaseImpact
import com.fintrack.engine.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow

enum class SetupMode { NEW_BOOK, METADATA_ONLY, EXISTING_FINANCIAL_BOOK, COMPLETE }

data class FinanceSession(val planning: PlanningSnapshot, val setupMode: SetupMode,
    val pendingRolloverThrough: YearMonth?) {
    val revision: Long get() = planning.ledger.revision
    val today: LocalDate get() = planning.asOf
}

/** Current-day application boundary. Historical reports use explicitly dated report APIs. */
interface FinanceSessionRepository {
    suspend fun snapshot(bookId: String): FinanceSession
    fun observe(bookId: String): Flow<FinanceSession>
    /** Applies only previously configured rollover policies; does not confirm or post schedules. */
    suspend fun prepareCurrentPeriod(bookId: String): CommandReceipt?
    suspend fun forecast(bookId: String, horizonDays: Int): CashFlowProjection
    suspend fun simulatePurchase(bookId: String, horizonDays: Int, date: LocalDate,
        amount: Money, funding: PurchaseFunding? = null): PurchaseImpact
}
