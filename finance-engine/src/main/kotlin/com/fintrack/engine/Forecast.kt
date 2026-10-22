package com.fintrack.engine

import java.time.LocalDate

data class CashEvent(val id: String, val date: LocalDate, val delta: Money) {
    init {
        require(id.isNotBlank()) { "Missing cash event ID" }
        require(delta.minor != 0L) { "Cash event must change cash" }
    }
}

data class ForecastResult(
    val asOf: LocalDate,
    val through: LocalDate,
    val closing: Money,
    val minimum: Money,
    val firstShortfall: LocalDate?,
    val assumptions: List<String>,
)

fun forecast(
    asOf: LocalDate,
    through: LocalDate,
    opening: Money,
    events: List<CashEvent>,
    assumptions: List<String>,
): ForecastResult {
    require(!through.isBefore(asOf)) { "Forecast horizon ends before it starts" }
    require(assumptions.isNotEmpty() && assumptions.all { it.isNotBlank() }) {
        "Forecast assumptions must be disclosed"
    }
    require(events.map { it.id }.toSet().size == events.size) { "Duplicate forecast event ID" }
    events.forEach {
        opening.requireCurrency(it.delta)
        require(!it.date.isBefore(asOf) && !it.date.isAfter(through)) { "Event outside forecast horizon" }
    }
    // Conservative same-day ordering exposes a shortfall before expected income arrives.
    val ordered = events.sortedWith(compareBy<CashEvent> { it.date }
        .thenBy { if (it.delta.minor < 0L) 0 else 1 }.thenBy { it.id })
    var balance = opening
    var minimum = opening
    var shortfall: LocalDate? = if (opening.minor < 0L) asOf else null
    ordered.forEach {
        balance += it.delta
        if (balance.minor < minimum.minor) minimum = balance
        if (shortfall == null && balance.minor < 0L) shortfall = it.date
    }
    return ForecastResult(asOf, through, balance, minimum, shortfall, assumptions.toList())
}
