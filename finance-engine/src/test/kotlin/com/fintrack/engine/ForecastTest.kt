package com.fintrack.engine

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ForecastTest {
    private val inr = CurrencySpec("INR", 2)
    private val start = LocalDate.of(2026, 10, 23)
    private val end = start.plusDays(7)
    private val assumptions = listOf("Manual expected income; no interest, fees or market returns")
    private fun m(value: Long) = Money(value, inr)
    private fun e(id: String, value: Long, date: LocalDate = start) = CashEvent(id, date, m(value))
    private fun run(events: List<CashEvent>, opening: Long = 100) =
        forecast(start, end, m(opening), events, assumptions)

    @Test fun emptyForecastPreservesOpeningAndAssumptions() {
        val result = run(emptyList())
        assertEquals(m(100), result.closing)
        assertEquals(m(100), result.minimum)
        assertEquals(assumptions, result.assumptions)
        assertNull(result.firstShortfall)
    }
    @Test fun sameDayOutflowsPrecedeIncomeAndExposeShortfall() {
        val result = run(listOf(e("income", 100), e("rent", -150)))
        assertEquals(m(50), result.closing)
        assertEquals(m(-50), result.minimum)
        assertEquals(start, result.firstShortfall)
    }
    @Test fun deterministicAcrossInputOrder() {
        val events = listOf(e("a", 50), e("b", -80), e("c", -40, end))
        assertEquals(run(events), run(events.reversed()))
    }
    @Test fun handlesInclusiveHorizonAndYearBoundary() {
        val date = LocalDate.of(2024, 12, 31)
        val result = forecast(date, date.plusDays(1), m(1),
            listOf(e("boundary", 1, date.plusDays(1))), assumptions)
        assertEquals(m(2), result.closing)
    }
    @Test fun negativeOpeningIsImmediateShortfall() {
        assertEquals(start, run(listOf(e("income", 100)), -1).firstShortfall)
    }
    @Test fun rejectsInvalidHorizonOrOutsideEvents() {
        assertThrows(IllegalArgumentException::class.java) {
            forecast(end, start, m(0), emptyList(), assumptions)
        }
        listOf(start.minusDays(1), end.plusDays(1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { run(listOf(e("outside", 1, it))) }
        }
    }
    @Test fun requiresExplicitAssumptions() {
        assertThrows(IllegalArgumentException::class.java) { forecast(start, end, m(0), emptyList(), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { forecast(start, end, m(0), emptyList(), listOf(" ")) }
    }
    @Test fun rejectsDuplicateEventIds() {
        assertThrows(IllegalArgumentException::class.java) { run(listOf(e("same", 10), e("same", -5))) }
    }
    @Test fun rejectsCurrencyMismatchAndOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            run(listOf(CashEvent("usd", start, Money(1, CurrencySpec("USD", 2)))))
        }
        assertThrows(ArithmeticException::class.java) { run(listOf(e("overflow", 1)), Long.MAX_VALUE) }
    }
    @Test fun rejectsInvalidEventMetadata() {
        assertThrows(IllegalArgumentException::class.java) { e(" ", 1) }
        assertThrows(IllegalArgumentException::class.java) { e("zero", 0) }
    }
    @Test fun doesNotMutateInputsOrRetainAssumptionsList() {
        val list = mutableListOf("Expected income is not guaranteed")
        val events = listOf(e("rent", -10))
        val result = forecast(start, end, m(100), events, list)
        list.clear()
        assertEquals(1, result.assumptions.size)
        assertEquals(listOf(e("rent", -10)), events)
    }
}
