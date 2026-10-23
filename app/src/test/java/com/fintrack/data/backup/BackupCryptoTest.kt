package com.fintrack.data.backup

import android.app.Application
import java.io.*
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackupCryptoTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun password() = "SYNTHETIC backup passphrase 42".toCharArray()
    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking { block() }
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }; throw AssertionError("Expected rejection")
    }
    private suspend fun encrypted(crypto: BackupCrypto, bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        crypto.encrypt(output, password()) { it.write(bytes) }
    }.toByteArray()

    @Test fun multiSegmentRoundTripRemovesPrivatePlaintext() = scenario {
        val stage = temporary.newFolder("stage"); val crypto = BackupCrypto(stage)
        val bytes = ByteArray(2_200_000) { (it % 251).toByte() }; var held: File? = null
        val cipher = encrypted(crypto, bytes)
        crypto.authenticatedFile(ByteArrayInputStream(cipher), password()) { file ->
            held = file; assertArrayEquals(bytes, file.readBytes())
        }
        assertNotNull(held); assertFalse(held!!.exists()); assertTrue(stage.listFiles()!!.isEmpty())
    }
    @Test fun repeatedEncryptionUsesFreshKeysAndSalt() = scenario {
        val crypto = BackupCrypto(temporary.newFolder("stage")); val plain = "SYNTHETIC sensitive amount 123456.78".toByteArray()
        val first = encrypted(crypto, plain); val second = encrypted(crypto, plain)
        assertFalse(first.contentEquals(second)); assertFalse(String(first, Charsets.ISO_8859_1).contains("123456.78"))
        for (cipher in listOf(first, second)) crypto.authenticatedFile(ByteArrayInputStream(cipher), password()) { assertArrayEquals(plain, it.readBytes()) }
    }
    @Test fun wrongPasswordNeverCallsConsumer() = scenario {
        val stage = temporary.newFolder("stage"); val crypto = BackupCrypto(stage); val cipher = encrypted(crypto, byteArrayOf(1, 2, 3))
        rejects { crypto.authenticatedFile(ByteArrayInputStream(cipher), "SYNTHETIC incorrect passphrase".toCharArray()) { throw AssertionError("Unauthenticated consumer") } }
        assertTrue(stage.listFiles()!!.isEmpty())
    }
    @Test fun tamperedHeadersSegmentsAndFinalTagNeverPublishPlaintext() = scenario {
        val stage = temporary.newFolder("stage"); val crypto = BackupCrypto(stage)
        val cipher = encrypted(crypto, ByteArray(2_100_000) { (it % 239).toByte() })
        val offset = 36 + ByteBuffer.wrap(cipher, 32, 4).int
        for (position in listOf(16, offset + 100, offset + 1_100_000, cipher.lastIndex)) {
            val damaged = cipher.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            rejects { crypto.authenticatedFile(ByteArrayInputStream(damaged), password()) { throw AssertionError("Tampered consumer") } }
            assertTrue(stage.listFiles()!!.isEmpty())
        }
    }
    @Test fun truncationAndTrailingCiphertextAreRejected() = scenario {
        val stage = temporary.newFolder("stage"); val crypto = BackupCrypto(stage); val cipher = encrypted(crypto, ByteArray(1_100_000) { 7 })
        for (length in listOf(0, 31, 35, cipher.size / 2, cipher.size - 1)) {
            rejects { crypto.authenticatedFile(ByteArrayInputStream(cipher.copyOf(length)), password()) { throw AssertionError("Truncated consumer") } }
        }
        rejects { crypto.authenticatedFile(ByteArrayInputStream(cipher + byteArrayOf(1)), password()) { throw AssertionError("Trailing consumer") } }
        assertTrue(stage.listFiles()!!.isEmpty())
    }
    @Test fun boundsRejectBeforeUnboundedKeyOrPasswordWork() = scenario {
        val stage = temporary.newFolder("stage"); val crypto = BackupCrypto(stage); val cipher = encrypted(crypto, byteArrayOf(9))
        for ((offset, value) in listOf(8 to 2, 12 to -1, 12 to 2_000_001, 32 to -1, 32 to Int.MAX_VALUE)) {
            val bad = cipher.copyOf(); ByteBuffer.wrap(bad, offset, 4).putInt(value)
            rejects { crypto.authenticatedFile(ByteArrayInputStream(bad), password()) { throw AssertionError("Invalid header consumer") } }
        }
        for (size in listOf(11, 1025)) rejects { crypto.encrypt(ByteArrayOutputStream(), CharArray(size) { 'x' }) { it.write(1) } }
        val constrained = BackupCrypto(stage, 1000)
        rejects { encrypted(constrained, ByteArray(1001)) }
        val tooLarge = encrypted(crypto, ByteArray(1001))
        rejects { constrained.authenticatedFile(ByteArrayInputStream(tooLarge), password()) { throw AssertionError("Oversize consumer") } }
        assertTrue(stage.listFiles()!!.isEmpty())
    }
    @Test fun callerStreamsAndPasswordRemainOwnedByCaller() = scenario {
        val crypto = BackupCrypto(temporary.newFolder("stage")); val original = password(); val before = original.copyOf()
        var outputClosed = false; var inputClosed = false
        val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true; super.close() } }
        crypto.encrypt(output, original) { it.write(byteArrayOf(4, 5, 6)) }
        val input = object : ByteArrayInputStream(output.toByteArray()) { override fun close() { inputClosed = true; super.close() } }
        crypto.authenticatedFile(input, original) { assertArrayEquals(byteArrayOf(4, 5, 6), it.readBytes()) }
        assertFalse(inputClosed); assertFalse(outputClosed); assertArrayEquals(before, original)
    }
    @Test fun cancelledCopyDeletesPartialPrivateFile() = scenario {
        val stage = temporary.newFolder("stage"); val crypto = BackupCrypto(stage); val cipher = encrypted(crypto, ByteArray(2_100_000) { 3 })
        val copy = async {
            val job = currentCoroutineContext().job
            val input = object : ByteArrayInputStream(cipher) {
                private var readBytes = 0
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(bytes, offset, length); if (count > 0) readBytes += count
                    if (readBytes > 1_100_000) job.cancel()
                    return count
                }
            }
            crypto.authenticatedFile(input, password()) { throw AssertionError("Cancelled consumer") }
        }
        assertTrue(rejects { copy.await() } is CancellationException); assertTrue(stage.listFiles()!!.isEmpty())
    }
    @Test fun firstUseCleansOnlyOwnedInterruptedFiles() = scenario {
        val stage = temporary.newFolder("stage"); val owned = File(stage, "ft-1234.bin").apply { writeText("SYNTHETIC interrupted plaintext") }
        val other = File(stage, "unrelated.txt").apply { writeText("Preserve this fixture") }
        BackupCrypto(stage); assertFalse(owned.exists()); assertEquals("Preserve this fixture", other.readText())
    }
}
