package com.fintrack.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ForecastDay(val date: LocalDate, val opening: Money, val closing: Money, val minimum: Money)

/** Daily chart data is the same cash model, including the conservative intraday trough. */
fun forecastTimeline(asOf: LocalDate, through: LocalDate, opening: Money,
    events: List<CashEvent>, assumptions: List<String>): List<ForecastDay> {
    require(through >= asOf && ChronoUnit.DAYS.between(asOf, through) <= 3660) { "Timeline horizon must be at most ten years" }
    val stable = events.toList()
    forecast(asOf, through, opening, stable, assumptions) // Shared currency/identity/date/overflow validation.
    val daily = stable.groupBy { it.date }
    val result = mutableListOf<ForecastDay>()
    var balance = opening
    var date = asOf
    while (true) {
        val before = balance
        var minimum = balance
        daily[date].orEmpty().sortedWith(compareBy<CashEvent> { if (it.delta.minor < 0) 0 else 1 }.thenBy { it.id }).forEach {
            balance += it.delta
            if (balance.minor < minimum.minor) minimum = balance
        }
        result += ForecastDay(date, before, balance, minimum)
        if (date == through) break
        date = date.plusDays(1)
    }
    return result
}
