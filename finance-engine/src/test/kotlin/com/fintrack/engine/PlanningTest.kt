package com.fintrack.engine

import org.junit.Assert.*
import org.junit.Test

class PlanningTest {
    private val inr = CurrencySpec("INR", 2)
    private fun m(value: Long) = Money(value, inr)

    @Test fun safeToSpendExplainsDeductions() {
        assertEquals(SafeToSpend(m(55000), m(0)), safeToSpend(m(100000), m(30000), m(10000), m(5000)))
    }
    @Test fun deficitIsVisibleWithoutNegativeAvailability() {
        assertEquals(SafeToSpend(m(0), m(15)), safeToSpend(m(10), m(20), m(5), m(0)))
    }
    @Test fun negativeLiquidAssetsReduceAvailability() {
        assertEquals(SafeToSpend(m(0), m(10)), safeToSpend(m(-10), m(0), m(0), m(0)))
    }
    @Test fun zeroSnapshotIsZero() {
        assertEquals(SafeToSpend(m(0), m(0)), safeToSpend(m(0), m(0), m(0), m(0)))
    }
    @Test fun rejectsNegativeCommitments() {
        assertThrows(IllegalArgumentException::class.java) { safeToSpend(m(1), m(-1), m(0), m(0)) }
        assertThrows(IllegalArgumentException::class.java) { safeToSpend(m(1), m(0), m(-1), m(0)) }
        assertThrows(IllegalArgumentException::class.java) { safeToSpend(m(1), m(0), m(0), m(-1)) }
    }
    @Test fun rejectsCurrencyMismatch() {
        val usd = Money(0, CurrencySpec("USD", 2))
        assertThrows(IllegalArgumentException::class.java) { safeToSpend(m(1), usd, m(0), m(0)) }
        assertThrows(IllegalArgumentException::class.java) { requiredContribution(m(1), usd, 1) }
        assertThrows(IllegalArgumentException::class.java) { previewDiversion(m(1), usd, true) }
    }
    @Test fun rejectsUnrepresentableDeficit() {
        assertThrows(ArithmeticException::class.java) { safeToSpend(m(Long.MIN_VALUE), m(0), m(0), m(0)) }
    }
    @Test fun reservationConsumptionDoesNotDeductSameBillTwice() {
        val before = safeToSpend(m(100000), m(20000), m(0), m(0))
        val after = safeToSpend(m(95000), m(15000), m(0), m(0))
        assertEquals(before, after)
    }
    @Test fun contributionRoundsUpOneMinorUnit() {
        assertEquals(m(334), requiredContribution(m(1000), m(0), 3))
        assertEquals(m(300), requiredContribution(m(1000), m(100), 3))
    }
    @Test fun fullyFundedAndOverfundedNeedZero() {
        assertEquals(m(0), requiredContribution(m(100), m(100), 2))
        assertEquals(m(0), requiredContribution(m(100), m(101), 2))
    }
    @Test fun contributionAtLongMaximumDoesNotOverflowRounding() {
        assertEquals(m(4611686018427387904L), requiredContribution(m(Long.MAX_VALUE), m(0), 2))
    }
    @Test fun rejectsInvalidGoalInputs() {
        assertThrows(IllegalArgumentException::class.java) { requiredContribution(m(100), m(0), 0) }
        assertThrows(IllegalArgumentException::class.java) { requiredContribution(m(100), m(0), -1) }
        assertThrows(IllegalArgumentException::class.java) { requiredContribution(m(-1), m(0), 1) }
        assertThrows(IllegalArgumentException::class.java) { requiredContribution(m(1), m(-1), 1) }
    }
    @Test fun protectedDiversionWarnsAndOnlyChangesVirtualPreview() {
        val actual = m(1000)
        val funded = m(500)
        val preview = previewDiversion(funded, m(100), true)
        assertEquals(m(400), preview.remaining)
        assertTrue(preview.requiresProtectedFundsConfirmation)
        assertEquals(m(1000), actual)
        assertEquals(m(500), funded)
    }
    @Test fun unprotectedDiversionCanEmptyBucket() {
        assertEquals(DiversionPreview(m(0), false), previewDiversion(m(500), m(500), false))
    }
    @Test fun rejectsOverdrawnOrNonpositiveDiversion() {
        listOf(-1L, 0L, 501L).forEach {
            assertThrows(IllegalArgumentException::class.java) { previewDiversion(m(500), m(it), true) }
        }
    }
}
