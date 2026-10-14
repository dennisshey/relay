package com.sidephone.aviary.transport.signal

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AttachmentCipherTest {
    @get:Rule val temporary = TemporaryFolder()
    private val aes = ByteArray(32) { it.toByte() }
    private val mac = ByteArray(32) { (it + 32).toByte() }
    private val iv = ByteArray(16) { (it + 64).toByte() }

    @Test fun streamedWireFormatMatchesSignalEncryptionAtBlockAndBufferBoundaries() {
        for (length in listOf(0, 1, 15, 16, 17, 65535, 65536, 65537)) {
            val source = temporary.newFile()
            val encrypted = temporary.newFile()
            val plain = temporary.newFile()
            val bytes = ByteArray(length) { (it % 251).toByte() }
            source.writeBytes(bytes)
            val digest = AttachmentCipher.encrypt(source, encrypted, aes, mac, iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(this@AttachmentCipherTest.iv))
            }
            val body = iv + cipher.doFinal(bytes)
            val tag = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(mac, "HmacSHA256")) }.doFinal(body)
            assertArrayEquals(body + tag, encrypted.readBytes())
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(body + tag), digest)
            AttachmentCipher.decrypt(encrypted, plain, aes + mac, digest, length.toLong())
            assertArrayEquals(bytes, plain.readBytes())
        }
    }

    @Test fun tamperingIsRejectedBeforePlaintextIsWritten() {
        val source = temporary.newFile().apply { writeText("private content") }
        val encrypted = temporary.newFile()
        val plain = temporary.newFile()
        val digest = AttachmentCipher.encrypt(source, encrypted, aes, mac, iv)
        java.io.RandomAccessFile(encrypted, "rw").use { it.seek(16); it.write(42) }
        assertTrue(runCatching { AttachmentCipher.decrypt(encrypted, plain, aes + mac, digest, source.length()) }.isFailure)
        assertEquals(0L, plain.length())
    }

    @Test fun largeFileRoundTripsWithStreamingBuffers() {
        val source = temporary.newFile()
        val encrypted = temporary.newFile()
        val plain = temporary.newFile()
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        source.outputStream().use { out -> repeat(512) { out.write(chunk) } }
        val digest = AttachmentCipher.encrypt(source, encrypted, aes, mac, iv)
        AttachmentCipher.decrypt(encrypted, plain, aes + mac, digest, source.length())
        fun hash(file: java.io.File): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    md.update(buffer, 0, count)
                }
            }
            return md.digest()
        }
        assertEquals(source.length(), plain.length())
        assertArrayEquals(hash(source), hash(plain))
    }
}
