package com.fintrack.engine

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }
data class Recurrence(val start: LocalDate, val frequency: Frequency, val interval: Int = 1,
    val end: LocalDate? = null, val exceptions: Set<LocalDate> = emptySet())

/** Pure calendar expansion. A scheduled occurrence is a plan, not a posted transaction. */
fun occurrences(rule: Recurrence, from: LocalDate, through: LocalDate): List<LocalDate> {
    require(rule.interval in 1..365 && (rule.end == null || !rule.end.isBefore(rule.start))) { "Invalid recurrence interval or end" }
    require(!through.isBefore(from) && ChronoUnit.DAYS.between(from, through) <= 3660) { "Recurrence horizon must be at most ten years" }
    require(from.year in 1..9999 && through.year in 1..9999 && rule.start.year in 1..9999) { "Unsupported calendar range" }
    val first = maxOf(from, rule.start)
    val last = minOf(through, rule.end ?: through)
    if (first.isAfter(last)) return emptyList()
    return generateSequence(first) { if (it < last) it.plusDays(1) else null }.filter { date ->
        if (date in rule.exceptions) false else when (rule.frequency) {
            Frequency.DAILY -> ChronoUnit.DAYS.between(rule.start, date) % rule.interval == 0L
            Frequency.WEEKLY -> ChronoUnit.DAYS.between(rule.start, date) % (7L * rule.interval) == 0L
            Frequency.MONTHLY -> ChronoUnit.MONTHS.between(YearMonth.from(rule.start), YearMonth.from(date)) % rule.interval == 0L &&
                date.dayOfMonth == minOf(rule.start.dayOfMonth, date.lengthOfMonth())
            Frequency.YEARLY -> (date.year - rule.start.year) % rule.interval == 0 && date.month == rule.start.month &&
                date.dayOfMonth == minOf(rule.start.dayOfMonth, date.lengthOfMonth())
        }
    }.toList()
}
