package com.fintrack.data.reports

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.fintrack.data.local.FinTrackDatabase
import com.fintrack.domain.ledger.*
import com.fintrack.domain.reports.*
import com.fintrack.engine.Money
import com.fintrack.engine.toLongChecked
import java.math.BigInteger
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map

class RoomFinancialReportsRepository(private val db: FinTrackDatabase, private val clock: Clock) : FinancialReportsRepository {
    private fun BigInteger.money() = Money(toLongChecked(), INR)
    private class Totals {
        var income = BigInteger.ZERO; var expense = BigInteger.ZERO; var liquid = BigInteger.ZERO
        var assets = BigInteger.ZERO; var equity = BigInteger.ZERO
        fun add(role: LedgerRole, amount: BigInteger, liquidAccount: Boolean) {
            when (role) {
                LedgerRole.INCOME -> income -= amount
                LedgerRole.EXPENSE -> expense += amount
                LedgerRole.EQUITY -> equity -= amount
                LedgerRole.ASSET -> { assets += amount; if (liquidAccount) liquid += amount }
            }
        }
        fun result(): OperatingTotals {
            check(assets == income - expense + equity) { "Report does not conserve actual money" }
            fun m(value: BigInteger) = Money(value.toLongChecked(), INR)
            return OperatingTotals(m(income), m(expense), m(income - expense), m(liquid), m(assets), m(equity))
        }
    }
    private class Recognized(private val basis: ReportBasis, period: ReportPeriod) {
        private val total = Totals()
        private val categories = linkedMapOf<Pair<LedgerRole, String>, BigInteger>()
        private val months = linkedMapOf<YearMonth, Totals>().apply {
            var month = YearMonth.from(period.from)
            while (month <= YearMonth.from(period.through)) { put(month, Totals()); month = month.plusMonths(1) }
        }
        fun add(date: LocalDate, role: LedgerRole, name: String, amount: BigInteger, liquid: Boolean) {
            total.add(role, amount, liquid); months.getValue(YearMonth.from(date)).add(role, amount, liquid)
            if (role == LedgerRole.INCOME || role == LedgerRole.EXPENSE) {
                val key = role to name
                require(key in categories || categories.size < 10_000) { "Report category limit exceeded; narrow the period" }
                categories[key] = (categories[key] ?: BigInteger.ZERO) + if (role == LedgerRole.INCOME) -amount else amount
            }
        }
        fun result(count: Long) = RecognizedReport(basis, total.result(), categories.map { (key, amount) ->
            CategoryTotal(key.first, key.second, Money(amount.toLongChecked(), INR))
        }.sortedWith(compareBy<CategoryTotal> { it.role.name }.thenByDescending { it.amount.minor }.thenBy { it.category }),
            months.map { MonthReport(it.key, it.value.result()) }, count)
    }
    private class AccountSums {
        var openingPosted = BigInteger.ZERO; var openingCleared = BigInteger.ZERO
        var closingPosted = BigInteger.ZERO; var closingCleared = BigInteger.ZERO
    }
    private class AssetSums {
        var liquid = BigInteger.ZERO; var investment = BigInteger.ZERO
        fun add(amount: BigInteger, isLiquid: Boolean) { if (isLiquid) liquid += amount else investment += amount }
    }
    override suspend fun report(bookId: String, period: ReportPeriod): FinancialReport {
        require(bookId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid book identity" }
        require(period.from.year in 1..9999 && period.through.year in 1..9999 &&
            ChronoUnit.DAYS.between(period.from, period.through) in 0..365) { "Choose an inclusive report period of 1..366 days" }
        return db.withTransaction {
            val book = requireNotNull(db.ledgerDao().book(bookId)) { "Book does not exist" }
            require(book.currencyCode == "INR" && book.exponent == 2) { "Unsupported report currency" }
            val zone = db.planningDao().profile(bookId)?.timezone?.let(ZoneId::of) ?: clock.zone
            require(period.through <= LocalDate.now(clock.withZone(zone))) { "Actual reports cannot include future dates; use forecasting" }
            val accounts = db.ledgerDao().accounts(bookId)
            require(accounts.size <= 10_000) { "Report account limit exceeded" }
            val accountSums = accounts.associate { it.id to AccountSums() }
            val posted = Recognized(ReportBasis.POSTED, period); val cleared = Recognized(ReportBasis.CLEARED, period)
            val openingPosted = AssetSums(); val openingCleared = AssetSums()
            val dailyPosted = linkedMapOf<LocalDate, AssetSums>(); val dailyCleared = linkedMapOf<LocalDate, AssetSums>()
            val context = currentCoroutineContext()
            // Stream integer postings. SQL SUM can overflow before cancellation; REAL/TOTAL would lose paise.
            db.openHelper.writableDatabase.query(SimpleSQLiteQuery("""SELECT j.effectiveDate,
                CASE WHEN j.clearing = 'CLEARED' THEN j.effectiveDate ELSE c.clearedDate END AS clearedDate,
                p.ledgerAccountId, p.minor, l.role, l.name, a.kind
                FROM journals j JOIN journal_seals s ON s.entryId = j.id JOIN postings p ON p.entryId = j.id
                JOIN ledger_accounts l ON l.id = p.ledgerAccountId LEFT JOIN accounts a ON a.id = l.ownerAccountId
                LEFT JOIN clearing_events c ON c.entryId = j.id WHERE j.bookId = ? AND j.effectiveDate <= ?""",
                arrayOf(bookId, period.through.toString()))).use { cursor ->
                var scanned = 0L
                while (cursor.moveToNext()) {
                    if (scanned++ % 128 == 0L) context.ensureActive()
                    val date = LocalDate.parse(cursor.getString(0))
                    val clearedDate = if (cursor.isNull(1)) null else LocalDate.parse(cursor.getString(1))
                    val accountId = cursor.getString(2); val amount = BigInteger.valueOf(cursor.getLong(3))
                    val role = LedgerRole.valueOf(cursor.getString(4)); val name = cursor.getString(5)
                    val liquid = !cursor.isNull(6) && AccountKind.valueOf(cursor.getString(6)) != AccountKind.INVESTMENT
                    if (date >= period.from) posted.add(date, role, name, amount, liquid)
                    if (clearedDate != null && clearedDate in period.from..period.through) cleared.add(clearedDate, role, name, amount, liquid)
                    if (role == LedgerRole.ASSET) {
                        val account = accountSums.getValue(accountId)
                        account.closingPosted += amount
                        if (date < period.from) { account.openingPosted += amount; openingPosted.add(amount, liquid) }
                        else dailyPosted.getOrPut(date) { AssetSums() }.add(amount, liquid)
                        if (clearedDate != null && clearedDate <= period.through) {
                            account.closingCleared += amount
                            if (clearedDate < period.from) { account.openingCleared += amount; openingCleared.add(amount, liquid) }
                            else dailyCleared.getOrPut(clearedDate) { AssetSums() }.add(amount, liquid)
                        }
                    }
                }
            }
            context.ensureActive()
            val counts = db.openHelper.writableDatabase.query(SimpleSQLiteQuery("""SELECT
                COUNT(CASE WHEN j.effectiveDate >= ? THEN 1 END),
                COUNT(CASE WHEN (CASE WHEN j.clearing = 'CLEARED' THEN j.effectiveDate ELSE c.clearedDate END) BETWEEN ? AND ? THEN 1 END),
                COUNT(CASE WHEN j.kind != 'REVERSAL' AND (j.clearing != 'CLEARED' AND (c.entryId IS NULL OR c.clearedDate > ?))
                    AND NOT EXISTS (SELECT 1 FROM journals r JOIN journal_seals rs ON rs.entryId = r.id WHERE r.reversalOf = j.id AND r.effectiveDate <= ?) THEN 1 END)
                FROM journals j JOIN journal_seals s ON s.entryId = j.id LEFT JOIN clearing_events c ON c.entryId = j.id
                WHERE j.bookId = ? AND j.effectiveDate <= ?""", arrayOf(period.from.toString(), period.from.toString(), period.through.toString(),
                    period.through.toString(), period.through.toString(), bookId, period.through.toString()))).use { cursor ->
                check(cursor.moveToFirst()); List(3) { cursor.getLong(it) }
            }
            val trend = buildList {
                var date = period.from
                while (date <= period.through) {
                    dailyPosted[date]?.let { openingPosted.liquid += it.liquid; openingPosted.investment += it.investment }
                    dailyCleared[date]?.let { openingCleared.liquid += it.liquid; openingCleared.investment += it.investment }
                    add(AssetTrendPoint(date, openingPosted.liquid.money(), openingCleared.liquid.money(),
                        openingPosted.investment.money(), openingCleared.investment.money(),
                        (openingPosted.liquid + openingPosted.investment).money(), (openingCleared.liquid + openingCleared.investment).money()))
                    date = date.plusDays(1)
                }
            }
            FinancialReport(bookId, book.revision, period, posted.result(counts[0]), cleared.result(counts[1]), accounts.map { account ->
                val sums = accountSums.getValue(account.id)
                ReportAccount(account.id, account.name, AccountKind.valueOf(account.kind), account.archived,
                    sums.openingPosted.money(), sums.openingCleared.money(), sums.closingPosted.money(), sums.closingCleared.money())
            }, trend, counts[2], listOf(
                "Actual sealed journals only; planned schedules, virtual allocations and projected income are excluded.",
                "Posted activity uses effective dates. Cleared activity uses actual clearing dates, which may be later.",
                "Transfers are not income or consumption expense; their fees are expense. Opening and reconciliation equity are reported separately.",
                "Refunds and reversals remain signed on their recorded recognition dates; prior periods are not silently rewritten.",
                "Tracked assets include archived accounts and manual investment book values. Liabilities, live market valuations and total personal net worth are not provided.",
                "Account labels and archive status are current metadata. Dated amounts include all currently recorded history, including later-entered backdated corrections; this is not an archived knowledge-at-the-time snapshot."
            ))
        }
    }
    override fun observe(bookId: String, period: ReportPeriod) = db.ledgerDao().revision(bookId).map { report(bookId, period) }
}
