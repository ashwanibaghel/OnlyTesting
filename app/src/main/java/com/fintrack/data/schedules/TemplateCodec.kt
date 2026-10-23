package com.fintrack.data.schedules

import com.fintrack.domain.ledger.*
import com.fintrack.engine.Money
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.LocalDate
import java.util.Base64

/** Versioned typed data only; no executable SQL and no decimal/float conversion. */
internal object TemplateCodec {
    fun encode(draft: TransactionDraft): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(1); out.writeUTF(draft.date.toString()); out.writeUTF(draft.clearing.name); out.writeUTF(draft.memo)
            fun amount(money: Money) { require(money.currency == INR); out.writeLong(money.minor) }
            when (draft) {
                is TransactionDraft.Income -> { out.writeInt(1); out.writeUTF(draft.accountId); amount(draft.amount); out.writeUTF(draft.category) }
                is TransactionDraft.Expense -> {
                    out.writeInt(2); out.writeUTF(draft.accountId); amount(draft.amount); out.writeInt(draft.splits.size)
                    draft.splits.forEach { out.writeUTF(it.category); amount(it.amount) }
                }
                is TransactionDraft.Transfer -> { out.writeInt(3); out.writeUTF(draft.sourceId); out.writeUTF(draft.destinationId); amount(draft.amount); amount(draft.fee) }
            }
        }
        return Base64.getEncoder().encodeToString(buffer.toByteArray())
    }
    fun decode(encoded: String): TransactionDraft {
        require(encoded.length <= 100_000) { "Oversized transaction template" }
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            require(input.readInt() == 1) { "Unsupported transaction template version" }
            val date = LocalDate.parse(input.readUTF()); val clearing = Clearing.valueOf(input.readUTF()); val memo = input.readUTF()
            fun money() = Money(input.readLong(), INR)
            val result = when (input.readInt()) {
                1 -> TransactionDraft.Income(input.readUTF(), money(), input.readUTF(), date, clearing, memo)
                2 -> {
                    val account = input.readUTF(); val amount = money(); val count = input.readInt()
                    require(count in 1..100) { "Invalid template split count" }
                    TransactionDraft.Expense(account, amount, List(count) { Split(input.readUTF(), money()) }, date, clearing, memo)
                }
                3 -> TransactionDraft.Transfer(input.readUTF(), input.readUTF(), money(), money(), date, clearing, memo)
                else -> error("Unsupported transaction template kind")
            }
            require(input.available() == 0) { "Trailing transaction template data" }
            return result
        }
    }
}
