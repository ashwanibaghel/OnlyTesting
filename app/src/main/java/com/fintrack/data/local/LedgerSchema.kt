package com.fintrack.data.local

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** SQL protection applies equally to freshly created and migrated databases. */
object LedgerSchema {
    val callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = installTriggers(db)
        override fun onOpen(db: SupportSQLiteDatabase) {
            // Configure Android's entire connection pool, not just Room's opening connection.
            db.setForeignKeyConstraintsEnabled(true)
            db.query("PRAGMA foreign_keys").use {
                check(it.moveToFirst() && it.getInt(0) == 1) { "Foreign-key enforcement is required" }
            }
            installTriggers(db)
        }
    }

    val migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.query("SELECT COUNT(*) FROM accounts WHERE currencyCode != 'INR' OR kind NOT IN ('BANK','CASH','INVESTMENT') OR trim(id) = '' OR id GLOB '*[^A-Za-z0-9_-]*' OR length(id) > 100 OR trim(name) = '' OR name != trim(name) OR length(name) > 100 OR archived NOT IN (0,1)").use {
                check(it.moveToFirst() && it.getLong(0) == 0L) { "Legacy metadata is unsupported; migration aborted without data loss" }
            }
            db.execSQL("ALTER TABLE accounts RENAME TO accounts_v1")
            tables.forEach(db::execSQL)
            db.execSQL("INSERT INTO books VALUES ('personal','local-owner','INR',2,0,0)")
            db.execSQL("INSERT INTO accounts (id,name,currencyCode,kind,archived,bookId) SELECT id,name,currencyCode,kind,archived,'personal' FROM accounts_v1")
            db.execSQL("INSERT INTO ledger_accounts SELECT id,'personal',id,'ASSET',name FROM accounts")
            db.execSQL("DROP TABLE accounts_v1")
            installTriggers(db)
        }
    }

    private val tables = listOf(
        "CREATE TABLE IF NOT EXISTS books (id TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, currencyCode TEXT NOT NULL, exponent INTEGER NOT NULL, revision INTEGER NOT NULL, createdAt INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS accounts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, currencyCode TEXT NOT NULL, kind TEXT NOT NULL, archived INTEGER NOT NULL, bookId TEXT NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE INDEX IF NOT EXISTS index_accounts_bookId ON accounts(bookId)",
        "CREATE TABLE IF NOT EXISTS ledger_accounts (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, ownerAccountId TEXT, role TEXT NOT NULL, name TEXT NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(ownerAccountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE INDEX IF NOT EXISTS index_ledger_accounts_bookId ON ledger_accounts(bookId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_ledger_accounts_ownerAccountId ON ledger_accounts(ownerAccountId)",
        "CREATE INDEX IF NOT EXISTS index_ledger_accounts_bookId_role_name ON ledger_accounts(bookId,role,name)",
        "CREATE TABLE IF NOT EXISTS commands (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, fingerprint TEXT NOT NULL, primaryId TEXT NOT NULL, secondaryId TEXT, revision INTEGER NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_commands_bookId_revision ON commands(bookId,revision)",
        "CREATE TABLE IF NOT EXISTS journals (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, commandId TEXT NOT NULL, kind TEXT NOT NULL, effectiveDate TEXT NOT NULL, createdAt INTEGER NOT NULL, clearing TEXT NOT NULL, memo TEXT NOT NULL, reason TEXT NOT NULL, reversalOf TEXT, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(commandId) REFERENCES commands(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(reversalOf) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE INDEX IF NOT EXISTS index_journals_bookId ON journals(bookId)",
        "CREATE INDEX IF NOT EXISTS index_journals_commandId ON journals(commandId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_journals_reversalOf ON journals(reversalOf)",
        "CREATE INDEX IF NOT EXISTS index_journals_effectiveDate ON journals(effectiveDate)",
        "CREATE TABLE IF NOT EXISTS postings (id TEXT NOT NULL PRIMARY KEY, entryId TEXT NOT NULL, ledgerAccountId TEXT NOT NULL, minor INTEGER NOT NULL, FOREIGN KEY(entryId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(ledgerAccountId) REFERENCES ledger_accounts(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE INDEX IF NOT EXISTS index_postings_entryId ON postings(entryId)",
        "CREATE INDEX IF NOT EXISTS index_postings_ledgerAccountId ON postings(ledgerAccountId)",
        "CREATE TABLE IF NOT EXISTS journal_seals (entryId TEXT NOT NULL PRIMARY KEY, FOREIGN KEY(entryId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE TABLE IF NOT EXISTS clearing_events (entryId TEXT NOT NULL PRIMARY KEY, commandId TEXT NOT NULL, clearedDate TEXT NOT NULL, FOREIGN KEY(entryId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(commandId) REFERENCES commands(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_clearing_events_commandId ON clearing_events(commandId)",
        "CREATE TABLE IF NOT EXISTS audit_events (commandId TEXT NOT NULL PRIMARY KEY, action TEXT NOT NULL, reason TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(commandId) REFERENCES commands(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE TABLE IF NOT EXISTS reconciliations (commandId TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, statementDate TEXT NOT NULL, basis TEXT NOT NULL, statementMinor INTEGER NOT NULL, ledgerMinor INTEGER NOT NULL, differenceMinor INTEGER NOT NULL, adjustmentId TEXT, FOREIGN KEY(commandId) REFERENCES commands(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(adjustmentId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE INDEX IF NOT EXISTS index_reconciliations_accountId ON reconciliations(accountId)",
        "CREATE INDEX IF NOT EXISTS index_reconciliations_adjustmentId ON reconciliations(adjustmentId)",
    )

    fun installTriggers(db: SupportSQLiteDatabase) {
        fun trigger(name: String, timing: String, table: String, body: String) {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS $name $timing ON $table BEGIN $body END")
        }
        val immutable = listOf("ledger_accounts", "commands", "journals", "postings", "journal_seals", "clearing_events", "audit_events", "reconciliations")
        immutable.forEach { table ->
            val key = when (table) {
                "journal_seals", "clearing_events" -> "entryId"
                "audit_events", "reconciliations" -> "commandId"
                else -> "id"
            }
            trigger("${table}_no_replace", "BEFORE INSERT", table,
                "SELECT CASE WHEN EXISTS (SELECT 1 FROM $table WHERE $key = NEW.$key) THEN RAISE(ABORT,'Cannot replace an immutable record') END;")
            trigger("${table}_no_update", "BEFORE UPDATE", table, "SELECT RAISE(ABORT,'Immutable ledger record');")
            trigger("${table}_no_delete", "BEFORE DELETE", table, "SELECT RAISE(ABORT,'Immutable ledger record');")
        }
        listOf("books", "accounts").forEach { table ->
            trigger("${table}_no_replace", "BEFORE INSERT", table,
                "SELECT CASE WHEN EXISTS (SELECT 1 FROM $table WHERE id = NEW.id) THEN RAISE(ABORT,'Cannot replace book/account') END;")
        }
        trigger("books_validate", "BEFORE INSERT", "books", "SELECT CASE WHEN NEW.currencyCode != 'INR' OR NEW.exponent != 2 OR NEW.revision != 0 OR trim(NEW.id) = '' OR trim(NEW.ownerId) = '' THEN RAISE(ABORT,'Unsupported book') END;")
        trigger("books_revision", "BEFORE UPDATE", "books", "SELECT CASE WHEN NEW.id != OLD.id OR NEW.ownerId != OLD.ownerId OR NEW.currencyCode != OLD.currencyCode OR NEW.exponent != OLD.exponent OR NEW.createdAt != OLD.createdAt OR typeof(NEW.revision) != 'integer' OR NEW.revision != OLD.revision + 1 THEN RAISE(ABORT,'Invalid book revision or metadata mutation') END;")
        trigger("books_no_delete", "BEFORE DELETE", "books", "SELECT RAISE(ABORT,'Books cannot be deleted');")
        trigger("accounts_validate", "BEFORE INSERT", "accounts", "SELECT CASE WHEN trim(NEW.id) = '' OR trim(NEW.name) = '' OR NEW.kind NOT IN ('BANK','CASH','INVESTMENT') OR NEW.archived NOT IN (0,1) OR NEW.currencyCode != (SELECT currencyCode FROM books WHERE id = NEW.bookId) THEN RAISE(ABORT,'Invalid account') END;")
        trigger("accounts_metadata", "BEFORE UPDATE", "accounts", "SELECT CASE WHEN NEW.id != OLD.id OR NEW.bookId != OLD.bookId OR NEW.name != OLD.name OR NEW.kind != OLD.kind OR NEW.currencyCode != OLD.currencyCode OR NEW.archived NOT IN (0,1) THEN RAISE(ABORT,'Account metadata is immutable except archive state') END;")
        trigger("accounts_no_delete", "BEFORE DELETE", "accounts", "SELECT RAISE(ABORT,'Archive accounts instead of deleting');")
        trigger("ledger_accounts_validate", "BEFORE INSERT", "ledger_accounts", "SELECT CASE WHEN trim(NEW.name) = '' OR NEW.role NOT IN ('ASSET','INCOME','EXPENSE','EQUITY') OR (NEW.role = 'ASSET') != (NEW.ownerAccountId IS NOT NULL) OR (NEW.ownerAccountId IS NOT NULL AND NEW.bookId != (SELECT bookId FROM accounts WHERE id = NEW.ownerAccountId)) THEN RAISE(ABORT,'Invalid ledger account ownership') END;")
        trigger("journals_validate", "BEFORE INSERT", "journals", "SELECT CASE WHEN NEW.bookId != (SELECT bookId FROM commands WHERE id = NEW.commandId) OR NEW.kind NOT IN ('OPENING','INCOME','EXPENSE','TRANSFER','REVERSAL','ADJUSTMENT') OR NEW.clearing NOT IN ('PENDING','CLEARED') OR (NEW.kind = 'REVERSAL') != (NEW.reversalOf IS NOT NULL) OR (NEW.reversalOf IS NOT NULL AND (NEW.bookId != (SELECT bookId FROM journals WHERE id = NEW.reversalOf) OR NOT EXISTS (SELECT 1 FROM journal_seals WHERE entryId = NEW.reversalOf) OR (SELECT kind FROM journals WHERE id = NEW.reversalOf) = 'REVERSAL')) THEN RAISE(ABORT,'Invalid journal relationships') END;")
        trigger("postings_validate", "BEFORE INSERT", "postings", "SELECT CASE WHEN typeof(NEW.minor) != 'integer' OR NEW.minor = 0 OR NEW.minor = (-9223372036854775807 - 1) OR EXISTS (SELECT 1 FROM journal_seals WHERE entryId = NEW.entryId) OR (SELECT bookId FROM journals WHERE id = NEW.entryId) != (SELECT bookId FROM ledger_accounts WHERE id = NEW.ledgerAccountId) OR EXISTS (SELECT 1 FROM ledger_accounts l JOIN accounts a ON a.id = l.ownerAccountId WHERE l.id = NEW.ledgerAccountId AND a.archived = 1) THEN RAISE(ABORT,'Invalid posting or sealed journal') END;")
        trigger("seal_balanced", "BEFORE INSERT", "journal_seals", """
            SELECT CASE WHEN (SELECT COUNT(*) FROM postings WHERE entryId = NEW.entryId) < 2
                OR (SELECT SUM(minor) FROM postings WHERE entryId = NEW.entryId) != 0
                OR (SELECT SUM(CASE WHEN minor > 0 THEN minor ELSE 0 END) FROM postings WHERE entryId = NEW.entryId) > 9223372036854775807
                OR NOT EXISTS (SELECT 1 FROM audit_events a JOIN journals j ON j.commandId = a.commandId WHERE j.id = NEW.entryId)
                THEN RAISE(ABORT,'Journal must have balanced, bounded, audited postings') END;
            SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM postings p JOIN ledger_accounts l ON l.id = p.ledgerAccountId WHERE p.entryId = NEW.entryId AND l.role = 'ASSET') THEN RAISE(ABORT,'Journal requires an owned asset account') END;
            SELECT CASE WHEN (SELECT kind FROM journals WHERE id = NEW.entryId) IN ('INCOME','EXPENSE') AND (
                (SELECT COUNT(*) FROM postings p JOIN ledger_accounts l ON l.id = p.ledgerAccountId WHERE p.entryId = NEW.entryId AND l.role = 'ASSET') != 1
                OR EXISTS (SELECT 1 FROM postings p JOIN ledger_accounts l ON l.id = p.ledgerAccountId JOIN journals j ON j.id = p.entryId WHERE p.entryId = NEW.entryId AND (
                    (j.kind = 'INCOME' AND ((l.role = 'ASSET' AND p.minor < 0) OR (l.role != 'ASSET' AND (l.role != 'INCOME' OR p.minor > 0))))
                    OR (j.kind = 'EXPENSE' AND ((l.role = 'ASSET' AND p.minor > 0) OR (l.role != 'ASSET' AND (l.role != 'EXPENSE' OR p.minor < 0)))))))
                THEN RAISE(ABORT,'Invalid income or expense classification') END;
            SELECT CASE WHEN (SELECT kind FROM journals WHERE id = NEW.entryId) = 'TRANSFER' AND (
                (SELECT COUNT(DISTINCT p.ledgerAccountId) FROM postings p JOIN ledger_accounts l ON l.id = p.ledgerAccountId WHERE p.entryId = NEW.entryId AND l.role = 'ASSET') != 2
                OR (SELECT COUNT(*) FROM postings p JOIN ledger_accounts l ON l.id = p.ledgerAccountId WHERE p.entryId = NEW.entryId AND l.role = 'ASSET' AND p.minor > 0) != 1
                OR (SELECT COUNT(*) FROM postings p JOIN ledger_accounts l ON l.id = p.ledgerAccountId WHERE p.entryId = NEW.entryId AND l.role = 'ASSET' AND p.minor < 0) != 1
                OR EXISTS (SELECT 1 FROM postings p JOIN ledger_accounts l ON l.id = p.ledgerAccountId WHERE p.entryId = NEW.entryId AND l.role != 'ASSET' AND (l.role != 'EXPENSE' OR p.minor < 0)))
                THEN RAISE(ABORT,'Transfer requires distinct asset accounts and expense-only fees') END;
            SELECT CASE WHEN (SELECT kind FROM journals WHERE id = NEW.entryId) IN ('OPENING','ADJUSTMENT') AND (
                (SELECT COUNT(*) FROM postings WHERE entryId = NEW.entryId) != 2
                OR (SELECT COUNT(*) FROM postings p JOIN ledger_accounts l ON l.id = p.ledgerAccountId WHERE p.entryId = NEW.entryId AND l.role = 'ASSET') != 1
                OR (SELECT COUNT(*) FROM postings p JOIN ledger_accounts l ON l.id = p.ledgerAccountId WHERE p.entryId = NEW.entryId AND l.role = 'EQUITY') != 1)
                THEN RAISE(ABORT,'Opening and adjustment require asset/equity legs') END;
            SELECT CASE WHEN (SELECT kind FROM journals WHERE id = NEW.entryId) = 'REVERSAL' AND (
                EXISTS (SELECT ledgerAccountId, -minor, COUNT(*) FROM postings WHERE entryId = (SELECT reversalOf FROM journals WHERE id = NEW.entryId) GROUP BY ledgerAccountId, minor EXCEPT SELECT ledgerAccountId, minor, COUNT(*) FROM postings WHERE entryId = NEW.entryId GROUP BY ledgerAccountId, minor)
                OR EXISTS (SELECT ledgerAccountId, minor, COUNT(*) FROM postings WHERE entryId = NEW.entryId GROUP BY ledgerAccountId, minor EXCEPT SELECT ledgerAccountId, -minor, COUNT(*) FROM postings WHERE entryId = (SELECT reversalOf FROM journals WHERE id = NEW.entryId) GROUP BY ledgerAccountId, minor))
                THEN RAISE(ABORT,'Reversal must exactly mirror original legs') END;
        """.trimIndent())
        trigger("clearing_validate", "BEFORE INSERT", "clearing_events", "SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM journal_seals WHERE entryId = NEW.entryId) OR (SELECT clearing FROM journals WHERE id = NEW.entryId) != 'PENDING' OR (SELECT kind FROM journals WHERE id = NEW.entryId) = 'REVERSAL' OR EXISTS (SELECT 1 FROM journals WHERE reversalOf = NEW.entryId) OR NEW.clearedDate < (SELECT effectiveDate FROM journals WHERE id = NEW.entryId) OR (SELECT bookId FROM commands WHERE id = NEW.commandId) != (SELECT bookId FROM journals WHERE id = NEW.entryId) THEN RAISE(ABORT,'Invalid clearing event') END;")
    }
}
