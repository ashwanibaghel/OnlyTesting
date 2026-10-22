package com.fintrack.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AggregateTest {
    private val inr = CurrencySpec("INR", 2)
    @Test fun cancellationUsesExactIntermediates() {
        assertEquals(Money(Long.MAX_VALUE, inr), sumMoney(listOf(Long.MAX_VALUE, 1L, -1L).map { Money(it, inr) }, inr))
    }
    @Test fun rejectsUnrepresentableFinalBalance() {
        assertThrows(ArithmeticException::class.java) { sumMoney(listOf(Money(Long.MAX_VALUE, inr), Money(1, inr)), inr) }
    }
    @Test fun emptyAggregateIsExactZero() { assertEquals(Money(0, inr), sumMoney(emptyList(), inr)) }
    @Test fun minimumBoundaryAndNegativeIntermediateCancellationStayExact() {
        assertEquals(Money(Long.MIN_VALUE, inr), sumMoney(listOf(Long.MIN_VALUE, -1L, 1L).map { Money(it, inr) }, inr))
        assertThrows(ArithmeticException::class.java) { sumMoney(listOf(Money(Long.MIN_VALUE, inr), Money(-1, inr)), inr) }
    }
    @Test fun mixedCurrencyCannotHideInsideAggregate() {
        assertThrows(IllegalArgumentException::class.java) { sumMoney(listOf(Money(1, CurrencySpec("USD", 2))), inr) }
    }
}
