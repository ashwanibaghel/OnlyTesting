package com.fintrack.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [AccountEntity::class], version = 1, exportSchema = true)
abstract class FinTrackDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
}
