package com.sidephone.aviary.transport.signal

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto for Signal's device-link provisioning handshake (the same scheme Signal
 * Desktop uses when you scan its QR code). The new device generates an ephemeral
 * X25519 pair; the primary encrypts a ProvisionMessage to it with keys derived via
 * HKDF from the ECDH shared secret. AES-256-CBC + HMAC-SHA256, standard primitives
 * via Bouncy Castle.
 */
class ProvisioningCrypto {

    private val privateKey = X25519PrivateKeyParameters(SecureRandom())
    private val publicKey: X25519PublicKeyParameters = privateKey.generatePublicKey()

    /** Signal's "djb type" key encoding: 0x05 prefix + raw 32 bytes. */
    fun publicKeySignalEncoded(): ByteArray =
        byteArrayOf(0x05) + publicKey.encoded

    /**
     * Decrypts a ProvisionEnvelope body from the primary device.
     * Envelope layout: [version:1][iv:16][ciphertext][mac:32], MAC over everything before it.
     */
    fun decryptEnvelope(theirPublicKeySignalEncoded: ByteArray, body: ByteArray): ByteArray {
        require(body.size > 1 + 16 + 32) { "provisioning envelope too short" }
        require(body[0].toInt() == 1) { "unknown provisioning version ${body[0]}" }

        val theirRaw = if (theirPublicKeySignalEncoded.size == 33)
            theirPublicKeySignalEncoded.copyOfRange(1, 33) else theirPublicKeySignalEncoded
        val shared = ByteArray(32)
        X25519Agreement().apply { init(privateKey) }
            .calculateAgreement(X25519PublicKeyParameters(theirRaw, 0), shared, 0)

        val derived = ByteArray(64)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(shared, null, "TextSecure Provisioning Message".encodeToByteArray()))
        }.generateBytes(derived, 0, 64)
        val cipherKey = derived.copyOfRange(0, 32)
        val macKey = derived.copyOfRange(32, 64)

        val iv = body.copyOfRange(1, 17)
        val ciphertext = body.copyOfRange(17, body.size - 32)
        val theirMac = body.copyOfRange(body.size - 32, body.size)

        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(macKey, "HmacSHA256"))
        }.doFinal(body.copyOfRange(0, body.size - 32))
        require(MessageDigest.isEqual(mac, theirMac)) { "provisioning MAC mismatch" }

        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(iv))
        }.doFinal(ciphertext)
    }
}
