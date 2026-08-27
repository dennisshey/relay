package com.sidephone.aviary.transport.signal

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.sidephone.aviary.data.InboxCategory
import com.sidephone.aviary.data.MessageEntity
import com.sidephone.aviary.data.MessageStatus
import com.sidephone.aviary.data.UnifiedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.signal.libsignal.metadata.ProtocolDuplicateMessageException
import org.signal.libsignal.metadata.ProtocolException
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.metadata.protocol.UnidentifiedSenderMessageContent
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.util.UUID

/**
 * Authenticated receive loop for the linked Signal device. Opens the chat websocket,
 * decrypts incoming envelopes with libsignal (double-ratchet, prekey, and sealed
 * sender), and writes message bodies into the unified inbox. Decryption state lives
 * in [AviaryProtocolStore]; nothing leaves the phone.
 *
 * Scope note: handles 1:1 text DataMessages to our ACI. Groups, attachments, sync
 * messages, and PNI-addressed messages are future work.
 */
class SignalReceiver(
    private val context: Context,
    private val account: SignalAccount,
    private val repo: UnifiedRepository,
    private val scope: CoroutineScope,
    private val contactNames: com.sidephone.aviary.data.ContactNames,
    private val avatarStore: com.sidephone.aviary.data.AvatarStore,
    private val mediaStore: com.sidephone.aviary.data.MediaStore,
    private val store: AviaryProtocolStore,
    private val onConnected: (Boolean) -> Unit,
) {
    private val certificateValidator = CertificateValidator(TRUST_ROOTS.map {
        ECPublicKey(Base64.decode(it, Base64.NO_WRAP))
    })
    private var socket: WebSocket? = null
    private var keepAliveJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var stopped = false
    @Volatile private var diagnosticsRun = false

    fun start() {
        stopped = false
        connect()
    }

    private fun connect() {
        if (stopped) return
        if (account.password == null) return
        // Signal's message-delivery socket authenticates via the Authorization
        // header (Basic <aci.deviceId:password>), not query params. Query params
        // pass keepalive auth but do NOT engage message delivery.
        val request = Request.Builder()
            .url("$CHAT_WS/v1/websocket/")
            .header("Authorization", "Basic ${account.authToken()}")
            .header("X-Signal-Agent", "OWA")
            .header("X-Signal-Receive-Stories", "false")
            .header("User-Agent", USER_AGENT)
            .build()
        val client = SignalTrust.okHttpClient(context)
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "receive websocket open"); onConnected(true)
                    startKeepAlive(webSocket)
                    if (!diagnosticsRun) {
                        diagnosticsRun = true
                        topUpPreKeysIfLow()
                        backfillNames()
                        scope.launch {
                            val who = runCatching {
                                SignalApi(SignalTrust.okHttpClient(context))
                                    .debugGet(account.authToken(), "/v1/accounts/whoami")
                            }.getOrElse { "err:${it.message}" }
                            Log.i("SignalReply", "whoami=$who  storedAci=${account.aci} pni=${account.pni}")
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    scope.launch { handleFrame(webSocket, bytes.toByteArray()) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "receive websocket closed $code $reason"); onConnected(false)
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "receive websocket failure: ${t.message}"); onConnected(false)
                    scheduleReconnect()
                }
            },
        )
    }

    /** Reconnect with a short fixed backoff (Signal drops idle/roamed sockets). */
    private fun scheduleReconnect() {
        keepAliveJob?.cancel()
        if (stopped || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(3_000)
            if (!stopped) { Log.i(TAG, "reconnecting receive websocket"); connect() }
        }
    }

    fun stop() {
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
        keepAliveJob?.cancel(); keepAliveJob = null
        socket?.close(1000, "stop"); socket = null
    }

    /** Refill one-time prekeys when the server's supply runs low. */
    private fun topUpPreKeysIfLow() {
        scope.launch {
            val api = SignalApi(SignalTrust.okHttpClient(context))
            val auth = account.authToken()
            val count = runCatching {
                JSONObject(api.debugGet(auth, "/v2/keys?identity=aci").substringAfter(": "))
                    .optInt("count", 0)
            }.getOrDefault(0)
            if (count >= PREKEY_LOW_THRESHOLD) return@launch
            val identity = account.identity(SignalAccount.KIND_ACI) ?: return@launch
            val keys = IdentityKeys.generate(identity)
            store.storeSignedPreKey(keys.signedPreKey.id, keys.signedPreKey)
            store.storeKyberPreKey(keys.lastResortKyberPreKey.id, keys.lastResortKyberPreKey)
            keys.oneTimePreKeys.forEach { store.storePreKey(it.id, it) }
            keys.oneTimeKyberPreKeys.forEach { store.storeKyberPreKey(it.id, it) }
            runCatching { api.uploadOneTimeKeys(auth, "aci", keys) }
                .onFailure { Log.w(TAG, "prekey top-up failed", it) }
        }
    }

    /**
     * Signal keeps a client websocket alive with a periodic application-level
     * request to /v1/keepalive (not a WebSocket ping). ~50s stays under the
     * server's idle window.
     */
    private fun startKeepAlive(ws: WebSocket) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(50_000)
                val req = MiniProto.Writer()
                    .string(1, "GET").string(2, "/v1/keepalive").varint(4, 1)
                    .toByteArray()
                val frame = MiniProto.Writer().varint(1, 1).bytes(2, req).toByteArray()
                if (!ws.send(frame.toByteString())) break
            }
        }
    }

    /** WebSocketMessage{type=1 REQUEST; request(2)={verb=1, path=2, body=3, id=4}}. */
    private suspend fun handleFrame(ws: WebSocket, frame: ByteArray) {
        val msg = MiniProto.parse(frame)
        if (MiniProto.varintField(msg, 1) != 1L) return // keepalive responses etc.
        val request = MiniProto.parse(MiniProto.bytesField(msg, 2) ?: return)
        val path = MiniProto.stringField(request, 2)
        val body = MiniProto.bytesField(request, 3)
        val requestId = MiniProto.varintField(request, 4) ?: 0

        if (path == "/api/v1/message" && body != null) {
            runCatching { processEnvelope(body) }
                .onFailure { Log.w(TAG, "envelope processing failed", it) }
        }
        // Always ack so the server dequeues (empty-queue markers included).
        ackRequest(ws, requestId)
    }

    private suspend fun processEnvelope(envelopeBytes: ByteArray) {
        val env = MiniProto.parse(envelopeBytes)
        val type = MiniProto.varintField(env, 1)?.toInt() ?: return
        // Server delivery receipt: no content; clientTimestamp (5) is our message's ts.
        if (type == TYPE_RECEIPT) {
            MiniProto.varintField(env, 5)?.let { repo.markDelivered(SignalTransport.ID, "out:$it") }
            return
        }
        val content = MiniProto.bytesField(env, 8) ?: return
        // Source is the string field (11) or, on modern envelopes, the binary field (19).
        val sourceServiceId = MiniProto.stringField(env, 11)
            ?: MiniProto.bytesField(env, 19)?.let {
                runCatching { ServiceId.parseFromBinary(it).rawUUID.toString() }.getOrNull()
            }
        val sourceDevice = MiniProto.varintField(env, 7)?.toInt() ?: 1
        val serverTimestamp = MiniProto.varintField(env, 10) ?: System.currentTimeMillis()
        // Envelope.timestamp (5) is the sender's sent-timestamp — what a retry receipt
        // must name to identify the message the sender should resend.
        val sentTimestamp = MiniProto.varintField(env, 5) ?: serverTimestamp

        var senderE164: String? = null
        var sender: String? = sourceServiceId
        var senderDevice = sourceDevice

        val plaintext: ByteArray = try {
            when (type) {
                TYPE_DOUBLE_RATCHET -> {
                    val addr = SignalProtocolAddress(sourceServiceId ?: return, sourceDevice)
                    SessionCipher(store, addr).decrypt(SignalMessage(content))
                }
                TYPE_PREKEY -> {
                    val addr = SignalProtocolAddress(sourceServiceId ?: return, sourceDevice)
                    SessionCipher(store, addr).decrypt(PreKeySignalMessage(content))
                }
                TYPE_UNIDENTIFIED_SENDER -> {
                    val cipher = SealedSessionCipher(
                        store, UUID.fromString(account.aci), account.number, account.deviceId,
                    )
                    val result = cipher.decrypt(certificateValidator, content, serverTimestamp)
                    sender = result.senderUuid
                    senderE164 = result.senderE164.orElse(null)
                    senderDevice = result.deviceId
                    result.paddedMessage
                }
                else -> return
            }
        } catch (e: Exception) {
            maybeSendRetryReceipt(type, content, sourceServiceId, sourceDevice, sentTimestamp, e)
            throw e
        }

        val senderAci = sender ?: return
        val senderAddress = SignalProtocolAddress(senderAci, senderDevice)
        val contentProto = MiniProto.parse(stripPadding(plaintext))

        // Receipt (Content.receiptMessage): mark our sent messages delivered or read.
        MiniProto.bytesField(contentProto, 5)?.let { receiptBytes ->
            val rm = MiniProto.parse(receiptBytes)
            val type = MiniProto.varintField(rm, 1) ?: 0L
            val timestamps = rm.filter { it.number == 2 }.mapNotNull { it.varint }
            when (type) {
                0L -> timestamps.forEach { repo.markDelivered(SignalTransport.ID, "out:$it") } // DELIVERY
                1L -> timestamps.forEach { // READ
                    repo.advanceStatusByExternal(SignalTransport.ID, "out:$it", MessageStatus.READ)
                }
            }
            return
        }

        // Typing indicator (Content.typingMessage = 6), direct chats only.
        MiniProto.bytesField(contentProto, 6)?.let { typingBytes ->
            val tm = MiniProto.parse(typingBytes)
            val started = (MiniProto.varintField(tm, 2) ?: 0L) == 0L // STARTED = 0
            val groupId = MiniProto.bytesField(tm, 3)
            if (groupId == null && senderAci != null) {
                repo.conversationByExternal(SignalTransport.ID, senderAci)?.let { c ->
                    (context.applicationContext as? com.sidephone.aviary.RelayApp)?.typing?.set(c.id, started)
                }
            }
            return
        }

        // A group message is preceded by a SenderKeyDistributionMessage (Content
        // field 7); process it so subsequent sender-key group messages decrypt.
        MiniProto.bytesField(contentProto, 7)?.let { skdm ->
            runCatching {
                GroupSessionBuilder(store).process(senderAddress, SenderKeyDistributionMessage(skdm))
            }.onFailure { Log.w(TAG, "SKDM process failed: ${it.message}") }
        }

        // Sync from our own other devices.
        MiniProto.bytesField(contentProto, 2)?.let { syncBytes -> // Content.syncMessage
            val syncProto = MiniProto.parse(syncBytes)
            // SyncMessage.sent (1): a message we sent from the phone — mirror it here.
            MiniProto.bytesField(syncProto, 1)?.let { sent ->
                val sentProto = MiniProto.parse(sent)
                val dm = MiniProto.bytesField(sentProto, 3) ?: return // Sent.message (DataMessage)
                // Sent.destinationServiceId — its field number has shifted across proto versions
                // (field 7 is now storyMessage), so take field 12 if it's a UUID, else the one
                // top-level string field that looks like a service-id. Without it, a 1:1 message
                // sent from Signal Desktop is dropped (its recipient is unknown).
                // destinationServiceId (field 12) is a binary ServiceId in newer proto; older
                // builds sent a UUID string. Try binary first, then a string UUID anywhere.
                val destBytes = MiniProto.bytesField(sentProto, 12)
                val destAci = destBytes?.let { b ->
                    runCatching { ServiceId.parseFromBinary(b).rawUUID.toString() }.getOrNull()
                        ?: UUID_RE.find(b.decodeToString())?.value
                } ?: sentProto.mapNotNull { it.bytes?.decodeToString() }
                    .firstNotNullOfOrNull { UUID_RE.find(it)?.value }
                processDataMessage(dm, "me", null, outgoing = true, destAci, serverTimestamp)
                return
            }
            // SyncMessage.read (5, repeated Read{senderAci=3}): we read these on another device,
            // so clear the matching 1:1 thread's unread dot + notification here too.
            val reads = syncProto.filter { it.number == 5 }.mapNotNull { it.bytes }
            if (reads.isNotEmpty()) {
                for (r in reads) {
                    val senderAci = MiniProto.stringField(MiniProto.parse(r), 3) ?: continue
                    repo.conversationByExternal(SignalTransport.ID, senderAci)?.let { c ->
                        repo.markRead(c.id)
                        com.sidephone.aviary.data.Notifier.cancel(context, c.id)
                    }
                }
                return
            }
            return
        }

        val dataMessage = MiniProto.bytesField(contentProto, 1) ?: return // Content.dataMessage
        processDataMessage(dataMessage, senderAci, senderE164, false, null, serverTimestamp)
    }

    /**
     * On a decryption failure, ask the sender to resend — and, for a sender-key group,
     * to redistribute their sender key. Maps the failed envelope to the ciphertext type
     * + bytes a DecryptionErrorMessage needs, then posts a retry receipt. Only resendable
     * (group) content and non-duplicates trigger a retry, avoiding receipt storms. This
     * recovers group threads that went silent after we missed the original SKDM.
     */
    private fun maybeSendRetryReceipt(
        envelopeType: Int,
        content: ByteArray,
        sourceServiceId: String?,
        sourceDevice: Int,
        sentTimestamp: Long,
        error: Exception,
    ) {
        if (error is DuplicateMessageException || error is ProtocolDuplicateMessageException) {
            return // we already have this message; nothing to resend
        }

        val ciphertextType: Int
        val ciphertext: ByteArray
        val senderAci: String?
        val senderDevice: Int

        when {
            envelopeType == TYPE_UNIDENTIFIED_SENDER && error is ProtocolException -> {
                val usmc = error.unidentifiedSenderMessageContent.orElse(null) ?: return
                // Only resend content the sender flagged resendable (i.e. group messages).
                if (usmc.contentHint != UnidentifiedSenderMessageContent.CONTENT_HINT_RESENDABLE) return
                ciphertextType = usmc.type
                ciphertext = usmc.content
                senderAci = error.senderAci?.rawUUID?.toString() ?: error.sender
                senderDevice = error.senderDevice
            }
            envelopeType == TYPE_DOUBLE_RATCHET || envelopeType == TYPE_PREKEY -> {
                ciphertextType = if (envelopeType == TYPE_PREKEY)
                    CiphertextMessage.PREKEY_TYPE else CiphertextMessage.WHISPER_TYPE
                ciphertext = content
                senderAci = sourceServiceId
                senderDevice = sourceDevice
            }
            else -> return
        }

        val aci = senderAci ?: return
        if (!retriedEnvelopes.add("$aci.$senderDevice:$sentTimestamp")) return // once per message
        Log.i(TAG, "decrypt failed (${error.javaClass.simpleName}); retry receipt -> $aci.$senderDevice")
        scope.launch {
            SignalSender(store, account, SignalTrust.okHttpClient(context))
                .sendRetryReceipt(
                    aci, senderDevice, ciphertextType, ciphertext, sentTimestamp,
                    System.currentTimeMillis(),
                )
                .onFailure { Log.w(TAG, "retry receipt send failed: ${it.message}") }
        }
    }

    /** Handle a DataMessage (incoming, or our own via sync): text + attachment. */
    private suspend fun processDataMessage(
        dmBytes: ByteArray,
        fromAci: String,
        fromE164: String?,
        outgoing: Boolean,
        recipientAci: String?,
        serverTimestamp: Long,
    ) {
        val dm = MiniProto.parse(dmBytes)

        // Reaction (DataMessage.reaction, field 16): a tapback on an existing message,
        // not a new bubble. Target is identified by author + original sent timestamp.
        MiniProto.bytesField(dm, 16)?.let { reactBytes ->
            val r = MiniProto.parse(reactBytes)
            val emoji = MiniProto.stringField(r, 1)
            val remove = (MiniProto.varintField(r, 2) ?: 0L) != 0L
            val targetAuthor = MiniProto.stringField(r, 4) // targetAuthorAci
            val targetTs = MiniProto.varintField(r, 5) ?: return // targetSentTimestamp
            val targetExternalId =
                if (targetAuthor != null && targetAuthor == account.aci) "out:$targetTs"
                else "$targetAuthor:$targetTs"
            val from = if (outgoing) "me" else fromAci
            repo.applyReaction(SignalTransport.ID, targetExternalId, from, emoji, remove)
            return
        }

        val text = MiniProto.stringField(dm, 1) // body
        val attachment = MiniProto.bytesField(dm, 2) // attachments (first)
        if (text == null && attachment == null) return

        // Quote (DataMessage.quote, field 8): this message replies to another. The quote
        // carries the original's timestamp (1), author (3), and a text snapshot (4).
        var replyToExternalId: String? = null
        var replyToPreview: String? = null
        MiniProto.bytesField(dm, 8)?.let { quoteBytes ->
            val q = MiniProto.parse(quoteBytes)
            val qTs = MiniProto.varintField(q, 1) ?: return@let // Quote.id (target sent ts)
            // Modern Signal carries the author as authorAciBinary(8), a raw ServiceId;
            // fall back to the legacy string author(2) for older senders.
            val qAuthor = MiniProto.bytesField(q, 8)?.let { serviceIdToUuid(it) }
                ?: MiniProto.stringField(q, 2)
            replyToExternalId =
                if (qAuthor != null && qAuthor == account.aci) "out:$qTs" else "$qAuthor:$qTs"
            replyToPreview = MiniProto.stringField(q, 3)?.take(90) // Quote.text snapshot
                ?: repo.bodyForExternal(SignalTransport.ID, replyToExternalId!!)?.take(90)
        }
        val groupMasterKey = MiniProto.bytesField(dm, 15)?.let { MiniProto.bytesField(MiniProto.parse(it), 1) }
        val profileKey = MiniProto.bytesField(dm, 6)
        val ts = MiniProto.varintField(dm, 7) ?: serverTimestamp

        var mediaPath: String? = null
        var mediaType: String? = null
        if (attachment != null) {
            val attachments = SignalAttachments(SignalTrust.okHttpClient(context))
            val pointer = attachments.parse(attachment)
            attachments.download(pointer)?.let { bytes ->
                mediaPath = mediaStore.save(bytes, pointer.contentType)
                mediaType = pointer.contentType
            }
        }

        // Mentions (DataMessage.bodyRanges, repeated field 5). Each range: start(1), length(2), and
        // mentionAci(3) as a string or a 16-byte ServiceId. Signal puts a single U+FFFC placeholder
        // in the body at each mention; we resolve the ACI to a name and swap the placeholder for
        // "@Name". A range that @-mentions our own ACI also flags the message so a muted (Secondary)
        // group still notifies. Returns (start, length, aci) per range.
        val mentionRanges: List<Triple<Int, Int, String>> = dm.asSequence()
            .filter { it.number == 5 && it.bytes != null }
            .mapNotNull { br ->
                val f = MiniProto.parse(br.bytes!!)
                val aci = MiniProto.stringField(f, 3)
                    ?: MiniProto.bytesField(f, 3)?.let { serviceIdToUuid(it) }
                    ?: return@mapNotNull null
                val start = (MiniProto.varintField(f, 1) ?: 0L).toInt()
                val length = (MiniProto.varintField(f, 2) ?: 1L).toInt()
                Triple(start, length, aci)
            }.toList()
        val mentioned = mentionRanges.any { it.third.equals(account.aci, ignoreCase = true) }
        val renderedText = applyMentions(text, mentionRanges)

        val msgSender = if (outgoing) "me" else fromAci
        val msgExternalId = if (outgoing) "out:$ts" else "$fromAci:$ts"
        val convo = if (groupMasterKey != null) {
            recordInto(
                externalId = "group:" + Base64.encodeToString(groupMasterKey, Base64.NO_WRAP),
                title = "Signal group", address = "group",
                messageSender = msgSender, messageExternalId = msgExternalId,
                text = renderedText, ts = ts, outgoing = outgoing,
                mediaPath = mediaPath, mediaType = mediaType,
                replyToExternalId = replyToExternalId, replyToPreview = replyToPreview,
                mentioned = mentioned,
            )
        } else {
            val convoAci = if (outgoing) (recipientAci ?: return) else fromAci
            recordInto(
                externalId = convoAci,
                // Use a name we already know (e.g. from a shared group); otherwise a placeholder
                // that resolveName() upgrades to their profile name once they reply.
                title = contactNames.get(convoAci) ?: fromE164 ?: "Signal user",
                address = fromE164 ?: convoAci,
                messageSender = msgSender, messageExternalId = msgExternalId,
                text = renderedText, ts = ts, outgoing = outgoing,
                mediaPath = mediaPath, mediaType = mediaType,
                replyToExternalId = replyToExternalId, replyToPreview = replyToPreview,
                mentioned = mentioned,
            )
        }

        if (!outgoing && profileKey != null && fromAci !in resolvedNames) {
            resolvedNames += fromAci
            resolveName(fromAci, profileKey)
        }
        if (groupMasterKey != null && convo.externalId !in resolvedGroups) {
            resolvedGroups += convo.externalId
            resolveGroupName(groupMasterKey, convo.id, convo.externalId)
        }
    }

    /** Replace each mention's U+FFFC placeholder in [body] with "@Name". Ranges are (start, len, aci). */
    private fun applyMentions(body: String?, ranges: List<Triple<Int, Int, String>>): String {
        val text = body ?: return ""
        if (ranges.isEmpty()) return text
        var out = text
        // Apply from the end so earlier character offsets stay valid as we substitute.
        for ((start, length, aci) in ranges.sortedByDescending { it.first }) {
            if (start < 0 || length < 0 || start + length > out.length) continue
            out = out.substring(0, start) + "@" + mentionName(aci) + out.substring(start + length)
        }
        return out
    }

    /** Display name for an @-mentioned ACI: our own → "you", else the known contact/profile name. */
    private fun mentionName(aci: String): String =
        if (aci.equals(account.aci, ignoreCase = true)) "you"
        else contactNames.get(aci) ?: "someone"

    private suspend fun recordInto(
        externalId: String, title: String, address: String,
        messageSender: String, messageExternalId: String,
        text: String, ts: Long, outgoing: Boolean,
        mediaPath: String? = null, mediaType: String? = null,
        replyToExternalId: String? = null, replyToPreview: String? = null,
        mentioned: Boolean = false,
    ): com.sidephone.aviary.data.ConversationEntity {
        val convo = repo.upsertConversation(
            transportId = SignalTransport.ID,
            externalId = externalId,
            address = address,
            title = title,
            category = InboxCategory.PRIMARY,
        )
        val rowId = repo.recordMessage(
            MessageEntity(
                conversationId = convo.id,
                transportId = SignalTransport.ID,
                externalId = messageExternalId,
                sender = messageSender,
                body = text,
                timestamp = ts,
                outgoing = outgoing,
                status = if (outgoing) MessageStatus.SENT else MessageStatus.RECEIVED,
                mediaPath = mediaPath,
                mediaType = mediaType,
                replyToExternalId = replyToExternalId,
                replyToPreview = replyToPreview,
            )
        )
        // Notify for genuinely new incoming messages (not our own, not re-syncs).
        if (rowId > 0 && !outgoing) {
            val isGroup = externalId.startsWith("group:")
            val senderName = contactNames.get(messageSender)
                ?: if (isGroup) "Someone" else convo.title
            // Secondary groups notify only when you're @mentioned; secondary 1:1 threads still
            // notify for every message; primary always notifies.
            val muted = convo.muted ||
                (convo.category == InboxCategory.SECONDARY && isGroup && !mentioned)
            com.sidephone.aviary.data.Notifier.post(
                context, convo.id,
                sender = senderName,
                body = text.ifBlank { "📎 Attachment" },
                // Group: prefer the sender's avatar, fall back to the group's.
                avatarPath = avatarStore.path(messageSender) ?: avatarStore.path(externalId),
                timestamp = ts,
                isGroup = isGroup,
                groupTitle = convo.title,
                muted = muted,
            )
        }
        return convo
    }

    /** Fetch the sender's profile name + avatar; rename their 1:1 thread. */
    private fun resolveName(aci: String, profileKey: ByteArray) {
        scope.launch {
            val info = SignalProfile(SignalTrust.okHttpClient(context))
                .fetch(account.authToken(), aci, profileKey)
            info.avatar?.let { avatarStore.save(aci, it) }
            val name = info.name ?: return@launch
            contactNames.put(aci, name)
            repo.conversationByExternal(SignalTransport.ID, aci)
                ?.let { repo.setConversationTitle(it.id, name) }
        }
    }

    /**
     * Backfill names for conversations created before they could be resolved:
     * group titles (we have the master key in the id) and 1:1 names already known.
     */
    private fun backfillNames() {
        scope.launch {
            repo.conversationsForTransport(SignalTransport.ID).forEach { convo ->
                when {
                    convo.externalId.startsWith("group:") &&
                        (convo.title == "Signal group" || !avatarStore.has(convo.externalId)) -> {
                        if (resolvedGroups.add(convo.externalId)) {
                            val masterKey = runCatching {
                                Base64.decode(convo.externalId.removePrefix("group:"), Base64.NO_WRAP)
                            }.getOrNull() ?: return@forEach
                            resolveGroupName(masterKey, convo.id, convo.externalId)
                        }
                    }
                    convo.title == "Signal user" ->
                        contactNames.get(convo.externalId)?.let { repo.setConversationTitle(convo.id, it) }
                }
            }
        }
    }

    /** Fetch the group's title + avatar and rename the thread. */
    private fun resolveGroupName(masterKey: ByteArray, convoId: Long, externalId: String) {
        val aci = account.aci ?: return
        val pni = account.pni ?: return
        scope.launch {
            val info = SignalGroups(SignalTrust.okHttpClient(context))
                .fetch(account.authToken(), aci, pni, masterKey)
            info.avatar?.let { avatarStore.save(externalId, it) }
            info.title?.let { repo.setConversationTitle(convoId, it) }
        }
    }

    private val resolvedNames = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    private val resolvedGroups = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    // (sender.device:sentTs) we've already asked to resend — one retry receipt per message.
    private val retriedEnvelopes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    private fun ackRequest(ws: WebSocket, requestId: Long) {
        val response = MiniProto.Writer().varint(1, requestId).varint(2, 200).string(3, "OK").toByteArray()
        ws.send(MiniProto.Writer().varint(1, 2).bytes(3, response).toByteArray().toByteString())
    }

    /**
     * Signal pads plaintext with PushTransportDetails: message | 0x80 | 0x00*.
     * Strip trailing zeros, then the 0x80 delimiter.
     */
    /** A ServiceId binary (16-byte ACI, or 17-byte PNI with a 0x01 prefix) -> its UUID string. */
    private fun serviceIdToUuid(b: ByteArray): String? = runCatching {
        val raw = if (b.size == 17) b.copyOfRange(1, 17) else b
        if (raw.size != 16) return null
        val bb = java.nio.ByteBuffer.wrap(raw)
        java.util.UUID(bb.long, bb.long).toString()
    }.getOrNull()

    private fun stripPadding(padded: ByteArray): ByteArray {
        var i = padded.size - 1
        while (i >= 0 && padded[i].toInt() == 0x00) i--
        if (i < 0 || padded[i].toInt() and 0xFF != 0x80) return padded // unpadded
        return padded.copyOfRange(0, i)
    }

    companion object {
        private const val TAG = "SignalReceiver"
        private val UUID_RE =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private const val CHAT_WS = "wss://chat.signal.org"
        private const val USER_AGENT = "Relay/0.1 (Android; linked-device)"

        private const val PREKEY_LOW_THRESHOLD = 20
        private const val TYPE_DOUBLE_RATCHET = 1
        private const val TYPE_PREKEY = 3
        private const val TYPE_RECEIPT = 5
        private const val TYPE_UNIDENTIFIED_SENDER = 6

        // Signal production sealed-sender trust roots (from Signal-Android BuildConfig).
        private val TRUST_ROOTS = listOf(
            "BXu6QIKVz5MA8gstzfOgRQGqyLqOwNKHL6INkv3IHWMF",
            "BUkY0I+9+oPgDCn4+Ac6Iu813yvqkDr/ga8DzLxFxuk6",
        )
    }
}
