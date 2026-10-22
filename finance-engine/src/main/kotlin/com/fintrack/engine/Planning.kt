package com.fintrack.engine

import java.math.BigInteger

data class SafeToSpend(val available: Money, val deficit: Money)

/** Reserved, uncovered obligations and buffer must represent disjoint commitments. */
fun safeToSpend(liquid: Money, reserved: Money, uncovered: Money, buffer: Money): SafeToSpend {
    listOf(reserved, uncovered, buffer).forEach {
        liquid.requireCurrency(it)
        require(it.minor >= 0L) { "Commitment cannot be negative" }
    }
    val raw = BigInteger.valueOf(liquid.minor) - BigInteger.valueOf(reserved.minor) -
        BigInteger.valueOf(uncovered.minor) - BigInteger.valueOf(buffer.minor)
    val available = raw.max(BigInteger.ZERO).toLongChecked()
    val deficit = raw.negate().max(BigInteger.ZERO).toLongChecked()
    return SafeToSpend(Money(available, liquid.currency), Money(deficit, liquid.currency))
}

/** Explicit period count prevents the engine from guessing whether this month is fundable. */
fun requiredContribution(target: Money, funded: Money, remainingPeriods: Int): Money {
    target.requireCurrency(funded)
    require(target.minor >= 0L && funded.minor >= 0L) { "Goal amounts cannot be negative" }
    require(remainingPeriods > 0) { "Contribution periods must be positive" }
    val remaining = (target.minor - funded.minor).coerceAtLeast(0L)
    val periods = remainingPeriods.toLong()
    val quotient = remaining / periods
    val remainder = remaining % periods
    return Money(quotient + if (remainder == 0L) 0L else 1L, target.currency)
}

data class DiversionPreview(val remaining: Money, val requiresProtectedFundsConfirmation: Boolean)

fun previewDiversion(funded: Money, amount: Money, protected: Boolean): DiversionPreview {
    funded.requireCurrency(amount)
    require(funded.minor >= 0L && amount.minor > 0L) { "Diversion must be positive" }
    require(amount.minor <= funded.minor) { "Diversion exceeds funded reserve" }
    return DiversionPreview(funded - amount, protected)
}
