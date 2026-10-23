package com.fintrack.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** V3 adds virtual ownership; the v1/v2 actual accounting schema and triggers stay intact. */
object PlanningSchema {
    val migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            tables.forEach(db::execSQL)
            installTriggers(db)
        }
    }

    private val tables = listOf(
        "CREATE TABLE IF NOT EXISTS financial_profiles (bookId TEXT NOT NULL PRIMARY KEY, timezone TEXT NOT NULL, safetyBufferMinor INTEGER NOT NULL, monthlyCapacityMinor INTEGER NOT NULL, horizonDays INTEGER NOT NULL, setupComplete INTEGER NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE TABLE IF NOT EXISTS reserve_buckets (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, protected INTEGER NOT NULL, category TEXT, month TEXT, archived INTEGER NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE INDEX IF NOT EXISTS index_reserve_buckets_bookId ON reserve_buckets(bookId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_reserve_buckets_bookId_category_month ON reserve_buckets(bookId,category,month)",
        "CREATE TABLE IF NOT EXISTS monthly_budgets (bucketId TEXT NOT NULL PRIMARY KEY, limitMinor INTEGER NOT NULL, carriedMinor INTEGER NOT NULL, rollover TEXT NOT NULL, closed INTEGER NOT NULL, FOREIGN KEY(bucketId) REFERENCES reserve_buckets(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE TABLE IF NOT EXISTS financial_goals (bucketId TEXT NOT NULL PRIMARY KEY, targetMinor INTEGER NOT NULL, deadline TEXT NOT NULL, priority INTEGER NOT NULL, monthlyMinor INTEGER NOT NULL, anchorDay INTEGER NOT NULL, FOREIGN KEY(bucketId) REFERENCES reserve_buckets(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE TABLE IF NOT EXISTS allocation_journals (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, commandId TEXT NOT NULL, kind TEXT NOT NULL, effectiveDate TEXT NOT NULL, reason TEXT NOT NULL, createdAt INTEGER NOT NULL, linkedJournalId TEXT, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(commandId) REFERENCES commands(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(linkedJournalId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE INDEX IF NOT EXISTS index_allocation_journals_bookId ON allocation_journals(bookId)",
        "CREATE INDEX IF NOT EXISTS index_allocation_journals_commandId ON allocation_journals(commandId)",
        "CREATE INDEX IF NOT EXISTS index_allocation_journals_linkedJournalId ON allocation_journals(linkedJournalId)",
        "CREATE TABLE IF NOT EXISTS allocation_legs (id TEXT NOT NULL PRIMARY KEY, entryId TEXT NOT NULL, bucketId TEXT, minor INTEGER NOT NULL, FOREIGN KEY(entryId) REFERENCES allocation_journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(bucketId) REFERENCES reserve_buckets(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE INDEX IF NOT EXISTS index_allocation_legs_entryId ON allocation_legs(entryId)",
        "CREATE INDEX IF NOT EXISTS index_allocation_legs_bucketId ON allocation_legs(bucketId)",
        "CREATE TABLE IF NOT EXISTS allocation_seals (entryId TEXT NOT NULL PRIMARY KEY, FOREIGN KEY(entryId) REFERENCES allocation_journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
    )

    fun installTriggers(db: SupportSQLiteDatabase) {
        fun trigger(name: String, timing: String, table: String, body: String) =
            db.execSQL("CREATE TRIGGER IF NOT EXISTS $name $timing ON $table BEGIN $body END")
        listOf("allocation_journals", "allocation_legs", "allocation_seals").forEach { table ->
            val key = if (table == "allocation_seals") "entryId" else "id"
            trigger("${table}_immutable_update", "BEFORE UPDATE", table, "SELECT RAISE(ABORT,'Immutable virtual ledger');")
            trigger("${table}_immutable_delete", "BEFORE DELETE", table, "SELECT RAISE(ABORT,'Immutable virtual ledger');")
            trigger("${table}_immutable_replace", "BEFORE INSERT", table,
                "SELECT CASE WHEN EXISTS (SELECT 1 FROM $table WHERE $key = NEW.$key) THEN RAISE(ABORT,'Immutable virtual ledger') END;")
        }
        trigger("allocation_journal_ownership", "BEFORE INSERT", "allocation_journals", """
            SELECT CASE WHEN NEW.bookId != (SELECT bookId FROM commands WHERE id = NEW.commandId)
                OR (NEW.linkedJournalId IS NOT NULL AND NEW.bookId != (SELECT bookId FROM journals WHERE id = NEW.linkedJournalId))
                THEN RAISE(ABORT,'Cross-book allocation') END;
        """.trimIndent())
        trigger("allocation_leg_valid", "BEFORE INSERT", "allocation_legs", """
            SELECT CASE WHEN typeof(NEW.minor) != 'integer' OR NEW.minor = 0 OR NEW.minor = (-9223372036854775807 - 1)
                OR EXISTS (SELECT 1 FROM allocation_seals WHERE entryId = NEW.entryId)
                OR (NEW.bucketId IS NOT NULL AND (SELECT bookId FROM reserve_buckets WHERE id = NEW.bucketId) != (SELECT bookId FROM allocation_journals WHERE id = NEW.entryId))
                THEN RAISE(ABORT,'Invalid allocation leg') END;
        """.trimIndent())
        trigger("allocation_seal_balanced", "BEFORE INSERT", "allocation_seals", """
            SELECT CASE WHEN (SELECT COUNT(*) FROM allocation_legs WHERE entryId = NEW.entryId) < 2
                OR (SELECT SUM(minor) FROM allocation_legs WHERE entryId = NEW.entryId) != 0
                OR NOT EXISTS (SELECT 1 FROM allocation_journals j JOIN audit_events a ON a.commandId = j.commandId WHERE j.id = NEW.entryId)
                THEN RAISE(ABORT,'Virtual journal must conserve audited ownership') END;
        """.trimIndent())
        listOf("reserve_buckets" to "id", "monthly_budgets" to "bucketId", "financial_goals" to "bucketId", "financial_profiles" to "bookId").forEach { (table, key) ->
            trigger("${table}_no_replace", "BEFORE INSERT", table, "SELECT CASE WHEN EXISTS (SELECT 1 FROM $table WHERE $key = NEW.$key) THEN RAISE(ABORT,'Do not replace financial metadata') END;")
            trigger("${table}_no_delete", "BEFORE DELETE", table, "SELECT RAISE(ABORT,'Retain financial metadata');")
        }
        trigger("bucket_identity", "BEFORE UPDATE", "reserve_buckets", "SELECT CASE WHEN NEW.id != OLD.id OR NEW.bookId != OLD.bookId OR NEW.kind != OLD.kind OR NEW.category IS NOT OLD.category OR NEW.month IS NOT OLD.month OR NEW.protected != OLD.protected THEN RAISE(ABORT,'Bucket identity is immutable') END;")
    }
}
