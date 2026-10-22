package com.fintrack.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** No balance column: money is derived exclusively from sealed journal postings. */
@Entity(tableName = "accounts", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"])], indices = [Index("bookId")])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currencyCode: String,
    val kind: String,
    val archived: Boolean = false,
    val bookId: String,
)
