package com.fintrack.ui.workflow

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fintrack.data.ledger.RoomFinancialReviewRepository
import com.fintrack.data.ledger.RoomLedgerRepository
import com.fintrack.data.local.*
import com.fintrack.data.planning.RoomPlanningRepository
import com.fintrack.data.schedules.RoomScheduleRepository
import com.fintrack.data.session.RoomFinanceSessionRepository
import com.fintrack.domain.ledger.*
import com.fintrack.engine.Money
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomWorkflowIntegrationTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 23)
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: FinTrackDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var reviews: RoomFinancialReviewRepository
    private lateinit var sessions: RoomFinanceSessionRepository
    private lateinit var planning: RoomPlanningRepository
    private var vm: FinancialWorkflowViewModel? = null
    private val store = ViewModelStore()
    private fun m(value: Long) = Money(value, INR)
    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000, block) }
    private fun income() = LedgerAction.Post(TransactionDraft.Income("bank", m(100), "Salary", today))
    @Before fun setup() = scenario {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext(); file = "w-${UUID.randomUUID().toString().take(16)}.db"
        db = Room.databaseBuilder(context, FinTrackDatabase::class.java, file)
            .addMigrations(*FinTrackMigrations.all).addCallback(LedgerSchema.callback).build()
        ledger = RoomLedgerRepository(db, clock); ledger.initializeBook()
        ledger.execute(LedgerCommand("initial-account", DEFAULT_BOOK, 0,
            LedgerAction.CreateAccount("bank", "Bank", AccountKind.BANK, m(1000), today)))
        planning = RoomPlanningRepository(db, ledger, clock)
        reviews = RoomFinancialReviewRepository(db, ledger, clock)
        sessions = RoomFinanceSessionRepository(db, ledger, RoomScheduleRepository(db, ledger, clock), clock, emptyFlow())
    }
    @After fun teardown() {
        val scope = vm?.viewModelScope?.coroutineContext?.get(Job)
        store.clear(); runBlocking { scope?.join() }
        Dispatchers.resetMain(); db.close(); context.deleteDatabase(file)
    }
    private suspend fun model(repository: FinancialReviewRepository = reviews): FinancialWorkflowViewModel {
        val model = FinancialWorkflowViewModel(ledger, sessions, planning, repository)
        vm = model; store.put("workflow", model)
        model.state.first { it.session != null }
        return model
    }

    @Test fun previewThenRapidConfirmationCommitsOnlyOneRealIncome() = scenario {
        val model = model(); val operation = FinancialOperation("real-income"); val before = ledger.snapshot()
        model.prepare(operation) { income() }
        val review = model.state.first { it.review != null && !it.busy }.review!!
        assertEquals(m(1100), review.effect.after.liquid); assertEquals(before, ledger.snapshot())
        model.confirm(operation, review.token); model.confirm(operation, review.token)
        model.state.first { !it.busy && operation.key in it.saved }
        assertEquals(m(1100), ledger.snapshot().liquid); assertEquals(before.history.size + 1, ledger.snapshot().history.size)
        assertNull(model.state.value.retry); assertNull(model.state.value.review)
    }
    @Test fun invalidActualPreviewCannotSubmitEarlierFinancialReview() = scenario {
        val model = model(); val operation = FinancialOperation("real-inputs"); val before = ledger.snapshot()
        model.prepare(operation) { income() }
        val old = model.state.first { it.review != null && !it.busy }.review!!
        model.edited(operation)
        model.prepare(operation) { LedgerAction.Post(TransactionDraft.Expense("bank", m(100), listOf(Split("Food", m(99))), today)) }
        model.state.first { !it.busy && it.commandError != null }
        model.confirm(operation, old.token)
        assertNull(model.state.value.retry); assertNull(model.state.value.review); assertEquals(before, ledger.snapshot())
        val corrected = FinancialOperation("real-corrected")
        model.prepare(corrected) { LedgerAction.Post(TransactionDraft.Expense("bank", m(100), listOf(Split("Food", m(100))), today)) }
        val valid = model.state.first { it.review != null && !it.busy }.review!!
        model.confirm(corrected, valid.token); model.state.first { !it.busy && corrected.key in it.saved }
        assertEquals(m(900), ledger.snapshot().liquid); assertEquals(before.history.size + 1, ledger.snapshot().history.size)
    }
    @Test fun lostRealApplyAcknowledgementRetriesOriginalReceiptWithoutDuplicate() = scenario {
        var loseResponse = true
        val unreliable = object : FinancialReviewRepository by reviews {
            override suspend fun apply(review: FinancialCommandReview): CommandReceipt {
                val receipt = reviews.apply(review)
                if (loseResponse) { loseResponse = false; error("Synthetic lost response after completed transaction") }
                return receipt
            }
        }
        val model = model(unreliable); val operation = FinancialOperation("real-retry"); val before = ledger.snapshot()
        model.prepare(operation) { income() }
        val review = model.state.first { it.review != null && !it.busy }.review!!
        model.confirm(operation, review.token)
        val failed = model.state.first { !it.busy && it.retry != null }.retry!!
        assertEquals(review.command, failed.command); assertEquals(m(1100), ledger.snapshot().liquid)
        model.retry(operation, failed.command.id); model.retry(operation, failed.command.id)
        model.state.first { !it.busy && operation.key in it.saved }
        assertEquals(m(1100), ledger.snapshot().liquid); assertEquals(before.history.size + 1, ledger.snapshot().history.size)
    }
}
