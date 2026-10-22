package com.fintrack.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ScheduleSchema {
    val migration3To4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) { tables.forEach(db::execSQL); installTriggers(db) }
    }
    private val tables = listOf(
        "CREATE TABLE IF NOT EXISTS scheduled_rules (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, commandId TEXT NOT NULL, name TEXT NOT NULL, template TEXT NOT NULL, startDate TEXT NOT NULL, frequency TEXT NOT NULL, interval INTEGER NOT NULL, endDate TEXT, mandatory INTEGER NOT NULL, stopDate TEXT, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(commandId) REFERENCES commands(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE INDEX IF NOT EXISTS index_scheduled_rules_bookId ON scheduled_rules(bookId)",
        "CREATE INDEX IF NOT EXISTS index_scheduled_rules_commandId ON scheduled_rules(commandId)",
        "CREATE TABLE IF NOT EXISTS schedule_exceptions (ruleId TEXT NOT NULL, date TEXT NOT NULL, PRIMARY KEY(ruleId,date), FOREIGN KEY(ruleId) REFERENCES scheduled_rules(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE TABLE IF NOT EXISTS schedule_occurrences (id TEXT NOT NULL PRIMARY KEY, ruleId TEXT NOT NULL, scheduledDate TEXT NOT NULL, commandId TEXT NOT NULL, status TEXT NOT NULL, journalId TEXT, reason TEXT NOT NULL, confirmedTemplate TEXT, FOREIGN KEY(ruleId) REFERENCES scheduled_rules(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(commandId) REFERENCES commands(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(journalId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_schedule_occurrences_ruleId_scheduledDate ON schedule_occurrences(ruleId,scheduledDate)",
        "CREATE INDEX IF NOT EXISTS index_schedule_occurrences_commandId ON schedule_occurrences(commandId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_schedule_occurrences_journalId ON schedule_occurrences(journalId)",
        "CREATE TABLE IF NOT EXISTS occurrence_resolutions (id TEXT NOT NULL PRIMARY KEY, occurrenceId TEXT NOT NULL, commandId TEXT NOT NULL, status TEXT NOT NULL, journalId TEXT, confirmedTemplate TEXT, reason TEXT NOT NULL, FOREIGN KEY(occurrenceId) REFERENCES schedule_occurrences(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(commandId) REFERENCES commands(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(journalId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE INDEX IF NOT EXISTS index_occurrence_resolutions_occurrenceId ON occurrence_resolutions(occurrenceId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_occurrence_resolutions_commandId ON occurrence_resolutions(commandId)",
        "CREATE INDEX IF NOT EXISTS index_occurrence_resolutions_journalId ON occurrence_resolutions(journalId)",
        "CREATE TABLE IF NOT EXISTS investment_contributions (journalId TEXT NOT NULL PRIMARY KEY, bucketId TEXT NOT NULL, investmentAccountId TEXT NOT NULL, minor INTEGER NOT NULL, date TEXT NOT NULL, reversalOf TEXT, FOREIGN KEY(journalId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(bucketId) REFERENCES reserve_buckets(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(investmentAccountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(reversalOf) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE INDEX IF NOT EXISTS index_investment_contributions_bucketId ON investment_contributions(bucketId)",
        "CREATE INDEX IF NOT EXISTS index_investment_contributions_investmentAccountId ON investment_contributions(investmentAccountId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_investment_contributions_reversalOf ON investment_contributions(reversalOf)",
        "CREATE TABLE IF NOT EXISTS weekly_reviews (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, commandId TEXT NOT NULL, weekOf TEXT NOT NULL, reviewedAt INTEGER NOT NULL, note TEXT NOT NULL, revision INTEGER NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(commandId) REFERENCES commands(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_weekly_reviews_bookId_weekOf ON weekly_reviews(bookId,weekOf)",
        "CREATE INDEX IF NOT EXISTS index_weekly_reviews_commandId ON weekly_reviews(commandId)",
    )

    fun installTriggers(db: SupportSQLiteDatabase) {
        fun trigger(name: String, timing: String, table: String, body: String) =
            db.execSQL("CREATE TRIGGER IF NOT EXISTS $name $timing ON $table BEGIN $body END")
        listOf("schedule_exceptions" to "ruleId = NEW.ruleId AND date = NEW.date", "schedule_occurrences" to "id = NEW.id",
            "occurrence_resolutions" to "id = NEW.id", "investment_contributions" to "journalId = NEW.journalId", "weekly_reviews" to "id = NEW.id").forEach { (table, key) ->
            trigger("${table}_no_update", "BEFORE UPDATE", table, "SELECT RAISE(ABORT,'Immutable financial planning history');")
            trigger("${table}_no_delete", "BEFORE DELETE", table, "SELECT RAISE(ABORT,'Immutable financial planning history');")
            trigger("${table}_no_replace", "BEFORE INSERT", table, "SELECT CASE WHEN EXISTS (SELECT 1 FROM $table WHERE $key) THEN RAISE(ABORT,'Cannot replace financial planning history') END;")
        }
        trigger("schedule_rule_validate", "BEFORE INSERT", "scheduled_rules", """
            SELECT CASE WHEN NEW.bookId != (SELECT bookId FROM commands WHERE id = NEW.commandId)
                OR trim(NEW.name) = '' OR NEW.frequency NOT IN ('DAILY','WEEKLY','MONTHLY','YEARLY') OR NEW.interval < 1 OR NEW.interval > 365
                OR NEW.mandatory NOT IN (0,1) OR (NEW.endDate IS NOT NULL AND NEW.endDate < NEW.startDate)
                OR EXISTS (SELECT 1 FROM scheduled_rules WHERE id = NEW.id)
                THEN RAISE(ABORT,'Invalid recurring rule') END;
        """.trimIndent())
        trigger("schedule_rule_identity", "BEFORE UPDATE", "scheduled_rules", """
            SELECT CASE WHEN NEW.id != OLD.id OR NEW.bookId != OLD.bookId OR NEW.commandId != OLD.commandId
                OR NEW.name != OLD.name OR NEW.template != OLD.template OR NEW.startDate != OLD.startDate OR NEW.frequency != OLD.frequency
                OR NEW.interval != OLD.interval OR NEW.endDate IS NOT OLD.endDate OR NEW.mandatory != OLD.mandatory
                OR NEW.stopDate IS NULL OR (OLD.stopDate IS NOT NULL AND NEW.stopDate > OLD.stopDate)
                THEN RAISE(ABORT,'Retain original recurring rule') END;
        """.trimIndent())
        trigger("schedule_rule_no_delete", "BEFORE DELETE", "scheduled_rules", "SELECT RAISE(ABORT,'Stop schedules instead of deleting history');")
        trigger("schedule_occurrence_valid", "BEFORE INSERT", "schedule_occurrences", """
            SELECT CASE WHEN NEW.status NOT IN ('CONFIRMED','SKIPPED') OR (NEW.status = 'CONFIRMED') != (NEW.journalId IS NOT NULL)
                OR (NEW.status = 'CONFIRMED') != (NEW.confirmedTemplate IS NOT NULL)
                OR (SELECT bookId FROM commands WHERE id = NEW.commandId) != (SELECT bookId FROM scheduled_rules WHERE id = NEW.ruleId)
                OR (NEW.journalId IS NOT NULL AND (((SELECT commandId FROM journals WHERE id = NEW.journalId) != NEW.commandId AND (SELECT action FROM audit_events WHERE commandId = NEW.commandId) != 'Schedule.LinkExisting')
                    OR (SELECT bookId FROM journals WHERE id = NEW.journalId) != (SELECT bookId FROM scheduled_rules WHERE id = NEW.ruleId)
                    OR NOT EXISTS (SELECT 1 FROM journal_seals WHERE entryId = NEW.journalId)))
                THEN RAISE(ABORT,'Invalid occurrence confirmation') END;
        """.trimIndent())
        trigger("occurrence_resolution_valid", "BEFORE INSERT", "occurrence_resolutions", """
            SELECT CASE WHEN NEW.status NOT IN ('CONFIRMED','SKIPPED','REOPENED')
                OR (NEW.status = 'CONFIRMED' AND (NEW.journalId IS NULL OR NEW.confirmedTemplate IS NULL))
                OR (SELECT bookId FROM commands WHERE id = NEW.commandId) != (SELECT r.bookId FROM schedule_occurrences o JOIN scheduled_rules r ON r.id = o.ruleId WHERE o.id = NEW.occurrenceId)
                OR (NEW.journalId IS NOT NULL AND (((SELECT commandId FROM journals WHERE id = NEW.journalId) != NEW.commandId AND (SELECT action FROM audit_events WHERE commandId = NEW.commandId) != 'Schedule.LinkExisting')
                    OR (SELECT bookId FROM journals WHERE id = NEW.journalId) != (SELECT bookId FROM commands WHERE id = NEW.commandId)
                    OR NOT EXISTS (SELECT 1 FROM journal_seals WHERE entryId = NEW.journalId)))
                THEN RAISE(ABORT,'Invalid occurrence resolution') END;
        """.trimIndent())
        trigger("contribution_valid", "BEFORE INSERT", "investment_contributions", """
            SELECT CASE WHEN typeof(NEW.minor) != 'integer' OR NEW.minor = 0
                OR (NEW.reversalOf IS NULL AND NEW.minor < 0) OR (NEW.reversalOf IS NOT NULL AND NEW.minor > 0)
                OR (SELECT kind FROM reserve_buckets WHERE id = NEW.bucketId) != 'INVESTMENT'
                OR (SELECT kind FROM accounts WHERE id = NEW.investmentAccountId) != 'INVESTMENT'
                OR (SELECT bookId FROM journals WHERE id = NEW.journalId) != (SELECT bookId FROM reserve_buckets WHERE id = NEW.bucketId)
                OR (SELECT bookId FROM accounts WHERE id = NEW.investmentAccountId) != (SELECT bookId FROM reserve_buckets WHERE id = NEW.bucketId)
                OR NOT EXISTS (SELECT 1 FROM journal_seals WHERE entryId = NEW.journalId)
                OR NOT EXISTS (SELECT 1 FROM postings WHERE entryId = NEW.journalId AND ledgerAccountId = NEW.investmentAccountId AND minor = NEW.minor)
                OR (NEW.reversalOf IS NOT NULL AND (SELECT reversalOf FROM journals WHERE id = NEW.journalId) IS NOT NEW.reversalOf)
                THEN RAISE(ABORT,'Invalid contribution linkage') END;
        """.trimIndent())
        // V4 adds metadata checks without silently changing the V3 trigger contract.
        listOf("INSERT", "UPDATE").forEach { operation ->
            trigger("v4_budget_${operation.lowercase()}", "BEFORE $operation", "monthly_budgets", "SELECT CASE WHEN typeof(NEW.limitMinor) != 'integer' OR typeof(NEW.carriedMinor) != 'integer' OR NEW.limitMinor < 0 OR NEW.carriedMinor < 0 OR NEW.rollover NOT IN ('OFF','POSITIVE_REMAINDER') OR NEW.closed NOT IN (0,1) OR (SELECT kind FROM reserve_buckets WHERE id = NEW.bucketId) != 'CATEGORY' THEN RAISE(ABORT,'Invalid budget metadata') END;")
            trigger("v4_goal_${operation.lowercase()}", "BEFORE $operation", "financial_goals", "SELECT CASE WHEN typeof(NEW.targetMinor) != 'integer' OR typeof(NEW.monthlyMinor) != 'integer' OR NEW.targetMinor <= 0 OR NEW.monthlyMinor < 0 OR NEW.priority < 1 OR NEW.priority > 100 OR NEW.anchorDay < 1 OR NEW.anchorDay > 31 OR (SELECT kind FROM reserve_buckets WHERE id = NEW.bucketId) NOT IN ('GOAL','INVESTMENT') THEN RAISE(ABORT,'Invalid goal metadata') END;")
            trigger("v4_profile_${operation.lowercase()}", "BEFORE $operation", "financial_profiles", "SELECT CASE WHEN typeof(NEW.safetyBufferMinor) != 'integer' OR typeof(NEW.monthlyCapacityMinor) != 'integer' OR NEW.safetyBufferMinor < 0 OR NEW.monthlyCapacityMinor < 0 OR NEW.horizonDays < 1 OR NEW.horizonDays > 366 OR NEW.setupComplete NOT IN (0,1) OR trim(NEW.timezone) = '' THEN RAISE(ABORT,'Invalid profile metadata') END;")
        }
        trigger("v4_bucket_validate", "BEFORE INSERT", "reserve_buckets", "SELECT CASE WHEN trim(NEW.name) = '' OR NEW.kind NOT IN ('CATEGORY','SAVINGS','EMERGENCY','GOAL','INVESTMENT') OR NEW.archived NOT IN (0,1) OR (NEW.kind = 'CATEGORY') != (NEW.category IS NOT NULL AND NEW.month IS NOT NULL) OR NEW.protected != CASE WHEN NEW.kind = 'CATEGORY' THEN 0 ELSE 1 END THEN RAISE(ABORT,'Invalid reserve metadata') END;")
    }
}
