package com.fintrack.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.fintrack.data.local.FinTrackDatabase
import com.fintrack.domain.backup.EncryptedBackupRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock

class RoomEncryptedBackupRepository(private val db: FinTrackDatabase, context: Context, private val clock: Clock) : EncryptedBackupRepository {
    private val codec = SnapshotCodec(BackupSchemaCatalog(context.applicationContext))
    private val crypto = BackupCrypto(File(context.applicationContext.noBackupFilesDir, "bk"))
    override suspend fun exportEncrypted(output: OutputStream, password: CharArray) = crypto.encrypt(output, password) { encrypted ->
        db.withTransaction { codec.write(db.openHelper.writableDatabase, encrypted, clock.instant()) }
    }
    override suspend fun inspectEncrypted(input: InputStream, password: CharArray) = crypto.authenticatedFile(input, password) { file ->
        file.inputStream().use { codec.read(it) }
    }
}
