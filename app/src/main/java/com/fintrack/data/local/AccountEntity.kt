package com.fintrack.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Metadata only. Balances will be derived from the future immutable journal. */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currencyCode: String,
    val kind: String,
    val archived: Boolean = false,
)
