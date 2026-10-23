package com.fintrack.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.fintrack.data.local.FinTrackDatabase
import com.fintrack.domain.backup.EncryptedBackupRepository
import com.fintrack.domain.backup.BackupStorageInspection
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock

class RoomEncryptedBackupRepository(private val db: FinTrackDatabase, context: Context, private val clock: Clock) : EncryptedBackupRepository {
    private val catalog = BackupSchemaCatalog(context.applicationContext)
    private val codec = SnapshotCodec(catalog)
    private val crypto = BackupCrypto(File(context.applicationContext.noBackupFilesDir, "bk"))
    private val stage = SnapshotStage(context.applicationContext)
    override suspend fun exportEncrypted(output: OutputStream, password: CharArray) = crypto.encrypt(output, password) { encrypted ->
        db.withTransaction { codec.write(db.openHelper.writableDatabase, encrypted, clock.instant()) }
    }
    override suspend fun inspectEncrypted(input: InputStream, password: CharArray) = crypto.authenticatedFile(input, password) { file ->
        file.inputStream().use { codec.read(it) }
    }
    override suspend fun inspectStorageEncrypted(input: InputStream, password: CharArray) = crypto.authenticatedFile(input, password) { file ->
        stage.withDatabase(file) { manifest, staged ->
            val raw = staged.openHelper.writableDatabase
            val counts = catalog.load(raw.version).tables.associate { table -> table.name to
                raw.query("SELECT COUNT(*) FROM `${table.name}`").use { check(it.moveToFirst()); it.getLong(0) } }
            BackupStorageInspection(manifest, raw.version, counts)
        }
    }
}
