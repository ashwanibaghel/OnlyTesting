package com.fintrack.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object HistorySchema {
    val migration5To6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX index_journals_bookId_effectiveDate_createdAt_id ON journals(bookId,effectiveDate,createdAt,id)")
        }
    }
}
