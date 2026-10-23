package com.fintrack.engine

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RecurrenceTest {
    private fun d(value: String) = LocalDate.parse(value)
    @Test fun monthEndPreservesOriginalAnchor() {
        assertEquals(listOf(d("2026-01-31"), d("2026-02-28"), d("2026-03-31")),
            occurrences(Recurrence(d("2026-01-31"), Frequency.MONTHLY), d("2026-01-01"), d("2026-03-31")))
    }
    @Test fun leapDayYearlyClampsAndReturnsToLeapDay() {
        val result = occurrences(Recurrence(d("2024-02-29"), Frequency.YEARLY), d("2024-01-01"), d("2028-12-31"))
        assertEquals(listOf("2024-02-29", "2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"), result.map { it.toString() })
    }
    @Test fun missedRunCatchUpAndExceptionsAreDeterministic() {
        val rule = Recurrence(d("2026-10-01"), Frequency.WEEKLY, end = d("2026-10-25"), exceptions = setOf(d("2026-10-15")))
        val expected = listOf(d("2026-10-08"), d("2026-10-22"))
        assertEquals(expected, occurrences(rule, d("2026-10-05"), d("2026-11-01")))
        assertEquals(expected, occurrences(rule, d("2026-10-05"), d("2026-11-01")))
    }
    @Test fun intervalAndHorizonBoundExpansion() {
        assertEquals(listOf(d("2026-01-31"), d("2026-03-31")), occurrences(
            Recurrence(d("2026-01-31"), Frequency.MONTHLY, 2), d("2026-01-01"), d("2026-04-30")))
        assertTrue(occurrences(Recurrence(d("2027-01-01"), Frequency.DAILY), d("2026-01-01"), d("2026-12-31")).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedHorizonIsRejected() {
        occurrences(Recurrence(d("2026-01-01"), Frequency.DAILY), d("2026-01-01"), d("2040-01-01"))
    }
    @Test fun datesAreCalendarBasedAcrossDstAndYearBoundary() {
        val result = occurrences(Recurrence(d("2026-12-30"), Frequency.DAILY, 2), d("2026-12-30"), d("2027-01-03"))
        assertEquals(listOf("2026-12-30", "2027-01-01", "2027-01-03"), result.map { it.toString() })
    }
}
