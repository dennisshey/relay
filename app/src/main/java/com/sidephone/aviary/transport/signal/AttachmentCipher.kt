package com.sidephone.aviary.transport.signal

import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Signal attachment wire format, produced with bounded memory. */
internal object AttachmentCipher {
    fun decrypt(source: File, target: File, key: ByteArray, digest: ByteArray?, size: Long) {
        require(key.size == 64 && source.length() >= 48) { "Invalid attachment" }
        val hash = MessageDigest.getInstance("SHA-256")
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key.copyOfRange(32, 64), "HmacSHA256"))
        }
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = source.length() - 32
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "Truncated attachment" }
                hash.update(buffer, 0, count); mac.update(buffer, 0, count)
                remaining -= count
            }
            val tag = ByteArray(32)
            java.io.DataInputStream(input).readFully(tag)
            hash.update(tag)
            check(MessageDigest.isEqual(mac.doFinal(), tag)) { "Attachment MAC mismatch" }
            check(digest == null || MessageDigest.isEqual(hash.digest(), digest)) { "Attachment digest mismatch" }
        }
        try {
            source.inputStream().buffered().use { input ->
                val iv = ByteArray(16)
                java.io.DataInputStream(input).readFully(iv)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 32), "AES"), IvParameterSpec(iv))
                }
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = source.length() - 48
                    var written = 0L
                    fun writePlain(bytes: ByteArray) {
                        val count = minOf(bytes.size.toLong(), (size - written).coerceAtLeast(0)).toInt()
                        output.write(bytes, 0, count)
                        written += count
                    }
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(count > 0) { "Truncated attachment" }
                        cipher.update(buffer, 0, count)?.let { writePlain(it) }
                        remaining -= count
                    }
                    writePlain(cipher.doFinal())
                    check(written == size) { "Attachment size mismatch" }
                }
            }
        } catch (e: Exception) { target.delete(); throw e }
    }

    fun encrypt(source: File, target: File, aesKey: ByteArray, macKey: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
        val digest = MessageDigest.getInstance("SHA-256")
        target.outputStream().buffered().use { output ->
            fun writeEncrypted(bytes: ByteArray) {
                output.write(bytes)
                mac.update(bytes)
                digest.update(bytes)
            }
            writeEncrypted(iv)
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    cipher.update(buffer, 0, count)?.let { writeEncrypted(it) }
                }
            }
            writeEncrypted(cipher.doFinal())
            val tag = mac.doFinal()
            output.write(tag)
            digest.update(tag)
        }
        return digest.digest()
    }
}
