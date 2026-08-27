package com.sidephone.aviary.transport.signal

import android.util.Base64
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import kotlin.random.Random

/** Serialized public key + signature, ready to base64 into the registration JSON. */
data class UploadKey(val keyId: Int, val publicKey: ByteArray, val signature: ByteArray) {
    fun publicKeyB64(): String = Base64.encodeToString(publicKey, Base64.NO_WRAP)
    fun signatureB64(): String = Base64.encodeToString(signature, Base64.NO_WRAP)
}

/**
 * All key material a linked device must generate and register for one identity
 * (there is one of these for ACI and one for PNI). Uses libsignal's native
 * primitives so the crypto matches Signal exactly.
 */
class IdentityKeys(
    /** The identity key pair handed to us by the primary device in the ProvisionMessage. */
    val identityKeyPair: IdentityKeyPair,
    val registrationId: Int,
    val signedPreKey: SignedPreKeyRecord,
    val lastResortKyberPreKey: KyberPreKeyRecord,
    val oneTimePreKeys: List<PreKeyRecord>,
    val oneTimeKyberPreKeys: List<KyberPreKeyRecord>,
) {
    fun signedPreKeyUpload() = UploadKey(
        signedPreKey.id,
        signedPreKey.keyPair.publicKey.serialize(),
        signedPreKey.signature,
    )

    fun lastResortKyberUpload() = UploadKey(
        lastResortKyberPreKey.id,
        lastResortKyberPreKey.keyPair.publicKey.serialize(),
        lastResortKyberPreKey.signature,
    )

    companion object {
        private const val ONE_TIME_COUNT = 100

        /** Reconstruct the identity key pair from the raw bytes in the ProvisionMessage. */
        fun identityFrom(publicKeyDjb: ByteArray, privateKey: ByteArray): IdentityKeyPair =
            IdentityKeyPair(IdentityKey(publicKeyDjb), ECPrivateKey(privateKey))

        fun generate(identityKeyPair: IdentityKeyPair): IdentityKeys {
            val idPrivate = identityKeyPair.privateKey
            val registrationId = KeyHelper.generateRegistrationId(false)
            val now = System.currentTimeMillis()
            val baseId = Random.nextInt(1, 0xFFFFFF)

            val signedPreKey = signedPreKey(baseId, now, idPrivate)
            val lastResortKyber = kyberPreKey(baseId, now, idPrivate)

            val oneTime = (1..ONE_TIME_COUNT).map { i ->
                PreKeyRecord(baseId + i, ECKeyPair.generate())
            }
            val oneTimeKyber = (1..ONE_TIME_COUNT).map { i ->
                kyberPreKey(baseId + ONE_TIME_COUNT + i, now, idPrivate)
            }

            return IdentityKeys(
                identityKeyPair = identityKeyPair,
                registrationId = registrationId,
                signedPreKey = signedPreKey,
                lastResortKyberPreKey = lastResortKyber,
                oneTimePreKeys = oneTime,
                oneTimeKyberPreKeys = oneTimeKyber,
            )
        }

        private fun signedPreKey(id: Int, now: Long, idPrivate: ECPrivateKey): SignedPreKeyRecord {
            val keyPair = ECKeyPair.generate()
            val signature = idPrivate.calculateSignature(keyPair.publicKey.serialize())
            return SignedPreKeyRecord(id, now, keyPair, signature)
        }

        private fun kyberPreKey(id: Int, now: Long, idPrivate: ECPrivateKey): KyberPreKeyRecord {
            val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val signature = idPrivate.calculateSignature(keyPair.publicKey.serialize())
            return KyberPreKeyRecord(id, now, keyPair, signature)
        }
    }
}
