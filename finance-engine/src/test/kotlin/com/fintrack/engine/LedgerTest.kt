package com.fintrack.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random

class LedgerTest {
    private val inr = CurrencySpec("INR", 2)
    private fun p(id: String, minor: Long) = Posting(id, Money(minor, inr))

    @Test fun transferConservesActualMoney() {
        val deltas = accountDeltas(transfer("bank", "cash", Money(10000, inr)))
        assertEquals(-10000L, deltas.getValue("bank").minor)
        assertEquals(10000L, deltas.getValue("cash").minor)
        assertEquals(0L, deltas.values.sumOf { it.minor })
    }
    @Test fun splitExpenseBalancesExactly() {
        validateJournal(listOf(p("bank", -10001), p("food", 5000), p("travel", 5001)))
    }
    @Test fun rejectsOffByOneSplit() {
        assertThrows(IllegalArgumentException::class.java) {
            validateJournal(listOf(p("bank", -10001), p("food", 5000), p("travel", 5000)))
        }
    }
    @Test fun rejectsIncompleteJournals() {
        assertThrows(IllegalArgumentException::class.java) { validateJournal(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { validateJournal(listOf(p("bank", 1))) }
    }
    @Test fun rejectsZeroLegAndMissingAccount() {
        assertThrows(IllegalArgumentException::class.java) { p("bank", 0) }
        assertThrows(IllegalArgumentException::class.java) { p(" ", 1) }
    }
    @Test fun rejectsMixedCurrencyJournal() {
        assertThrows(IllegalArgumentException::class.java) {
            validateJournal(listOf(p("bank", -1), Posting("cash", Money(1, CurrencySpec("USD", 2)))))
        }
    }
    @Test fun rejectsSelfTransferAndNonpositiveAmounts() {
        assertThrows(IllegalArgumentException::class.java) { transfer("a", "a", Money(1, inr)) }
        listOf(0L, -1L, Long.MIN_VALUE).forEach {
            assertThrows(IllegalArgumentException::class.java) { transfer("a", "b", Money(it, inr)) }
        }
    }
    @Test fun exactIntermediateCancellationIsOrderIndependent() {
        val legs = listOf(p("a", Long.MAX_VALUE), p("a", 1), p("a", -1), p("b", -Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, accountDeltas(legs).getValue("a").minor)
        assertEquals(accountDeltas(legs), accountDeltas(legs.reversed()))
    }
    @Test fun rejectsFinalAccountOverflow() {
        assertThrows(ArithmeticException::class.java) {
            accountDeltas(listOf(p("a", Long.MAX_VALUE), p("a", 1), p("b", Long.MIN_VALUE)))
        }
    }
    @Test fun reversalCancelsEveryAccount() {
        val legs = listOf(p("bank", -101), p("food", 100), p("fees", 1))
        val reversal = legs.map { it.copy(amount = -it.amount) }
        assertEquals(setOf(0L), accountDeltas(legs + reversal).values.map { it.minor }.toSet())
    }
    @Test fun seededTransfersConserveForManyAmounts() {
        val random = Random(20261023)
        repeat(1000) {
            val amount = (random.nextLong().ushr(1)).coerceAtLeast(1)
            val deltas = accountDeltas(transfer("a", "b", Money(amount, inr)))
            assertEquals(0L, deltas.getValue("a").minor + deltas.getValue("b").minor)
        }
    }
}
