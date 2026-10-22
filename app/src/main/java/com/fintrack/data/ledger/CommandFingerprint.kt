package com.fintrack.data.ledger

import com.fintrack.domain.ledger.*
import com.fintrack.engine.Money
import com.fintrack.data.planning.writePlanning
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Length-prefixed canonical encoding, not ambiguous concatenation or data-class toString. */
internal fun fingerprint(command: LedgerCommand): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        fun text(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            out.writeInt(encoded.size)
            out.write(encoded)
        }
        fun money(value: Money) { text(value.currency.code); out.writeInt(value.currency.exponent); out.writeLong(value.minor) }
        fun draft(value: TransactionDraft) {
            text(value.date.toString()); text(value.clearing.name); text(value.memo)
            when (value) {
                is TransactionDraft.Income -> { text("income"); text(value.accountId); money(value.amount); text(value.category) }
                is TransactionDraft.Expense -> {
                    text("expense"); text(value.accountId); money(value.amount); out.writeInt(value.splits.size)
                    value.splits.forEach { text(it.category); money(it.amount) }
                }
                is TransactionDraft.Transfer -> { text("transfer"); text(value.sourceId); text(value.destinationId); money(value.amount); money(value.fee) }
            }
        }
        text("fintrack-command-v1"); text(command.bookId); out.writeLong(command.expectedRevision)
        when (val action = command.action) {
            is LedgerAction.Plan -> { text("planning-v1"); writePlanning(out, action.operation) }
            is LedgerAction.CreateAccount -> { text("create"); text(action.accountId); text(action.name); text(action.kind.name); money(action.opening); text(action.date.toString()) }
            is LedgerAction.Post -> { text("post"); draft(action.draft) }
            is LedgerAction.Archive -> { text("archive"); text(action.accountId); out.writeBoolean(action.archived) }
            is LedgerAction.Reverse -> { text("reverse"); text(action.entryId); text(action.date.toString()); text(action.reason) }
            is LedgerAction.Correct -> { text("correct"); text(action.entryId); draft(action.replacement); text(action.reason) }
            is LedgerAction.Clear -> { text("clear"); text(action.entryId); text(action.date.toString()) }
            is LedgerAction.Reconcile -> { text("reconcile"); text(action.accountId); text(action.statementDate.toString()); money(action.statement); text(action.basis.name); text(action.reason) }
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
}
