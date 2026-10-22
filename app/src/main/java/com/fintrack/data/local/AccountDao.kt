package com.fintrack.data.local

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT COUNT(*) FROM accounts")
    fun observeCount(): Flow<Int>
}
