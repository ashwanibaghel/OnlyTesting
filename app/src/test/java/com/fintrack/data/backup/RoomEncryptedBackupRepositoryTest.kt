package com.fintrack.data.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.ledger.RoomLedgerRepository
import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.*
import com.fintrack.engine.Money
import java.io.*
import java.nio.ByteBuffer
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomEncryptedBackupRepositoryTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var backups: RoomEncryptedBackupRepository
    private fun uid() = UUID.randomUUID().toString()
    private fun m(value: Long) = Money(value, INR)
    private fun password() = "SYNTHETIC backup passphrase 42".toCharArray()
    private fun scenario(block: suspend () -> Unit) = runBlocking { block() }
    private fun open() = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
        .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
    private fun repositories() { ledger = RoomLedgerRepository(db, clock); backups = RoomEncryptedBackupRepository(db, context, clock) }
    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext(); file = "b-${uid().take(16)}.db"; db = open(); repositories()
        ledger.initializeBook(); send(LedgerAction.CreateAccount("bank", "SYNTHETIC private bank", AccountKind.BANK, m(1000), today))
        send(LedgerAction.CreateAccount("cash", "Cash", AccountKind.CASH, m(0), today))
        send(LedgerAction.Plan(PlanningAction.CreateReserve("save", "SYNTHETIC savings", BucketKind.SAVINGS)))
        send(LedgerAction.Plan(PlanningAction.Move(null, "save", m(100), "Synthetic reserve funding")))
        send(LedgerAction.Post(TransactionDraft.Expense("bank", m(75), listOf(Split("Food", m(75))), today, Clearing.PENDING)))
        send(LedgerAction.Post(TransactionDraft.Transfer("bank", "cash", m(10), m(1), today)))
    }
    @After fun teardown() { db.close(); context.deleteDatabase(file) }
    private suspend fun send(action: LedgerAction) = ledger.execute(LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision, action))
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }; throw AssertionError("Expected rejection")
    }
    private fun rows() = BackupSchemaCatalog(context).load(6).tables.associate { table -> table.name to
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `${table.name}`").use { check(it.moveToFirst()); it.getLong(0) } }
    private fun noStaging() = assertTrue(File(context.noBackupFilesDir, "bk").listFiles()!!.isEmpty())

    @Test fun encryptedSnapshotMatchesEveryPersistedTable() = scenario {
        val before = ledger.snapshot(); val counts = rows(); val output = ByteArrayOutputStream()
        val written = backups.exportEncrypted(output, password()); val bytes = output.toByteArray()
        assertEquals(counts, written.tableRows); assertEquals(counts.values.sum(), written.totalRows)
        assertEquals(mapOf(DEFAULT_BOOK to before.revision), written.bookRevisions); assertEquals(6, written.schemaVersion)
        assertEquals(clock.instant(), written.createdAt); assertEquals(64, written.payloadSha256.length)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("SYNTHETIC private bank"))
        assertEquals(written, backups.inspectEncrypted(ByteArrayInputStream(bytes), password()))
        val storage = backups.inspectStorageEncrypted(ByteArrayInputStream(bytes), password())
        assertEquals(written, storage.source); assertEquals(6, storage.migratedSchemaVersion); assertEquals(counts, storage.migratedTableRows)
        assertEquals(before, ledger.snapshot()); assertEquals(counts, rows()); noStaging()
    }
    @Test fun reopeningRetainsStateAndOriginalReceipt() = scenario {
        val command = LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision,
            LedgerAction.Post(TransactionDraft.Income("bank", m(40), "Salary", today)))
        val receipt = ledger.execute(command); val before = ledger.snapshot(); val output = ByteArrayOutputStream()
        val manifest = backups.exportEncrypted(output, password()); db.close(); db = open(); repositories()
        assertEquals(manifest, backups.inspectEncrypted(ByteArrayInputStream(output.toByteArray()), password()))
        assertEquals(receipt, ledger.execute(command)); assertEquals(before, ledger.snapshot()); noStaging()
    }
    @Test fun authenticationFailureCannotChangeFinancialRows() = scenario {
        val before = ledger.snapshot(); val counts = rows(); val output = ByteArrayOutputStream(); backups.exportEncrypted(output, password())
        val bytes = output.toByteArray()
        rejects { backups.inspectEncrypted(ByteArrayInputStream(bytes), "SYNTHETIC wrong passphrase".toCharArray()) }
        val bad = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        rejects { backups.inspectEncrypted(ByteArrayInputStream(bad), password()) }
        assertEquals(before, ledger.snapshot()); assertEquals(counts, rows()); noStaging()
    }
    @Test fun authenticatedMalformedPayloadIsStillRejected() = scenario {
        val codec = SnapshotCodec(BackupSchemaCatalog(context)); val raw = ByteArrayOutputStream()
        db.withTransaction { codec.write(db.openHelper.writableDatabase, raw, clock.instant()) }
        val plain = raw.toByteArray(); val before = ledger.snapshot(); val counts = rows()
        val crypto = BackupCrypto(File(context.noBackupFilesDir, "bk"))
        val wrongVersion = plain.copyOf().also { ByteBuffer.wrap(it, 8, 4).putInt(7) }
        val wrongIdentity = plain.copyOf().also { it[16] = 'z'.code.toByte() }
        val wrongTable = plain.copyOf().also {
            // First table identity follows magic/version/identity/createdAt/table-count.
            val identityLength = ByteBuffer.wrap(it, 12, 4).int; val nameStart = 16 + identityLength + 8 + 4 + 4
            it[nameStart] = '!'.code.toByte()
        }
        val source = ByteArrayInputStream(plain); val fields = DataInputStream(source)
        fields.skipBytes(12)
        fun skipText() { fields.skipBytes(fields.readInt()) }
        skipText(); fields.readLong(); fields.readInt(); skipText()
        repeat(fields.readInt()) { skipText() }
        val rowCountOffset = plain.size - source.available(); fields.readLong()
        val firstValueOffset = plain.size - source.available()
        val badRows = listOf(-1L, Long.MAX_VALUE).map { value -> plain.copyOf().also { ByteBuffer.wrap(it, rowCountOffset, 8).putLong(value) } }
        val badTags = listOf(0, 1, 99).map { tag -> plain.copyOf().also { it[firstValueOffset] = tag.toByte() } }
        val badText = plain.copyOf().also { ByteBuffer.wrap(it, firstValueOffset + 1, 4).putInt(Int.MAX_VALUE) }
        val badUtf8 = plain.copyOf().also { it[firstValueOffset + 5] = 0xff.toByte() }
        for (payload in listOf(byteArrayOf(1), wrongVersion, wrongIdentity, wrongTable, plain.copyOf(plain.size - 1), plain + byteArrayOf(1)) +
            badRows + badTags + listOf(badText, badUtf8)) {
            val output = ByteArrayOutputStream(); crypto.encrypt(output, password()) { it.write(payload) }
            rejects { backups.inspectEncrypted(ByteArrayInputStream(output.toByteArray()), password()) }; noStaging()
        }
        assertEquals(before, ledger.snapshot()); assertEquals(counts, rows())
    }
    @Test fun failedAndCancelledExportsDoNotLockOrModifyBook() = scenario {
        val before = ledger.snapshot(); val counts = rows()
        val broken = object : OutputStream() { override fun write(value: Int) { throw IOException("Synthetic destination failure") } }
        assertTrue(rejects { backups.exportEncrypted(broken, password()) } is IOException)
        val cancelled = Job().apply { cancel() }
        assertTrue(rejects { withContext(cancelled) { backups.exportEncrypted(ByteArrayOutputStream(), password()) } } is CancellationException)
        val success = ByteArrayOutputStream(); val manifest = backups.exportEncrypted(success, password())
        assertEquals(manifest, backups.inspectEncrypted(ByteArrayInputStream(success.toByteArray()), password()))
        assertEquals(before, ledger.snapshot()); assertEquals(counts, rows()); noStaging()
    }
    @Test fun trustedSchemaHistoryAndAllBooksRemainPreserved() = scenario {
        val catalog = BackupSchemaCatalog(context)
        for (version in 1..6) {
            val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("com.fintrack.data.local.FinTrackDatabase/$version.json"))
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            val schema = catalog.load(version); assertEquals(expected.getString("identityHash"), schema.identity)
            assertEquals(expected.getJSONArray("entities").length(), schema.tables.size)
        }
        ledger.initializeBook("other")
        ledger.execute(LedgerCommand(uid(), "other", 0, LedgerAction.CreateAccount("foreign", "Other cash", AccountKind.CASH, m(777), today)))
        val output = ByteArrayOutputStream(); val manifest = backups.exportEncrypted(output, password())
        assertEquals(setOf(DEFAULT_BOOK, "other"), manifest.bookRevisions.keys)
        assertEquals(manifest, backups.inspectEncrypted(ByteArrayInputStream(output.toByteArray()), password()))
        assertEquals(m(924), ledger.snapshot().liquid); assertEquals(m(777), ledger.snapshot("other").liquid); noStaging()
    }
}
