package com.fintrack.data.ledger

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.PlanningAction
import com.fintrack.engine.Money
import java.time.*
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomLedgerScalingTest {
    private fun m(value: Long) = Money(value, INR)
    private fun uid() = UUID.randomUUID().toString()

    @Test fun largeAccountBookPreservesSnapshotsAndReceipts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val file = "q-${uid().take(16)}.db"
        val db = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
            .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
        try {
            val today = LocalDate.of(2026, 10, 23)
            val ledger = RoomLedgerRepository(db, Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC))
            ledger.initializeBook()
            val setup = LedgerCommand(uid(), DEFAULT_BOOK, 0, LedgerAction.Setup((0 until 1000).map { index ->
                LedgerAction.CreateAccount("acct_$index", "Account $index", when {
                    index % 5 == 0 -> AccountKind.INVESTMENT
                    index % 2 == 0 -> AccountKind.BANK
                    else -> AccountKind.CASH
                }, m(100), today.minusDays(30))
            }, emptyList(), PlanningAction.Configure("UTC", m(0), m(0), 30, true)))
            lateinit var setupReceipt: CommandReceipt
            val setupMs = measureTimeMillis { setupReceipt = ledger.execute(setup) }
            suspend fun send(action: LedgerAction) = ledger.execute(LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision, action))
            val paid = send(LedgerAction.Post(TransactionDraft.Expense("acct_1", m(10),
                (1..10).map { Split("Category $it", m(1)) }, today, Clearing.PENDING)))
            val transfer = send(LedgerAction.Post(TransactionDraft.Transfer("acct_2", "acct_5", m(7), m(2), today, Clearing.PENDING)))
            send(LedgerAction.Reverse(paid.primaryId, today, "Cancelled pending split expense"))
            val pending = ledger.snapshot()
            assertEquals(m(79991), pending.liquid)
            assertEquals(m(100000), com.fintrack.engine.sumMoney(pending.accounts.map { it.cleared }, INR))
            assertEquals(m(100), pending.accounts.single { it.id == "acct_1" }.posted)
            send(LedgerAction.Clear(transfer.primaryId, today)); send(LedgerAction.Archive("acct_999", true))
            val expected = ledger.snapshot()
            assertEquals(1000, expected.accounts.size); assertEquals(1003, expected.history.size)
            assertEquals(m(79991), expected.liquid); assertEquals(m(0), expected.income); assertEquals(m(2), expected.expense)
            assertEquals(m(99998), com.fintrack.engine.sumMoney(expected.accounts.map { it.posted }, INR))
            assertTrue(expected.accounts.all { it.posted == it.cleared }); assertTrue(expected.accounts.single { it.id == "acct_999" }.archived)
            assertEquals(m(91), expected.accounts.single { it.id == "acct_2" }.posted)
            assertEquals(m(107), expected.accounts.single { it.id == "acct_5" }.posted)
            assertEquals(paid.primaryId, expected.history.single { it.kind == "REVERSAL" }.reversalOf)
            val samples = List(5) { measureTimeMillis { assertEquals(expected, ledger.snapshot()) } }
            assertEquals(setupReceipt, ledger.execute(setup)); assertEquals(expected, ledger.snapshot())
            println("HOST NATIVE SQLITE SCALING: accounts=1000 journals=1003 setupMs=$setupMs snapshotMs=$samples medianMs=${samples.sorted()[2]}")
        } finally { db.close(); context.deleteDatabase(file) }
    }

    @Test fun indexedControlLookupRetainsOriginalIdentityAndBookIsolation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val file = "q-${uid().take(16)}.db"
        val db = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
            .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
        try {
            val today = LocalDate.of(2026, 10, 23)
            val ledger = RoomLedgerRepository(db, Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC))
            ledger.initializeBook(); ledger.initializeBook("other")
            suspend fun send(action: LedgerAction) = ledger.execute(LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision, action))
            send(LedgerAction.CreateAccount("bank", "Bank", AccountKind.BANK, m(100), today))
            // Explicit legacy-metadata fixture: a foreign name match created before this book's category.
            db.ledgerDao().insertLedgerAccount(LedgerAccountEntity("foreign-food", "other", null, "EXPENSE", "Food"))
            val first = send(LedgerAction.Post(TransactionDraft.Expense("bank", m(10), listOf(Split("Food", m(10))), today)))
            val original = ledger.snapshot().history.single { it.id == first.primaryId }.legs.single { it.role == LedgerRole.EXPENSE }.accountId
            // Duplicate legacy labels must continue using the first stored control, rather than a lexically smaller ID.
            db.ledgerDao().insertLedgerAccount(LedgerAccountEntity("000-duplicate", DEFAULT_BOOK, null, "EXPENSE", "Food"))
            val next = send(LedgerAction.Post(TransactionDraft.Expense("bank", m(1), listOf(Split("Food", m(1))), today)))
            val snapshot = ledger.snapshot()
            assertEquals(original, snapshot.history.single { it.id == next.primaryId }.legs.single { it.role == LedgerRole.EXPENSE }.accountId)
            assertEquals(m(89), snapshot.liquid); assertEquals(m(11), snapshot.expense)
            assertEquals(2, snapshot.history.count { it.kind == "EXPENSE" })
        } finally { db.close(); context.deleteDatabase(file) }
    }
}
