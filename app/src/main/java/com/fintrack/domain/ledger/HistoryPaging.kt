package com.fintrack.domain.ledger

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

enum class HistoryClearing { ALL, PENDING, CLEARED }
data class HistoryQuery(val filter: HistoryFilter = HistoryFilter(), val kind: String? = null,
    val clearing: HistoryClearing = HistoryClearing.ALL, val includeReversed: Boolean = true)
data class HistoryCursor(val revision: Long, val queryFingerprint: String,
    val date: LocalDate, val createdAt: Long, val id: String)
/** An empty filtered page can still have a cursor when its bounded search scan is incomplete. */
data class HistoryPage(val revision: Long, val entries: List<HistoryEntry>, val next: HistoryCursor?, val scanned: Int) {
    val exhausted: Boolean get() = next == null
}
interface HistoryRepository {
    suspend fun page(bookId: String, query: HistoryQuery = HistoryQuery(), size: Int = 50,
        cursor: HistoryCursor? = null): HistoryPage
    fun observeFirstPage(bookId: String, query: HistoryQuery = HistoryQuery(), size: Int = 50): Flow<HistoryPage>
}
