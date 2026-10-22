package com.fintrack.engine

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BudgetingTest {
    private val currency = CurrencySpec("INR", 2)
    private fun m(value: Long) = Money(value, currency)
    private val today = LocalDate.of(2026, 10, 1)
    private fun goal(id: String, priority: Int = 1, funded: Long = 0, deadline: LocalDate = today.plusMonths(2)) =
        GoalPlan(id, m(900), m(funded), deadline, priority, m(300))

    @Test fun rolloverOffReleasesUnusedPermission() {
        val result = budgetPosition(m(100), m(20), m(80), RolloverPolicy.OFF)
        assertEquals(m(40), result.remaining); assertEquals(m(0), result.carryForward)
    }
    @Test fun positiveRolloverDoesNotCarryOverspendAsMoney() {
        assertEquals(m(40), budgetPosition(m(100), m(20), m(80), RolloverPolicy.POSITIVE_REMAINDER).carryForward)
        val overspent = budgetPosition(m(100), m(0), m(130), RolloverPolicy.POSITIVE_REMAINDER)
        assertEquals(m(30), overspent.overspent); assertEquals(m(0), overspent.carryForward)
    }
    @Test fun signedRefundChangesSpendingWithoutInventingFunding() {
        assertEquals(m(120), budgetPosition(m(100), m(0), m(-20), RolloverPolicy.OFF).remaining)
        assertEquals(m(0), reservationPosition(m(100), emptyList()).reserved)
    }
    @Test fun allocationsExposeNegativeCashDeficit() {
        val result = reservationPosition(m(-10), listOf(m(20), m(30)))
        assertEquals(m(50), result.reserved); assertEquals(m(0), result.unassigned); assertEquals(m(60), result.deficit)
    }
    @Test fun fundedCoverageIsConsumedOnlyOnceAcrossBills() {
        val result = coverObligations(listOf(Obligation("b", "food", m(80)), Obligation("a", "food", m(80))), mapOf("food" to m(100)), currency)
        assertEquals(listOf(m(80), m(20)), result.map { it.covered })
        assertEquals(m(60), sumMoney(result.map { it.uncovered }, currency))
        assertEquals(m(40), safeToSpend(m(200), m(100), m(60), m(0)).available)
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateObligationIsRejected() {
        coverObligations(listOf(Obligation("a", null, m(1)), Obligation("a", null, m(1))), emptyMap(), currency)
    }
    @Test(expected = ArithmeticException::class) fun reservationOverflowIsVisible() {
        reservationPosition(m(Long.MAX_VALUE), listOf(m(Long.MAX_VALUE), m(1)))
    }
    @Test(expected = ArithmeticException::class) fun budgetOverflowIsVisible() {
        budgetPosition(m(Long.MAX_VALUE), m(1), m(0), RolloverPolicy.OFF)
    }
    @Test fun monthlyAnchorAndDeadlineExcludeMissedContributionDates() {
        assertEquals(2, remainingContributionPeriods(today.plusDays(1), today.plusMonths(2), 1))
        assertEquals(0, remainingContributionPeriods(today, today.minusDays(1), 1))
        assertEquals(LocalDate.of(2028, 2, 29), contributionDate(java.time.YearMonth.of(2028, 2), 31))
    }
    @Test fun priorityAndStableIdDetermineCollisionWithoutInventingMoney() {
        val result = assessGoals(listOf(goal("b", 2), goal("a")), today, m(400))
        assertEquals(listOf("a", "b"), result.assessments.map { it.id })
        assertEquals(listOf(m(300), m(100)), result.assessments.map { it.affordableMonthly })
        assertEquals(m(200), result.shortage)
        assertEquals(m(200), result.assessments.last().monthlyShortfall)
    }
    @Test fun fundingAndDiversionRecalculateRequiredContributions() {
        val before = assessGoals(listOf(goal("a", funded = 600)), today, m(300)).assessments.single()
        val after = assessGoals(listOf(goal("a", funded = 300)), today, m(300)).assessments.single()
        assertEquals(m(100), before.requiredMonthly); assertEquals(m(200), after.requiredMonthly)
    }
    @Test fun expiredAndFundedGoalsRemainHonest() {
        val expired = assessGoals(listOf(goal("a", deadline = today.minusDays(1))), today, m(0)).assessments.single()
        assertTrue(expired.deadlineMissed); assertNull(expired.requiredMonthly); assertNull(expired.projectedCompletion)
        val complete = assessGoals(listOf(goal("a", funded = 1000)), today, m(0)).assessments.single()
        assertEquals(m(0), complete.remaining); assertEquals(today, complete.projectedCompletion)
    }
    @Test(expected = IllegalArgumentException::class) fun negativeReservationIsRejected() {
        reservationPosition(m(100), listOf(m(-1)))
    }
    @Test fun currentMoneyNeverIncludesMonthlyProjectedCapacity() {
        assessGoals(listOf(goal("a")), today, m(10000))
        assertEquals(m(10), safeToSpend(m(10), m(0), m(0), m(0)).available)
    }
}
