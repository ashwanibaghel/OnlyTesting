package com.fintrack.data.backup

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fintrack.domain.backup.BackupManifest
import java.io.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class SnapshotColumn(val name: String, val affinity: String, val nullable: Boolean)
internal data class SnapshotTable(val name: String, val columns: List<SnapshotColumn>, val createSql: String, val indices: List<String>)
internal data class SnapshotSchema(val version: Int, val identity: String, val tables: List<SnapshotTable>)

/** SQL and column identities come exclusively from compiler-generated schemas packaged with the app. */
internal class BackupSchemaCatalog(private val context: Context) {
    fun load(version: Int): SnapshotSchema {
        require(version in 1..6) { "Unsupported backup database version; update the app" }
        val source = context.assets.open("com.fintrack.data.local.FinTrackDatabase/$version.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
        check(source.getInt("version") == version)
        val entities = source.getJSONArray("entities")
        val tables = (0 until entities.length()).map { index ->
            val entity = entities.getJSONObject(index); val name = entity.getString("tableName"); val fields = entity.getJSONArray("fields")
            val columns = (0 until fields.length()).map { field ->
                val column = fields.getJSONObject(field)
                SnapshotColumn(column.getString("columnName"), column.getString("affinity"), !column.optBoolean("notNull", false))
            }
            check((listOf(name) + columns.map { it.name }).all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) })
            check(columns.all { it.affinity in setOf("INTEGER", "TEXT") })
            val indices = entity.optJSONArray("indices")
            SnapshotTable(name, columns, entity.getString("createSql").replace("\${TABLE_NAME}", name),
                if (indices == null) emptyList() else (0 until indices.length()).map { indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", name) })
        }
        return SnapshotSchema(version, source.getString("identityHash"), tables)
    }
}

internal class SnapshotCodec(private val catalog: BackupSchemaCatalog) {
    suspend fun write(db: SupportSQLiteDatabase, output: OutputStream, createdAt: Instant): BackupManifest {
        val schema = catalog.load(db.version); val hash = MessageDigest.getInstance("SHA-256")
        val data = DataOutputStream(DigestOutputStream(output, hash)); val context = currentCoroutineContext()
        data.write(MAGIC); data.writeInt(schema.version); text(data, schema.identity); data.writeLong(createdAt.toEpochMilli())
        data.writeInt(schema.tables.size)
        val counts = linkedMapOf<String, Long>(); val books = linkedMapOf<String, Long>(); var total = 0L
        for (table in schema.tables) {
            context.ensureActive(); text(data, table.name); data.writeInt(table.columns.size); table.columns.forEach { text(data, it.name) }
            val count = db.query("SELECT COUNT(*) FROM `${table.name}`").use { check(it.moveToFirst()); it.getLong(0) }
            require(count in 0..MAX_ROWS && count <= MAX_ROWS - total) { "Backup row limit exceeded" }; total += count; counts[table.name] = count
            data.writeLong(count); var written = 0L
            db.query("SELECT ${table.columns.joinToString(",") { "`${it.name}`" }} FROM `${table.name}`").use { cursor ->
                while (cursor.moveToNext()) {
                    if (written % 64 == 0L) context.ensureActive()
                    val row = table.columns.mapIndexed { index, column ->
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> { require(column.nullable) { "Unexpected null in snapshot source" }; data.writeByte(0); null }
                            Cursor.FIELD_TYPE_INTEGER -> { require(column.affinity == "INTEGER") { "Invalid snapshot source type" }; data.writeByte(1); cursor.getLong(index).also(data::writeLong) }
                            Cursor.FIELD_TYPE_STRING -> { require(column.affinity == "TEXT") { "Invalid snapshot source type" }; data.writeByte(2); cursor.getString(index).also { text(data, it) } }
                            else -> error("Snapshot source contains unsupported binary or floating-point data")
                        }
                    }
                    recordBook(table, row, books); written++
                }
            }
            check(written == count) { "Snapshot source changed while exporting" }
        }
        data.write(END); data.flush()
        return BackupManifest(schema.version, createdAt, books.toMap(), counts.toMap(), total, hash.digest().hex())
    }

