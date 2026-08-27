package com.sidephone.aviary.transport.signal

import android.util.Base64
import com.sidephone.aviary.transport.signal.MiniProto.Writer
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Signal encrypts a linked device's display name so the server never sees it.
 * Scheme (matches Signal's DeviceNameCipher): ECDH(ephemeral, identityPublic) →
 * HMAC-derived synthetic IV + cipher key → AES-256-CTR. Serialized as the
 * DeviceName proto { ephemeralPublic=1, syntheticIv=2, ciphertext=3 }.
 */
object DeviceName {

    fun encryptBase64(name: String, identityKeyPair: org.signal.libsignal.protocol.IdentityKeyPair): String {
        val plaintext = name.encodeToByteArray()
        val ephemeral: ECKeyPair = ECKeyPair.generate()
        val identityPublic = identityKeyPair.publicKey.publicKey // ECPublicKey

        val masterSecret = ephemeral.privateKey.calculateAgreement(identityPublic)

        val syntheticIv = hmac(masterSecret, "auth".encodeToByteArray(), plaintext).copyOf(16)
        val cipherKey = hmac(masterSecret, "cipher".encodeToByteArray(), syntheticIv)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(cipherKey, "AES"),
            IvParameterSpec(ByteArray(16)),
        )
        val ciphertext = cipher.doFinal(plaintext)

        val proto = Writer()
            .bytes(1, ephemeral.publicKey.serialize())
            .bytes(2, syntheticIv)
            .bytes(3, ciphertext)
            .toByteArray()
        return Base64.encodeToString(proto, Base64.NO_WRAP)
    }

    private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        data.forEach { mac.update(it) }
        return mac.doFinal()
    }
}
