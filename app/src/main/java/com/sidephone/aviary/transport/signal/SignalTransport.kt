package com.sidephone.aviary.transport.signal

import android.content.Context
import android.util.Base64
import android.util.Log
import com.sidephone.aviary.data.ConversationEntity
import com.sidephone.aviary.data.InboxCategory
import com.sidephone.aviary.data.MessageEntity
import com.sidephone.aviary.data.MessageStatus
import com.sidephone.aviary.data.Protocol
import com.sidephone.aviary.data.UnifiedRepository
import com.sidephone.aviary.transport.MessageTransport
import com.sidephone.aviary.transport.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URLEncoder

/**
 * Signal as a linked device (like Signal Desktop). Flow:
 *   1. Provisioning handshake over the provisioning websocket → QR scanned by the
 *      primary phone → we receive & decrypt a ProvisionMessage (identity keys,
 *      number, provisioning code).  [DONE]
 *   2. Generate key material with libsignal and register via PUT /v1/devices/link,
 *      then upload one-time prekeys.  [DONE — needs live validation]
 *   3. Authenticated receive loop + send.  [NEXT MILESTONE]
 *
 * Credentials and keys are stored only on-device, Keystore-encrypted.
 */
class SignalTransport(
    private val context: Context,
    private val repo: UnifiedRepository,
    private val scope: CoroutineScope,
    private val contactNames: com.sidephone.aviary.data.ContactNames,
    private val avatarStore: com.sidephone.aviary.data.AvatarStore,
    private val mediaStore: com.sidephone.aviary.data.MediaStore,
) : MessageTransport {

    override val id = ID
    override val protocol = Protocol.SIGNAL

    sealed class LinkState {
        data object Idle : LinkState()
        data object Connecting : LinkState()
        /** Show this URI as a QR code; the primary phone scans it. */
        data class AwaitingScan(val uri: String) : LinkState()
        /** ProvisionMessage received; registering with the server. */
        data object Registering : LinkState()
        data class Linked(val number: String, val deviceId: Int) : LinkState()
        data class Failed(val message: String) : LinkState()
    }

    private val account = SignalAccount(context)

    private val _status = MutableStateFlow<TransportStatus>(
        TransportStatus.NeedsSetup("Link to your Signal account")
    )
    override val status: StateFlow<TransportStatus> = _status

    private val _linkState = MutableStateFlow<LinkState>(LinkState.Idle)
    val linkState: StateFlow<LinkState> = _linkState

    private var linkJob: Job? = null
    private var socket: WebSocket? = null
    private var receiver: SignalReceiver? = null

    /** libsignal state, shared by the receive and send paths. Built once linked. */
    private val store: AviaryProtocolStore by lazy {
        AviaryProtocolStore(context, account.identity(SignalAccount.KIND_ACI)!!, account.aciRegistrationId)
    }

    override suspend fun start() {
        if (account.isRegistered) {
            startReceiving()
        } else {
            _status.value = TransportStatus.NeedsSetup("Link to your Signal account")
        }
    }

    /** Opens the authenticated receive websocket. Idempotent and thread-safe. */
    @Synchronized
    fun startReceiving() {
        if (!account.isRegistered || receiver != null) return
        receiver = SignalReceiver(context, account, repo, scope, contactNames, avatarStore, mediaStore, store) { connected ->
            _status.value = if (connected) {
                TransportStatus.Ready
            } else {
                TransportStatus.Linking(
                    "Linked as device ${account.deviceId} for ${account.number} — reconnecting…"
                )
            }
        }.also { it.start() }
    }

    @Synchronized
    fun stopReceiving() {
        receiver?.stop(); receiver = null
    }

    fun isRegistered(): Boolean = account.isRegistered

    override suspend fun sendText(
        conversation: ConversationEntity, body: String, replyTo: MessageEntity?,
    ): Result<Unit> {
        if (!account.isRegistered) {
            return Result.failure(IllegalStateException("Link Signal first"))
        }
        // Record the message immediately so it appears instantly, then send and
        // update the status when the network call resolves.
        val ts = System.currentTimeMillis()
        val externalId = "out:$ts"
        Log.i("SignalReply", "SEND ts=$ts extId=$externalId body='${body.take(24)}'")
        // An inline reply carries a Quote naming the target's author + sent timestamp.
        val quote = replyTo?.let {
            val authorAci = if (it.outgoing) account.aci else it.sender
            if (authorAci == null) null
            else SignalSender.Quote(it.timestamp, authorAci, it.body)
        }
        if (replyTo != null) {
            Log.i(
                "SignalReply",
                "quote target: extId=${replyTo.externalId} outgoing=${replyTo.outgoing} " +
                    "sender=${replyTo.sender} ts=${replyTo.timestamp} myAci=${account.aci} " +
                    "-> author=${quote?.authorAci} id=${quote?.targetTimestamp}",
            )
        }
        repo.recordMessage(
            MessageEntity(
                conversationId = conversation.id,
                transportId = ID,
                externalId = externalId,
                sender = "me",
                body = body,
                timestamp = ts,
                outgoing = true,
                status = MessageStatus.PENDING,
                replyToExternalId = replyTo?.externalId,
                replyToPreview = replyTo?.let { it.body.ifBlank { null } },
            )
        )
        return withContext(Dispatchers.IO) {
            val sender = SignalSender(store, account, SignalTrust.okHttpClient(context))
            val sent: Result<Unit> = if (conversation.externalId.startsWith("group:")) {
                val masterKey = Base64.decode(
                    conversation.externalId.removePrefix("group:"), Base64.NO_WRAP
                )
                val group = SignalGroups(SignalTrust.okHttpClient(context))
                    .fetch(account.authToken(), account.aci!!, account.pni!!, masterKey)
                if (group.memberAcis.isEmpty()) {
                    Result.failure(IllegalStateException("Couldn't load group members"))
                } else {
                    sender.sendGroup(masterKey, group, body, ts, quote)
                }
            } else {
                sender.sendDirect(conversation.externalId, body, ts, quote)
            }
            repo.setStatusByExternal(
                ID, externalId,
                if (sent.isSuccess) MessageStatus.SENT else MessageStatus.FAILED,
            )
            sent
        }
    }

    override suspend fun sendReaction(
        conversation: ConversationEntity, message: MessageEntity, emoji: String, add: Boolean,
    ): Result<Unit> {
        if (!account.isRegistered) return Result.failure(IllegalStateException("Link Signal first"))
        val targetExternal = message.externalId
            ?: return Result.failure(IllegalStateException("message not sent yet"))
        val targetAuthor = (if (message.outgoing) account.aci else message.sender)
            ?: return Result.failure(IllegalStateException("no target author"))
        val ts = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            val sender = SignalSender(store, account, SignalTrust.okHttpClient(context))
            val r = if (conversation.externalId.startsWith("group:")) {
                val masterKey = Base64.decode(conversation.externalId.removePrefix("group:"), Base64.NO_WRAP)
                val group = SignalGroups(SignalTrust.okHttpClient(context))
                    .fetch(account.authToken(), account.aci!!, account.pni!!, masterKey)
                sender.sendReactionGroup(masterKey, group, targetAuthor, message.timestamp, emoji, !add, ts)
            } else {
                sender.sendReactionDirect(conversation.externalId, targetAuthor, message.timestamp, emoji, !add, ts)
            }
            if (r.isSuccess) repo.applyReaction(ID, targetExternal, "me", if (add) emoji else null, remove = !add)
            r
        }
    }

    override suspend fun sendTyping(conversation: ConversationEntity, isTyping: Boolean) {
        if (!account.isRegistered || conversation.externalId.startsWith("group:")) return
        withContext(Dispatchers.IO) {
            runCatching {
                SignalSender(store, account, SignalTrust.okHttpClient(context))
                    .sendTyping(conversation.externalId, isTyping, System.currentTimeMillis())
            }
        }
    }

    override suspend fun markConversationRead(conversation: ConversationEntity) {
        // Read receipts are 1:1 for now.
        if (!account.isRegistered || conversation.externalId.startsWith("group:")) return
        withContext(Dispatchers.IO) {
            val extId = repo.latestIncomingExternalId(conversation.id) ?: return@withContext
            val targetTs = extId.substringAfter(":").toLongOrNull() ?: return@withContext
            runCatching {
                SignalSender(store, account, SignalTrust.okHttpClient(context))
                    .sendReadReceipt(conversation.externalId, listOf(targetTs), System.currentTimeMillis())
            }
        }
    }

    override suspend fun sendMedia(
        conversation: ConversationEntity,
        media: ByteArray,
        contentType: String?,
        fileName: String?,
        caption: String,
    ): Result<Unit> {
        if (!account.isRegistered) return Result.failure(IllegalStateException("Link Signal first"))
        val ts = System.currentTimeMillis()
        val externalId = "out:$ts"
        // Optimistic: store a local copy and show the message immediately.
        val localPath = runCatching { mediaStore.save(media, contentType) }.getOrNull()
        repo.recordMessage(
            MessageEntity(
                conversationId = conversation.id, transportId = ID, externalId = externalId,
                sender = "me", body = caption, timestamp = ts, outgoing = true,
                status = MessageStatus.PENDING, mediaPath = localPath, mediaType = contentType,
            )
        )
        return withContext(Dispatchers.IO) {
            val sender = SignalSender(store, account, SignalTrust.okHttpClient(context))
            val sent = if (conversation.externalId.startsWith("group:")) {
                val masterKey = Base64.decode(conversation.externalId.removePrefix("group:"), Base64.NO_WRAP)
                val group = SignalGroups(SignalTrust.okHttpClient(context))
                    .fetch(account.authToken(), account.aci!!, account.pni!!, masterKey)
                if (group.memberAcis.isEmpty()) Result.failure(IllegalStateException("Couldn't load group members"))
                else sender.sendMediaGroup(masterKey, group, media, contentType, fileName, caption, ts)
            } else {
                sender.sendMediaDirect(conversation.externalId, media, contentType, fileName, caption, ts)
            }
            repo.setStatusByExternal(ID, externalId, if (sent.isSuccess) MessageStatus.SENT else MessageStatus.FAILED)
            sent
        }
    }

    override val canStartConversations: Boolean get() = account.isRegistered

    override suspend fun startConversation(address: String): Result<Long> = withContext(Dispatchers.IO) {
        if (!account.isRegistered) return@withContext Result.failure(IllegalStateException("Link Signal first"))
        val cds = SignalContactDiscovery(account, SignalTrust.okHttpClient(context))
        // A Signal @username contains letters; otherwise treat it as a phone number (CDSI lookup).
        val isUsername = address.any { it.isLetter() }
        val (aci, addr, title) = if (isUsername) {
            val a = cds.aciForUsername(address)
                ?: return@withContext Result.failure(IllegalStateException("No Signal user “$address”"))
            Triple(a, address.trim().removePrefix("@"), address.trim().removePrefix("@"))
        } else {
            val e164 = normalizeE164(address)
                ?: return@withContext Result.failure(IllegalStateException("Enter a valid phone number"))
            val a = cds.aciFor(e164)
                ?: return@withContext Result.failure(IllegalStateException("$address isn't on Signal"))
            Triple(a, e164, contactNames.get(e164) ?: e164)
        }
        val convo = repo.upsertConversation(
            transportId = ID, externalId = aci, address = addr, title = title,
            category = InboxCategory.PRIMARY,
        )
        Result.success(convo.id)
    }

    /** Open (or create) a 1:1 with a Signal ACI we already know — e.g. a member of a group. */
    suspend fun startWithAci(aci: String, name: String?): Result<Long> = withContext(Dispatchers.IO) {
        if (!account.isRegistered) return@withContext Result.failure(IllegalStateException("Link Signal first"))
        val convo = repo.upsertConversation(
            transportId = ID, externalId = aci, address = aci,
            title = name ?: contactNames.get(aci) ?: "Signal user", category = InboxCategory.PRIMARY,
        )
        Result.success(convo.id)
    }

    /** Best-effort E.164: keep a leading +, else assume US (+1) for a 10-digit number. */
    private fun normalizeE164(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("+")) {
            val d = "+" + trimmed.drop(1).filter { it.isDigit() }
            return d.takeIf { it.length in 9..16 }
        }
        val d = trimmed.filter { it.isDigit() }
        return when {
            d.length == 10 -> "+1$d"
            d.length == 11 && d.startsWith("1") -> "+$d"
            d.length in 8..15 -> "+$d"
            else -> null
        }
    }

    override suspend fun resend(
        conversation: ConversationEntity, message: MessageEntity,
    ): Result<Unit> {
        if (!account.isRegistered) return Result.failure(IllegalStateException("Link Signal first"))
        val externalId = message.externalId ?: "out:${message.timestamp}"
        val ts = externalId.substringAfter("out:").toLongOrNull() ?: message.timestamp
        repo.setMessageStatus(message.id, MessageStatus.PENDING)
        return withContext(Dispatchers.IO) {
            val sender = SignalSender(store, account, SignalTrust.okHttpClient(context))
            val isGroup = conversation.externalId.startsWith("group:")
            val masterKey = if (isGroup)
                Base64.decode(conversation.externalId.removePrefix("group:"), Base64.NO_WRAP) else null
            val group = if (isGroup) SignalGroups(SignalTrust.okHttpClient(context))
                .fetch(account.authToken(), account.aci!!, account.pni!!, masterKey!!) else null
            val media = message.mediaPath?.let { runCatching { java.io.File(it).readBytes() }.getOrNull() }
            // Reply-quote is dropped on retry (target reconstruction isn't stored); text/media kept.
            val sent: Result<Unit> = when {
                media != null && isGroup ->
                    sender.sendMediaGroup(masterKey!!, group!!, media, message.mediaType, null, message.body, ts)
                media != null ->
                    sender.sendMediaDirect(conversation.externalId, media, message.mediaType, null, message.body, ts)
                isGroup ->
                    if (group!!.memberAcis.isEmpty()) Result.failure(IllegalStateException("Couldn't load group members"))
                    else sender.sendGroup(masterKey!!, group, message.body, ts, null)
                else -> sender.sendDirect(conversation.externalId, message.body, ts, null)
            }
            repo.setStatusByExternal(ID, externalId, if (sent.isSuccess) MessageStatus.SENT else MessageStatus.FAILED)
            sent
        }
    }

    fun beginLinking() {
        // Always start a fresh session: Signal's provisioning UUID expires after a
        // few minutes, so a reused socket would show a stale QR the primary phone
        // rejects as "not valid". Tear down any prior attempt first.
        socket?.close(1000, "restart")
        socket = null
        linkJob?.cancel()
        _linkState.value = LinkState.Connecting
        linkJob = scope.launch { runProvisioningSocket() }
    }

    fun cancelLinking() {
        socket?.close(1000, "cancelled")
        linkJob?.cancel()
        if (_linkState.value !is LinkState.Linked) _linkState.value = LinkState.Idle
    }

    private fun runProvisioningSocket() {
        val crypto = ProvisioningCrypto()
        val client = SignalTrust.okHttpClient(context)
        val request = Request.Builder()
            .url("$PROVISIONING_WS/v1/websocket/provisioning/")
            .header("User-Agent", USER_AGENT)
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { handleFrame(webSocket, bytes.toByteArray(), crypto) }
                    .onFailure { e ->
                        _linkState.value = LinkState.Failed(e.message ?: "provisioning error")
                        webSocket.close(1000, null)
                    }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (_linkState.value !is LinkState.Linked &&
                    _linkState.value !is LinkState.Registering
                ) {
                    _linkState.value = LinkState.Failed(t.message ?: "connection failed")
                }
            }
        })
    }

    private fun handleFrame(ws: WebSocket, frame: ByteArray, crypto: ProvisioningCrypto) {
        val msg = MiniProto.parse(frame)
        val requestBytes = MiniProto.bytesField(msg, 2) ?: return
        val request = MiniProto.parse(requestBytes)
        val path = MiniProto.stringField(request, 2) ?: return
        val body = MiniProto.bytesField(request, 3)
        val requestId = MiniProto.varintField(request, 4) ?: 0

        when (path) {
            "/v1/address" -> {
                val uuid = MiniProto.stringField(MiniProto.parse(body ?: return), 1) ?: return
                // Match signal-cli exactly: STANDARD base64 (not URL-safe) with padding
                // stripped, then URL-encode both params. The primary phone decodes the
                // pub_key with a strict standard base64 decoder that rejects '-'/'_', so
                // URL-safe encoding here makes some QRs "invalid".
                val pubKey = Base64.encodeToString(crypto.publicKeySignalEncoded(), Base64.NO_WRAP)
                    .replace("=", "")
                val qrUri = "sgnl://linkdevice?uuid=" +
                    URLEncoder.encode(uuid, "UTF-8") +
                    "&pub_key=" + URLEncoder.encode(pubKey, "UTF-8")
                Log.i(TAG, "provisioning uuid=$uuid qr=$qrUri")
                _linkState.value = LinkState.AwaitingScan(qrUri)
            }

            "/v1/message" -> {
                val envelope = MiniProto.parse(body ?: return)
                val theirKey = MiniProto.bytesField(envelope, 1) ?: return
                val encrypted = MiniProto.bytesField(envelope, 2) ?: return
                val plain = crypto.decryptEnvelope(theirKey, encrypted)
                onProvisionMessage(MiniProto.parse(plain))
                ws.close(1000, "done")
            }
        }
        ackRequest(ws, requestId)
    }

    /**
     * ProvisionMessage field numbers per Signal's Provisioning.proto. These are
     * version-sensitive; validate against the current proto if linking fails.
     */
    private fun onProvisionMessage(pm: List<MiniProto.Field>) {
        val aciPublic = MiniProto.bytesField(pm, 1) ?: error("missing ACI identity public key")
        val aciPrivate = MiniProto.bytesField(pm, 2) ?: error("missing ACI identity private key")
        val number = MiniProto.stringField(pm, 3) ?: error("missing number")
        val provisioningCode = MiniProto.stringField(pm, 4) ?: error("missing provisioning code")
        val aciUuid = MiniProto.stringField(pm, 8)
        val pniUuid = MiniProto.stringField(pm, 10)
        val pniPublic = MiniProto.bytesField(pm, 11)
        val pniPrivate = MiniProto.bytesField(pm, 12)

        _linkState.value = LinkState.Registering
        _status.value = TransportStatus.Linking("Registering device with Signal…")

        scope.launch {
            runCatching {
                register(
                    number = number,
                    provisioningCode = provisioningCode,
                    aciUuid = aciUuid,
                    pniUuid = pniUuid,
                    aciPublic = aciPublic,
                    aciPrivate = aciPrivate,
                    pniPublic = pniPublic,
                    pniPrivate = pniPrivate,
                )
            }.onSuccess {
                _linkState.value = LinkState.Linked(account.number ?: number, account.deviceId)
                _status.value = TransportStatus.Linking(
                    "Linked as device ${account.deviceId} for ${account.number} — connecting…"
                )
                startReceiving()
            }.onFailure { e ->
                _linkState.value = LinkState.Failed(e.message ?: "registration failed")
                _status.value = TransportStatus.Error(e.message ?: "registration failed")
            }
        }
    }

    private suspend fun register(
        number: String,
        provisioningCode: String,
        aciUuid: String?,
        pniUuid: String?,
        aciPublic: ByteArray,
        aciPrivate: ByteArray,
        pniPublic: ByteArray?,
        pniPrivate: ByteArray?,
    ) = withContext(Dispatchers.IO) {
        // Re-link starts clean: drop the old receive socket and any stale key state.
        stopReceiving()
        val aciIdentity = IdentityKeys.identityFrom(aciPublic, aciPrivate)
        // PNI identity is required by the modern link flow; fall back to ACI's shape
        // only if the primary didn't send one (older accounts).
        val pniIdentity = if (pniPublic != null && pniPrivate != null)
            IdentityKeys.identityFrom(pniPublic, pniPrivate) else aciIdentity

        val aciKeys = IdentityKeys.generate(aciIdentity)
        val pniKeys = IdentityKeys.generate(pniIdentity)

        val password = SignalAccount.generatePassword()
        val deviceName = DeviceName.encryptBase64("Relay (SP-01)", aciIdentity)

        val api = SignalApi(SignalTrust.okHttpClient(context))
        val result = api.linkDevice(
            number = number,
            password = password,
            provisioningCode = provisioningCode,
            encryptedDeviceName = deviceName,
            aci = aciKeys,
            pni = pniKeys,
        )

        account.apply {
            this.number = number
            this.password = password
            this.aci = result.aci
            this.pni = result.pni
            this.deviceId = result.deviceId
            this.aciRegistrationId = aciKeys.registrationId
            this.pniRegistrationId = pniKeys.registrationId
            storeIdentity(SignalAccount.KIND_ACI, aciIdentity)
            storeIdentity(SignalAccount.KIND_PNI, pniIdentity)
        }

        // Persist the ACI pre-keys (with private halves) so the receive loop can
        // decrypt PreKeySignalMessages that consume the keys we just uploaded.
        AviaryProtocolStore(context, aciIdentity, aciKeys.registrationId).apply {
            clear() // drop any prior link's stale sessions/keys
            storeSignedPreKey(aciKeys.signedPreKey.id, aciKeys.signedPreKey)
            storeKyberPreKey(aciKeys.lastResortKyberPreKey.id, aciKeys.lastResortKyberPreKey)
            aciKeys.oneTimePreKeys.forEach { storePreKey(it.id, it) }
            aciKeys.oneTimeKyberPreKeys.forEach { storeKyberPreKey(it.id, it) }
        }

        // One-time prekey upload. The endpoint's ?identity= expects the literal
        // "aci"/"pni", NOT the UUID — passing the UUID makes the upload silently
        // fail and leaves the device with zero one-time prekeys.
        runCatching {
            api.uploadOneTimeKeys(account.authToken(), "aci", aciKeys)
            api.uploadOneTimeKeys(account.authToken(), "pni", pniKeys)
        }.onFailure { Log.w(TAG, "one-time prekey upload failed", it) }
    }

    private fun ackRequest(ws: WebSocket, requestId: Long) {
        val response = MiniProto.Writer()
            .varint(1, requestId)
            .varint(2, 200)
            .string(3, "OK")
            .toByteArray()
        val frame = MiniProto.Writer()
            .varint(1, 2)
            .bytes(3, response)
            .toByteArray()
        ws.send(frame.toByteString())
    }

    companion object {
        const val ID = "signal"
        private const val TAG = "SignalProvision"
        private const val PROVISIONING_WS = "wss://chat.signal.org"
        private const val USER_AGENT = "Relay/0.1 (Android; linked-device)"
    }
}
