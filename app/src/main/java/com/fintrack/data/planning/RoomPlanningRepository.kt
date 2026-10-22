package com.fintrack.data.planning

import androidx.room.withTransaction
import com.fintrack.data.local.FinTrackDatabase
import com.fintrack.domain.ledger.LedgerRepository
import com.fintrack.domain.planning.PlanningRepository
import com.fintrack.engine.Money
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.map

class RoomPlanningRepository(private val db: FinTrackDatabase, private val ledger: LedgerRepository,
    clock: Clock) : PlanningRepository {
    private val store = RoomPlanningStore(db, clock)
    override suspend fun snapshot(bookId: String, asOf: LocalDate) = db.withTransaction {
        store.read(ledger.snapshot(bookId), bookId, asOf)
    }
    override fun observe(bookId: String, asOf: LocalDate) = db.ledgerDao().revision(bookId).map { snapshot(bookId, asOf) }
    override suspend fun previewDiversion(bookId: String, sourceId: String, destinationId: String?,
        amount: Money, asOf: LocalDate) = db.withTransaction {
        store.diversion(snapshot(bookId, asOf), sourceId, destinationId, amount)
    }
}
