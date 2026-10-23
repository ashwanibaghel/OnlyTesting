package com.fintrack.data.backup

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.ledger.RoomLedgerRepository
import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.*
import com.fintrack.engine.Money
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
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
class SnapshotStageTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 23)
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private val owned = mutableListOf<File>()
    private fun uid() = UUID.randomUUID().toString()
    private fun m(value: Long) = Money(value, INR)
    private fun scenario(block: suspend () -> Unit) = runBlocking { block() }
    private fun stage() = SnapshotStage(context)
    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext(); name = "st-${uid().take(16)}.db"
        db = Room.databaseBuilder(context, FinTrackDatabase::class.java, name).addMigrations(*FinTrackMigrations.all)
            .addCallback(LedgerSchema.callback).build()
        ledger = RoomLedgerRepository(db, clock); ledger.initializeBook()
        send(LedgerAction.CreateAccount("bank", "Synthetic bank", AccountKind.BANK, m(1000), today))
        send(LedgerAction.Plan(PlanningAction.CreateReserve("reserve", "Savings", BucketKind.SAVINGS)))
        send(LedgerAction.Plan(PlanningAction.Move(null, "reserve", m(100), "Synthetic funding")))
        send(LedgerAction.Post(TransactionDraft.Expense("bank", m(40), listOf(Split("Food", m(40))), today, Clearing.PENDING)))
    }
    @After fun teardown() { db.close(); context.deleteDatabase(name); owned.forEach { it.delete() } }
    private suspend fun send(action: LedgerAction) = ledger.execute(LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision, action))
    private suspend fun payload(): File = File.createTempFile("payload-", ".bin", context.cacheDir).also { file ->
        owned += file
        file.outputStream().use { out -> db.withTransaction { SnapshotCodec(BackupSchemaCatalog(context)).write(db.openHelper.writableDatabase, out, clock.instant()) } }
    }
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }; throw AssertionError("Expected rejection")
    }
    private fun emptyStage() = assertTrue(File(context.noBackupFilesDir, "bk-db").listFiles()!!.isEmpty())
    private fun disableTriggers() {
        val raw = db.openHelper.writableDatabase
        val names = raw.query("SELECT name FROM sqlite_master WHERE type='trigger'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        names.forEach { raw.execSQL("DROP TRIGGER `$it`") }
    }

    @Test fun currentSnapshotIsIsolatedAndOriginalReceiptsRemainReplayable() = scenario {
        val command = LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision,
            LedgerAction.Post(TransactionDraft.Income("bank", m(35), "Salary", today)))
        val receipt = ledger.execute(command); val before = ledger.snapshot(); val file = payload()
        stage().withDatabase(file) { manifest, staged ->
            assertEquals(6, manifest.schemaVersion); assertEquals(6, staged.openHelper.writableDatabase.version)
            assertNotEquals(db.openHelper.writableDatabase.path, staged.openHelper.writableDatabase.path)
            val reconstructed = RoomLedgerRepository(staged, clock)
            assertEquals(before, reconstructed.snapshot()); assertEquals(receipt, reconstructed.execute(command))
            staged.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            assertTrue(rejects { staged.openHelper.writableDatabase.execSQL("DELETE FROM postings") } is android.database.SQLException)
        }
        assertEquals(before, ledger.snapshot()); assertEquals(receipt, ledger.execute(command)); emptyStage()
    }

    @Test fun everyHistoricalSchemaMigratesWithPreservedMetadataAndNoInventedBalances() = scenario {
        val catalog = BackupSchemaCatalog(context)
        for (version in 1..6) {
            val schema = catalog.load(version)
            val legacy = File.createTempFile("legacy-", ".db", context.cacheDir).also { owned += it }
            // Canonical historical SQL creates a genuine source database without auto-migrating it before export.
            SQLiteDatabase.openOrCreateDatabase(legacy, null).use { raw ->
                schema.tables.forEach { raw.execSQL(it.createSql); it.indices.forEach(raw::execSQL) }
                raw.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                raw.execSQL("INSERT INTO room_master_table VALUES (42,?)", arrayOf(schema.identity)); raw.version = version
                if (version >= 2) raw.execSQL("INSERT INTO books VALUES ('personal','local-owner','INR',2,0,0)")
                if (version == 1) raw.execSQL("INSERT INTO accounts VALUES ('legacy','Historic cash','INR','CASH',1)")
                else {
                    raw.execSQL("INSERT INTO accounts VALUES ('legacy','Historic cash','INR','CASH',1,'personal')")
                    raw.execSQL("INSERT INTO ledger_accounts VALUES ('legacy','personal','legacy','ASSET','Historic cash')")
                }
            }
            val source = File.createTempFile("historic-", ".bin", context.cacheDir).also { owned += it }
            writeHistoricalPayload(legacy, schema, source)
            stage().withDatabase(source) { manifest, migrated ->
                assertEquals(version, manifest.schemaVersion); assertEquals(6, migrated.openHelper.writableDatabase.version)
                val snapshot = RoomLedgerRepository(migrated, clock).snapshot()
                assertEquals(m(0), snapshot.liquid); assertTrue(snapshot.history.isEmpty())
                assertEquals("legacy", snapshot.accounts.single().id); assertTrue(snapshot.accounts.single().archived)
            }
            emptyStage()
        }
        assertEquals(m(960), ledger.snapshot().liquid)
    }

    /** Same framing as exported historical payloads, with rows read through Android SQLite to avoid auto-migrating the source. */
    private fun writeHistoricalPayload(database: File, schema: SnapshotSchema, output: File) {
        java.io.DataOutputStream(output.outputStream()).use { data ->
            fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); data.writeInt(bytes.size); data.write(bytes) }
            data.write("FTSNAP01".toByteArray()); data.writeInt(schema.version); text(schema.identity)
            data.writeLong(clock.millis()); data.writeInt(schema.tables.size)
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                for (table in schema.tables) {
                    text(table.name); data.writeInt(table.columns.size); table.columns.forEach { text(it.name) }
                    raw.rawQuery("SELECT ${table.columns.joinToString(",") { "`${it.name}`" }} FROM `${table.name}`", null).use { rows ->
                        data.writeLong(rows.count.toLong())
                        while (rows.moveToNext()) table.columns.forEachIndexed { index, column -> when {
                            rows.isNull(index) -> data.writeByte(0)
                            column.affinity == "INTEGER" -> { data.writeByte(1); data.writeLong(rows.getLong(index)) }
                            else -> { data.writeByte(2); text(rows.getString(index)) }
                        } }
                    }
                }
            }
            data.write("FTEND001".toByteArray())
        }
    }

    @Test fun rejectedHistoricalMigrationCannotChangeLiveStateOrSourcePayload() = scenario {
        val schema = BackupSchemaCatalog(context).load(1)
        val legacy = File.createTempFile("unsupported-", ".db", context.cacheDir).also { owned += it }
        SQLiteDatabase.openOrCreateDatabase(legacy, null).use { raw ->
            schema.tables.forEach { raw.execSQL(it.createSql) }
            raw.execSQL("INSERT INTO accounts VALUES ('legacy','Unsupported currency','USD','BANK',0)")
            raw.version = 1
        }
        val file = File.createTempFile("unsupported-", ".bin", context.cacheDir).also { owned += it }
        writeHistoricalPayload(legacy, schema, file)
        val before = ledger.snapshot(); val source = file.readBytes(); var called = false
        val error = rejects { stage().withDatabase(file) { _, _ -> called = true } }
        assertTrue(error.toString().contains("Legacy metadata is unsupported"))
        assertFalse(called); assertEquals(before, ledger.snapshot()); assertArrayEquals(source, file.readBytes()); emptyStage()
    }

    @Test fun brokenForeignKeyCannotReachStagedConsumerOrAlterLiveDatabase() = scenario {
        db.openHelper.writableDatabase.setForeignKeyConstraintsEnabled(false); disableTriggers()
        db.openHelper.writableDatabase.execSQL("UPDATE postings SET ledgerAccountId='missing'")
        val before = payload().readBytes(); var called = false
        rejects { stage().withDatabase(payload()) { _, _ -> called = true } }
        assertFalse(called); assertArrayEquals(before, payload().readBytes()); emptyStage()
    }

    @Test fun crossTableUniqueConstraintIsEnforcedDuringReconstruction() = scenario {
        disableTriggers(); val raw = db.openHelper.writableDatabase
        raw.execSQL("DROP INDEX index_ledger_accounts_ownerAccountId")
        raw.execSQL("INSERT INTO ledger_accounts VALUES ('duplicate','personal','bank','ASSET','Duplicate owner')")
        var called = false; rejects { stage().withDatabase(payload()) { _, _ -> called = true } }
        assertFalse(called); assertEquals(1, raw.query("SELECT COUNT(*) FROM ledger_accounts WHERE id='duplicate'").use { it.moveToFirst(); it.getInt(0) }); emptyStage()
    }

    @Test fun duplicatePrimaryKeyInTypedRowsIsRejected() = scenario {
        send(LedgerAction.CreateAccount("cash", "Cash", AccountKind.CASH, m(0), today))
        val file = payload(); val before = ledger.snapshot()
        // Equal byte lengths preserve every framing field while deliberately duplicating the bank account key.
        file.writeBytes(String(file.readBytes(), Charsets.ISO_8859_1).replace("cash", "bank").toByteArray(Charsets.ISO_8859_1))
        var called = false; rejects { stage().withDatabase(file) { _, _ -> called = true } }
        assertFalse(called); assertEquals(before, ledger.snapshot()); emptyStage()
    }

    @Test fun sqlLikeLabelsAreBoundAsData() = scenario {
        val label = "x'); DROP TABLE accounts;--"
        send(LedgerAction.CreateAccount("quoted", label, AccountKind.CASH, m(0), today))
        val before = ledger.snapshot()
        stage().withDatabase(payload()) { _, staged ->
            assertEquals(before, RoomLedgerRepository(staged, clock).snapshot())
            assertEquals(label, RoomLedgerRepository(staged, clock).snapshot().accounts.single { it.id == "quoted" }.name)
        }
        assertEquals(before, ledger.snapshot()); emptyStage()
    }

    @Test fun malformedPayloadRollsBackAndRemovesAllStagingFiles() = scenario {
        val before = ledger.snapshot(); val file = payload(); file.appendBytes(byteArrayOf(1))
        var called = false; rejects { stage().withDatabase(file) { _, _ -> called = true } }
        assertFalse(called); assertEquals(before, ledger.snapshot()); emptyStage()
    }

    @Test fun failedAndCancelledConsumersAlwaysCloseAndRemoveStaging() = scenario {
        val before = ledger.snapshot(); val file = payload()
        val failure = IllegalStateException("Synthetic consumer failure")
        val caught = rejects { stage().withDatabase(file) { _, _ -> throw failure } }
        assertEquals(failure.javaClass, caught.javaClass); assertEquals(failure.message, caught.message)
        assertTrue(rejects { stage().withDatabase(file) { _, _ -> throw CancellationException("Synthetic cancellation") } } is CancellationException)
        val cancelled = Job().apply { cancel() }
        assertTrue(rejects { withContext(cancelled) { stage().withDatabase(file) { _, _ -> fail("Cancelled consumer") } } } is CancellationException)
        assertEquals(before, ledger.snapshot()); emptyStage()
    }

    @Test fun storagePageLimitRejectsWithoutTouchingLiveData() = scenario {
        val before = ledger.snapshot(); var called = false
        rejects { SnapshotStage(context, maxPages = 4).withDatabase(payload()) { _, _ -> called = true } }
        assertFalse(called); assertEquals(before, ledger.snapshot()); emptyStage()
    }

    @Test fun storageInspectionDoesNotPretendToValidateFinancialSemantics() = scenario {
        // SQL-valid but financially invalid: an extra non-zero leg breaks double entry.
        disableTriggers(); db.openHelper.writableDatabase.execSQL("UPDATE postings SET minor=minor+1 WHERE minor>0")
        stage().withDatabase(payload()) { _, staged ->
            staged.openHelper.writableDatabase.query("SELECT minor FROM postings").use { assertTrue(it.moveToFirst()) }
        }
        emptyStage() // This gate must remain named storage inspection until semantic validation is implemented.
    }

    @Test fun interruptedCleanupOnlyRemovesOwnedDatabaseFiles() {
        val root = File(context.cacheDir, "st-root-${uid()}").also { check(it.mkdirs()) }
        val folder = File(root, "bk-db").also { check(it.mkdir()) }
        val files = listOf("db-123.db", "db-123.db-wal", "db-123.db-shm", "db-123.db-journal", "keep.db", "db-other.db")
            .associateWith { File(folder, it).also { file -> file.writeText("Synthetic interrupted fixture") } }
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        try {
            SnapshotStage(wrapped)
            assertEquals(setOf("keep.db", "db-other.db"), folder.listFiles()!!.map { it.name }.toSet())
            assertEquals("Synthetic interrupted fixture", files.getValue("keep.db").readText())
        } finally {
            files.values.forEach { it.delete() }; check(folder.delete()); check(root.delete())
        }
    }
}
