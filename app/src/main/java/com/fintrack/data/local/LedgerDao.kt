package com.fintrack.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Internal persistence mechanics. Financial callers must use LedgerRepository. */
@Dao
internal interface LedgerDao {
    @Insert suspend fun insertBook(value: BookEntity)
    @Insert suspend fun insertAccount(value: AccountEntity)
    @Insert suspend fun insertLedgerAccount(value: LedgerAccountEntity)
    @Insert suspend fun insertCommand(value: CommandEntity)
    @Insert suspend fun insertAudit(value: AuditEntity)
    @Insert suspend fun insertJournal(value: JournalEntity)
    @Insert suspend fun insertPostings(value: List<PostingEntity>)
    @Insert suspend fun seal(value: JournalSealEntity)
    @Insert suspend fun insertClearing(value: ClearingEntity)
    @Insert suspend fun insertReconciliation(value: ReconciliationEntity)

    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): BookEntity?
    @Query("SELECT revision FROM books WHERE id = :id") fun revision(id: String): Flow<Long?>
    @Query("SELECT * FROM commands WHERE id = :id") suspend fun command(id: String): CommandEntity?
    @Query("SELECT * FROM accounts WHERE bookId = :book ORDER BY name, id") suspend fun accounts(book: String): List<AccountEntity>
    @Query("SELECT * FROM accounts WHERE bookId = :book AND id = :id") suspend fun account(book: String, id: String): AccountEntity?
    @Query("SELECT * FROM ledger_accounts WHERE bookId = :book") suspend fun ledgerAccounts(book: String): List<LedgerAccountEntity>
    @Query("SELECT j.* FROM journals j JOIN journal_seals s ON s.entryId = j.id WHERE j.bookId = :book ORDER BY j.effectiveDate, j.createdAt, j.id")
    suspend fun journals(book: String): List<JournalEntity>
    @Query("SELECT * FROM journals WHERE id = :id AND bookId = :book") suspend fun journal(book: String, id: String): JournalEntity?
    @Query("SELECT p.* FROM postings p JOIN journals j ON j.id = p.entryId JOIN journal_seals s ON s.entryId = j.id WHERE j.bookId = :book")
    suspend fun postings(book: String): List<PostingEntity>
    @Query("SELECT c.* FROM clearing_events c JOIN journals j ON j.id = c.entryId WHERE j.bookId = :book")
    suspend fun clearings(book: String): List<ClearingEntity>
    @Query("UPDATE books SET revision = :next WHERE id = :book AND revision = :expected")
    suspend fun advance(book: String, expected: Long, next: Long): Int
    @Query("UPDATE accounts SET archived = :archived WHERE id = :id AND bookId = :book")
    suspend fun archive(book: String, id: String, archived: Boolean): Int
}
