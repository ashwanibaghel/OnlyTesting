package com.fintrack.data.session

import androidx.room.withTransaction
import com.fintrack.data.local.FinTrackDatabase
import com.fintrack.data.schedules.RoomScheduleStore
import com.fintrack.domain.ledger.*
import com.fintrack.domain.reports.FinancialReportsRepository
import com.fintrack.domain.reports.ReportPeriod
import com.fintrack.domain.schedules.WeeklyReviewRecord
import com.fintrack.domain.session.*
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

class RoomFinancialOverviewRepository(private val db: FinTrackDatabase,
    private val sessions: FinanceSessionRepository, private val reports: FinancialReportsRepository,
    private val history: HistoryRepository, clock: Clock,
    private val clockPulses: Flow<Unit> = overviewPulses()) : FinancialOverviewRepository {
    private val schedules = RoomScheduleStore(db, clock)

    override suspend fun snapshot(bookId: String): FinancialOverview = db.withTransaction {
        val session = sessions.snapshot(bookId); val current = session.planning; val today = session.today
        val month = YearMonth.from(today); val week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val monthly = reports.report(bookId, ReportPeriod(month.atDay(1), today))
        val weekly = if (week == month.atDay(1)) monthly else reports.report(bookId, ReportPeriod(week, today))
        val recent = history.page(bookId, size = 10)
        val pending = history.page(bookId, HistoryQuery(clearing = HistoryClearing.PENDING, includeReversed = false), 20)
        val through = today.plusDays(current.profile.obligationHorizonDays.toLong())
        // Reuse the already-read funded buckets; do not rebuild the full planning snapshot just to read schedules.
        val planned = schedules.read(bookId, today, through, current.buckets)
        val recorded = db.scheduleDao().review(bookId, week.toString())?.let {
            WeeklyReviewRecord(LocalDate.parse(it.weekOf), it.reviewedAt, it.note, it.revision)
        }
        val status = WeeklyReviewStatus(week, recorded, recorded != null && session.revision > recorded.revision)
        check(monthly.bookId == bookId && weekly.bookId == bookId &&
            monthly.period == ReportPeriod(month.atDay(1), today) && weekly.period == ReportPeriod(week, today) &&
            planned.asOf == today && planned.through == through &&
            listOf(monthly.revision, weekly.revision, recent.revision, pending.revision, planned.revision).all { it == session.revision } &&
            db.ledgerDao().book(bookId)?.revision == session.revision) { "Overview sources disagree; refresh storage" }
        val attention = buildList {
            if (session.setupMode != SetupMode.COMPLETE) add(FinancialAttention.SetupNeeded(session.setupMode))
            session.pendingRolloverThrough?.let { add(FinancialAttention.PendingRollover(it)) }
            if (monthly.unresolvedPendingCount > 0) add(FinancialAttention.PendingClearing(monthly.unresolvedPendingCount))
            current.budgets.filter { it.month == month && !it.closed && it.position.overspent.minor > 0 }.forEach {
                add(FinancialAttention.BudgetOverrun(it.bucketId, it.category, it.position.overspent))
            }
            if (current.reservations.deficit.minor > 0) add(FinancialAttention.ReservationShortfall(current.reservations.deficit))
            if (current.uncoveredObligations.minor > 0) add(FinancialAttention.UncoveredObligations(current.uncoveredObligations, through))
            if (current.safeToSpend.deficit.minor > 0) add(FinancialAttention.SafetyDeficit(current.safeToSpend.deficit))
            val affected = current.goalFunding.filter { it.requiresBackingReview }.map { it.bucketId }.sorted()
            if (affected.isNotEmpty()) add(FinancialAttention.GoalBackingReview(affected))
            if (current.goalCollisions.shortage.minor > 0) add(FinancialAttention.GoalCollision(current.goalCollisions.shortage))
            current.goalCollisions.assessments.filter { it.deadlineMissed }.forEach { add(FinancialAttention.MissedGoalDeadline(it.id)) }
            if (recorded == null) add(FinancialAttention.WeeklyReviewDue(week))
            if (status.changedSinceReview) add(FinancialAttention.ChangedSinceReview(recorded!!.revision, session.revision))
        }
        FinancialOverview(session, monthly, weekly, recent, pending, planned, status, attention)
    }
    override fun observe(bookId: String): Flow<FinancialOverview> = merge(
        db.ledgerDao().revision(bookId).map { Unit }, clockPulses
    ).map { snapshot(bookId) }.distinctUntilChanged()
}
private fun overviewPulses(): Flow<Unit> = flow {
    while (true) { delay(60_000); emit(Unit) }
}
