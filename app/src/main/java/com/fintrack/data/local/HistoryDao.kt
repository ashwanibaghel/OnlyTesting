package com.fintrack.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

internal data class HistoryRecord(@Embedded val journal: JournalEntity, val reversedBy: String?, val clearedDate: String?)
internal data class HistoryPosting(val entryId: String, val ledgerAccountId: String, val label: String, val role: String, val minor: Long)

@Dao
internal interface HistoryDao {
    @RawQuery suspend fun window(query: SupportSQLiteQuery): List<HistoryRecord>
    @Query("""SELECT p.entryId, p.ledgerAccountId, l.name AS label, l.role, p.minor FROM postings p
        JOIN ledger_accounts l ON l.id = p.ledgerAccountId JOIN journals j ON j.id = p.entryId
        JOIN journal_seals s ON s.entryId = j.id WHERE j.bookId = :book AND p.entryId IN (:ids) ORDER BY p.id""")
    suspend fun postings(book: String, ids: List<String>): List<HistoryPosting>
}
