package com.fintrack.data.ledger

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.local.*
import com.fintrack.data.planning.RoomPlanningRepository
import com.fintrack.domain.ledger.*
import com.fintrack.domain.planning.PlanningAction
import com.fintrack.engine.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
class RoomSetupRepositoryTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private fun m(value: Long) = Money(value, INR)
    private fun uid() = UUID.randomUUID().toString()
    private fun scenario(block: suspend () -> Unit) = runBlocking { block() }
    private fun open() = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
        .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
    @Before fun setup() = scenario {
        context = ApplicationProvider.getApplicationContext(); file = "o-${uid().take(16)}.db"
        db = open(); ledger = RoomLedgerRepository(db, clock); ledger.initializeBook()
    }
    @After fun teardown() { db.close(); context.deleteDatabase(file) }
    private suspend fun command(action: LedgerAction) = LedgerCommand(uid(), DEFAULT_BOOK, ledger.snapshot().revision, action)
    private suspend fun send(action: LedgerAction) = ledger.execute(command(action))
    private fun account(id: String = "bank", amount: Long = 1000, kind: AccountKind = AccountKind.BANK) =
        LedgerAction.CreateAccount(id, id, kind, m(amount), today)
    private fun profile(zone: String = "Asia/Kolkata") = PlanningAction.Configure(zone, m(100), m(500), 30, true)
    private fun setupAction(accounts: List<LedgerAction.CreateAccount> = listOf(account()),
        existing: List<OpeningDeclaration> = emptyList(), zone: String = "Asia/Kolkata") = LedgerAction.Setup(accounts, existing, profile(zone))
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block() } catch (_: Exception) { return }
        throw AssertionError("Expected rejection")
    }
    private fun count(table: String) = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        check(it.moveToFirst()); it.getLong(0)
    }

    @Test fun setupPostsOnlyDeclaredOpeningEquityAndCompletesOneRevision() = scenario {
        val receipt = send(setupAction(listOf(account(), account("cash", 200, AccountKind.CASH), account("invest", 300, AccountKind.INVESTMENT))))
        val result = ledger.snapshot()
        assertEquals(1L, receipt.revision); assertEquals(1L, result.revision)
        assertEquals(3, result.accounts.size); assertEquals(3, result.history.size)
        assertTrue(result.history.all { it.kind == "OPENING" && it.legs.sumOf { leg -> leg.amount.minor } == 0L })
        assertEquals(m(1200), result.liquid); assertEquals(m(0), result.income); assertEquals(m(0), result.expense)
        assertEquals(1L, count("commands")); assertEquals(1L, count("audit_events"))
        val plan = RoomPlanningRepository(db, ledger, clock).snapshot(DEFAULT_BOOK, today)
        assertTrue(plan.profile.setupComplete); assertEquals("Asia/Kolkata", plan.profile.timezone)
        assertEquals(m(1100), plan.safeToSpend.available); assertEquals(m(0), plan.reservations.reserved)
    }
    @Test fun retryReturnsOriginalReceiptWithoutDuplicateAccountsOrPostings() = scenario {
        val request = command(setupAction()); val receipt = ledger.execute(request); val before = ledger.snapshot()
        assertEquals(receipt, ledger.execute(request)); assertEquals(before, ledger.snapshot())
        assertEquals(1L, count("commands")); assertEquals(2L, count("postings"))
        rejects { ledger.execute(request.copy(action = setupAction(listOf(account(amount = 2000))))) }
        assertEquals(before, ledger.snapshot())
    }
    @Test fun secondSetupCannotReplaceCompletedBook() = scenario {
        send(setupAction()); val before = ledger.snapshot()
        rejects { send(setupAction(listOf(account("replacement", 9000)))) }
        assertEquals(before, ledger.snapshot())
    }
    @Test fun invalidLaterAccountRollsBackEarlierOpeningProfileAndAudit() = scenario {
        rejects { send(setupAction(listOf(account(), account("invalid").copy(name = " ")))) }
        assertEquals(0L, ledger.snapshot().revision); assertTrue(ledger.snapshot().accounts.isEmpty())
        listOf("commands", "audit_events", "financial_profiles", "postings", "journals").forEach { assertEquals(it, 0L, count(it)) }
    }
    @Test fun aggregateOverflowRollsBackWholeSetup() = scenario {
        rejects { send(setupAction(listOf(account(amount = Long.MAX_VALUE), account("cash", 1, AccountKind.CASH)))) }
        assertEquals(0L, ledger.snapshot().revision); assertTrue(ledger.snapshot().accounts.isEmpty())
        assertEquals(0L, count("financial_profiles")); assertEquals(0L, count("commands"))
    }
    @Test fun selectedBookTimezoneControlsFirstOpeningDate() = scenario {
        val dateInSelectedZone = today.plusDays(1)
        send(setupAction(listOf(account().copy(date = dateInSelectedZone)), zone = "Pacific/Kiritimati"))
        assertEquals(dateInSelectedZone, ledger.snapshot().history.single().effectiveDate)
    }
    @Test fun futureOpeningInSelectedZoneRollsBackEverything() = scenario {
        rejects { send(setupAction(listOf(account().copy(date = today)), zone = "Pacific/Honolulu").copy(
            newAccounts = listOf(account().copy(date = today.plusDays(1))))) }
        assertTrue(ledger.snapshot().accounts.isEmpty()); assertEquals(0L, count("financial_profiles"))
    }
    @Test fun duplicateDeclarationsCannotCreateExtraMoney() = scenario {
        rejects { send(setupAction(listOf(account(), account()))) }
        send(LedgerAction.CreateAccount("legacy", "Legacy cash", AccountKind.CASH, m(0), today))
        val before = ledger.snapshot()
        val declaration = OpeningDeclaration("legacy", m(500), today)
        rejects { send(setupAction(emptyList(), listOf(declaration, declaration))) }
        rejects { send(setupAction(listOf(account("legacy")), listOf(declaration))) }
        assertEquals(before, ledger.snapshot())
    }
    @Test fun metadataOnlyArchivedAccountKeepsIdentityAndArchiveState() = scenario {
        send(LedgerAction.CreateAccount("legacy", "Legacy cash", AccountKind.CASH, m(0), today))
        send(LedgerAction.Archive("legacy", true))
        send(setupAction(emptyList(), listOf(OpeningDeclaration("legacy", m(700), today))))
        val result = ledger.snapshot(); val account = result.accounts.single()
        assertEquals("legacy", account.id); assertEquals("Legacy cash", account.name)
        assertEquals(AccountKind.CASH, account.kind); assertTrue(account.archived)
        assertEquals(m(700), account.posted); assertEquals(1, result.history.size)
    }
    @Test fun missingMetadataOpeningIsRejectedWithoutAssumingZero() = scenario {
        send(account("legacy", 0)); val before = ledger.snapshot()
        rejects { send(setupAction()) }; assertEquals(before, ledger.snapshot())
        assertEquals(0L, count("financial_profiles"))
    }
    @Test fun existingFinancialBookCompletesProfileWithoutRedeclaringMoney() = scenario {
        send(account()); val before = ledger.snapshot()
        rejects { send(setupAction(emptyList(), listOf(OpeningDeclaration("bank", m(9000), today)))) }
        send(LedgerAction.Plan(profile()))
        val after = ledger.snapshot()
        assertEquals(before.accounts, after.accounts); assertEquals(before.history, after.history)
        assertTrue(db.planningDao().profile(DEFAULT_BOOK)!!.setupComplete)
    }
    @Test fun completedZeroOpeningCannotBeResetAndOpenedAgain() = scenario {
        send(setupAction(listOf(account(amount = 0)))); val before = ledger.snapshot()
        rejects { send(LedgerAction.Plan(profile().copy(setupComplete = false))) }
        rejects { send(setupAction(emptyList(), listOf(OpeningDeclaration("bank", m(500), today)))) }
        assertEquals(before, ledger.snapshot()); assertTrue(db.planningDao().profile(DEFAULT_BOOK)!!.setupComplete)
    }
    @Test fun openingFailureAfterArchivedDeclarationRestoresArchiveAndProfile() = scenario {
        send(account("legacy", 0)); send(LedgerAction.Archive("legacy", true)); val before = ledger.snapshot()
        rejects { send(setupAction(listOf(account().copy(name = " ")), listOf(OpeningDeclaration("legacy", m(500), today)))) }
        assertEquals(before, ledger.snapshot()); assertEquals(0L, count("financial_profiles"))
    }
    @Test fun completedSetupSurvivesCloseAndReopenWithIdempotentReceipt() = scenario {
        val request = command(setupAction()); val receipt = ledger.execute(request); val before = ledger.snapshot()
        db.close(); db = open(); ledger = RoomLedgerRepository(db, clock)
        assertEquals(before, ledger.snapshot()); assertEquals(receipt, ledger.execute(request))
        assertTrue(db.planningDao().profile(DEFAULT_BOOK)!!.setupComplete)
    }
}
