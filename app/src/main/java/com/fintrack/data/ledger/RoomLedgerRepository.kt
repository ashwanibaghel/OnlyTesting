package com.fintrack.data.ledger

import androidx.room.withTransaction
import com.fintrack.data.local.*
import com.fintrack.domain.ledger.*
import com.fintrack.engine.Money
import com.fintrack.engine.Posting
import com.fintrack.engine.sumMoney
import com.fintrack.engine.validateJournal
import java.math.BigInteger
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLedgerRepository(private val db: FinTrackDatabase, private val clock: Clock) : LedgerRepository {
    private val dao get() = db.ledgerDao()
    private fun id() = UUID.randomUUID().toString()
    private fun money(value: Long) = Money(value, INR)
    private fun identifier(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid identifier" } }
    private fun label(value: String) { require(value.isNotBlank() && value == value.trim() && value.length <= 100) { "Name/category must be trimmed and 1..100 characters" } }
    private fun date(value: LocalDate) { require(value.year in 1..9999 && !value.isAfter(LocalDate.now(clock))) { "Actual entries cannot have future or unsupported dates" } }
    private fun reason(value: String) { require(value.isNotBlank() && value.length <= 500) { "A correction/reconciliation reason is required (1..500 characters)" } }
    private fun currency(value: Money) { require(value.currency == INR) { "This book supports INR with two minor digits only" } }

    override suspend fun initializeBook(bookId: String, currency: com.fintrack.engine.CurrencySpec) {
        identifier(bookId)
        require(currency == INR) { "Unsupported book currency" }
        db.withTransaction {
            if (dao.book(bookId) == null) dao.insertBook(BookEntity(bookId, "local-owner", "INR", 2, 0, clock.millis()))
        }
    }

    override suspend fun execute(command: LedgerCommand): CommandReceipt {
        // Detach caller-owned split lists before fingerprinting or suspending.
        fun frozen(draft: TransactionDraft): TransactionDraft =
            if (draft is TransactionDraft.Expense) draft.copy(splits = draft.splits.toList()) else draft
        val action = when (val a = command.action) {
            is LedgerAction.Post -> a.copy(draft = frozen(a.draft))
            is LedgerAction.Correct -> a.copy(replacement = frozen(a.replacement))
            else -> a
        }
        val input = command.copy(action = action)
        identifier(input.id); identifier(input.bookId)
        require(input.expectedRevision >= 0) { "Invalid expected revision" }
        val hash = fingerprint(input)
        return db.withTransaction {
            dao.command(input.id)?.let {
                require(it.bookId == input.bookId && it.fingerprint == hash) { "Command key already used with a different payload" }
                return@withTransaction it.receipt()
            }
            val book = requireNotNull(dao.book(input.bookId)) { "Book does not exist" }
            require(book.currencyCode == "INR" && book.exponent == 2) { "Unsupported stored currency" }
            check(book.revision == input.expectedRevision) { "Stale financial state. Refresh and review before resubmitting" }
            val next = Math.addExact(book.revision, 1)
            val primary = when (action) {
                is LedgerAction.CreateAccount -> action.accountId
                is LedgerAction.Archive -> action.accountId
                is LedgerAction.Clear -> action.entryId
                is LedgerAction.Reconcile -> input.id
                else -> id()
            }
            val secondary = if (action is LedgerAction.Correct) id() else null
            val receipt = CommandEntity(input.id, input.bookId, hash, primary, secondary, next)
            dao.insertCommand(receipt)
            val explanation = when (action) {
                is LedgerAction.Reverse -> action.reason
                is LedgerAction.Correct -> action.reason
                is LedgerAction.Reconcile -> action.reason
                else -> ""
            }
            dao.insertAudit(AuditEntity(input.id, action.javaClass.simpleName, explanation, clock.millis()))
            when (action) {
                is LedgerAction.CreateAccount -> {
                    identifier(action.accountId); label(action.name); currency(action.opening); date(action.date)
                    dao.insertAccount(AccountEntity(action.accountId, action.name, "INR", action.kind.name, false, input.bookId))
                    dao.insertLedgerAccount(LedgerAccountEntity(action.accountId, input.bookId, action.accountId, "ASSET", action.name))
                    if (action.opening.minor != 0L) {
                        val equity = control(input.bookId, LedgerRole.EQUITY, "Opening equity")
                        postJournal(input, id(), "OPENING", action.date, Clearing.CLEARED, "Opening balance", "", null,
                            listOf(Posting(action.accountId, action.opening), Posting(equity, -action.opening)))
                    }
                }
                is LedgerAction.Post -> postDraft(input, primary, action.draft)
                is LedgerAction.Archive -> {
                    account(input.bookId, action.accountId, allowArchived = true)
                    check(dao.archive(input.bookId, action.accountId, action.archived) == 1)
                }
                is LedgerAction.Reverse -> reverse(input, primary, action.entryId, action.date, action.reason)
                is LedgerAction.Correct -> {
                    reverse(input, requireNotNull(secondary), action.entryId, action.replacement.date, action.reason)
                    postDraft(input, primary, action.replacement, action.reason)
                }
                is LedgerAction.Clear -> {
                    date(action.date)
                    val original = original(input.bookId, action.entryId)
                    require(original.clearedDate == null && original.kind != "REVERSAL" && original.reversedBy == null) { "Entry cannot be cleared" }
                    require(!action.date.isBefore(original.effectiveDate)) { "Clearing predates transaction" }
                    dao.insertClearing(ClearingEntity(action.entryId, input.id, action.date.toString()))
                }
                is LedgerAction.Reconcile -> {
                    reason(action.reason); date(action.statementDate)
                    account(input.bookId, action.accountId)
                    val preview = reconciliation(input.bookId, action.accountId, action.statementDate, action.statement, action.basis)
                    val adjustment = if (preview.difference.minor != 0L) id() else null
                    if (adjustment != null) {
                        val equity = control(input.bookId, LedgerRole.EQUITY, "Reconciliation equity")
                        postJournal(input, adjustment, "ADJUSTMENT", action.statementDate, Clearing.CLEARED,
                            "Statement reconciliation", action.reason, null,
                            listOf(Posting(action.accountId, preview.difference), Posting(equity, -preview.difference)))
                    }
                    dao.insertReconciliation(ReconciliationEntity(input.id, action.accountId, action.statementDate.toString(),
                        action.basis.name, action.statement.minor, preview.ledger.minor, preview.difference.minor, adjustment))
                }
            }
            // Validate all final derived positions exactly; any overflow rolls back every inserted row.
            snapshotInside(input.bookId)
            check(dao.advance(input.bookId, book.revision, next) == 1) { "Stale book revision" }
            receipt.receipt()
        }
    }

    private fun CommandEntity.receipt() = CommandReceipt(id, primaryId, secondaryId, revision)

    private suspend fun account(book: String, id: String, allowArchived: Boolean = false): AccountEntity {
        identifier(id)
        val account = requireNotNull(dao.accounts(book).firstOrNull { it.id == id }) { "Account is missing or belongs to another book" }
        require(allowArchived || !account.archived) { "Account is archived. Explicitly unarchive before posting" }
        require(account.currencyCode == "INR") { "Account currency mismatch" }
        return account
    }

    private suspend fun control(book: String, role: LedgerRole, name: String): String {
        label(name)
        val existing = dao.ledgerAccounts(book).firstOrNull { it.role == role.name && it.name == name }
        if (existing != null) return existing.id
        val newId = id()
        dao.insertLedgerAccount(LedgerAccountEntity(newId, book, null, role.name, name))
        return newId
    }

    private suspend fun postDraft(command: LedgerCommand, entryId: String, draft: TransactionDraft, reason: String = "") {
        date(draft.date)
        require(draft.memo.length <= 500) { "Memo is too long" }
        val legs = mutableListOf<Posting>()
        val kind = when (draft) {
            is TransactionDraft.Income -> {
                currency(draft.amount); require(draft.amount.minor > 0) { "Income must be positive" }
                account(command.bookId, draft.accountId)
                legs += Posting(draft.accountId, draft.amount)
                legs += Posting(control(command.bookId, LedgerRole.INCOME, draft.category), -draft.amount)
                "INCOME"
            }
            is TransactionDraft.Expense -> {
                currency(draft.amount); require(draft.amount.minor > 0) { "Expense must be positive" }
                require(draft.splits.size in 1..100) { "Expense needs 1..100 splits" }
                account(command.bookId, draft.accountId)
                draft.splits.forEach { currency(it.amount); label(it.category); require(it.amount.minor > 0) { "Split must be positive" } }
                require(sumMoney(draft.splits.map { it.amount }, INR) == draft.amount) { "Splits must equal the expense exactly" }
                legs += Posting(draft.accountId, -draft.amount)
                draft.splits.forEach { legs += Posting(control(command.bookId, LedgerRole.EXPENSE, it.category), it.amount) }
                "EXPENSE"
            }
            is TransactionDraft.Transfer -> {
                currency(draft.amount); currency(draft.fee)
                require(draft.sourceId != draft.destinationId) { "Transfer accounts must differ" }
                require(draft.amount.minor > 0 && draft.fee.minor >= 0) { "Invalid transfer amount or fee" }
                account(command.bookId, draft.sourceId); account(command.bookId, draft.destinationId)
                legs += Posting(draft.sourceId, -(draft.amount + draft.fee))
                legs += Posting(draft.destinationId, draft.amount)
                if (draft.fee.minor > 0) legs += Posting(control(command.bookId, LedgerRole.EXPENSE, "Transfer fees"), draft.fee)
                "TRANSFER"
            }
        }
        postJournal(command, entryId, kind, draft.date, draft.clearing, draft.memo, reason, null, legs)
    }

    private suspend fun postJournal(command: LedgerCommand, id: String, kind: String, date: LocalDate,
        clearing: Clearing, memo: String, reason: String, reversalOf: String?, legs: List<Posting>) {
        validateJournal(legs)
        // Keep gross positive journal totals representable in SQLite's signed integer SUM.
        val gross = legs.filter { it.amount.minor > 0 }.fold(BigInteger.ZERO) { sum, leg -> sum + BigInteger.valueOf(leg.amount.minor) }
        require(gross <= BigInteger.valueOf(Long.MAX_VALUE)) { "Gross journal amount exceeds signed 64-bit range" }
        dao.insertJournal(JournalEntity(id, command.bookId, command.id, kind, date.toString(), clock.millis(), clearing.name, memo, reason, reversalOf))
        dao.insertPostings(legs.map { PostingEntity(this.id(), id, it.accountId, it.amount.minor) })
        dao.seal(JournalSealEntity(id))
    }

    private suspend fun original(book: String, entryId: String): HistoryEntry =
        requireNotNull(snapshotInside(book).history.firstOrNull { it.id == entryId }) { "Transaction is missing or belongs to another book" }

    private suspend fun reverse(command: LedgerCommand, id: String, originalId: String, at: LocalDate, why: String) {
        reason(why); date(at)
        val entry = original(command.bookId, originalId)
        require(entry.kind != "REVERSAL" && entry.reversedBy == null) { "Entry already reversed or is itself a reversal" }
        require(!at.isBefore(entry.effectiveDate) && (entry.clearedDate == null || !at.isBefore(entry.clearedDate))) { "Reversal predates original or clearing" }
        entry.legs.filter { it.role == LedgerRole.ASSET }.forEach { account(command.bookId, it.accountId) }
        postJournal(command, id, "REVERSAL", at, if (entry.clearedDate != null) Clearing.CLEARED else Clearing.PENDING,
            "Reversal of ${entry.id}", why, entry.id, entry.legs.map { Posting(it.accountId, -it.amount) })
    }

    override suspend fun snapshot(bookId: String): LedgerSnapshot = db.withTransaction { snapshotInside(bookId) }

    private suspend fun snapshotInside(bookId: String): LedgerSnapshot {
        val book = requireNotNull(dao.book(bookId)) { "Book does not exist" }
        val journals = dao.journals(bookId)
        val legs = dao.postings(bookId).groupBy { it.entryId }
        val controls = dao.ledgerAccounts(bookId).associateBy { it.id }
        val clearing = dao.clearings(bookId).associateBy { it.entryId }
        val reversed = journals.filter { it.reversalOf != null }.associateBy { it.reversalOf }
        val history = journals.map { journal ->
            val date = LocalDate.parse(journal.effectiveDate)
            HistoryEntry(journal.id, journal.kind, date, journal.createdAt, journal.memo, journal.reason, journal.reversalOf,
                reversed[journal.id]?.id, if (journal.clearing == "CLEARED") date else clearing[journal.id]?.let { LocalDate.parse(it.clearedDate) },
                legs[journal.id].orEmpty().map { leg ->
                    val ledger = controls.getValue(leg.ledgerAccountId)
                    HistoryLeg(ledger.id, ledger.name, LedgerRole.valueOf(ledger.role), money(leg.minor))
                })
        }
        // Also validate control account totals, not only displayed asset balances.
        history.flatMap { it.legs }.groupBy { it.accountId }.values.forEach { sumMoney(it.map { leg -> leg.amount }, INR) }
        val accounts = dao.accounts(bookId).map { account ->
            val accountLegs = history.flatMap { entry -> entry.legs.filter { it.accountId == account.id } }
            val clearedLegs = history.filter { it.clearedDate != null }.flatMap { entry -> entry.legs.filter { it.accountId == account.id } }
            AccountBalance(account.id, account.name, AccountKind.valueOf(account.kind), account.archived,
                sumMoney(accountLegs.map { it.amount }, INR), sumMoney(clearedLegs.map { it.amount }, INR))
        }
        val income = sumMoney(history.flatMap { it.legs }.filter { it.role == LedgerRole.INCOME }.map { -it.amount }, INR)
        val expense = sumMoney(history.flatMap { it.legs }.filter { it.role == LedgerRole.EXPENSE }.map { it.amount }, INR)
        return LedgerSnapshot(book.revision, accounts, history, sumMoney(accounts.filter { it.liquid }.map { it.posted }, INR), income, expense)
    }

    override fun observe(bookId: String): Flow<LedgerSnapshot> = dao.revision(bookId).map { snapshot(bookId) }

    override suspend fun history(bookId: String, filter: HistoryFilter): List<HistoryEntry> = db.withTransaction {
        require(filter.from == null || filter.through == null || !filter.from.isAfter(filter.through)) { "Invalid history date range" }
        if (filter.accountId != null) account(bookId, filter.accountId, allowArchived = true)
        snapshotInside(bookId).history.filter { entry ->
            (filter.accountId == null || entry.legs.any { it.accountId == filter.accountId }) &&
                (filter.from == null || !entry.effectiveDate.isBefore(filter.from)) &&
                (filter.through == null || !entry.effectiveDate.isAfter(filter.through)) &&
                (filter.query.isBlank() || (listOf(entry.memo, entry.reason, entry.kind) + entry.legs.map { it.label })
                    .any { it.contains(filter.query, ignoreCase = true) })
        }.reversed()
    }

    override suspend fun reconcile(bookId: String, accountId: String, date: LocalDate,
        statement: Money, basis: BalanceBasis): ReconciliationPreview = db.withTransaction {
        reconciliation(bookId, accountId, date, statement, basis)
    }

    private suspend fun reconciliation(bookId: String, accountId: String, at: LocalDate,
        statement: Money, basis: BalanceBasis): ReconciliationPreview {
        currency(statement); date(at); account(bookId, accountId, allowArchived = true)
        val snapshot = snapshotInside(bookId)
        val entries = snapshot.history.filter { !it.effectiveDate.isAfter(at) }
        val posted = sumMoney(entries.flatMap { it.legs }.filter { it.accountId == accountId }.map { it.amount }, INR)
        val cleared = sumMoney(entries.filter { it.clearedDate != null && !it.clearedDate.isAfter(at) }
            .flatMap { it.legs }.filter { it.accountId == accountId }.map { it.amount }, INR)
        val ledger = if (basis == BalanceBasis.POSTED) posted else cleared
        return ReconciliationPreview(accountId, at, basis, statement, ledger, posted - cleared, statement - ledger, snapshot.revision)
    }
}
