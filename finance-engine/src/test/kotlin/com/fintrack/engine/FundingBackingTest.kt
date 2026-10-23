package com.fintrack.engine

import org.junit.Assert.*
import org.junit.Test

class FundingBackingTest {
    private val currency = CurrencySpec("INR", 2)
    private fun m(value: Long) = Money(value, currency)
    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        throw AssertionError("Expected invalid backing rejection")
    }
    @Test fun fullyBackedClaimsLeaveExactUnassignedMoney() {
        val result = assessBacking(m(1000), mapOf("a" to m(300), "b" to m(200)))
        assertEquals(m(500), result.backedTotal); assertEquals(m(500), result.unassigned); assertEquals(m(0), result.deficit)
        assertTrue(result.claims.values.all { it.fullyBacked && !it.ambiguous })
    }
    @Test fun oneUnsupportedClaimKeepsNominalEntitlementButReportsActualBacking() {
        val result = assessBacking(m(500), mapOf("a" to m(800)))
        assertEquals(ClaimBacking(m(800), m(500), m(500)), result.claims.getValue("a"))
        assertEquals(m(300), result.deficit); assertEquals(m(0), result.unassigned)
    }
    @Test fun sharedShortfallProducesRangesWithoutInventingLossPriority() {
        val result = assessBacking(m(500), mapOf("a" to m(400), "b" to m(400)))
        assertEquals(m(500), result.backedTotal); assertEquals(m(300), result.deficit)
        assertEquals(ClaimBacking(m(400), m(100), m(400)), result.claims.getValue("a"))
        assertEquals(result.claims.getValue("a"), result.claims.getValue("b"))
        assertTrue(result.claims.values.all { it.ambiguous })
        // Upper bounds overlap: the global total is 500, never 400 + 400.
        assertEquals(m(800), sumMoney(result.claims.values.map { it.maximum }, currency))
    }
    @Test fun negativeActualMoneyCannotBackAClaim() {
        val result = assessBacking(m(Long.MIN_VALUE), mapOf("a" to m(10)))
        assertEquals(m(Long.MIN_VALUE), result.available)
        assertEquals(m(0), result.backedTotal); assertEquals(m(10), result.deficit)
        assertEquals(ClaimBacking(m(10), m(0), m(0)), result.claims.getValue("a"))
    }
    @Test fun invalidClaimsCurrenciesAndBoundsAreRejected() {
        rejects { assessBacking(m(100), mapOf("" to m(1))) }
        rejects { assessBacking(m(100), mapOf("a" to m(-1))) }
        rejects { assessBacking(m(100), mapOf("a" to Money(1, CurrencySpec("USD", 2)))) }
        rejects { ClaimBacking(m(1), m(2), m(2)) }
        rejects { ClaimBacking(m(2), m(2), m(1)) }
    }
    @Test fun maximumRangeIsCheckedWithoutOverflowOrClipping() {
        val result = assessBacking(m(Long.MAX_VALUE - 1), mapOf("a" to m(Long.MAX_VALUE)))
        assertEquals(m(1), result.deficit); assertEquals(m(Long.MAX_VALUE - 1), result.claims.getValue("a").minimum)
        try { assessBacking(m(Long.MAX_VALUE), mapOf("a" to m(Long.MAX_VALUE), "b" to m(1))); fail("Overflow must be rejected") }
        catch (_: ArithmeticException) { }
    }
    @Test fun exhaustiveSmallAllocationsProveSharpBoundsAndConservation() {
        for (a in 0..4) for (b in 0..4) for (c in 0..4) {
            val nominal = listOf(a, b, c)
            for (cash in 0..(a + b + c + 2)) {
                val expectedTotal = minOf(cash, a + b + c)
                val feasible = mutableListOf<List<Int>>()
                for (x in 0..a) for (y in 0..b) for (z in 0..c)
                    if (x + y + z == expectedTotal) feasible += listOf(x, y, z)
                val result = assessBacking(m(cash.toLong()), nominal.mapIndexed { index, value -> "$index" to m(value.toLong()) }.toMap())
                assertEquals(m(expectedTotal.toLong()), result.backedTotal)
                for (index in nominal.indices) {
                    val bound = result.claims.getValue("$index")
                    assertEquals(m(feasible.minOf { it[index] }.toLong()), bound.minimum)
                    assertEquals(m(feasible.maxOf { it[index] }.toLong()), bound.maximum)
                }
                assertTrue(sumMoney(result.claims.values.map { it.minimum }, currency).minor <= result.backedTotal.minor)
                assertEquals(m(cash.toLong()), result.backedTotal + result.unassigned)
            }
        }
    }
}
