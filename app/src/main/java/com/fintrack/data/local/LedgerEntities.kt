package com.fintrack.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(@PrimaryKey val id: String, val ownerId: String,
    val currencyCode: String, val exponent: Int, val revision: Long, val createdAt: Long)

@Entity(tableName = "ledger_accounts", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"]),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["ownerAccountId"]),
], indices = [Index("bookId"), Index(value = ["ownerAccountId"], unique = true),
    Index(value = ["bookId", "role", "name"])])
data class LedgerAccountEntity(@PrimaryKey val id: String, val bookId: String,
    val ownerAccountId: String?, val role: String, val name: String)

@Entity(tableName = "commands", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"]),
], indices = [Index(value = ["bookId", "revision"], unique = true)])
data class CommandEntity(@PrimaryKey val id: String, val bookId: String, val fingerprint: String,
    val primaryId: String, val secondaryId: String?, val revision: Long)

@Entity(tableName = "journals", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"]),
    ForeignKey(entity = CommandEntity::class, parentColumns = ["id"], childColumns = ["commandId"]),
    ForeignKey(entity = JournalEntity::class, parentColumns = ["id"], childColumns = ["reversalOf"]),
], indices = [Index("bookId"), Index("commandId"), Index(value = ["reversalOf"], unique = true), Index("effectiveDate")])
data class JournalEntity(@PrimaryKey val id: String, val bookId: String, val commandId: String,
    val kind: String, val effectiveDate: String, val createdAt: Long, val clearing: String,
    val memo: String, val reason: String, val reversalOf: String?)

@Entity(tableName = "postings", foreignKeys = [
    ForeignKey(entity = JournalEntity::class, parentColumns = ["id"], childColumns = ["entryId"]),
    ForeignKey(entity = LedgerAccountEntity::class, parentColumns = ["id"], childColumns = ["ledgerAccountId"]),
], indices = [Index("entryId"), Index("ledgerAccountId")])
data class PostingEntity(@PrimaryKey val id: String, val entryId: String, val ledgerAccountId: String, val minor: Long)

@Entity(tableName = "journal_seals", foreignKeys = [
    ForeignKey(entity = JournalEntity::class, parentColumns = ["id"], childColumns = ["entryId"]),
])
data class JournalSealEntity(@PrimaryKey val entryId: String)

@Entity(tableName = "clearing_events", foreignKeys = [
    ForeignKey(entity = JournalEntity::class, parentColumns = ["id"], childColumns = ["entryId"]),
    ForeignKey(entity = CommandEntity::class, parentColumns = ["id"], childColumns = ["commandId"]),
], indices = [Index(value = ["commandId"], unique = true)])
data class ClearingEntity(@PrimaryKey val entryId: String, val commandId: String, val clearedDate: String)

@Entity(tableName = "audit_events", foreignKeys = [
    ForeignKey(entity = CommandEntity::class, parentColumns = ["id"], childColumns = ["commandId"]),
])
data class AuditEntity(@PrimaryKey val commandId: String, val action: String, val reason: String, val createdAt: Long)

@Entity(tableName = "reconciliations", foreignKeys = [
    ForeignKey(entity = CommandEntity::class, parentColumns = ["id"], childColumns = ["commandId"]),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"]),
    ForeignKey(entity = JournalEntity::class, parentColumns = ["id"], childColumns = ["adjustmentId"]),
], indices = [Index("accountId"), Index("adjustmentId")])
data class ReconciliationEntity(@PrimaryKey val commandId: String, val accountId: String,
    val statementDate: String, val basis: String, val statementMinor: Long, val ledgerMinor: Long,
    val differenceMinor: Long, val adjustmentId: String?)
