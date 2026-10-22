package com.fintrack.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    private val inr = CurrencySpec("INR", 2)

    @Test fun parsesMinorUnitsExactly() { assertEquals(12345L, Money.parse("123.45", inr).minor) }
    @Test fun parsesNegativeAndZero() {
        assertEquals(-1L, Money.parse("-0.01", inr).minor)
        assertEquals(0L, Money.parse("0", inr).minor)
    }
    @Test fun supportsZeroAndThreeDecimalCurrencies() {
        assertEquals(12L, Money.parse("12", CurrencySpec("JPY", 0)).minor)
        assertEquals(12345L, Money.parse("12.345", CurrencySpec("KWD", 3)).minor)
    }
    @Test fun trailingZerosDoNotLoseValue() { assertEquals(100L, Money.parse("1.000", inr).minor) }
    @Test fun rejectsFractionalMinorUnit() {
        assertThrows(ArithmeticException::class.java) { Money.parse("0.001", inr) }
    }
    @Test fun rejectsMalformedOrLocaleAmbiguousInput() {
        listOf("", "NaN", "1e3", "1,000", " 1", "+1", ".5", "1.").forEach {
            assertThrows(IllegalArgumentException::class.java) { Money.parse(it, inr) }
        }
    }
    @Test fun checksCurrencyAndExponent() {
        assertThrows(IllegalArgumentException::class.java) { Money(1, inr) + Money(1, CurrencySpec("USD", 2)) }
        assertThrows(IllegalArgumentException::class.java) { Money(1, inr) - Money(1, CurrencySpec("INR", 3)) }
    }
    @Test fun rejectsInvalidCurrencyMetadata() {
        assertThrows(IllegalArgumentException::class.java) { CurrencySpec("inr", 2) }
        assertThrows(IllegalArgumentException::class.java) { CurrencySpec("INR", -1) }
        assertThrows(IllegalArgumentException::class.java) { CurrencySpec("INR", 4) }
    }
    @Test fun rejectsArithmeticOverflow() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE, inr) + Money(1, inr) }
        assertThrows(ArithmeticException::class.java) { Money(Long.MIN_VALUE, inr) - Money(1, inr) }
        assertThrows(ArithmeticException::class.java) { -Money(Long.MIN_VALUE, inr) }
        assertThrows(ArithmeticException::class.java) { Money.parse("92233720368547758.08", inr) }
    }
    @Test fun longBoundariesRoundTrip() {
        listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L).forEach {
            val money = Money(it, inr)
            assertEquals(money, Money.parse(money.toDecimal().toPlainString(), inr))
        }
    }
}
