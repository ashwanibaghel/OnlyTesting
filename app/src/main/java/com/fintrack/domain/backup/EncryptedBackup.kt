package com.fintrack.domain.backup

import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/** Authenticated structural inspection only. Financial validation and reviewed restore are separate gates. */
data class BackupManifest(val schemaVersion: Int, val createdAt: Instant, val bookRevisions: Map<String, Long>,
    val tableRows: Map<String, Long>, val totalRows: Long, val payloadSha256: String)
/** Authenticated file reconstructed/migrated in isolation; financial semantics and restore review remain separate. */
data class BackupStorageInspection(val source: BackupManifest, val migratedSchemaVersion: Int, val migratedTableRows: Map<String, Long>)
interface EncryptedBackupRepository {
    /** Streams a consistent complete database snapshot. Caller retains ownership of the output stream. */
    suspend fun exportEncrypted(output: OutputStream, password: CharArray): BackupManifest
    /** Authenticates the entire file before parsing; never replaces live financial data. Caller owns input. */
    suspend fun inspectEncrypted(input: InputStream, password: CharArray): BackupManifest
    /** Also verifies SQL constraints and existing migrations in private staging; never writes the live database. */
    suspend fun inspectStorageEncrypted(input: InputStream, password: CharArray): BackupStorageInspection
}
