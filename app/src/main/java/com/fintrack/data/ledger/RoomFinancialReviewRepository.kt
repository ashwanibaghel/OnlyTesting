package com.fintrack.data.ledger

import androidx.room.withTransaction
import com.fintrack.data.local.FinTrackDatabase
import com.fintrack.data.planning.RoomPlanningStore
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.BucketKind
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Runs the actual command implementation, with the entire preview transaction rolled back. */
class RoomFinancialReviewRepository(private val db: FinTrackDatabase,
    private val ledger: LedgerRepository, private val clock: Clock) : FinancialReviewRepository {
    private val planning = RoomPlanningStore(db, clock)
    private class PreviewRollback(val result: FinancialCommandReview) : RuntimeException(null, null, false, false)

    private suspend fun position(book: String): ReviewedFinancialPosition {
        val zone = db.planningDao().profile(book)?.timezone?.let(ZoneId::of) ?: clock.zone
        val current = planning.read(ledger.snapshot(book), book, LocalDate.now(clock.withZone(zone)))
        val reserves = current.buckets.map {
            val identity = if (it.kind == BucketKind.CATEGORY) "category:${it.category!!.length}:${it.category}:${it.month}" else "bucket:${it.id}"
            ReviewedReservePosition(identity, it.name, it.kind.name, it.protected, it.funded, it.archived, it.backing)
        }.sortedBy { it.identity }
        val budgets = current.budgets.map { ReviewedBudgetPosition(it.category, it.month, it.limit, it.carried,
            it.rollover, it.closed, it.position, it.funded, it.backing) }.sortedWith(compareBy<ReviewedBudgetPosition> { it.month }.thenBy { it.category })
        return ReviewedFinancialPosition(current.ledger.revision, current.asOf,
            current.ledger.accounts.sortedBy { it.id }, current.ledger.liquid, current.ledger.income,
            current.ledger.expense, current.ledger.history.size, current.profile, reserves, budgets,
            current.reservations, current.safeToSpend, current.uncoveredObligations,
            current.goalFunding.sortedBy { it.bucketId }, current.goalCollisions, current.goalFundingAssumptions, current.investmentBacking)
    }

    private suspend fun saved(command: LedgerCommand, hash: String): CommandReceipt? {
        val stored = db.ledgerDao().command(command.id) ?: return null
        require(stored.bookId == command.bookId && stored.fingerprint == hash) { "Command key already used with a different payload" }
        return CommandReceipt(stored.id, stored.primaryId, stored.secondaryId, stored.revision)
    }

    override suspend fun preview(command: LedgerCommand): FinancialCommandReview {
        val captured = command.copy(action = command.action.detached())
        val hash = fingerprint(captured)
        return try {
            db.withTransaction {
                val previous = saved(captured, hash)
                val before = position(captured.bookId)
                if (previous != null) throw PreviewRollback(FinancialCommandReview(captured, hash, before, before, previous))
                check(before.revision == captured.expectedRevision) { "Financial state changed; refresh and review again" }
                ledger.execute(captured)
                val after = position(captured.bookId)
                // Throwing through the OUTER transaction rolls back nested execute, commands, audits and all metadata.
                throw PreviewRollback(FinancialCommandReview(captured, hash, before, after, null))
            }
        } catch (rollback: PreviewRollback) { rollback.result }
    }

    override suspend fun apply(review: FinancialCommandReview): CommandReceipt {
        val captured = review.command.copy(action = review.command.action.detached())
        val hash = fingerprint(captured)
        require(hash == review.fingerprint) { "Reviewed command values changed" }
        return db.withTransaction {
            saved(captured, hash)?.let { return@withTransaction it }
            check(review.alreadySaved == null) { "Reviewed receipt no longer exists; refresh storage" }
            check(position(captured.bookId) == review.before && review.before.revision == captured.expectedRevision) {
                "Financial state or book date changed; refresh and review again"
            }
            val receipt = ledger.execute(captured)
            check(position(captured.bookId) == review.after) { "Financial outcome changed; transaction rolled back for a fresh review" }
            receipt
        }
    }
}
