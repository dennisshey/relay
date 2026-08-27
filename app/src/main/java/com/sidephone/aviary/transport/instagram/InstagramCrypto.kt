package com.sidephone.aviary.transport.instagram

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Instagram's password encryption ("enc_password"). The plaintext password never leaves the
 * device unencrypted: a random 32-byte session key encrypts the password with AES-256-GCM, and
 * that session key is sealed to Instagram's RSA public key (fetched from the qe/sync headers).
 * Format and byte layout mirror the Instagram-for-Android client exactly.
 */
object InstagramCrypto {

    /** `#PWD_INSTAGRAM:4:{ts}:{base64(payload)}` — see byte layout below. */
    fun encryptPassword(password: String, pubKeyId: Int, pubKeyBase64: String): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()

        val sessionKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

        // The header value is base64 of the PEM text; decode to PEM, then to DER.
        val pem = String(Base64.decode(pubKeyBase64, Base64.DEFAULT))
        val der = Base64.decode(
            pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), ""),
            Base64.DEFAULT,
        )
        val rsaPub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val rsaEncrypted = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, rsaPub)
            doFinal(sessionKey)
        }

        val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
            updateAAD(timestamp.toByteArray())
        }
        val encrypted = gcm.doFinal(password.toByteArray()) // ciphertext || 16-byte tag
        val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)
        val ciphertext = encrypted.copyOfRange(0, encrypted.size - 16)

        val payload = ByteBuffer.allocate(1 + 1 + 12 + 2 + rsaEncrypted.size + 16 + ciphertext.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(1)                       // version
            .put(pubKeyId.toByte())       // key id
            .put(iv)                      // 12-byte GCM IV
            .putShort(rsaEncrypted.size.toShort()) // RSA blob length (LE)
            .put(rsaEncrypted)            // sealed session key
            .put(tag)                     // 16-byte GCM tag
            .put(ciphertext)              // AES-GCM ciphertext
            .array()

        return "#PWD_INSTAGRAM:4:$timestamp:${Base64.encodeToString(payload, Base64.NO_WRAP)}"
    }

    /** Anti-CSRF-ish value Instagram derives from a uuid: "2" + sum of the string's char codes. */
    fun jazoest(symbol: String): String = "2" + symbol.sumOf { it.code }
}
