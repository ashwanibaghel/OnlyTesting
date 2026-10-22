package com.fintrack.engine

import java.math.BigInteger

data class Posting(val accountId: String, val amount: Money) {
    init {
        require(accountId.isNotBlank()) { "Missing ledger account ID" }
        require(amount.minor != 0L) { "Zero postings are not journal legs" }
    }
}

/** Mathematical validation only. Repository must also validate account roles and command semantics. */
fun validateJournal(postings: List<Posting>) {
    require(postings.size >= 2) { "Journal needs at least two legs" }
    val currency = postings.first().amount.currency
    require(postings.all { it.amount.currency == currency }) { "Journal currency mismatch" }
    val sum = postings.fold(BigInteger.ZERO) { total, posting ->
        total + BigInteger.valueOf(posting.amount.minor)
    }
    require(sum == BigInteger.ZERO) { "Unbalanced journal" }
}

fun accountDeltas(postings: List<Posting>): Map<String, Money> {
    validateJournal(postings)
    val currency = postings.first().amount.currency
    return postings.groupBy { it.accountId }.mapValues { (_, legs) ->
        Money(exactSum(legs.map { it.amount.minor }), currency)
    }
}

fun transfer(sourceId: String, destinationId: String, amount: Money): List<Posting> {
    require(sourceId != destinationId) { "Transfer accounts must differ" }
    require(amount.minor > 0L) { "Transfer amount must be positive" }
    return listOf(Posting(sourceId, -amount), Posting(destinationId, amount))
}
