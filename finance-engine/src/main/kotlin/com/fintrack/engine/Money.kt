package com.fintrack.engine

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

data class CurrencySpec(val code: String, val exponent: Int) {
    init {
        require(code.matches(Regex("[A-Z]{3}"))) { "Currency code must have three uppercase letters" }
        require(exponent in 0..3) { "Unsupported currency exponent" }
    }
}

data class Money(val minor: Long, val currency: CurrencySpec) {
    operator fun plus(other: Money): Money {
        requireCurrency(other)
        return copy(minor = Math.addExact(minor, other.minor))
    }

    operator fun minus(other: Money): Money {
        requireCurrency(other)
        return copy(minor = Math.subtractExact(minor, other.minor))
    }

    operator fun unaryMinus(): Money = copy(minor = Math.negateExact(minor))

    fun requireCurrency(other: Money) {
        require(currency == other.currency) { "Mixed currencies or currency exponents" }
    }

    fun toDecimal(): BigDecimal = BigDecimal.valueOf(minor, currency.exponent)

    companion object {
        /** Canonical decimal input only. UI is responsible for explicit locale conversion. */
        fun parse(decimal: String, currency: CurrencySpec): Money {
            require(decimal.matches(Regex("-?[0-9]+(\\.[0-9]+)?"))) { "Invalid decimal amount" }
            val value = BigDecimal(decimal).setScale(currency.exponent, RoundingMode.UNNECESSARY)
                .movePointRight(currency.exponent).longValueExact()
            return Money(value, currency)
        }
    }
}

internal fun exactSum(values: Iterable<Long>): Long =
    values.fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value) }.toLongChecked()

/** BigInteger.longValueExact is Android API 31; this remains exact on our API 26 baseline. */
fun BigInteger.toLongChecked(): Long {
    if (this < BigInteger.valueOf(Long.MIN_VALUE) || this > BigInteger.valueOf(Long.MAX_VALUE)) {
        throw ArithmeticException("Amount exceeds signed 64-bit minor-unit range")
    }
    return toLong()
}

/** Aggregate exactly before checking final bounds; never use SQLite REAL or floating point. */
fun sumMoney(values: Iterable<Money>, currency: CurrencySpec): Money {
    val amounts = values.map {
        require(it.currency == currency) { "Mixed currencies in aggregate" }
        it.minor
    }
    return Money(exactSum(amounts), currency)
}
