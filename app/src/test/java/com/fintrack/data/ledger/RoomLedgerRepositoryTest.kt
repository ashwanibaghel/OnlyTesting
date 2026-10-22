package com.fintrack.data.ledger

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.engine.CurrencySpec
import com.fintrack.engine.Money
import com.fintrack.ui.ledger.LedgerViewModel
import com.fintrack.ui.ledger.ReconciliationInputs
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomLedgerRepositoryTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var repository: RoomLedgerRepository
    private fun uid() = UUID.randomUUID().toString()
    private fun m(value: Long) = Money(value, INR)
    private fun scenario(block: suspend () -> Unit) = runBlocking { block() }

    private fun open(): FinTrackDatabase = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
        .addMigrations(LedgerSchema.migration1To2, PlanningSchema.migration2To3).addCallback(LedgerSchema.callback).build()

    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext()
        file = "ledger-${uid()}.db"
        db = open()
        repository = RoomLedgerRepository(db, clock)
        repository.initializeBook()
    }
    @After fun teardown() { db.close(); context.deleteDatabase(file) }

    private suspend fun send(action: LedgerAction, key: String = uid(), revision: Long? = null,
        book: String = DEFAULT_BOOK): CommandReceipt = repository.execute(LedgerCommand(key, book,
        revision ?: repository.snapshot(book).revision, action))

    private suspend fun create(id: String = "bank", opening: Long = 0, kind: AccountKind = AccountKind.BANK,
        book: String = DEFAULT_BOOK, name: String = id): CommandReceipt =
        send(LedgerAction.CreateAccount(id, name, kind, m(opening), today.minusDays(10)), book = book)

    private fun income(account: String = "bank", amount: Long = 100, date: LocalDate = today,
        clearing: Clearing = Clearing.CLEARED) = LedgerAction.Post(TransactionDraft.Income(account, m(amount), "Salary", date, clearing, "Monthly salary"))
    private fun expense(amount: Long = 50, clearing: Clearing = Clearing.CLEARED, date: LocalDate = today) =
        LedgerAction.Post(TransactionDraft.Expense("bank", m(amount), listOf(Split("Food", m(amount))), date, clearing))
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected rejection")
    }
    private fun count(table: String): Long = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        check(it.moveToFirst()); it.getLong(0)
    }
    private suspend fun balance(id: String = "bank") = repository.snapshot().accounts.single { it.id == id }

    /** Simulates a committed transaction whose acknowledgement never reached the screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun lostAcknowledgementScenario(reconcile: Boolean) {
        create()
        val attempts = mutableListOf<LedgerCommand>()
        var loseAcknowledgement = true
        val unreliable = object : LedgerRepository by repository {
            override suspend fun execute(command: LedgerCommand): CommandReceipt {
                attempts += command
                val receipt = repository.execute(command)
                if (loseAcknowledgement) {
                    loseAcknowledgement = false
                    error("Acknowledgement lost after commit")
                }
                return receipt
            }
        }
        val store = ViewModelStore()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val vm = LedgerViewModel(unreliable)
            store.put("ledger", vm)
            withTimeout(10_000) { vm.state.first { it.snapshot != null } }
            val inputs = ReconciliationInputs("bank", today.toString(), "1.00", BalanceBasis.CLEARED)
            if (reconcile) {
                vm.review(inputs)
                val reviewed = withTimeout(10_000) { vm.state.first { !it.busy && it.review != null } }.review!!
                vm.applyReview(reviewed.id, inputs, "Checked statement")
                vm.applyReview(reviewed.id, inputs, "Checked statement")
            } else vm.submit("income-ui") { income() }
            val failed = withTimeout(10_000) { vm.state.first { !it.busy && it.retryCommand != null } }.retryCommand!!
            val committed = repository.snapshot()
            vm.retry(failed.id)
            vm.retry(failed.id)
            withTimeout(10_000) { vm.state.first { !it.busy && it.error == null } }
            vm.retry(failed.id)
            assertEquals(listOf(failed, failed), attempts)
            assertEquals(committed, repository.snapshot())
            assertEquals(1, committed.history.size)
            assertEquals(m(100), committed.accounts.single().posted)
            assertEquals(2L, count("commands")) // Account creation plus exactly one financial command.
            assertEquals(1L, count("journals"))
            assertEquals(2L, count("postings"))
            assertEquals(if (reconcile) 1L else 0L, count("reconciliations"))
            if (reconcile) {
                assertEquals(m(0), committed.income)
                assertEquals(LedgerAction.Reconcile("bank", today, m(100), BalanceBasis.CLEARED,
                    "Checked statement"), failed.action)
            } else assertEquals(m(100), committed.income)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun viewModelIncomeRetryAfterCommittedFailureDoesNotDuplicatePostings() = scenario {
        lostAcknowledgementScenario(reconcile = false)
    }

    @Test fun viewModelReconciliationRetryAfterCommittedFailureDoesNotDuplicatePostings() = scenario {
        lostAcknowledgementScenario(reconcile = true)
    }

    @Test fun acceptanceScenarioPersistsAndReversesWithoutInflatedReports() = scenario {
        create("bank", 2_000_000, name = "SBI")
        create("cash", kind = AccountKind.CASH, name = "Cash")
        send(income(amount = 3_000_000))
        val purchase = send(expense(500_000))
        send(LedgerAction.Post(TransactionDraft.Transfer("bank", "cash", m(1_000_000), date = today)))
        val result = repository.snapshot()
        assertEquals(3_500_000L, balance().posted.minor)
        assertEquals(1_000_000L, balance("cash").posted.minor)
        assertEquals(m(4_500_000), result.liquid)
        assertEquals(m(3_000_000), result.income)
        assertEquals(m(500_000), result.expense)
        send(LedgerAction.Reverse(purchase.primaryId, today, "Purchase cancelled"))
        val corrected = repository.snapshot()
        assertEquals(m(4_000_000), balance().posted)
        assertEquals(m(0), corrected.expense)
        assertEquals(m(3_000_000), corrected.income)
        db.close(); db = open(); repository = RoomLedgerRepository(db, clock)
        assertEquals(corrected, repository.snapshot())
    }

    @Test fun openingUsesEquityAndZeroOpeningHasNoZeroJournal() = scenario {
        create(opening = 200)
        create("cash", kind = AccountKind.CASH)
        val snapshot = repository.snapshot()
        assertEquals(1, snapshot.history.size)
        assertEquals("OPENING", snapshot.history.single().kind)
        assertEquals(setOf(LedgerRole.ASSET, LedgerRole.EQUITY), snapshot.history.single().legs.map { it.role }.toSet())
        assertEquals(m(0), snapshot.income); assertEquals(m(0), snapshot.expense)
        assertEquals(2L, count("audit_events"))
    }

    @Test fun eachPersistedJournalConservesMoney() = scenario {
        create(opening = 10000); send(income()); send(expense())
        repository.snapshot().history.forEach { entry ->
            assertTrue(entry.legs.size >= 2)
            assertEquals(0L, entry.legs.sumOf { it.amount.minor })
        }
    }

    @Test fun transferPrincipalDoesNotBecomeIncomeOrExpense() = scenario {
        create(opening = 1000); create("cash", kind = AccountKind.CASH)
        send(LedgerAction.Post(TransactionDraft.Transfer("bank", "cash", m(400), date = today)))
        assertEquals(m(600), balance().posted); assertEquals(m(400), balance("cash").posted)
        assertEquals(m(1000), repository.snapshot().liquid)
        assertEquals(m(0), repository.snapshot().income); assertEquals(m(0), repository.snapshot().expense)
    }

    @Test fun transferFeeIsSingleExpenseAndReversalCancelsIt() = scenario {
        create(opening = 1000); create("cash", kind = AccountKind.CASH)
        val transfer = send(LedgerAction.Post(TransactionDraft.Transfer("bank", "cash", m(400), m(25), today)))
        assertEquals(m(575), balance().posted); assertEquals(m(400), balance("cash").posted)
        assertEquals(m(975), repository.snapshot().liquid); assertEquals(m(25), repository.snapshot().expense)
        send(LedgerAction.Reverse(transfer.primaryId, today, "Transfer rejected"))
        assertEquals(m(1000), balance().posted); assertEquals(m(0), balance("cash").posted)
        assertEquals(m(0), repository.snapshot().expense)
    }

    @Test fun splitsPersistDistinctCategoriesAndRejectOffByOneAtomically() = scenario {
        create(opening = 1000)
        send(LedgerAction.Post(TransactionDraft.Expense("bank", m(101), listOf(Split("Food", m(50)), Split("Travel", m(51))), today)))
        assertEquals(m(101), repository.snapshot().expense)
        val before = repository.snapshot(); val commands = count("commands")
        rejects { send(LedgerAction.Post(TransactionDraft.Expense("bank", m(101), listOf(Split("Food", m(50)), Split("Travel", m(50))), today))) }
        assertEquals(before, repository.snapshot()); assertEquals(commands, count("commands"))
    }

    @Test fun identicalCommandRetryReturnsOriginalReceiptAfterLaterRevision() = scenario {
        create()
        val command = LedgerCommand(uid(), DEFAULT_BOOK, repository.snapshot().revision, income())
        val receipt = repository.execute(command)
        send(expense(1))
        assertEquals(receipt, repository.execute(command))
        assertEquals(m(100), repository.snapshot().income)
    }

    @Test fun reusedKeyWithChangedPayloadIsRejected() = scenario {
        create(); val key = uid(); val revision = repository.snapshot().revision
        send(income(), key, revision)
        rejects { send(income(amount = 200), key, revision) }
        assertEquals(m(100), balance().posted)
    }

    @Test fun concurrentDuplicateSubmissionsPostExactlyOnce() = scenario {
        create()
        val command = LedgerCommand(uid(), DEFAULT_BOOK, repository.snapshot().revision, income())
        val receipts = coroutineScope { (1..20).map { async(Dispatchers.Default) { repository.execute(command) } }.awaitAll() }
        assertEquals(1, receipts.toSet().size)
        assertEquals(m(100), balance().posted); assertEquals(1L, count("journals"))
    }

    @Test fun separateRoomConnectionsAlsoDeduplicateTheSameCommand() = scenario {
        create()
        val otherDb = open()
        try {
            val other = RoomLedgerRepository(otherDb, clock)
            val command = LedgerCommand(uid(), DEFAULT_BOOK, repository.snapshot().revision, income())
            val receipts = coroutineScope {
                listOf(async(Dispatchers.Default) { repository.execute(command) }, async(Dispatchers.Default) { other.execute(command) }).awaitAll()
            }
            assertEquals(receipts[0], receipts[1]); assertEquals(m(100), balance().posted)
        } finally { otherDb.close() }
    }

    @Test fun concurrentDifferentCommandsAtSameRevisionAllowOnlyOne() = scenario {
        create(); val revision = repository.snapshot().revision
        val results = coroutineScope { (1..2).map { async(Dispatchers.Default) {
            try { send(income(), revision = revision); true } catch (_: IllegalStateException) { false }
        } }.awaitAll() }
        assertEquals(1, results.count { it }); assertEquals(m(100), balance().posted)
    }

    @Test fun correctionAppendsReversalAndReplacementPreservingOriginal() = scenario {
        create(opening = 1000); val original = send(expense(100))
        val receipt = send(LedgerAction.Correct(original.primaryId, (expense(70) as LedgerAction.Post).draft, "Receipt corrected"))
        val snapshot = repository.snapshot()
        assertEquals(m(930), balance().posted); assertEquals(m(70), snapshot.expense)
        assertEquals(4, snapshot.history.size)
        assertEquals(receipt.secondaryId, snapshot.history.single { it.id == original.primaryId }.reversedBy)
        assertEquals("EXPENSE", snapshot.history.single { it.id == original.primaryId }.kind)
    }

    @Test fun failedReplacementRollsBackItsAlreadyInsertedReversalAndAudit() = scenario {
        create(opening = 1000); val original = send(expense(100))
        val before = repository.snapshot(); val audit = count("audit_events")
        rejects { send(LedgerAction.Correct(original.primaryId, TransactionDraft.Income("missing", m(1), "Salary", today), "Wrong account")) }
        assertEquals(before, repository.snapshot()); assertEquals(audit, count("audit_events"))
        assertNull(repository.snapshot().history.single { it.id == original.primaryId }.reversedBy)
    }

    @Test fun duplicateReversalAndReversalOfReversalAreRejected() = scenario {
        create(); val original = send(income())
        val reversal = send(LedgerAction.Reverse(original.primaryId, today, "Cancelled"))
        rejects { send(LedgerAction.Reverse(original.primaryId, today, "Again")) }
        rejects { send(LedgerAction.Reverse(reversal.primaryId, today, "Undo undo")) }
        assertEquals(m(0), repository.snapshot().income)
    }

    @Test fun correctionRequiresReasonAndCannotPredateOriginal() = scenario {
        create(); val original = send(income())
        rejects { send(LedgerAction.Reverse(original.primaryId, today, " ")) }
        rejects { send(LedgerAction.Reverse(original.primaryId, today.minusDays(1), "Backdated")) }
        assertEquals(m(100), balance().posted)
    }

    @Test fun postedAndClearedBalancesAndAsOfReconciliationDiffer() = scenario {
        create(opening = 1000); send(expense(200, Clearing.PENDING, today.minusDays(1)))
        assertEquals(m(800), balance().posted); assertEquals(m(1000), balance().cleared)
        val cleared = repository.reconcile(DEFAULT_BOOK, "bank", today, m(1000), BalanceBasis.CLEARED)
        assertEquals(m(0), cleared.difference); assertEquals(m(-200), cleared.pending)
        val posted = repository.reconcile(DEFAULT_BOOK, "bank", today, m(1000), BalanceBasis.POSTED)
        assertEquals(m(200), posted.difference)
        assertEquals(m(1000), repository.reconcile(DEFAULT_BOOK, "bank", today.minusDays(2), m(1000), BalanceBasis.POSTED).ledger)
    }

    @Test fun clearingIsAppendOnlyAndRespectsStatementDate() = scenario {
        create(); val pending = send(income(date = today.minusDays(2), clearing = Clearing.PENDING))
        send(LedgerAction.Clear(pending.primaryId, today))
        assertEquals(m(100), balance().cleared)
        assertEquals(m(0), repository.reconcile(DEFAULT_BOOK, "bank", today.minusDays(1), m(0), BalanceBasis.CLEARED).ledger)
        rejects { send(LedgerAction.Clear(pending.primaryId, today)) }
        rejects { send(LedgerAction.Reverse(pending.primaryId, today.minusDays(1), "Before clearing")) }
    }

    @Test fun reversedPendingPairCannotBeSeparatelyCleared() = scenario {
        create(); val pending = send(income(clearing = Clearing.PENDING))
        val reversed = send(LedgerAction.Reverse(pending.primaryId, today, "Pending cancelled"))
        rejects { send(LedgerAction.Clear(pending.primaryId, today)) }
        rejects { send(LedgerAction.Clear(reversed.primaryId, today)) }
        assertEquals(m(0), balance().posted); assertEquals(m(0), balance().cleared)
    }

    @Test fun reconciliationPostsExplicitEquityAndPreservesPendingEntries() = scenario {
        create(opening = 1000); send(expense(200, Clearing.PENDING))
        send(LedgerAction.Reconcile("bank", today, m(900), BalanceBasis.CLEARED, "Statement fee adjustment"))
        assertEquals(m(900), balance().cleared); assertEquals(m(700), balance().posted)
        assertEquals(m(200), repository.snapshot().expense); assertEquals(m(0), repository.snapshot().income)
        assertEquals(1L, count("reconciliations"))
        assertEquals(1, repository.snapshot().history.count { it.kind == "ADJUSTMENT" })
    }

    @Test fun matchedStatementStoresEvidenceWithoutZeroJournal() = scenario {
        create(opening = 100); val journals = count("journals")
        send(LedgerAction.Reconcile("bank", today, m(100), BalanceBasis.POSTED, "Statement matched"))
        assertEquals(journals, count("journals")); assertEquals(1L, count("reconciliations"))
    }

    @Test fun staleReconciliationRejectsAfterOtherFinancialChange() = scenario {
        create(); val preview = repository.reconcile(DEFAULT_BOOK, "bank", today, m(100), BalanceBasis.POSTED)
        send(income())
        rejects { send(LedgerAction.Reconcile("bank", today, preview.statement, preview.basis, "Old preview"), revision = preview.revision) }
        assertEquals(0L, count("reconciliations"))
    }

    @Test fun negativeBalanceIsRepresentedWithoutClamping() = scenario {
        create(); send(expense(500)); assertEquals(m(-500), balance().posted)
        assertEquals(m(-500), repository.snapshot().liquid)
    }

    @Test fun archivedAccountRejectsPostingButItsMoneyRemainsInTotals() = scenario {
        create(opening = 100); send(LedgerAction.Archive("bank", true))
        rejects { send(income()) }
        assertEquals(m(100), repository.snapshot().liquid)
        assertEquals(1, repository.history(DEFAULT_BOOK, HistoryFilter(accountId = "bank")).size)
        send(LedgerAction.Archive("bank", false)); send(income())
        assertEquals(m(200), balance().posted)
    }

    @Test fun investmentAccountIsTrackedButNonLiquid() = scenario {
        create(opening = 1000); create("investment", kind = AccountKind.INVESTMENT)
        send(LedgerAction.Post(TransactionDraft.Transfer("bank", "investment", m(400), date = today)))
        assertEquals(m(600), repository.snapshot().liquid); assertEquals(m(400), balance("investment").posted)
        assertFalse(balance("investment").liquid); assertEquals(m(0), repository.snapshot().expense)
    }

    @Test fun balanceOverflowRollsBackAllRowsAndRevision() = scenario {
        create(opening = Long.MAX_VALUE); val before = repository.snapshot(); val commands = count("commands")
        rejects { send(income(amount = 1)) }
        assertEquals(before, repository.snapshot()); assertEquals(commands, count("commands"))
    }

    @Test fun unnegatableOpeningAndFeeOverflowAreRejected() = scenario {
        rejects { create(opening = Long.MIN_VALUE) }
        assertEquals(0L, count("accounts")); create(); create("cash", kind = AccountKind.CASH)
        rejects { send(LedgerAction.Post(TransactionDraft.Transfer("bank", "cash", m(Long.MAX_VALUE), m(1), today))) }
        assertEquals(0L, count("journals"))
    }

    @Test fun aggregateLiquidOverflowIsRejectedEvenWhenEachAccountFits() = scenario {
        create(opening = Long.MAX_VALUE)
        rejects { create("cash", opening = 1, kind = AccountKind.CASH) }
        assertEquals(1L, count("accounts")); assertEquals(m(Long.MAX_VALUE), repository.snapshot().liquid)
    }

    @Test fun crossBookOperationsAndSelfTransferAreRejected() = scenario {
        create(); repository.initializeBook("other"); create("otherBank", book = "other")
        rejects { send(income(account = "otherBank")) }
        rejects { send(LedgerAction.Post(TransactionDraft.Transfer("bank", "otherBank", m(1), date = today))) }
        rejects { send(LedgerAction.Post(TransactionDraft.Transfer("bank", "bank", m(1), date = today))) }
        rejects { repository.history(DEFAULT_BOOK, HistoryFilter(accountId = "otherBank")) }
    }

    @Test fun unsupportedCurrencyAndExponentAreRejectedWithoutWrites() = scenario {
        create()
        listOf(CurrencySpec("USD", 2), CurrencySpec("INR", 3)).forEach { currency ->
            rejects { send(LedgerAction.Post(TransactionDraft.Income("bank", Money(100, currency), "Salary", today))) }
            rejects { repository.initializeBook("wrong", currency) }
        }
        assertEquals(0L, count("journals"))
    }

    @Test fun invalidAmountsNamesMissingReferencesAndFutureDatesAreRejected() = scenario {
        rejects { create(name = " ") }; create()
        rejects { send(income(account = "missing")) }
        rejects { send(income(amount = 0)) }; rejects { send(expense(-1)) }
        rejects { send(income(date = today.plusDays(1))) }
        assertEquals(0L, count("journals"))
    }

    @Test fun searchAndDateFilterReturnPersistedAccountHistory() = scenario {
        create(); send(income(date = today.minusDays(1))); send(expense())
        val matches = repository.history(DEFAULT_BOOK, HistoryFilter("bank", today.minusDays(1), today.minusDays(1), "salary"))
        assertEquals(1, matches.size); assertEquals("INCOME", matches.single().kind)
        assertEquals(repository.snapshot(), repository.observe().first())
    }

    @Test fun databaseRejectsMutationDeletionAndReplaceOfPostedRows() = scenario {
        create(opening = 100)
        val sql = db.openHelper.writableDatabase
        rejects { sql.execSQL("UPDATE postings SET minor = 5") }
        rejects { sql.execSQL("DELETE FROM postings") }
        rejects { sql.execSQL("DELETE FROM journals") }
        rejects { sql.execSQL("DELETE FROM journal_seals") }
        rejects { sql.execSQL("UPDATE audit_events SET reason = 'changed'") }
        rejects { sql.execSQL("INSERT OR REPLACE INTO journal_seals SELECT entryId FROM journal_seals") }
        assertEquals(m(100), balance().posted)
    }

    @Test fun databaseRejectsAddingLegToSealedJournal() = scenario {
        create(opening = 100)
        val entry = repository.snapshot().history.single().id
        rejects { db.ledgerDao().insertPostings(listOf(PostingEntity(uid(), entry, "bank", 1))) }
        assertEquals(2L, count("postings"))
    }

    private suspend fun rawJournal(legs: List<Pair<String, Long>>) = db.withTransaction {
        val command = uid(); val entry = uid()
        db.ledgerDao().insertCommand(CommandEntity(command, DEFAULT_BOOK, "test", entry, null, repository.snapshot().revision + 1))
        db.ledgerDao().insertAudit(AuditEntity(command, "test", "synthetic", clock.millis()))
        db.ledgerDao().insertJournal(JournalEntity(entry, DEFAULT_BOOK, command, "INCOME", today.toString(), clock.millis(), "CLEARED", "", "", null))
        db.ledgerDao().insertPostings(legs.map { PostingEntity(uid(), entry, it.first, it.second) })
        db.ledgerDao().seal(JournalSealEntity(entry))
    }

    @Test fun nativeForeignKeysRejectMissingReferencesAndRollbackDraft() = scenario {
        create(); val before = count("commands")
        val foreignKeyFailure = rejects {
            db.ledgerDao().insertLedgerAccount(LedgerAccountEntity(uid(), "missing-book", null, "EQUITY", "Orphan control"))
        }
        assertTrue(foreignKeyFailure.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
        rejects { rawJournal(listOf("bank" to 100, "missing" to -100)) }
        assertEquals(before, count("commands")); assertEquals(0L, count("postings"))
        db.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
    }

    @Test fun databaseRefusesUnbalancedOrSingleLegSeal() = scenario {
        create(); create("cash", kind = AccountKind.CASH)
        rejects { rawJournal(listOf("bank" to 100, "cash" to -99)) }
        rejects { rawJournal(listOf("bank" to 100)) }
        assertEquals(0L, count("journals")); assertEquals(0L, count("journal_seals"))
    }

    @Test fun databaseRejectsZeroUnnegatableAndCrossBookLegs() = scenario {
        create(); repository.initializeBook("other"); create("otherBank", book = "other")
        rejects { rawJournal(listOf("bank" to 0, "bank" to 0)) }
        rejects { rawJournal(listOf("bank" to Long.MIN_VALUE, "bank" to Long.MAX_VALUE)) }
        rejects { rawJournal(listOf("bank" to 100, "otherBank" to -100)) }
        assertEquals(0L, count("journal_seals"))
    }

    @Test fun databaseRejectsBalancedTransferDisguisedAsIncome() = scenario {
        create(); create("cash", kind = AccountKind.CASH)
        rejects { rawJournal(listOf("bank" to 100, "cash" to -100)) }
        assertEquals(0L, count("journal_seals")); assertEquals(m(0), repository.snapshot().income)
    }

    @Test fun databaseRejectsInvalidLedgerRolesAndAssetOwnership() = scenario {
        create()
        rejects { db.ledgerDao().insertLedgerAccount(LedgerAccountEntity(uid(), DEFAULT_BOOK, null, "LIABILITY", "Unsupported debt")) }
        rejects { db.ledgerDao().insertLedgerAccount(LedgerAccountEntity(uid(), DEFAULT_BOOK, null, "ASSET", "Missing owner")) }
        rejects { db.ledgerDao().insertLedgerAccount(LedgerAccountEntity(uid(), DEFAULT_BOOK, "bank", "INCOME", "Invalid owned control")) }
        assertEquals(1L, count("ledger_accounts"))
    }

    private fun legacy(currency: String = "INR", withAccounts: Boolean = true) {
        db.close(); context.deleteDatabase(file)
        val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("com.fintrack.data.local.FinTrackDatabase/1.json"))
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("database")
        context.openOrCreateDatabase(file, 0, null).use { old ->
            val entities = source.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = source.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            if (withAccounts) {
                old.execSQL("INSERT INTO accounts VALUES ('legacy-bank','Same name',?,'BANK',0)", arrayOf(currency))
                old.execSQL("INSERT INTO accounts VALUES ('legacy-cash','Same name',?,'CASH',1)", arrayOf(currency))
                old.execSQL("INSERT INTO accounts VALUES ('legacy-investment','Investment',?,'INVESTMENT',0)", arrayOf(currency))
            }
            old.version = 1
        }
        db = open(); repository = RoomLedgerRepository(db, clock)
    }

    @Test fun migrationFromExportedV1PreservesAllMetadataAndAllowsRealPosting() = scenario {
        legacy()
        val migrated = repository.snapshot()
        assertEquals(3, migrated.accounts.size); assertEquals(m(0), migrated.liquid)
        assertEquals(0, migrated.history.size)
        assertTrue(migrated.accounts.single { it.id == "legacy-cash" }.archived)
        assertFalse(migrated.accounts.single { it.id == "legacy-investment" }.liquid)
        send(income(account = "legacy-bank", amount = 12345))
        db.close(); db = open(); repository = RoomLedgerRepository(db, clock)
        assertEquals(m(12345), balance("legacy-bank").posted)
        rejects { db.openHelper.writableDatabase.execSQL("DELETE FROM postings") }
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun migrationOfEmptyV1ProducesValidV2() = scenario {
        legacy(withAccounts = false); repository.initializeBook()
        assertEquals(0, repository.snapshot().accounts.size)
        create(opening = 1); assertEquals(m(1), balance().posted)
    }

    @Test fun unsupportedLegacyCurrencyFailsMigrationWithoutDeletingV1() = scenario {
        legacy(currency = "USD")
        rejects { repository.snapshot() }
        db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(file).path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
            assertEquals(1, old.version)
            old.rawQuery("SELECT currencyCode FROM accounts", null).use { assertTrue(it.moveToFirst()); assertEquals("USD", it.getString(0)) }
        }
    }
}
