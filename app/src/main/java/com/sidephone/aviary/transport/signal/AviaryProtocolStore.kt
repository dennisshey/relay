package com.sidephone.aviary.transport.signal

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * A SignalProtocolStore that keeps libsignal's session/identity/pre-key state in
 * memory for speed but persists every write to Keystore-encrypted storage, so the
 * linked device survives restarts. On construction it re-seeds the in-memory store
 * from disk. Nothing leaves the phone.
 *
 * One store instance per identity (we use ACI; PNI messages are out of scope for
 * the first receive milestone).
 */
class AviaryProtocolStore(
    context: Context,
    identityKeyPair: IdentityKeyPair,
    registrationId: Int,
) : InMemorySignalProtocolStore(identityKeyPair, registrationId) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "aviary_signal_store", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val pendingGroups = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal val challenge = SignalChallenge(prefs.getString("verification.challenge", null)) { token ->
        check(prefs.edit().apply {
            if (token == null) remove("verification.challenge") else putString("verification.challenge", token)
        }.commit()) { "Couldn't save Signal verification state" }
    }

    internal fun requireVerification(body: String): Nothing {
        val token = runCatching { org.json.JSONObject(body).getString("token") }.getOrDefault("")
        // Even an unsupported/malformed challenge must stop automatic sending.
        challenge.requireVerification(token)
        throw SignalVerificationRequired()
    }

    init {
        // Re-seed memory from disk without re-persisting (call super, not our overrides).
        for ((key, value) in prefs.all) {
            // Decode inside the guard: this store shares its prefs file, and one entry that isn't
            // a base-64 record (or isn't a String at all) used to throw straight out of the
            // constructor and take the whole app down at launch. Skip the row, keep the rest.
            runCatching {
                val bytes = Base64.decode(value as String, Base64.NO_WRAP)
                when {
                    key.startsWith("group-send.") -> {
                        if (GroupDelivery.decode(bytes).pending.isNotEmpty())
                            pendingGroups[key] = key.split('.')[1].toLong()
                    }
                    key.startsWith(PRE) ->
                        super.storePreKey(key.idAfter(PRE), PreKeyRecord(bytes))
                    key.startsWith(SIGNED) ->
                        super.storeSignedPreKey(key.idAfter(SIGNED), SignedPreKeyRecord(bytes))
                    key.startsWith(KYBER) ->
                        super.storeKyberPreKey(key.idAfter(KYBER), KyberPreKeyRecord(bytes))
                    key.startsWith(SESSION) ->
                        super.storeSession(key.addrAfter(SESSION), SessionRecord(bytes))
                    key.startsWith(IDENTITY) ->
                        super.saveIdentity(key.addrAfter(IDENTITY), IdentityKey(bytes))
                    key.startsWith(SENDERKEY) -> {
                        val rest = key.removePrefix(SENDERKEY)
                        val uuid = java.util.UUID.fromString(rest.substringAfterLast('.'))
                        val addrPart = rest.substringBeforeLast('.')
                        val device = addrPart.substringAfterLast('.').toInt()
                        val addr = SignalProtocolAddress(addrPart.substringBeforeLast('.'), device)
                        super.storeSenderKey(addr, uuid, SenderKeyRecord(bytes))
                    }
                }
            }
        }
    }

    override fun storePreKey(id: Int, record: PreKeyRecord) {
        super.storePreKey(id, record); put("$PRE$id", record.serialize())
    }

    override fun removePreKey(id: Int) {
        super.removePreKey(id); prefs.edit().remove("$PRE$id").apply()
    }

    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) {
        super.storeSignedPreKey(id, record); put("$SIGNED$id", record.serialize())
    }

    override fun removeSignedPreKey(id: Int) {
        super.removeSignedPreKey(id); prefs.edit().remove("$SIGNED$id").apply()
    }

    override fun storeKyberPreKey(id: Int, record: KyberPreKeyRecord) {
        super.storeKyberPreKey(id, record); put("$KYBER$id", record.serialize())
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        super.storeSession(address, record); put("$SESSION${address.key()}", record.serialize())
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        super.deleteSession(address); prefs.edit().remove("$SESSION${address.key()}").apply()
    }

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        val change = super.saveIdentity(address, identityKey)
        put("$IDENTITY${address.key()}", identityKey.serialize())
        return change
    }

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: java.util.UUID,
        record: SenderKeyRecord,
    ) {
        super.storeSenderKey(sender, distributionId, record)
        put("$SENDERKEY${sender.key()}.$distributionId", record.serialize())
    }

    private fun put(key: String, bytes: ByteArray) {
        prefs.edit().putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    // Serialize group attempts, including retries, across all sender instances.
    private val groupSendLocks = Array(64) { Any() }
    internal fun groupSendLock(key: String): Any = groupSendLocks[(key.hashCode() and Int.MAX_VALUE) % groupSendLocks.size]

    internal fun groupDelivery(key: String): GroupDelivery? = prefs.getString(key, null)?.let {
        GroupDelivery.decode(Base64.decode(it, Base64.NO_WRAP))
    }

    internal fun saveGroupDelivery(key: String, delivery: GroupDelivery) {
        // Publish pending before persistence so an early receipt cannot hide a partial failure.
        if (delivery.pending.isNotEmpty()) pendingGroups[key] = key.split('.')[1].toLong()
        check(prefs.edit().putString(key, Base64.encodeToString(delivery.encode(), Base64.NO_WRAP)).commit()) {
            "Couldn't persist group delivery progress"
        }
        if (delivery.pending.isEmpty()) pendingGroups.remove(key)
    }

    internal fun hasPendingGroup(timestamp: Long): Boolean = pendingGroups.containsValue(timestamp)

    // Include the primary device as well as linked devices, including sessions restored at startup.
    @Synchronized
    internal fun sendingDevices(aci: String): List<Int> = prefs.all.keys
        .filter { it.startsWith("$SESSION$aci.") }
        .mapNotNull { it.substringAfterLast('.').toIntOrNull() }
        .filter { loadSession(SignalProtocolAddress(aci, it)).hasSenderChain() }

    internal fun checkSendCooldown(aci: String, endpoint: String) {
        val until = prefs.getLong("cooldown.$endpoint.$aci", 0)
        if (until > System.currentTimeMillis())
            throw com.sidephone.aviary.transport.SendDeferredException(until)
    }

    internal fun deferSend(aci: String, endpoint: String, retryAfter: String?): Nothing {
        val now = System.currentTimeMillis()
        val until = retryAfter?.toLongOrNull()?.takeIf { it in 0..31536000 }
            ?.let { now + it * 1000 }
            ?: runCatching { java.time.ZonedDateTime.parse(retryAfter, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: (now + 300_000)
        val deadline = maxOf(now + 1_000, until)
        check(prefs.edit().putLong("cooldown.$endpoint.$aci", deadline).commit())
        throw com.sidephone.aviary.transport.SendDeferredException(deadline)
    }

    /** Wipe all persisted key/session state (used when re-linking). */
    fun clear() {
        challenge.state.value?.let { challenge.accepted(it) }
        pendingGroups.clear(); prefs.edit().clear().apply()
    }

    private fun String.idAfter(prefix: String) = removePrefix(prefix).toInt()

    private fun String.addrAfter(prefix: String): SignalProtocolAddress {
        val rest = removePrefix(prefix)
        val device = rest.substringAfterLast('.').toInt()
        return SignalProtocolAddress(rest.substringBeforeLast('.'), device)
    }

    private fun SignalProtocolAddress.key() = "$name.$deviceId"

    companion object {
        private const val PRE = "prekey."
        private const val SIGNED = "signed."
        private const val KYBER = "kyber."
        private const val SESSION = "session."
        private const val IDENTITY = "identity."
        private const val SENDERKEY = "senderkey."
    }
}
