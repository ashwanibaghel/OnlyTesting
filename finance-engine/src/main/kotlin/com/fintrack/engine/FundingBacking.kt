package com.fintrack.engine

/** Sharp bounds for one claim when a shared backing shortfall has not been allocated. */
data class ClaimBacking(val claimed: Money, val minimum: Money, val maximum: Money) {
    init {
        claimed.requireCurrency(minimum); claimed.requireCurrency(maximum)
        require(claimed.minor >= 0 && minimum.minor >= 0 && minimum.minor <= maximum.minor && maximum.minor <= claimed.minor)
    }
    val fullyBacked: Boolean get() = minimum == claimed
    val ambiguous: Boolean get() = minimum != maximum
}

/** Per-claim maxima overlap; use backedTotal for the shared total, never their sum. */
data class BackingAssessment(val available: Money, val claimedTotal: Money, val backedTotal: Money,
    val unassigned: Money, val deficit: Money, val claims: Map<String, ClaimBacking>)

fun assessBacking(available: Money, claims: Map<String, Money>): BackingAssessment {
    claims.forEach { (id, amount) ->
        require(id.isNotBlank()) { "Missing funding claim identity" }
        available.requireCurrency(amount); require(amount.minor >= 0) { "Negative funding claim" }
    }
    val total = sumMoney(claims.values, available.currency)
    val physical = available.minor.coerceAtLeast(0)
    val backed = minOf(total.minor, physical)
    val shortage = Math.subtractExact(total.minor, backed)
    fun m(value: Long) = Money(value, available.currency)
    val bounds = claims.toSortedMap().mapValues { (_, claim) ->
        ClaimBacking(claim, m((claim.minor - shortage).coerceAtLeast(0)), m(minOf(claim.minor, physical)))
    }
    return BackingAssessment(available, total, m(backed), m(Math.subtractExact(physical, backed)), m(shortage), bounds)
}
