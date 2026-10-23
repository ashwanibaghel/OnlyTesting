package com.fintrack.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** V5 retains historical associations while enforcing one CURRENT occurrence per payment. */
object ScheduleLinkSchema {
    private fun latest(column: String) = """
        (SELECT e.$column FROM occurrence_resolutions e JOIN commands c ON c.id = e.commandId
         WHERE e.occurrenceId = o.id ORDER BY c.revision DESC, e.id DESC LIMIT 1)
    """.trimIndent()
    private val livePayments = """
        SELECT o.id, o.ruleId, COALESCE(${latest("status")}, o.status) AS status,
            COALESCE(${latest("journalId")}, o.journalId) AS journalId FROM schedule_occurrences o
    """.trimIndent()

    val migration4To5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.query("SELECT COUNT(*) FROM (SELECT journalId FROM ($livePayments) WHERE status = 'CONFIRMED' GROUP BY journalId HAVING COUNT(*) > 1)").use {
                check(it.moveToFirst() && it.getLong(0) == 0L) { "Conflicting existing payment associations; migration aborted without changing data" }
            }
            db.query("""SELECT COUNT(*) FROM ($livePayments) l JOIN scheduled_rules r ON r.id = l.ruleId
                LEFT JOIN journals j ON j.id = l.journalId LEFT JOIN journal_seals s ON s.entryId = j.id
                WHERE l.status = 'CONFIRMED' AND (j.id IS NULL OR j.bookId != r.bookId OR s.entryId IS NULL)""").use {
                check(it.moveToFirst() && it.getLong(0) == 0L) { "Invalid existing payment association; migration aborted without changing data" }
            }
            db.execSQL("DROP INDEX index_schedule_occurrences_journalId")
            db.execSQL("CREATE INDEX index_schedule_occurrences_journalId ON schedule_occurrences(journalId)")
            installTriggers(db)
        }
    }

    fun installTriggers(db: SupportSQLiteDatabase) {
        for ((table, ownOccurrence) in listOf("schedule_occurrences" to "NEW.id", "occurrence_resolutions" to "NEW.occurrenceId")) {
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS v5_${table}_live_payment BEFORE INSERT ON $table BEGIN
                SELECT CASE WHEN NEW.status = 'CONFIRMED' AND EXISTS (
                    SELECT 1 FROM schedule_occurrences o WHERE o.id != $ownOccurrence
                    AND (o.journalId = NEW.journalId OR o.id IN
                        (SELECT occurrenceId FROM occurrence_resolutions WHERE journalId = NEW.journalId))
                    AND COALESCE(${latest("status")}, o.status) = 'CONFIRMED'
                    AND COALESCE(${latest("journalId")}, o.journalId) = NEW.journalId
                ) THEN RAISE(ABORT,'Existing payment already has a current occurrence') END;
            END""".trimIndent())
        }
    }
}
