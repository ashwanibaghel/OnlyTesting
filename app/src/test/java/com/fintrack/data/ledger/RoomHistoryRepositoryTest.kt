package com.fintrack.data.ledger

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.data.reports.RoomFinancialReportsRepository
import com.fintrack.domain.reports.FinancialReport
import com.fintrack.domain.reports.ReportPeriod
import com.fintrack.engine.Money
import java.time.*
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
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
class RoomHistoryRepositoryTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var history: RoomHistoryRepository
    private fun m(value: Long) = Money(value, INR)
    private fun uid() = UUID.randomUUID().toString()
    private fun scenario(block: suspend () -> Unit) = runBlocking { block() }
    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext(); file = "h-${uid().take(16)}.db"
        db = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
            .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
        ledger = RoomLedgerRepository(db, clock); history = RoomHistoryRepository(db)
        ledger.initializeBook(); send(LedgerAction.CreateAccount("bank", "Bank", AccountKind.BANK, m(1000), today))
        send(LedgerAction.CreateAccount("cash", "Cash", AccountKind.CASH, m(0), today))
        send(LedgerAction.Post(TransactionDraft.Income("bank", m(1), "Salary", today)))
        send(LedgerAction.Post(TransactionDraft.Expense("bank", m(1), listOf(Split("Food", m(1))), today)))
    }
    @After fun teardown() { db.close(); context.deleteDatabase(file) }
    private suspend fun command(action: LedgerAction, book: String = DEFAULT_BOOK) = LedgerCommand(uid(), book, ledger.snapshot(book).revision, action)
    private suspend fun send(action: LedgerAction) = ledger.execute(command(action))
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block() } catch (_: Exception) { return }
        throw AssertionError("Expected rejection")
    }
    /** Explicit synthetic sealed financial history, seeded once to avoid O(n²) test setup through full snapshots. */
    private suspend fun seed(count: Int, needleAt: Int? = null) {
        db.withTransaction {
            val dao = db.ledgerDao(); var revision = dao.book(DEFAULT_BOOK)!!.revision
            val controls = dao.ledgerAccounts(DEFAULT_BOOK)
            val salary = controls.single { it.role == "INCOME" && it.name == "Salary" }.id
            val food = controls.single { it.role == "EXPENSE" && it.name == "Food" }.id
            repeat(count) { index ->
                val date = today.minusDays((index / 100).toLong())
                val clearing = if (index % 3 == 0) Clearing.PENDING else Clearing.CLEARED
                val memo = if (index == needleAt) "Rare NEEDLE" else "Synthetic history $index"
                val draft: TransactionDraft = if (index % 2 == 0) TransactionDraft.Income("bank", m(1), "Salary", date, clearing, memo)
                    else TransactionDraft.Expense("bank", m(1), listOf(Split("Food", m(1))), date, clearing, memo)
                val id = "seed-$index"; val entry = "entry-${index.toString().padStart(6, '0')}"
                val input = LedgerCommand(id, DEFAULT_BOOK, revision, LedgerAction.Post(draft))
                dao.insertCommand(CommandEntity(id, DEFAULT_BOOK, fingerprint(input), entry, null, revision + 1))
                dao.insertAudit(AuditEntity(id, "Post", "", clock.millis() + index % 10))
                dao.insertJournal(JournalEntity(entry, DEFAULT_BOOK, id, if (index % 2 == 0) "INCOME" else "EXPENSE", date.toString(),
                    clock.millis() + index % 10, clearing.name, memo, "", null))
                val delta = if (index % 2 == 0) 1L else -1L
                dao.insertPostings(listOf(PostingEntity("asset-$index", entry, "bank", delta),
                    PostingEntity("control-$index", entry, if (index % 2 == 0) salary else food, -delta)))
                dao.seal(JournalSealEntity(entry)); check(dao.advance(DEFAULT_BOOK, revision, revision + 1) == 1); revision++
            }
        }
    }

    @Test fun pagesCoverTiedDatesAndTimesWithoutDuplicatesOrMissingHistory() = scenario {
        seed(173); val expected = ledger.history(DEFAULT_BOOK)
        val actual = mutableListOf<HistoryEntry>(); var cursor: HistoryCursor? = null
        do {
            val page = history.page(DEFAULT_BOOK, size = 17, cursor = cursor)
            assertTrue(page.scanned <= 17); assertTrue(page.entries.size <= 17)
            actual += page.entries; cursor = page.next
        } while (cursor != null)
        assertEquals(expected.map { it.id }, actual.map { it.id }); assertEquals(actual.size, actual.map { it.id }.toSet().size)
        for ((left, right) in expected.zip(actual)) {
            val order = compareBy<HistoryLeg> { it.accountId }.thenBy { it.amount.minor }
            assertEquals(left.copy(legs = left.legs.sortedWith(order)), right.copy(legs = right.legs.sortedWith(order)))
        }
    }
    @Test fun financialRevisionAndFilterChangesRejectStaleCursor() = scenario {
        seed(25); val cursor = history.page(DEFAULT_BOOK, size = 10).next!!
        rejects { history.page(DEFAULT_BOOK, HistoryQuery(HistoryFilter(query = "Food")), 10, cursor) }
        send(LedgerAction.Post(TransactionDraft.Income("bank", m(1), "Salary", today)))
        rejects { history.page(DEFAULT_BOOK, size = 10, cursor = cursor) }
    }
    @Test fun cursorCannotCrossBooksEvenWhenRevisionsMatch() = scenario {
        seed(3); val cursor = history.page(DEFAULT_BOOK, size = 2).next!!
        ledger.initializeBook("other")
        ledger.execute(command(LedgerAction.CreateAccount("other-bank", "Other", AccountKind.BANK, m(0), today), "other"))
        while (ledger.snapshot("other").revision < cursor.revision)
            ledger.execute(command(LedgerAction.Archive("other-bank", false), "other"))
        assertEquals(cursor.revision, ledger.snapshot("other").revision)
        rejects { history.page("other", size = 2, cursor = cursor) }
    }
    @Test fun accountDateKindAndClearingFiltersUseActualRecordedHistory() = scenario {
        seed(20)
        val transfer = send(LedgerAction.Post(TransactionDraft.Transfer("bank", "cash", m(20), m(2), today)))
        val query = HistoryQuery(HistoryFilter("cash", today, today), "TRANSFER", HistoryClearing.CLEARED)
        assertEquals(listOf(transfer.primaryId), history.page(DEFAULT_BOOK, query).entries.map { it.id })
        val pending = history.page(DEFAULT_BOOK, HistoryQuery(clearing = HistoryClearing.PENDING))
        assertTrue(pending.entries.isNotEmpty()); assertTrue(pending.entries.all { it.clearedDate == null })
        val cleared = history.page(DEFAULT_BOOK, HistoryQuery(clearing = HistoryClearing.CLEARED))
        assertTrue(cleared.entries.all { it.clearedDate != null })
    }
    @Test fun unicodeAndWildcardsAreLiteralCaseInsensitiveSearchText() = scenario {
        seed(20)
        val special = send(LedgerAction.Post(TransactionDraft.Income("bank", m(10), "Réservé", today, memo = "CAFÉ cafe_100% !")))
        for (query in listOf("café", "réservé", "%", "_")) {
            val result = history.page(DEFAULT_BOOK, HistoryQuery(HistoryFilter(query = query)))
            assertEquals(query, listOf(special.primaryId), result.entries.map { it.id }); assertTrue(result.exhausted)
        }
        assertTrue(history.page(DEFAULT_BOOK, HistoryQuery(HistoryFilter(query = "' OR 1=1 --"))).entries.isEmpty())
    }
    @Test fun hiddenReversalsDoNotAlterActualBookTotals() = scenario {
        val original = send(LedgerAction.Post(TransactionDraft.Expense("bank", m(100), listOf(Split("Food", m(100))), today)))
        val reversal = send(LedgerAction.Reverse(original.primaryId, today, "Refund")); val before = ledger.snapshot()
        val result = history.page(DEFAULT_BOOK, HistoryQuery(includeReversed = false))
        assertTrue(result.entries.none { it.id == original.primaryId || it.id == reversal.primaryId })
        assertEquals(before, ledger.snapshot())
    }
    @Test fun clearingEventInvalidatesCursorAndUpdatesActualStatus() = scenario {
        seed(6); val cursor = history.page(DEFAULT_BOOK, size = 2).next!!
        val pending = ledger.history(DEFAULT_BOOK).first { it.clearedDate == null }
        send(LedgerAction.Clear(pending.id, today))
        rejects { history.page(DEFAULT_BOOK, size = 2, cursor = cursor) }
        val result = history.page(DEFAULT_BOOK, HistoryQuery(clearing = HistoryClearing.CLEARED))
        assertEquals(today, result.entries.single { it.id == pending.id }.clearedDate)
    }
    @Test fun emptyAndExactFinalPagesHaveNoSpuriousContinuation() = scenario {
        ledger.initializeBook("empty"); val empty = history.page("empty")
        assertTrue(empty.entries.isEmpty()); assertTrue(empty.exhausted); assertEquals(0, empty.scanned)
        seed(7); val all = history.page(DEFAULT_BOOK, size = 10)
        assertEquals(10, all.entries.size); assertTrue(all.exhausted)
    }
    @Test fun invalidRequestsAndForeignAccountAreRejected() = scenario {
        rejects { history.page(DEFAULT_BOOK, size = 0) }; rejects { history.page(DEFAULT_BOOK, size = 201) }
        rejects { history.page(DEFAULT_BOOK, HistoryQuery(HistoryFilter(from = today, through = today.minusDays(1)))) }
        rejects { history.page(DEFAULT_BOOK, HistoryQuery(HistoryFilter(query = "x".repeat(101)))) }
        rejects { history.page(DEFAULT_BOOK, HistoryQuery(kind = "invented")) }
        ledger.initializeBook("other")
        ledger.execute(command(LedgerAction.CreateAccount("other-bank", "Other", AccountKind.BANK, m(0), today), "other"))
        rejects { history.page(DEFAULT_BOOK, HistoryQuery(HistoryFilter(accountId = "other-bank"))) }
    }
    @Test fun largeHistoryHasBoundedSearchAndIndexedChronologicalBrowse() = scenario {
        val seedMs = measureTimeMillis { seed(10_050, needleAt = 10_049) }
        val query = HistoryQuery(HistoryFilter(query = "needle"))
        lateinit var first: HistoryPage
        val searchMs = measureTimeMillis { first = history.page(DEFAULT_BOOK, query, 10) }
        assertTrue(first.entries.isEmpty()); assertEquals(10_000, first.scanned); assertFalse(first.exhausted)
        val remaining = history.page(DEFAULT_BOOK, query, 10, first.next)
        assertEquals(1, remaining.entries.size); assertEquals("Rare NEEDLE", remaining.entries.single().memo); assertTrue(remaining.exhausted)
        lateinit var browse: HistoryPage
        val browseMs = measureTimeMillis { browse = history.page(DEFAULT_BOOK, size = 100) }
        assertEquals(100, browse.entries.size); assertEquals(100, browse.scanned)
        val production = history.windowQuery(DEFAULT_BOOK, HistoryQuery(), null, 101)
        val plan = db.openHelper.writableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${production.sql}", arrayOf<Any>(DEFAULT_BOOK, 101)))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
        println("HOST NATIVE SQLITE: rows=10053 seedMs=$seedMs boundedSearchMs=$searchMs page100Ms=$browseMs plan=$plan")
        assertFalse("Chronological browsing must avoid a temporary ordering sort: $plan", plan.any { it.contains("TEMP B-TREE", ignoreCase = true) })
        assertTrue(plan.any { it.contains("index_journals_bookId_effectiveDate_createdAt_id") })
        lateinit var report: FinancialReport
        val reportMs = measureTimeMillis {
            report = RoomFinancialReportsRepository(db, clock).report(DEFAULT_BOOK, ReportPeriod(today.minusDays(100), today))
        }
        assertEquals(10_053L, report.posted.journalCount)
        assertEquals(m(5026), report.posted.totals.income); assertEquals(m(5026), report.posted.totals.expense)
        assertEquals(m(1000), report.assetTrend.last().postedAssets); assertEquals(101, report.assetTrend.size)
        println("HOST NATIVE SQLITE REPORT: journals=10053 reportMs=$reportMs postedIncome=5026 postedExpense=5026 trackedAssets=1000")
    }
    @Test fun populatedV5MigrationPreservesCursorHistoryAndOriginalReceipt() = scenario {
        seed(25); val before = ledger.snapshot(); val first = history.page(DEFAULT_BOOK, size = 7)
        val oldCommand = LedgerCommand("seed-0", DEFAULT_BOOK, 4,
            LedgerAction.Post(TransactionDraft.Income("bank", m(1), "Salary", today, Clearing.PENDING, "Synthetic history 0")))
        val receipt = ledger.execute(oldCommand)
        val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("com.fintrack.data.local.FinTrackDatabase/5.json"))
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("database")
        val entities = source.getJSONArray("entities")
        data class Rows(val table: String, val columns: List<String>, val rows: List<List<Any?>>)
        val captured = (0 until entities.length()).map { index ->
            val entity = entities.getJSONObject(index); val table = entity.getString("tableName"); val fields = entity.getJSONArray("fields")
            val columns = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
            db.openHelper.writableDatabase.query("SELECT ${columns.joinToString(",") { "`$it`" }} FROM `$table`").use { cursor ->
                val rows = mutableListOf<List<Any?>>()
                while (cursor.moveToNext()) rows += columns.indices.map { col -> when (cursor.getType(col)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(col)
                    android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(col)
                    else -> error("Unexpected legacy type")
                } }
                Rows(table, columns, rows)
            }
        }
        db.close(); context.deleteDatabase(file)
        context.openOrCreateDatabase(file, 0, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true); old.beginTransaction()
            try {
                old.execSQL("PRAGMA defer_foreign_keys = ON")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                for (value in captured) for (row in value.rows) old.execSQL(
                    "INSERT INTO `${value.table}` (${value.columns.joinToString(",") { "`$it`" }}) VALUES (${value.columns.joinToString(",") { "?" }})", row.toTypedArray())
                old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                old.execSQL("INSERT INTO room_master_table VALUES (42,?)", arrayOf(source.getString("identityHash")))
                old.version = 5; old.setTransactionSuccessful()
            } finally { old.endTransaction() }
        }
        db = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
            .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
        ledger = RoomLedgerRepository(db, clock); history = RoomHistoryRepository(db)
        assertEquals(before, ledger.snapshot()); assertEquals(receipt, ledger.execute(oldCommand))
        val next = history.page(DEFAULT_BOOK, size = 7, cursor = first.next)
        assertEquals(ledger.history(DEFAULT_BOOK).drop(7).take(7).map { it.id }, next.entries.map { it.id })
        assertEquals(6, db.openHelper.writableDatabase.version)
    }
}
