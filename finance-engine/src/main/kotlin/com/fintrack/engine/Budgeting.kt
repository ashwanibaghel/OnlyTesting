package com.fintrack.engine

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class RolloverPolicy { OFF, POSITIVE_REMAINDER }

data class BudgetPosition(val allowance: Money, val spent: Money, val remaining: Money,
    val overspent: Money, val carryForward: Money)

/** Budget permission and virtual funding are intentionally separate inputs and projections. */
fun budgetPosition(limit: Money, carried: Money, spent: Money, policy: RolloverPolicy): BudgetPosition {
    limit.requireCurrency(carried); limit.requireCurrency(spent)
    require(limit.minor >= 0 && carried.minor >= 0) { "Budget target and carried allowance cannot be negative" }
    val allowance = limit + carried
    val raw = allowance - spent
    val remaining = Money(raw.minor.coerceAtLeast(0), limit.currency)
    val overspent = if (raw.minor < 0) -raw else Money(0, limit.currency)
    return BudgetPosition(allowance, spent, remaining, overspent,
        if (policy == RolloverPolicy.POSITIVE_REMAINDER) remaining else Money(0, limit.currency))
}

data class ReservationPosition(val reserved: Money, val unassigned: Money, val deficit: Money)

fun reservationPosition(liquid: Money, reservations: List<Money>): ReservationPosition {
    reservations.forEach { liquid.requireCurrency(it); require(it.minor >= 0) { "Negative funded reserve" } }
    val reserved = sumMoney(reservations, liquid.currency)
    val raw = liquid - reserved
    return ReservationPosition(reserved, Money(raw.minor.coerceAtLeast(0), liquid.currency),
        if (raw.minor < 0) -raw else Money(0, liquid.currency))
}

/** Partial cover is assigned once, in stable occurrence order, never once per bill. */
data class Obligation(val id: String, val bucketId: String?, val amount: Money)
data class ObligationCoverage(val id: String, val covered: Money, val uncovered: Money)

fun coverObligations(obligations: List<Obligation>, funded: Map<String, Money>, currency: CurrencySpec): List<ObligationCoverage> {
    require(obligations.map { it.id }.toSet().size == obligations.size) { "Duplicate obligation" }
    funded.values.forEach { require(it.currency == currency && it.minor >= 0) { "Invalid funded coverage" } }
    val balances = funded.mapValues { it.value.minor }.toMutableMap()
    return obligations.sortedBy { it.id }.map {
        require(it.id.isNotBlank() && it.amount.currency == currency && it.amount.minor >= 0) { "Invalid obligation" }
        val covered = minOf(it.amount.minor, balances[it.bucketId] ?: 0)
        it.bucketId?.let { id -> balances[id] = Math.subtractExact(balances[id] ?: 0, covered) }
        ObligationCoverage(it.id, Money(covered, currency), Money(Math.subtractExact(it.amount.minor, covered), currency))
    }
}

data class GoalPlan(val id: String, val target: Money, val funded: Money, val deadline: LocalDate,
    val priority: Int, val monthlyPlan: Money, val anchorDay: Int = 1)
data class GoalAssessment(val id: String, val remaining: Money, val periods: Int,
    val requiredMonthly: Money?, val plannedMonthly: Money, val affordableMonthly: Money,
    val monthlyShortfall: Money, val deadlineMissed: Boolean, val projectedCompletion: LocalDate?)
data class GoalCollisions(val assessments: List<GoalAssessment>, val requiredMonthly: Money,
    val monthlyCapacity: Money, val shortage: Money)

/** An anchored due date is clamped per month without changing the original anchor. */
fun contributionDate(month: YearMonth, anchorDay: Int): LocalDate {
    require(anchorDay in 1..31) { "Contribution day must be 1..31" }
    return month.atDay(minOf(anchorDay, month.lengthOfMonth()))
}

fun remainingContributionPeriods(asOf: LocalDate, deadline: LocalDate, anchorDay: Int): Int {
    require(anchorDay in 1..31) { "Contribution day must be 1..31" }
    if (deadline.isBefore(asOf)) return 0
    var first = YearMonth.from(asOf)
    if (contributionDate(first, anchorDay).isBefore(asOf)) first = first.plusMonths(1)
    var last = YearMonth.from(deadline)
    if (contributionDate(last, anchorDay).isAfter(deadline)) last = last.minusMonths(1)
    return (ChronoUnit.MONTHS.between(first, last) + 1).coerceAtLeast(0).toInt()
}

/** Capacity is explicitly projected monthly funding, never current spendable money. */
fun assessGoals(goals: List<GoalPlan>, asOf: LocalDate, capacity: Money): GoalCollisions {
    require(capacity.minor >= 0) { "Monthly capacity cannot be negative" }
    require(goals.map { it.id }.toSet().size == goals.size) { "Duplicate goal" }
    var free = capacity
    val results = goals.sortedWith(compareBy<GoalPlan> { it.priority }.thenBy { it.id }).map { goal ->
        listOf(goal.target, goal.funded, goal.monthlyPlan).forEach {
            capacity.requireCurrency(it); require(it.minor >= 0) { "Goal figures cannot be negative" }
        }
        require(goal.id.isNotBlank() && goal.priority in 1..100) { "Invalid goal identity or priority" }
        val remaining = Money((goal.target.minor - goal.funded.minor).coerceAtLeast(0), capacity.currency)
        val periods = remainingContributionPeriods(asOf, goal.deadline, goal.anchorDay)
        val required = when {
            remaining.minor == 0L -> Money(0, capacity.currency)
            periods == 0 -> null
            else -> requiredContribution(goal.target, goal.funded, periods)
        }
        val requested = Money(minOf(goal.monthlyPlan.minor, remaining.minor), capacity.currency)
        val affordable = Money(minOf(requested.minor, free.minor), capacity.currency)
        free -= affordable
        val shortage = Money(((required?.minor ?: remaining.minor) - affordable.minor).coerceAtLeast(0), capacity.currency)
        val completion = when {
            remaining.minor == 0L -> asOf
            affordable.minor == 0L -> null
            else -> {
                val months = remaining.minor / affordable.minor + if (remaining.minor % affordable.minor == 0L) 0 else 1
                var first = YearMonth.from(asOf)
                if (contributionDate(first, goal.anchorDay).isBefore(asOf)) first = first.plusMonths(1)
                // Dates beyond the supported planning horizon are disclosed as unknown.
                if (months > 1200) null else contributionDate(first.plusMonths(months - 1), goal.anchorDay)
            }
        }
        GoalAssessment(goal.id, remaining, periods, required, requested, affordable, shortage,
            remaining.minor > 0 && periods == 0, completion)
    }
    val required = sumMoney(results.mapNotNull { it.requiredMonthly }, capacity.currency)
    return GoalCollisions(results, required, capacity,
        Money((required.minor - capacity.minor).coerceAtLeast(0), capacity.currency))
}
