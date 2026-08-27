package com.sidephone.aviary.transport.signal

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.DecryptionErrorMessage
import org.signal.libsignal.protocol.message.PlaintextContent
import org.signal.libsignal.protocol.state.PreKeyBundle

/**
 * Sends Signal text messages (1:1 and group) as a linked device. Establishes a
 * session with each recipient device from their pre-key bundle, encrypts the
 * padded Content with libsignal, and posts the per-device ciphertexts. Also sends
 * a sync transcript to our own other devices so sent messages show on the phone.
 * Identified (non-sealed) send; group messages are fanned out per-member as 1:1
 * ciphertexts carrying the group context.
 */
class SignalSender(
    private val store: AviaryProtocolStore,
    private val account: SignalAccount,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://chat.signal.org",
) {
    /** A message being replied to: its sent timestamp, author ACI, and a text snapshot. */
    data class Quote(val targetTimestamp: Long, val authorAci: String, val text: String)

    /** Send a 1:1 message with the given (already-recorded) timestamp. */
    fun sendDirect(recipientAci: String, body: String, ts: Long, quote: Quote? = null): Result<Unit> =
        runCatching {
            val dataMessage = dataMessage(body, ts, null, quote)
            sendContent(recipientAci, content(dataMessage), ts, null).getOrThrow()
            syncSent(dataMessage, ts, recipientAci)
        }

    /** Send a group message to every member with the given timestamp. */
    fun sendGroup(
        masterKey: ByteArray, group: SignalGroups.Info, body: String, ts: Long, quote: Quote? = null,
    ): Result<Unit> =
        runCatching {
            val groupContext = MiniProto.Writer().bytes(1, masterKey)
                .varint(2, group.revision.toLong()).toByteArray()
            val dataMessage = dataMessage(body, ts, groupContext, quote)
            val padded = content(dataMessage)
            val self = account.aci
            var anySent = false
            group.memberAcis.filter { it != self }.forEach { member ->
                sendContent(member, padded, ts, null)
                    .onSuccess { anySent = true }
                    .onFailure { Log.w(TAG, "group send to $member failed: ${it.message}") }
            }
            check(anySent) { "could not deliver to any group member" }
            syncSent(dataMessage, ts, null)
        }

    /** Send a 1:1 message with a media attachment. */
    fun sendMediaDirect(
        recipientAci: String, media: ByteArray, contentType: String?, fileName: String?,
        caption: String, ts: Long,
    ): Result<Unit> = runCatching {
        val pointer = SignalAttachments(client).upload(account.authToken(), media, contentType, fileName)
            ?: error("attachment upload failed")
        val dm = mediaDataMessage(caption, ts, null, pointer)
        sendContent(recipientAci, content(dm), ts, null).getOrThrow()
        syncSent(dm, ts, recipientAci)
    }

    /** Send a group message with a media attachment. */
    fun sendMediaGroup(
        masterKey: ByteArray, group: SignalGroups.Info, media: ByteArray, contentType: String?,
        fileName: String?, caption: String, ts: Long,
    ): Result<Unit> = runCatching {
        val pointer = SignalAttachments(client).upload(account.authToken(), media, contentType, fileName)
            ?: error("attachment upload failed")
        val groupContext = MiniProto.Writer().bytes(1, masterKey)
            .varint(2, group.revision.toLong()).toByteArray()
        val dm = mediaDataMessage(caption, ts, groupContext, pointer)
        val padded = content(dm)
        var anySent = false
        group.memberAcis.filter { it != account.aci }.forEach { member ->
            sendContent(member, padded, ts, null).onSuccess { anySent = true }
                .onFailure { Log.w(TAG, "group media send to $member failed: ${it.message}") }
        }
        check(anySent) { "could not deliver to any group member" }
        syncSent(dm, ts, null)
    }

    /** DataMessage carrying only a reaction (DataMessage.reaction = 16). */
    private fun reactionDataMessage(
        targetAuthorAci: String, targetTs: Long, emoji: String, remove: Boolean, ts: Long,
        groupContext: ByteArray?,
    ): ByteArray {
        val reaction = MiniProto.Writer()
            .string(1, emoji)
            .varint(2, if (remove) 1 else 0)
            .string(4, targetAuthorAci)  // targetAuthorAci (string ACI, matches our receive parse)
            .varint(5, targetTs)         // targetSentTimestamp
            .toByteArray()
        return MiniProto.Writer().varint(7, ts).bytes(16, reaction)
            .also { if (groupContext != null) it.bytes(15, groupContext) }
            .toByteArray()
    }

    /** React to a 1:1 message. */
    fun sendReactionDirect(
        recipientAci: String, targetAuthorAci: String, targetTs: Long,
        emoji: String, remove: Boolean, ts: Long,
    ): Result<Unit> = runCatching {
        val dm = reactionDataMessage(targetAuthorAci, targetTs, emoji, remove, ts, null)
        sendContent(recipientAci, content(dm), ts, null).getOrThrow()
        syncSent(dm, ts, recipientAci)
    }

    /** React to a group message (delivers to every member). */
    fun sendReactionGroup(
        masterKey: ByteArray, group: SignalGroups.Info, targetAuthorAci: String, targetTs: Long,
        emoji: String, remove: Boolean, ts: Long,
    ): Result<Unit> = runCatching {
        val groupContext = MiniProto.Writer().bytes(1, masterKey)
            .varint(2, group.revision.toLong()).toByteArray()
        val dm = reactionDataMessage(targetAuthorAci, targetTs, emoji, remove, ts, groupContext)
        val padded = content(dm)
        group.memberAcis.filter { it != account.aci }.forEach { sendContent(it, padded, ts, null) }
        syncSent(dm, ts, null)
    }

    /** Send READ receipts for [timestamps] to the sender. */
    fun sendReadReceipt(recipientAci: String, timestamps: List<Long>, ts: Long): Result<Unit> =
        runCatching {
            val receipt = MiniProto.Writer().varint(1, 1) // ReceiptMessage.type = READ
                .also { w -> timestamps.forEach { w.varint(2, it) } }
                .toByteArray()
            val padded = pad(MiniProto.Writer().bytes(5, receipt).toByteArray()) // Content.receiptMessage
            sendContent(recipientAci, padded, ts, null).getOrThrow()
        }

    /** Send a typing indicator (started/stopped) to [recipientAci]. */
    fun sendTyping(recipientAci: String, started: Boolean, ts: Long): Result<Unit> = runCatching {
        val typing = MiniProto.Writer().varint(1, ts)
            .varint(2, if (started) 0 else 1) // TypingMessage.action STARTED/STOPPED
            .toByteArray()
        val padded = pad(MiniProto.Writer().bytes(6, typing).toByteArray()) // Content.typingMessage
        sendContent(recipientAci, padded, ts, null).getOrThrow()
    }

    private fun mediaDataMessage(
        caption: String, ts: Long, groupContext: ByteArray?, attachmentPointer: ByteArray,
    ): ByteArray = MiniProto.Writer()
        .also { if (caption.isNotEmpty()) it.string(1, caption) }
        .bytes(2, attachmentPointer) // DataMessage.attachments
        .varint(7, ts)
        .also { if (groupContext != null) it.bytes(15, groupContext) }
        .toByteArray()

    /**
     * Ask [senderAci]'s device to resend a message we couldn't decrypt — and, for a
     * sender-key group, to redistribute their sender key. This is Signal's standard
     * retry-receipt: an unencrypted PLAINTEXT_CONTENT envelope wrapping a
     * DecryptionErrorMessage that names the failed message by its type + timestamp.
     * Recovers group threads that went silent after we missed the original SKDM
     * (e.g. across reinstalls).
     */
    fun sendRetryReceipt(
        senderAci: String,
        senderDevice: Int,
        originalCiphertextType: Int,
        originalCiphertext: ByteArray,
        originalTimestamp: Long,
        now: Long,
    ): Result<Unit> = runCatching {
        val dem = DecryptionErrorMessage.forOriginalMessage(
            originalCiphertext, originalCiphertextType, originalTimestamp, senderDevice,
        )
        val plaintext = PlaintextContent(dem).serialize()
        val address = SignalProtocolAddress(senderAci, senderDevice)
        val registrationId = store.loadSession(address)?.remoteRegistrationId ?: 0
        val messages = JSONArray().put(JSONObject().apply {
            put("type", 8) // Envelope.Type.PLAINTEXT_CONTENT
            put("destinationDeviceId", senderDevice)
            put("destinationRegistrationId", registrationId)
            put("content", Base64.encodeToString(plaintext, Base64.NO_WRAP))
        })
        val payload = JSONObject().apply {
            put("destination", senderAci)
            put("timestamp", now)
            put("online", false)
            put("urgent", false)
            put("messages", messages)
        }
        val request = Request.Builder()
            .url("$baseUrl/v1/messages/$senderAci")
            .header("Authorization", "Basic ${account.authToken()}")
            .put(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "retry receipt HTTP ${resp.code}: $text" }
        }
        Log.i(TAG, "sent retry receipt to $senderAci.$senderDevice for ts=$originalTimestamp")
    }

    /** Encrypt one padded Content for each of a recipient's devices and post it. */
    private fun sendContent(
        recipientAci: String,
        paddedContent: ByteArray,
        timestamp: Long,
        excludeDeviceId: Int?,
    ): Result<Unit> = runCatching {
        val bundle = fetchPreKeys(recipientAci)
        val identityKey = IdentityKey(Base64.decode(bundle.getString("identityKey"), Base64.NO_WRAP))
        val devices = bundle.getJSONArray("devices")

        val messages = JSONArray()
        for (i in 0 until devices.length()) {
            val d = devices.getJSONObject(i)
            val deviceId = d.getInt("deviceId")
            if (deviceId == excludeDeviceId) continue
            val address = SignalProtocolAddress(recipientAci, deviceId)
            if (!store.containsSession(address)) {
                SessionBuilder(store, address).process(buildBundle(d, identityKey))
            }
            val ciphertext = SessionCipher(store, address).encrypt(paddedContent)
            messages.put(JSONObject().apply {
                put("type", if (ciphertext.type == CiphertextMessage.PREKEY_TYPE) 3 else 1)
                put("destinationDeviceId", deviceId)
                put("destinationRegistrationId", d.getInt("registrationId"))
                put("content", Base64.encodeToString(ciphertext.serialize(), Base64.NO_WRAP))
            })
        }
        if (messages.length() == 0) return@runCatching

        val payload = JSONObject().apply {
            put("destination", recipientAci)
            put("timestamp", timestamp)
            put("online", false)
            put("urgent", true)
            put("messages", messages)
        }
        val request = Request.Builder()
            .url("$baseUrl/v1/messages/$recipientAci")
            .header("Authorization", "Basic ${account.authToken()}")
            .put(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "send failed HTTP ${resp.code}: $text" }
        }
    }

    /** Post a SyncMessage.Sent transcript to our own other devices (best-effort). */
    private fun syncSent(dataMessage: ByteArray, timestamp: Long, destinationAci: String?) {
        val ourAci = account.aci ?: return
        val sent = MiniProto.Writer().varint(2, timestamp).bytes(3, dataMessage)
            .also { if (destinationAci != null) it.string(7, destinationAci) }
            .toByteArray()
        val sync = MiniProto.Writer().bytes(1, sent).toByteArray() // SyncMessage.sent = 1
        val content = MiniProto.Writer().bytes(2, sync).toByteArray() // Content.syncMessage = 2
        runCatching { sendContent(ourAci, pad(content), timestamp, account.deviceId).getOrThrow() }
            .onFailure { Log.w(TAG, "sent-sync failed: ${it.message}") }
    }

    // ---- proto builders ----

    private fun dataMessage(
        body: String, ts: Long, groupContext: ByteArray?, quote: Quote? = null,
    ): ByteArray =
        MiniProto.Writer().string(1, body).varint(7, ts)
            .also { if (quote != null) it.bytes(8, quoteProto(quote)) } // DataMessage.quote
            .also { if (groupContext != null) it.bytes(15, groupContext) }
            .toByteArray()

    /**
     * DataMessage.Quote: id(1)=target sent ts, text(3)=snapshot, type(7)=NORMAL,
     * authorAciBinary(8)=the author's ACI as a raw 16-byte ServiceId. Modern Signal
     * matches the quote by (authorAciBinary, id); the old string author(2) is gone,
     * so we MUST send the binary field or recipients report "original not found".
     */
    private fun quoteProto(q: Quote): ByteArray {
        val w = MiniProto.Writer()
            .varint(1, q.targetTimestamp)
            .string(3, q.text)
            .varint(7, 0) // Quote.Type.NORMAL
        aciToBytes(q.authorAci)?.let { w.bytes(8, it) }
        return w.toByteArray()
    }

    /** A bare ACI UUID string -> its 16-byte ServiceId encoding (no type prefix for ACIs). */
    private fun aciToBytes(aci: String): ByteArray? = runCatching {
        val u = java.util.UUID.fromString(aci)
        java.nio.ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    }.getOrNull()

    /** Content.dataMessage = 1, padded and tagged with its timestamp for the send call. */
    private fun content(dataMessage: ByteArray): ByteArray =
        pad(MiniProto.Writer().bytes(1, dataMessage).toByteArray())

    private fun fetchPreKeys(aci: String): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl/v2/keys/$aci/*")
            .header("Authorization", "Basic ${account.authToken()}")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "prekey fetch HTTP ${resp.code}: $text" }
            return JSONObject(text)
        }
    }

    private fun buildBundle(d: JSONObject, identityKey: IdentityKey): PreKeyBundle {
        val signed = d.getJSONObject("signedPreKey")
        val pq = d.getJSONObject("pqPreKey")
        val preKey = d.optJSONObject("preKey")
        return PreKeyBundle(
            d.getInt("registrationId"),
            d.getInt("deviceId"),
            preKey?.getInt("keyId") ?: -1,
            preKey?.let { ECPublicKey(Base64.decode(it.getString("publicKey"), Base64.NO_WRAP)) },
            signed.getInt("keyId"),
            ECPublicKey(Base64.decode(signed.getString("publicKey"), Base64.NO_WRAP)),
            Base64.decode(signed.getString("signature"), Base64.NO_WRAP),
            identityKey,
            pq.getInt("keyId"),
            KEMPublicKey(Base64.decode(pq.getString("publicKey"), Base64.NO_WRAP)),
            Base64.decode(pq.getString("signature"), Base64.NO_WRAP),
        )
    }

    /** Signal pads plaintext to a multiple of 160: message | 0x80 | 0x00*. */
    private fun pad(msg: ByteArray): ByteArray {
        val blocks = (msg.size + 1 + 159) / 160
        return ByteArray(blocks * 160).also {
            msg.copyInto(it)
            it[msg.size] = 0x80.toByte()
        }
    }

    companion object {
        private const val TAG = "SignalSender"
        private val JSON = "application/json".toMediaType()
    }
}
