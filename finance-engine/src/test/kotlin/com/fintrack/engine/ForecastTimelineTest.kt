package com.fintrack.engine

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ForecastTimelineTest {
    private val c = CurrencySpec("INR", 2)
    private val today = LocalDate.of(2028, 2, 28)
    private fun m(value: Long) = Money(value, c)
    private val assumptions = listOf("Only the supplied dated plans are projected")
    @Test fun emptyDaysCarryActualOpeningWithoutInventingIncome() {
        val result = forecastTimeline(today, today.plusDays(2), m(12345), emptyList(), assumptions)
        assertEquals(3, result.size); assertTrue(result.all { it.opening == m(12345) && it.closing == m(12345) && it.minimum == m(12345) })
        assertEquals(listOf(today, LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 1)), result.map { it.date })
    }
    @Test fun sameDayCashTroughIsVisibleEvenWithPositiveClosing() {
        val events = listOf(CashEvent("salary", today, m(100)), CashEvent("bill", today, m(-150)))
        val result = forecastTimeline(today, today.plusDays(1), m(100), events, assumptions)
        assertEquals(m(-50), result.first().minimum); assertEquals(m(50), result.first().closing)
        assertEquals(m(50), result.last().opening)
        val base = forecast(today, today.plusDays(1), m(100), events, assumptions)
        assertEquals(base.closing, result.last().closing); assertEquals(base.minimum, result.minBy { it.minimum.minor }.minimum)
    }
    @Test fun minorUnitsStayExactAcrossDailyPoints() {
        val result = forecastTimeline(today, today.plusDays(1), m(2), listOf(CashEvent("purchase", today, m(-1))), assumptions)
        assertEquals(m(1), result.last().closing)
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedTimelineIsRejectedBeforeExpandingDates() {
        forecastTimeline(today, today.plusYears(20), m(1), emptyList(), assumptions)
    }
    @Test(expected = IllegalArgumentException::class) fun currencyMismatchIsRejected() {
        forecastTimeline(today, today, m(1), listOf(CashEvent("foreign", today, Money(1, CurrencySpec("USD", 2)))), assumptions)
    }
    @Test(expected = IllegalArgumentException::class) fun chartCannotAcceptUndisclosedAssumptions() {
        forecastTimeline(today, today, m(1), emptyList(), emptyList())
    }
}
