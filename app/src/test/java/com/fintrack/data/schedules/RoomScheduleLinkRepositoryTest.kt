package com.fintrack.data.schedules

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.ledger.RoomFinancialReviewRepository
import com.fintrack.data.ledger.RoomLedgerRepository
import com.fintrack.data.local.*
import com.fintrack.data.planning.RoomPlanningRepository
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.PlanningAction
import com.fintrack.domain.schedules.*
import com.fintrack.engine.*
import java.time.*
import java.util.UUID
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
class RoomScheduleLinkRepositoryTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var planning: RoomPlanningRepository
    private lateinit var schedules: RoomScheduleRepository
    private lateinit var payment: CommandReceipt
    private lateinit var paymentCommand: LedgerCommand
    private fun m(value: Long) = Money(value, INR)
    private fun uid() = UUID.randomUUID().toString()
    private fun scenario(block: suspend () -> Unit) = runBlocking { block() }
    private fun open() = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
        .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
    private fun repositories() {
        ledger = RoomLedgerRepository(db, clock); planning = RoomPlanningRepository(db, ledger, clock)
        schedules = RoomScheduleRepository(db, ledger, clock)
    }
    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext(); file = "l-${uid().take(16)}.db"
        db = open(); repositories(); ledger.initializeBook()
        send(LedgerAction.CreateAccount("bank", "Bank", AccountKind.BANK, m(1000), today))
        send(LedgerAction.Plan(PlanningAction.SetBudget("food", "Food", YearMonth.from(today), m(500))))
        send(LedgerAction.Plan(PlanningAction.Move(null, "food", m(300), "Fund food")))
        rule("a-wrong", 200); rule("b-right", 100)
        paymentCommand = command(LedgerAction.Post(expense(100)))
        payment = ledger.execute(paymentCommand)
    }
    @After fun teardown() { db.close(); context.deleteDatabase(file) }
    private fun expense(amount: Long) = TransactionDraft.Expense("bank", m(amount), listOf(Split("Food", m(amount))), today)
    private suspend fun command(action: LedgerAction) = LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision, action)
    private suspend fun send(action: LedgerAction) = ledger.execute(command(action))
    private suspend fun schedule(action: ScheduleAction) = send(LedgerAction.Schedule(action))
    private suspend fun rule(id: String, amount: Long, date: LocalDate = today) = schedule(ScheduleAction.Define(
        ScheduledRule(id, id, expense(amount).copy(date = date), Recurrence(date, Frequency.MONTHLY), true)))
    private suspend fun linkWrong() = schedule(ScheduleAction.LinkExisting("a-wrong", today, payment.primaryId, "Initial mistaken association"))
    private fun unlink() = ScheduleAction.Unlink("a-wrong", today, payment.primaryId, "Payment belongs to the other bill")
    private suspend fun state() = schedules.snapshot(DEFAULT_BOOK, today, today.plusDays(30))
    private suspend fun snapshot() = planning.snapshot(DEFAULT_BOOK, today)
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected rejection")
    }
    private fun count(table: String) = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst()); it.getLong(0) }

    @Test fun unlinkRestoresOriginalPlanWithoutReversingOrRefundingActualPayment() = scenario {
        linkWrong(); val before = snapshot()
        assertEquals(m(100), (state().occurrences.single { it.ruleId == "a-wrong" }.template as TransactionDraft.Expense).amount)
        schedule(unlink())
        val after = snapshot(); val item = state().occurrences.single { it.ruleId == "a-wrong" }
        assertEquals(OccurrenceStatus.REOPENED, item.status); assertNull(item.journalId)
        assertEquals(m(200), (item.template as TransactionDraft.Expense).amount)
        assertEquals(before.ledger.accounts, after.ledger.accounts); assertEquals(before.ledger.history, after.ledger.history)
        assertEquals(before.reservations, after.reservations); assertEquals(m(200), after.reservations.reserved)
        assertEquals(payment.primaryId, db.scheduleDao().occurrences(DEFAULT_BOOK).single().journalId)
        assertEquals(1L, count("occurrence_resolutions"))
    }
    @Test fun unlinkedPaymentCanSettleCorrectOccurrenceWithoutAnotherPosting() = scenario {
        linkWrong(); schedule(unlink()); val before = ledger.snapshot(); val funded = snapshot().reservations
        schedule(ScheduleAction.LinkExisting("b-right", today, payment.primaryId, "Correct association"))
        val result = state()
        assertEquals(OccurrenceStatus.REOPENED, result.occurrences.single { it.ruleId == "a-wrong" }.status)
        assertEquals(OccurrenceStatus.CONFIRMED, result.occurrences.single { it.ruleId == "b-right" }.status)
        assertEquals(before.accounts, ledger.snapshot().accounts); assertEquals(before.history, ledger.snapshot().history)
        assertEquals(funded, snapshot().reservations); assertEquals(m(100), ledger.snapshot().expense)
    }
    @Test fun stalePaymentAssociationCannotUnlinkAReplacementJournal() = scenario {
        linkWrong(); send(LedgerAction.Correct(payment.primaryId, expense(150), "Correct actual bill"))
        val before = snapshot(); val associations = state()
        rejects { schedule(unlink()) }
        assertEquals(before, snapshot()); assertEquals(associations, state())
    }
    @Test fun unlinkReviewUsesRealEffectsAndRollsBackItsProspectiveDecision() = scenario {
        linkWrong(); val before = snapshot(); val repository = RoomFinancialReviewRepository(db, ledger, clock)
        val review = repository.preview(command(LedgerAction.Schedule(unlink())))
        assertEquals(m(0), review.before.uncoveredObligations); assertEquals(m(100), review.after.uncoveredObligations)
        assertEquals(before, snapshot()); assertEquals(0L, count("occurrence_resolutions"))
        repository.apply(review)
        assertEquals(OccurrenceStatus.REOPENED, state().occurrences.single { it.ruleId == "a-wrong" }.status)
        assertEquals(before.ledger.history, ledger.snapshot().history)
    }
    @Test fun onePaymentStillCannotSettleTwoCurrentOccurrences() = scenario {
        linkWrong(); val before = snapshot(); val associations = state()
        rejects { schedule(ScheduleAction.LinkExisting("b-right", today, payment.primaryId, "Must unlink first")) }
        assertEquals(before, snapshot()); assertEquals(associations, state())
    }
    @Test fun exactUnlinkRetryCannotUnlinkLaterCorrectAssociation() = scenario {
        linkWrong(); val request = command(LedgerAction.Schedule(unlink())); val receipt = ledger.execute(request)
        schedule(ScheduleAction.LinkExisting("b-right", today, payment.primaryId, "Correct bill"))
        val before = snapshot(); val associations = state()
        assertEquals(receipt, ledger.execute(request)); assertEquals(associations, state()); assertEquals(before, snapshot())
        rejects { schedule(unlink()) }; assertEquals(associations, state())
    }
    @Test fun invalidReasonAndForeignBookCannotChangeAssociation() = scenario {
        linkWrong(); ledger.initializeBook("other"); val before = snapshot(); val associations = state(); val audits = count("audit_events")
        rejects { schedule(unlink().copy(reason = " ")) }
        rejects { schedule(unlink().copy(ruleId = "b-right")) }
        rejects { ledger.execute(LedgerCommand(uid(), "other", 0, LedgerAction.Schedule(unlink()))) }
        assertEquals(before, snapshot()); assertEquals(associations, state()); assertEquals(audits, count("audit_events"))
    }
    @Test fun stoppedFuturePlanIsNotResurrectedByUnlinkingWrongPayment() = scenario {
        val date = today.plusDays(1); rule("future", 300, date)
        schedule(ScheduleAction.LinkExisting("future", date, payment.primaryId, "Early payment association"))
        schedule(ScheduleAction.Stop("future", today, "Future bill cancelled"))
        val before = snapshot()
        schedule(ScheduleAction.Unlink("future", date, payment.primaryId, "Wrong payment"))
        assertEquals(OccurrenceStatus.SKIPPED, state().occurrences.single { it.ruleId == "future" }.status)
        assertEquals(before.uncoveredObligations, snapshot().uncoveredObligations)
        assertEquals(before.ledger.accounts, ledger.snapshot().accounts); assertEquals(before.ledger.history, ledger.snapshot().history)
    }
    @Test fun unlinkPreservesAnOriginalOverdueObligation() = scenario {
        val date = today.minusDays(1); rule("overdue", 300, date)
        schedule(ScheduleAction.LinkExisting("overdue", date, payment.primaryId, "Mistaken association"))
        val before = snapshot()
        schedule(ScheduleAction.Unlink("overdue", date, payment.primaryId, "Still unpaid"))
        val item = state().occurrences.single { it.ruleId == "overdue" && it.scheduledDate == date }
        assertEquals(OccurrenceStatus.REOPENED, item.status); assertEquals(m(300), (item.template as TransactionDraft.Expense).amount)
        assertEquals(before.uncoveredObligations + m(300), snapshot().uncoveredObligations)
        assertEquals(before.ledger.history, ledger.snapshot().history)
    }
    @Test fun sqlGuardRejectsSecondCurrentInitialAssociation() = scenario {
        val linked = linkWrong(); val before = state()
        val error = rejects { db.scheduleDao().insertOccurrence(ScheduleOccurrenceEntity("b-right@$today", "b-right", today.toString(),
            linked.commandId, "CONFIRMED", payment.primaryId, "Synthetic duplicate", TemplateCodec.encode(expense(100)))) }
        assertTrue(error.message.orEmpty().contains("current occurrence")); assertEquals(before, state())
    }
    @Test fun sqlGuardRejectsSecondCurrentResolutionAssociation() = scenario {
        val linked = linkWrong(); schedule(ScheduleAction.Skip("b-right", today, "Temporarily skipped")); val before = state()
        val error = rejects { db.scheduleDao().insertResolution(OccurrenceResolutionEntity(uid(), "b-right@$today", linked.commandId,
            "CONFIRMED", payment.primaryId, TemplateCodec.encode(expense(100)), "Synthetic duplicate")) }
        assertTrue(error.message.orEmpty().contains("current occurrence")); assertEquals(before, state())
    }
    @Test fun historicalAssociationsRemainImmutableAfterRelinking() = scenario {
        linkWrong(); schedule(unlink()); schedule(ScheduleAction.LinkExisting("b-right", today, payment.primaryId, "Correct bill"))
        val before = state(); val actual = ledger.snapshot()
        rejects { db.openHelper.writableDatabase.execSQL("UPDATE schedule_occurrences SET reason = 'erased' WHERE ruleId = 'a-wrong'") }
        rejects { db.openHelper.writableDatabase.execSQL("DELETE FROM occurrence_resolutions") }
        val old = db.scheduleDao().occurrences(DEFAULT_BOOK).single { it.ruleId == "a-wrong" }
        rejects { db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO schedule_occurrences VALUES (?,?,?,?,?,?,?,?)",
            arrayOf(old.id, old.ruleId, old.scheduledDate, old.commandId, old.status, old.journalId, old.reason, old.confirmedTemplate)) }
        assertEquals(before, state()); assertEquals(actual, ledger.snapshot()); assertEquals(2L, count("schedule_occurrences"))
    }

    private fun recreateV4(conflicting: Boolean = false, linkCommand: String) {
        val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("com.fintrack.data.local.FinTrackDatabase/4.json"))
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("database")
        val entities = source.getJSONArray("entities")
        data class Rows(val table: String, val columns: List<String>, val rows: List<List<Any?>>)
        val captured = (0 until entities.length()).map { index ->
            val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
            val fields = entity.getJSONArray("fields")
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
            old.setForeignKeyConstraintsEnabled(true)
            old.beginTransaction()
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
                if (conflicting) {
                    // V4's historical unique index cannot prevent a duplicate introduced by a later resolution.
                    old.execSQL("INSERT INTO schedule_occurrences VALUES (?,?,?,?,?,?,?,?)",
                        arrayOf("b-right@$today", "b-right", today.toString(), linkCommand, "SKIPPED", null, "Legacy skipped", null))
                    old.execSQL("INSERT INTO occurrence_resolutions VALUES (?,?,?,?,?,?,?)",
                        arrayOf("legacy-conflict", "b-right@$today", linkCommand, "CONFIRMED", payment.primaryId, TemplateCodec.encode(expense(100)), "Legacy conflict"))
                }
                old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                old.execSQL("INSERT INTO room_master_table VALUES (42,?)", arrayOf(source.getString("identityHash")))
                old.version = 4; old.setTransactionSuccessful()
            } finally { old.endTransaction() }
        }
        db = open(); repositories()
    }
    @Test fun populatedV4MigrationRetainsRowsReceiptsAndAllowsCorrectRelink() = scenario {
        val linked = linkWrong(); val before = snapshot(); val associations = state(); val audits = count("audit_events")
        recreateV4(linkCommand = linked.commandId)
        assertEquals(before, snapshot()); assertEquals(associations, state()); assertEquals(audits, count("audit_events"))
        assertEquals(payment, ledger.execute(paymentCommand))
        db.openHelper.writableDatabase.query("PRAGMA index_list('schedule_occurrences')").use { cursor ->
            var checked = false
            while (cursor.moveToNext()) if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_schedule_occurrences_journalId") {
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("unique"))); checked = true
            }
            assertTrue(checked)
        }
        schedule(unlink()); schedule(ScheduleAction.LinkExisting("b-right", today, payment.primaryId, "Correct after migration"))
        assertEquals(before.ledger.accounts, ledger.snapshot().accounts); assertEquals(before.ledger.history, ledger.snapshot().history)
        assertEquals(before.reservations, snapshot().reservations)
    }
    @Test fun conflictingV4MigrationAbortsWithoutDeletingFinancialHistory() = scenario {
        val linked = linkWrong(); val journals = count("journals"); val commands = count("commands")
        recreateV4(conflicting = true, linkCommand = linked.commandId)
        val error = rejects { ledger.snapshot() }
        val messages = generateSequence(error as Throwable) { it.cause }.joinToString { it.message.orEmpty() }
        assertTrue(messages.contains("Conflicting existing payment associations")); db.close()
        context.openOrCreateDatabase(file, 0, null).use { old ->
            assertEquals(4, old.version)
            old.rawQuery("SELECT COUNT(*) FROM journals", null).use { check(it.moveToFirst()); assertEquals(journals, it.getLong(0)) }
            old.rawQuery("SELECT COUNT(*) FROM commands", null).use { check(it.moveToFirst()); assertEquals(commands, it.getLong(0)) }
            old.rawQuery("SELECT COUNT(*) FROM occurrence_resolutions", null).use { check(it.moveToFirst()); assertEquals(1L, it.getLong(0)) }
        }
    }
}
