package com.fintrack.domain.ledger

import com.fintrack.engine.CurrencySpec
import com.fintrack.engine.Money
import com.fintrack.domain.planning.PlanningAction
import com.fintrack.domain.schedules.ScheduleAction
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

val INR = CurrencySpec("INR", 2)
const val DEFAULT_BOOK = "personal"

enum class AccountKind { BANK, CASH, INVESTMENT }
enum class LedgerRole { ASSET, INCOME, EXPENSE, EQUITY }
enum class Clearing { PENDING, CLEARED }
enum class BalanceBasis { POSTED, CLEARED }

data class Split(val category: String, val amount: Money)
data class OpeningDeclaration(val accountId: String, val balance: Money, val date: LocalDate)

sealed interface TransactionDraft {
    val date: LocalDate
    val clearing: Clearing
    val memo: String

    data class Income(val accountId: String, val amount: Money, val category: String,
        override val date: LocalDate, override val clearing: Clearing = Clearing.CLEARED,
        override val memo: String = "") : TransactionDraft

    data class Expense(val accountId: String, val amount: Money, val splits: List<Split>,
        override val date: LocalDate, override val clearing: Clearing = Clearing.CLEARED,
        override val memo: String = "") : TransactionDraft

    data class Transfer(val sourceId: String, val destinationId: String, val amount: Money,
        val fee: Money = Money(0, INR), override val date: LocalDate,
        override val clearing: Clearing = Clearing.CLEARED,
        override val memo: String = "") : TransactionDraft
}

sealed interface LedgerAction {
    data class Setup(val newAccounts: List<CreateAccount>, val existingOpenings: List<OpeningDeclaration>,
        val profile: PlanningAction.Configure) : LedgerAction
    data class Plan(val operation: PlanningAction) : LedgerAction
    data class Schedule(val operation: ScheduleAction) : LedgerAction
    data class CreateAccount(val accountId: String, val name: String, val kind: AccountKind,
        val opening: Money, val date: LocalDate) : LedgerAction
    data class Post(val draft: TransactionDraft) : LedgerAction
    data class Archive(val accountId: String, val archived: Boolean) : LedgerAction
    data class Reverse(val entryId: String, val date: LocalDate, val reason: String) : LedgerAction
    data class Correct(val entryId: String, val replacement: TransactionDraft, val reason: String) : LedgerAction
    data class Clear(val entryId: String, val date: LocalDate) : LedgerAction
    data class Reconcile(val accountId: String, val statementDate: LocalDate,
        val statement: Money, val basis: BalanceBasis, val reason: String) : LedgerAction
}

data class LedgerCommand(val id: String, val bookId: String, val expectedRevision: Long, val action: LedgerAction)
data class CommandReceipt(val commandId: String, val primaryId: String,
    val secondaryId: String?, val revision: Long)
data class AccountBalance(val id: String, val name: String, val kind: AccountKind,
    val archived: Boolean, val posted: Money, val cleared: Money) {
    val liquid: Boolean get() = kind != AccountKind.INVESTMENT
}
data class HistoryLeg(val accountId: String, val label: String, val role: LedgerRole, val amount: Money)
data class HistoryEntry(val id: String, val kind: String, val effectiveDate: LocalDate,
    val createdAt: Long, val memo: String, val reason: String, val reversalOf: String?,
    val reversedBy: String?, val clearedDate: LocalDate?, val legs: List<HistoryLeg>)
data class HistoryFilter(val accountId: String? = null, val from: LocalDate? = null,
    val through: LocalDate? = null, val query: String = "")
data class LedgerSnapshot(val revision: Long, val accounts: List<AccountBalance>,
    val history: List<HistoryEntry>, val liquid: Money, val income: Money, val expense: Money)
data class ReconciliationPreview(val accountId: String, val date: LocalDate, val basis: BalanceBasis,
    val statement: Money, val ledger: Money, val pending: Money, val difference: Money, val revision: Long)

interface LedgerRepository {
    suspend fun initializeBook(bookId: String = DEFAULT_BOOK, currency: CurrencySpec = INR)
    suspend fun execute(command: LedgerCommand): CommandReceipt
    suspend fun snapshot(bookId: String = DEFAULT_BOOK): LedgerSnapshot
    fun observe(bookId: String = DEFAULT_BOOK): Flow<LedgerSnapshot>
    suspend fun history(bookId: String, filter: HistoryFilter = HistoryFilter()): List<HistoryEntry>
    suspend fun reconcile(bookId: String, accountId: String, date: LocalDate,
        statement: Money, basis: BalanceBasis): ReconciliationPreview
}