    /** Input must already be fully authenticated; rows are bounded and checked against trusted schema metadata. */
    suspend fun read(input: InputStream, schemaReady: suspend (SnapshotSchema) -> Unit = {},
        rowConsumer: suspend (SnapshotTable, List<Any?>) -> Unit = { _, _ -> }): BackupManifest {
        val hash = MessageDigest.getInstance("SHA-256"); val data = DataInputStream(DigestInputStream(input, hash))
        val context = currentCoroutineContext(); val magic = ByteArray(MAGIC.size).also(data::readFully)
        require(magic.contentEquals(MAGIC)) { "Invalid snapshot payload" }
        val schema = catalog.load(data.readInt()); require(text(data) == schema.identity) { "Snapshot schema identity mismatch" }
        val createdAt = Instant.ofEpochMilli(data.readLong()); require(data.readInt() == schema.tables.size) { "Invalid snapshot table count" }
        schemaReady(schema)
        val counts = linkedMapOf<String, Long>(); val books = linkedMapOf<String, Long>(); var total = 0L
        for (table in schema.tables) {
            context.ensureActive(); require(text(data) == table.name && data.readInt() == table.columns.size) { "Unexpected snapshot table" }
            table.columns.forEach { require(text(data) == it.name) { "Unexpected snapshot column" } }
            val count = data.readLong(); require(count in 0..MAX_ROWS && count <= MAX_ROWS - total) { "Invalid snapshot row count" }
            total += count; counts[table.name] = count
            var index = 0L
            while (index < count) {
                if (index % 64 == 0L) context.ensureActive()
                val row = table.columns.map { column -> when (data.readUnsignedByte()) {
                    0 -> { require(column.nullable) { "Invalid null snapshot value" }; null }
                    1 -> { require(column.affinity == "INTEGER") { "Invalid integer snapshot value" }; data.readLong() }
                    2 -> { require(column.affinity == "TEXT") { "Invalid text snapshot value" }; text(data) }
                    else -> error("Invalid snapshot value tag")
                } }
                recordBook(table, row, books); rowConsumer(table, row); index++
            }
        }
        require(ByteArray(END.size).also(data::readFully).contentEquals(END) && data.read() == -1) { "Incomplete or trailing snapshot payload" }
        return BackupManifest(schema.version, createdAt, books.toMap(), counts.toMap(), total, hash.digest().hex())
    }
    private fun recordBook(table: SnapshotTable, row: List<Any?>, books: MutableMap<String, Long>) {
        if (table.name != "books") return
        val id = row[table.columns.indexOfFirst { it.name == "id" }] as String
        val revision = row[table.columns.indexOfFirst { it.name == "revision" }] as Long
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && revision >= 0 && id !in books && books.size < 10_000) { "Invalid snapshot book identity/revision" }
        books[id] = revision
    }
    private fun text(data: DataOutputStream, value: String) {
        require(value.length <= MAX_TEXT) { "Snapshot text exceeds limit" }
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        require(encoded.remaining() <= MAX_TEXT) { "Snapshot text exceeds limit" }
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }; data.writeInt(bytes.size); data.write(bytes)
    }
    private fun text(data: DataInputStream): String {
        val size = data.readInt(); require(size in 0..MAX_TEXT) { "Invalid snapshot text length" }
        return strictUtf8(ByteArray(size).also(data::readFully))
    }
    private companion object {
        const val MAX_TEXT = 1024 * 1024
        const val MAX_ROWS = 10_000_000L
        val MAGIC = "FTSNAP01".toByteArray(Charsets.US_ASCII)
        val END = "FTEND001".toByteArray(Charsets.US_ASCII)
    }
}
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
