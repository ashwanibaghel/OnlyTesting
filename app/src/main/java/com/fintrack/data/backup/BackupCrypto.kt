package com.fintrack.data.backup

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig2026
import java.io.*
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*

/** Password-wrapped random Tink streaming keys; no plaintext keyset/password persistence. */
internal class BackupCrypto(private val staging: File, private val maxPayload: Long = MAX_PAYLOAD) {
    init { require(maxPayload in 1..MAX_PAYLOAD); cleanOnce(staging) }
    private val config get() = StreamingAeadConfig2026.get()
    private val parameters get() = PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB

    suspend fun <T> encrypt(output: OutputStream, password: CharArray, producer: suspend (OutputStream) -> T): T {
        val captured = password.copyOf()
        try {
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val fixed = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).apply {
                    write(MAGIC); writeInt(1); writeInt(ITERATIONS); write(salt)
                } }.toByteArray()
                val wrapping = derive(captured, salt, ITERATIONS)
                currentCoroutineContext().ensureActive()
                val key = KeysetHandle.generateNew(parameters, config)
                val wrapped = TinkProtoKeysetFormat.serializeEncryptedKeyset(key, wrapping, fixed, config)
                require(wrapped.size in 1..MAX_WRAPPED) { "Unsupported wrapped backup key" }
                val header = fixed + ByteBuffer.allocate(4).putInt(wrapped.size).array() + wrapped
                output.write(header)
                val borrowed = object : FilterOutputStream(output) { override fun close() { flush() } }
                val result = key.getPrimitive(config, StreamingAead::class.java).newEncryptingStream(borrowed, header).use { encrypted ->
                    producer(LimitedOutput(encrypted, maxPayload))
                }
                output.flush(); result
            }
        } finally { captured.fill('\u0000') }
    }

    /** Consumer is called only after authenticated EOF. The private plaintext file is always removed afterward. */
    suspend fun <T> authenticatedFile(input: InputStream, password: CharArray, consumer: suspend (File) -> T): T {
        val captured = password.copyOf()
        try {
            return withContext(Dispatchers.IO) {
                val context = currentCoroutineContext(); context.ensureActive()
                val borrowed = object : FilterInputStream(input) { override fun close() = Unit }
                val limited = LimitedInput(borrowed, maxPayload + 1024 * 1024)
                val data = DataInputStream(limited); val fixed = ByteArray(32); data.readFully(fixed)
                require(fixed.copyOfRange(0, 8).contentEquals(MAGIC)) { "Unsupported or damaged backup" }
                val fields = ByteBuffer.wrap(fixed); fields.position(8)
                require(fields.int == 1) { "Unsupported backup format; update the app" }
                val iterations = fields.int
                require(iterations in ITERATIONS..MAX_ITERATIONS) { "Invalid backup key derivation settings" }
                val salt = ByteArray(16).also { fields.get(it) }
                val size = data.readInt(); require(size in 1..MAX_WRAPPED) { "Invalid wrapped backup key length" }
                val wrapped = ByteArray(size).also(data::readFully)
                val header = fixed + ByteBuffer.allocate(4).putInt(size).array() + wrapped
                val key = TinkProtoKeysetFormat.parseEncryptedKeyset(
                    wrapped, derive(captured, salt, iterations), fixed, config)
                require(key.size() == 1 && key.getAt(0).key.parameters == parameters) { "Unexpected backup encryption parameters" }
                context.ensureActive()
                val file = File.createTempFile("ft-", ".bin", staging)
                try {
                    key.getPrimitive(config, StreamingAead::class.java).newDecryptingStream(limited, header).use { decrypted ->
                        file.outputStream().use { destination ->
                            val bounded = LimitedOutput(destination, maxPayload); val buffer = ByteArray(64 * 1024)
                            try {
                                while (true) {
                                    context.ensureActive(); val read = decrypted.read(buffer)
                                    if (read == -1) break
                                    if (read > 0) bounded.write(buffer, 0, read)
                                }
                            } finally { buffer.fill(0) }
                        }
                    }
                    context.ensureActive(); consumer(file)
                } finally {
                    check(file.delete() || !file.exists()) { "Could not remove private backup staging file" }
                }
            }
        } finally { captured.fill('\u0000') }
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): Aead {
        try {
            require(password.size in 12..1024) { "Use a backup passphrase of 12..1024 characters" }
            val spec = PBEKeySpec(password, salt, iterations, 256)
            try {
                val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                try { return PasswordAead(bytes) } finally { bytes.fill(0) }
            } finally { spec.clearPassword() }
        } finally { password.fill('\u0000') }
    }
    private class PasswordAead(bytes: ByteArray) : Aead {
        private val key = SecretKeySpec(bytes, "AES")
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key)
            check(cipher.iv.size == 12); cipher.updateAAD(associatedData)
            return cipher.iv + cipher.doFinal(plaintext)
        }
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            if (ciphertext.size < 28) throw GeneralSecurityException("Invalid wrapped backup key")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, 12)); cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
        }
    }
    companion object {
        const val MAX_PAYLOAD = 512L * 1024 * 1024
        private const val ITERATIONS = 600_000
        private const val MAX_ITERATIONS = 2_000_000
        private const val MAX_WRAPPED = 8192
        private val MAGIC = "FTBACK01".toByteArray(Charsets.US_ASCII)
        private val cleaned = mutableSetOf<String>()
        @Synchronized private fun cleanOnce(directory: File) {
            check(directory.isDirectory || directory.mkdirs()) { "Private backup staging is unavailable" }
            val root = directory.canonicalFile
            if (root.path in cleaned) return
            for (file in requireNotNull(root.listFiles()) { "Cannot read private backup staging" }) {
                if (file.name.matches(Regex("ft-[0-9]+\\.bin")) && file.isFile && file.canonicalFile.parentFile == root)
                    check(file.delete()) { "Cannot remove interrupted private backup staging" }
            }
            cleaned += root.path
        }
    }
}

internal class LimitedOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
    private var count = 0L
    private fun reserve(size: Int) { require(size >= 0 && size.toLong() <= limit - count) { "Backup size limit exceeded" }; count += size }
    override fun write(value: Int) { reserve(1); out.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { reserve(length); out.write(bytes, offset, length) }
}
private class LimitedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L
    private fun accept(read: Int): Int { if (read > 0) { require(read.toLong() <= limit - count) { "Backup size limit exceeded" }; count += read }; return read }
    override fun read(): Int { val value = `in`.read(); if (value != -1) accept(1); return value }
    override fun read(bytes: ByteArray, offset: Int, length: Int) = accept(`in`.read(bytes, offset, length))
    override fun skip(amount: Long): Long {
        if (amount <= 0) return 0
        val buffer = ByteArray(minOf(8192L, amount).toInt()); var skipped = 0L
        while (skipped < amount) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), amount - skipped).toInt())
            if (read == -1) break
            if (read > 0) skipped += read
        }
        return skipped
    }
    override fun markSupported() = false
    override fun mark(readlimit: Int) = Unit
    override fun reset(): Unit = throw IOException("Backup input cannot be reset")
}
internal fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()
