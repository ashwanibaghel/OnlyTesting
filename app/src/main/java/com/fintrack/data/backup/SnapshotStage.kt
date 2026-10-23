package com.fintrack.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import androidx.room.Room
import androidx.room.RoomDatabase
import com.fintrack.data.local.FinTrackDatabase
import com.fintrack.data.local.FinTrackMigrations
import com.fintrack.data.local.LedgerSchema
import com.fintrack.domain.backup.BackupManifest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reconstructs only trusted schema SQL in an isolated database. No live database handle/path is accepted. */
internal class SnapshotStage(context: Context, private val maxPages: Long = MAX_PAGES) {
    private val context = context.applicationContext
    private val directory = File(this.context.noBackupFilesDir, "bk-db")
    private val codec = SnapshotCodec(BackupSchemaCatalog(this.context))

    init { require(maxPages in 1..MAX_PAGES); cleanOnce(directory) }

    /** Storage integrity is not financial semantic validation or permission to restore. */
    suspend fun <T> withDatabase(authenticatedPayload: File, consumer: suspend (BackupManifest, FinTrackDatabase) -> T): T =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val file = File.createTempFile("db-", ".db", directory)
            var room: FinTrackDatabase? = null
            try {
                val manifest = materialize(file, authenticatedPayload)
                currentCoroutineContext().ensureActive()
                room = Room.databaseBuilder(context, FinTrackDatabase::class.java, file.absolutePath)
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
                val staged = room.openHelper.writableDatabase // Historical versions use real production migrations.
                staged.query("PRAGMA integrity_check").use {
                    check(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) { "Backup database integrity check failed" }
                }
                staged.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "Backup contains broken relationships" } }
                currentCoroutineContext().ensureActive()
                consumer(manifest, room)
            } finally {
                try { room?.close() } finally { removeOwned(file) }
            }
        }

    private suspend fun materialize(file: File, payload: File): BackupManifest {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            raw.setForeignKeyConstraintsEnabled(true)
            raw.rawQuery("PRAGMA page_size", null).use { check(it.moveToFirst() && it.getLong(0) == 4096L) { "Unsupported staging page size" } }
            raw.rawQuery("PRAGMA max_page_count=$maxPages", null).use { check(it.moveToFirst() && it.getLong(0) == maxPages) }
            val inserts = mutableMapOf<String, SQLiteStatement>()
            raw.beginTransaction()
            try {
                raw.execSQL("PRAGMA defer_foreign_keys=ON")
                val manifest = payload.inputStream().use { input -> codec.read(input, schemaReady = { schema ->
                    schema.tables.forEach { table -> raw.execSQL(table.createSql); table.indices.forEach(raw::execSQL) }
                    raw.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                    raw.execSQL("INSERT INTO room_master_table (id,identity_hash) VALUES (42,?)", arrayOf(schema.identity))
                    raw.version = schema.version
                }, rowConsumer = { table, values ->
                    val statement = inserts.getOrPut(table.name) {
                        raw.compileStatement("INSERT INTO `${table.name}` (${table.columns.joinToString(",") { "`${it.name}`" }}) VALUES (${table.columns.joinToString(",") { "?" }})")
                    }
                    statement.clearBindings()
                    values.forEachIndexed { index, value -> when (value) {
                        null -> statement.bindNull(index + 1)
                        is Long -> statement.bindLong(index + 1, value)
                        is String -> statement.bindString(index + 1, value)
                        else -> error("Unsupported typed snapshot value")
                    } }
                    statement.executeInsert()
                }) }
                raw.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "Backup contains broken relationships" } }
                currentCoroutineContext().ensureActive()
                raw.setTransactionSuccessful()
                return manifest
            } finally {
                try { inserts.values.forEach { it.close() } } finally { raw.endTransaction() }
            }
        }
    }

    private fun removeOwned(file: File) {
        check(file.parentFile?.canonicalFile == directory.canonicalFile && file.name.matches(Regex("db-[0-9]+\\.db")))
        for (suffix in listOf("-wal", "-shm", "-journal", "")) {
            val owned = File(file.path + suffix)
            check(owned.delete() || !owned.exists()) { "Cannot remove private backup database staging" }
        }
    }

    private companion object {
        // 512 MiB of SQLite pages. An export whose indexed reconstruction exceeds this limit is rejected.
        const val MAX_PAGES = 131_072L
        val cleaned = mutableSetOf<String>()
        @Synchronized fun cleanOnce(directory: File) {
            check(directory.isDirectory || directory.mkdirs()) { "Private database staging is unavailable" }
            val root = directory.canonicalFile
            if (root.path in cleaned) return
            for (file in requireNotNull(root.listFiles())) {
                if (file.name.matches(Regex("db-[0-9]+\\.db(?:-wal|-shm|-journal)?")) && file.isFile && file.canonicalFile.parentFile == root)
                    check(file.delete()) { "Cannot remove interrupted private database staging" }
            }
            cleaned += root.path
        }
    }
}
