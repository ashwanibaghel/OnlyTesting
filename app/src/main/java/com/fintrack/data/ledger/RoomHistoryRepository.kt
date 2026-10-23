package com.fintrack.data.ledger

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.engine.Money
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.flow.map

class RoomHistoryRepository(private val db: FinTrackDatabase) : HistoryRepository {
    private val kinds = setOf("OPENING", "INCOME", "EXPENSE", "TRANSFER", "REVERSAL", "ADJUSTMENT")
    private fun identifier(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid history identity" } }
    private fun fingerprint(book: String, query: HistoryQuery): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String?) { out.writeBoolean(value != null); if (value != null) { val encoded = value.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) } }
            text("fintrack-history-query-v1"); text(book); text(query.filter.accountId)
            text(query.filter.from?.toString()); text(query.filter.through?.toString()); text(query.filter.query)
            text(query.kind); text(query.clearing.name); out.writeBoolean(query.includeReversed)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    internal fun windowQuery(book: String, query: HistoryQuery, cursor: HistoryCursor?, take: Int): SimpleSQLiteQuery {
        val where = mutableListOf("j.bookId = ?"); val args = mutableListOf<Any>(book)
        query.filter.accountId?.let { where += "EXISTS (SELECT 1 FROM postings p WHERE p.entryId = j.id AND p.ledgerAccountId = ?)"; args += it }
        query.filter.from?.let { where += "j.effectiveDate >= ?"; args += it.toString() }
        query.filter.through?.let { where += "j.effectiveDate <= ?"; args += it.toString() }
        query.kind?.let { where += "j.kind = ?"; args += it }
        val isCleared = "(j.clearing = 'CLEARED' OR c.entryId IS NOT NULL)"
        when (query.clearing) {
            HistoryClearing.PENDING -> where += "NOT $isCleared"
            HistoryClearing.CLEARED -> where += isCleared
            HistoryClearing.ALL -> Unit
        }
        if (!query.includeReversed) where += "j.kind != 'REVERSAL' AND r.id IS NULL"
        cursor?.let { where += "(j.effectiveDate, j.createdAt, j.id) < (?, ?, ?)"; args += it.date.toString(); args += it.createdAt; args += it.id }
        args += take
        return SimpleSQLiteQuery("""SELECT j.*, r.id AS reversedBy,
            CASE WHEN j.clearing = 'CLEARED' THEN j.effectiveDate ELSE c.clearedDate END AS clearedDate
            FROM journals j JOIN journal_seals s ON s.entryId = j.id
            LEFT JOIN journals r ON r.reversalOf = j.id LEFT JOIN clearing_events c ON c.entryId = j.id
            WHERE ${where.joinToString(" AND ")} ORDER BY j.effectiveDate DESC, j.createdAt DESC, j.id DESC LIMIT ?""", args.toTypedArray())
    }

    override suspend fun page(bookId: String, query: HistoryQuery, size: Int, cursor: HistoryCursor?): HistoryPage {
        identifier(bookId); query.filter.accountId?.let(::identifier)
        require(size in 1..200 && query.filter.query.length <= 100) { "Choose 1..200 rows and a search up to 100 characters" }
        require(query.kind == null || query.kind in kinds) { "Unknown transaction kind" }
        require(listOfNotNull(query.filter.from, query.filter.through).all { it.year in 1..9999 }) { "Unsupported history date" }
        val from = query.filter.from; val through = query.filter.through
        require(from == null || through == null || from <= through) { "Invalid history date range" }
        val hash = fingerprint(bookId, query)
        return db.withTransaction {
            val book = requireNotNull(db.ledgerDao().book(bookId)) { "Book does not exist" }
            if (query.filter.accountId != null) requireNotNull(db.ledgerDao().account(bookId, query.filter.accountId)) { "Account belongs to another book or does not exist" }
            cursor?.let { identifier(it.id); require(it.date.year in 1..9999 && it.revision == book.revision && it.queryFingerprint == hash) { "History changed or filters changed; restart from the first page" } }
            val result = mutableListOf<HistoryEntry>(); var scanned = 0; var after = cursor; var more = false
            val searching = query.filter.query.isNotBlank()
            val scanLimit = if (searching) 10_000 else size
            while (result.size < size && scanned < scanLimit) {
                val take = minOf(if (searching) 200 else size, scanLimit - scanned)
                val rows = db.historyDao().window(windowQuery(bookId, query, after, take + 1))
                if (rows.isEmpty()) { more = false; break }
                val candidates = rows.take(take)
                val postings = db.historyDao().postings(bookId, candidates.map { it.journal.id }).groupBy { it.entryId }
                for ((index, row) in candidates.withIndex()) {
                    val value = row.journal
                    after = HistoryCursor(book.revision, hash, LocalDate.parse(value.effectiveDate), value.createdAt, value.id)
                    scanned++
                    val entry = HistoryEntry(value.id, value.kind, LocalDate.parse(value.effectiveDate), value.createdAt, value.memo,
                        value.reason, value.reversalOf, row.reversedBy, row.clearedDate?.let(LocalDate::parse),
                        postings[value.id].orEmpty().map { HistoryLeg(it.ledgerAccountId, it.label, LedgerRole.valueOf(it.role), Money(it.minor, INR)) })
                    if (!searching || (listOf(entry.memo, entry.reason, entry.kind) + entry.legs.map { it.label }).any { it.contains(query.filter.query, ignoreCase = true) }) result += entry
                    more = index < rows.lastIndex
                    if (result.size == size) break
                }
                if (!more) break
            }
            HistoryPage(book.revision, result.toList(), if (more) after else null, scanned)
        }
    }

    override fun observeFirstPage(bookId: String, query: HistoryQuery, size: Int) =
        db.ledgerDao().revision(bookId).map { page(bookId, query, size, null) }
}
