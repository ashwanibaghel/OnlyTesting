package com.fintrack.data.schedules

import com.fintrack.domain.ledger.*
import com.fintrack.engine.Money
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.LocalDate
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class TemplateCodecTest {
    private val date = LocalDate.of(2026, 10, 23)
    @Test fun exactLongAndUnicodeIncomeRoundTrip() {
        val source = TransactionDraft.Income("bank", Money(Long.MAX_VALUE, INR), "Salary ₹", date, Clearing.PENDING, "वेतन")
        assertEquals(source, TemplateCodec.decode(TemplateCodec.encode(source)))
    }
    @Test fun splitAndTransferRoundTripWithoutFloatingPoint() {
        val source = TransactionDraft.Expense("bank", Money(3, INR), listOf(Split("Food", Money(1, INR)), Split("Travel", Money(2, INR))), date)
        assertEquals(source, TemplateCodec.decode(TemplateCodec.encode(source)))
        val transfer = TransactionDraft.Transfer("bank", "cash", Money(100, INR), Money(5, INR), date)
        assertEquals(transfer, TemplateCodec.decode(TemplateCodec.encode(transfer)))
    }
    @Test(expected = IllegalArgumentException::class) fun unknownVersionIsRejected() {
        val bytes = ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(2) }.toByteArray()
        TemplateCodec.decode(Base64.getEncoder().encodeToString(bytes))
    }
    @Test(expected = IllegalArgumentException::class) fun trailingDataIsRejected() {
        val source = TransactionDraft.Income("bank", Money(1, INR), "Salary", date)
        val data = Base64.getDecoder().decode(TemplateCodec.encode(source)) + byteArrayOf(1)
        TemplateCodec.decode(Base64.getEncoder().encodeToString(data))
    }
    @Test(expected = IllegalArgumentException::class) fun maliciousSplitCountIsRejectedBeforeAllocation() {
        val data = ByteArrayOutputStream()
        DataOutputStream(data).use {
            it.writeInt(1); it.writeUTF(date.toString()); it.writeUTF("CLEARED"); it.writeUTF("")
            it.writeInt(2); it.writeUTF("bank"); it.writeLong(1); it.writeInt(Int.MAX_VALUE)
        }
        TemplateCodec.decode(Base64.getEncoder().encodeToString(data.toByteArray()))
    }
    @Test(expected = IllegalArgumentException::class) fun malformedEncodingIsRejected() { TemplateCodec.decode("%%invalid%%") }
    @Test(expected = IllegalArgumentException::class) fun oversizedEncodingIsRejected() { TemplateCodec.decode("a".repeat(100001)) }
}
