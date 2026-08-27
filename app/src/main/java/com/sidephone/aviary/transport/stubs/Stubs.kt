package com.sidephone.aviary.transport.imessage

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.sidephone.aviary.data.ConversationEntity
import com.sidephone.aviary.data.InboxCategory
import com.sidephone.aviary.data.MessageEntity
import com.sidephone.aviary.data.MessageStatus
import com.sidephone.aviary.data.Protocol
import com.sidephone.aviary.data.UnifiedRepository
import com.sidephone.aviary.imessage.ImessageNative
import com.sidephone.aviary.transport.MessageTransport
import com.sidephone.aviary.transport.ReachabilityAware
import com.sidephone.aviary.transport.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * iMessage via rustpush (OpenBubbles' engine) embedded as libaviary_imessage.so.
 *
 * Anisette (ADI) is generated on-device (Apple's libstoreservicescore.so via
 * android-loader). IDS validation data (NAC) comes from the RelayConfig's host/code
 * — a Mac generator/relay the user configures (Stage 7). This class only orchestrates
 * the native init → login → 2FA → register → send/poll flow.
 */
class IMessageTransport(
    private val context: Context,
    private val repo: UnifiedRepository,
    private val scope: CoroutineScope,
    private val avatarStore: com.sidephone.aviary.data.AvatarStore,
    private val mediaStore: com.sidephone.aviary.data.MediaStore,
) : MessageTransport, ReachabilityAware {

    override val id = ID
    override val protocol = Protocol.IMESSAGE

    sealed class SetupState {
        data object Idle : SetupState()
        /** No relay host/code configured yet. */
        data object NeedsConfig : SetupState()
        data object LoggingIn : SetupState()
        data class Needs2FA(val kind: String) : SetupState()
        data object Registering : SetupState()
        data object Ready : SetupState()
        data class Failed(val message: String) : SetupState()
    }

    private val prefs = context.getSharedPreferences("imessage", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow<TransportStatus>(
        TransportStatus.NeedsSetup("Set up iMessage")
    )
    override val status: StateFlow<TransportStatus> = _status

    private val _setup = MutableStateFlow<SetupState>(SetupState.Idle)
    val setup: StateFlow<SetupState> = _setup

    private var pollJob: Job? = null
    @Volatile private var initialized = false

    // Cache of address -> iMessage-reachable, so the router doesn't do an IDS lookup
    // on every send. iMessage capability changes rarely.
    private val reachCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // Apple re-delivers cached messages on each reconnect; skip ones we've already
    // handled this session so we don't churn the DB (which caused UI flicker).
    private val seenGuids =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** True if the address (phone or email) is registered on iMessage — routes it blue. */
    override suspend fun canReach(address: String): Boolean {
        if (!initialized || _status.value !is TransportStatus.Ready) return false
        reachCache[address]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val res = json(ImessageNative.nativeCanReach(address))
                val reachable = res.optBoolean("ok") && res.optBoolean("reachable")
                // Only cache positive/known results; leave transient failures uncached.
                if (res.optBoolean("ok")) reachCache[address] = reachable
                reachable
            }.getOrDefault(false)
        }
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        // One-time cleanup: earlier builds keyed iMessage threads differently, leaving
        // duplicate conversations. Wipe once so re-synced messages land under the clean key.
        if (!prefs.getBoolean(KEY_CLEANED, false)) {
            repo.clearTransport(id)
            prefs.edit().putBoolean(KEY_CLEANED, true).apply()
        }
        val config = prefs.getString(KEY_CONFIG, null)
        if (config.isNullOrBlank()) {
            _status.value = TransportStatus.NeedsSetup("Set up iMessage")
            _setup.value = SetupState.NeedsConfig
            return@withContext
        }
        // Only stand up the push connection at launch if we completed setup before. An
        // unregistered app has no saved push certificate, so connecting would re-activate with
        // Apple on a loop (hammering albert.apple.com at every launch). Stay dormant until the
        // user explicitly signs in — this is how OpenBubbles behaves: activate once, then reuse.
        if (!prefs.getBoolean(KEY_REGISTERED, false)) {
            _status.value = TransportStatus.NeedsSetup("Sign in to iMessage")
            _setup.value = SetupState.Idle
            return@withContext
        }
        when (val r = ensureInit(config)) {
            is InitResult.Registered -> onRegistered()
            is InitResult.NotRegistered ->
                _status.value = TransportStatus.NeedsSetup("Sign in to iMessage")
            is InitResult.Error -> fail(r.message)
        }
    }

    /** Persist the one-time Mac hardware config (OABS blob from Mac Hardware Info.app). */
    fun configure(macConfig: String) {
        prefs.edit().putString(KEY_CONFIG, macConfig).apply()
    }

    /** The saved Mac hardware config, if one was already entered (so the UI can reuse it). */
    fun savedMacConfig(): String = prefs.getString(KEY_CONFIG, "").orEmpty()

    /**
     * Nuke the native iMessage state — the push keypair, keystore keys, IDS registration and cached
     * identity — for a clean re-setup. Needed when the saved state is a legacy/partial format or the
     * keystore holds a stale activation key that collides with a fresh activation ("Key already
     * exists"). The Mac hardware config (in prefs) is kept, so re-setup only needs the Apple ID
     * again. The process is killed afterward so the running (hammering) push connection dies and the
     * next launch re-initializes from the clean slate.
     */
    fun resetAccountAndRestart() {
        val dir = java.io.File(context.filesDir, "imessage")
        dir.listFiles()?.forEach { f -> runCatching { f.delete() } }
        prefs.edit().remove(KEY_CLEANED).remove(KEY_REGISTERED).apply()
        Log.w(TAG, "iMessage native state wiped; killing process for a clean restart")
        // Kill the process: the APS retry loop holds its own Arc and won't stop otherwise, and a
        // fresh process guarantees nativeInit reloads from the now-empty config dir.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ---- setup flow (called from the UI) -----------------------------------

    suspend fun login(email: String, password: String): Result<SetupState> =
        withContext(Dispatchers.IO) {
            val config = prefs.getString(KEY_CONFIG, null)
            if (config.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("paste your Mac config first"))
            }
            _setup.value = SetupState.LoggingIn
            when (val init = ensureInit(config)) {
                is InitResult.Error -> return@withContext fail(init.message).let { Result.failure(Exception(init.message)) }
                else -> {}
            }
            val rawLogin = ImessageNative.nativeLogin(email, password)
            Log.i(TAG, "nativeLogin => $rawLogin")
            val res = json(rawLogin)
            if (!res.optBoolean("ok")) return@withContext fail(res.optString("error")).let {
                Result.failure(Exception(res.optString("error")))
            }
            when (res.optString("state")) {
                "logged_in" -> register()
                "needs_2fa" -> {
                    val st = SetupState.Needs2FA(res.optString("kind", "device"))
                    _setup.value = st
                    Result.success(st)
                }
                else -> Result.success(_setup.value)
            }
        }

    suspend fun submit2fa(code: String): Result<SetupState> = withContext(Dispatchers.IO) {
        val raw = ImessageNative.nativeSubmit2fa(code)
        Log.i(TAG, "nativeSubmit2fa => $raw")
        val res = json(raw)
        if (!res.optBoolean("ok")) return@withContext fail(res.optString("error")).let {
            Result.failure(Exception(res.optString("error")))
        }
        if (res.optString("state") == "logged_in") register()
        else Result.success(_setup.value)
    }

    private suspend fun register(): Result<SetupState> {
        _setup.value = SetupState.Registering
        val res = json(ImessageNative.nativeRegister())
        if (!res.optBoolean("ok")) {
            fail(res.optString("error"))
            return Result.failure(Exception(res.optString("error")))
        }
        onRegistered()
        return Result.success(SetupState.Ready)
    }

    // ---- send / receive ----------------------------------------------------

    override suspend fun sendText(
        conversation: ConversationEntity, body: String, replyTo: MessageEntity?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            // Optimistic: show the bubble immediately as "Sending…" (tagged iMessage so it's
            // blue even in an SMS-owned thread), then reconcile once the network send returns.
            // Pre-generate the guid and store it on the row NOW, so a fast Delivered receipt
            // can't race in before we've persisted the id (which used to drop the label).
            val replyGuid = replyTo?.externalId
            val guid = java.util.UUID.randomUUID().toString().uppercase()
            seenGuids.add(guid) // it'll echo back on our own devices; don't re-insert it
            val rowId = repo.recordMessage(
                MessageEntity(
                    conversationId = conversation.id,
                    transportId = id,
                    externalId = guid,
                    sender = "me",
                    body = body,
                    timestamp = System.currentTimeMillis(),
                    outgoing = true,
                    status = MessageStatus.PENDING,
                    replyToExternalId = replyGuid,
                    replyToPreview = replyTo?.let { it.body.ifBlank { null } },
                )
            )
            val participants = JSONArray().apply { put(conversation.address) }.toString()
            val res = json(ImessageNative.nativeSendText(participants, body, replyGuid ?: "", guid))
            if (res.optBoolean("ok")) {
                // Only PENDING→SENT: never downgrade a DELIVERED/READ that already raced in.
                repo.upgradeFromPending(rowId, MessageStatus.SENT)
                Result.success(Unit)
            } else {
                repo.setMessageStatus(rowId, MessageStatus.FAILED)
                Result.failure(Exception(res.optString("error", "send failed")))
            }
        }

    override val canStartConversations: Boolean get() = false

    private fun participantsJson(convo: ConversationEntity) =
        JSONArray().apply { put(convo.address) }.toString()

    override suspend fun sendReaction(
        conversation: ConversationEntity, message: MessageEntity, emoji: String, add: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val guid = message.externalId
            ?: return@withContext Result.failure(IllegalStateException("message not sent yet"))
        val res = json(ImessageNative.nativeSendReaction(
            participantsJson(conversation), guid, message.body, emoji, add,
        ))
        if (res.optBoolean("ok")) {
            // Show our own tapback immediately.
            repo.applyReaction(id, guid, "me", if (add) emoji else null, remove = !add)
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(res.optString("error", "reaction failed")))
        }
    }

    override suspend fun sendTyping(conversation: ConversationEntity, isTyping: Boolean) {
        withContext(Dispatchers.IO) {
            runCatching { ImessageNative.nativeSendTyping(participantsJson(conversation), isTyping) }
        }
    }

    override suspend fun markConversationRead(conversation: ConversationEntity) {
        withContext(Dispatchers.IO) {
            val guid = repo.latestIncomingExternalId(conversation.id) ?: return@withContext
            runCatching { ImessageNative.nativeSendRead(participantsJson(conversation), guid) }
        }
    }

    override suspend fun editMessage(
        conversation: ConversationEntity, message: MessageEntity, newBody: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val guid = message.externalId
            ?: return@withContext Result.failure(IllegalStateException("message not sent yet"))
        val res = json(ImessageNative.nativeSendEdit(participantsJson(conversation), guid, newBody))
        if (res.optBoolean("ok")) {
            repo.editMessageByExternal(id, guid, newBody)
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(res.optString("error", "edit failed")))
        }
    }

    override suspend fun unsendMessage(
        conversation: ConversationEntity, message: MessageEntity,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val guid = message.externalId
            ?: return@withContext Result.failure(IllegalStateException("message not sent yet"))
        val res = json(ImessageNative.nativeSendUnsend(participantsJson(conversation), guid))
        if (res.optBoolean("ok")) {
            repo.deleteMessageByExternal(id, guid)
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(res.optString("error", "unsend failed")))
        }
    }

    override suspend fun sendMedia(
        conversation: ConversationEntity,
        media: ByteArray,
        contentType: String?,
        fileName: String?,
        caption: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // The recipient's iPhone renders inline off the UTI + filename extension, so resolve a real
        // MIME (sniff bytes if the picker gave a generic one) and give it a proper name.
        val mime = com.sidephone.aviary.data.MediaSniff.resolveMime(contentType, media)
        val sendName = com.sidephone.aviary.data.MediaSniff.fileName(mime, fileName)
        val guid = java.util.UUID.randomUUID().toString().uppercase()
        seenGuids.add(guid) // echoes back on our own devices; don't re-insert
        // Optimistic: keep a local copy so the bubble renders immediately.
        val localPath = runCatching { mediaStore.save(media, mime) }.getOrNull()
        val rowId = repo.recordMessage(
            MessageEntity(
                conversationId = conversation.id,
                transportId = id,
                externalId = guid,
                sender = "me",
                body = caption,
                timestamp = System.currentTimeMillis(),
                outgoing = true,
                status = MessageStatus.PENDING,
                mediaPath = localPath,
                mediaType = mime,
            )
        )
        // The native uploader (MMCS) reads from a file path; hand it a temp file.
        val tmp = java.io.File.createTempFile("imsend", null, context.cacheDir)
            .apply { writeBytes(media) }
        try {
            val res = json(ImessageNative.nativeSendMedia(
                participantsJson(conversation), tmp.absolutePath, mime,
                sendName, caption, guid,
            ))
            if (res.optBoolean("ok")) {
                repo.upgradeFromPending(rowId, MessageStatus.SENT)
                Result.success(Unit)
            } else {
                repo.setMessageStatus(rowId, MessageStatus.FAILED)
                Result.failure(Exception(res.optString("error", "send failed")))
            }
        } finally {
            tmp.delete()
        }
    }

    override suspend fun resend(
        conversation: ConversationEntity, message: MessageEntity,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        repo.setMessageStatus(message.id, MessageStatus.PENDING)
        // Reuse the row's guid so a delivery receipt still matches it (it never delivered before).
        val guid = message.externalId
            ?: java.util.UUID.randomUUID().toString().uppercase().also { seenGuids.add(it) }
        val res = if (message.mediaPath != null) {
            val bytes = runCatching { java.io.File(message.mediaPath).readBytes() }.getOrNull()
            if (bytes == null) {
                repo.setMessageStatus(message.id, MessageStatus.FAILED)
                return@withContext Result.failure(IllegalStateException("attachment file missing"))
            }
            val mime = com.sidephone.aviary.data.MediaSniff.resolveMime(message.mediaType, bytes)
            val sendName = com.sidephone.aviary.data.MediaSniff.fileName(mime, null)
            val tmp = java.io.File.createTempFile("imsend", null, context.cacheDir).apply { writeBytes(bytes) }
            try {
                json(ImessageNative.nativeSendMedia(
                    participantsJson(conversation), tmp.absolutePath, mime, sendName, message.body, guid,
                ))
            } finally { tmp.delete() }
        } else {
            json(ImessageNative.nativeSendText(
                participantsJson(conversation), message.body, message.replyToExternalId ?: "", guid,
            ))
        }
        if (res.optBoolean("ok")) {
            repo.setSentResult(message.id, guid, MessageStatus.SENT)
            Result.success(Unit)
        } else {
            repo.setMessageStatus(message.id, MessageStatus.FAILED)
            Result.failure(Exception(res.optString("error", "send failed")))
        }
    }

    private fun onRegistered() {
        // Remember we completed setup, so future launches reuse the saved certificate instead of
        // re-activating (see start()).
        prefs.edit().putBoolean(KEY_REGISTERED, true).apply()
        _setup.value = SetupState.Ready
        _status.value = TransportStatus.Ready
        startPolling()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "iMessage handles: ${ImessageNative.nativeHandles()}")
            // One-time cleanup: collapse duplicate threads that resolve to the same contact
            // (e.g. a person reached by both email and phone) into a single contact-keyed thread.
            runCatching { mergeDuplicateContactThreads() }
                .onFailure { Log.w(TAG, "merge duplicates failed", it) }
            // Also collapse SMS↔iMessage duplicates for the same number into one thread.
            runCatching { repo.mergePhoneDuplicates() }
                .onFailure { Log.w(TAG, "merge phone duplicates failed", it) }
            // Repair rows that earlier builds tagged with the literal string "null" (which made
            // every text message show a bogus "Attachment" label).
            runCatching { repo.repairLiteralNulls(id) }
                .onFailure { Log.w(TAG, "repair literal nulls failed", it) }
            // Clean the leftover object-replacement glyph from old attachment captions.
            runCatching { repo.stripPlaceholderChars() }
                .onFailure { Log.w(TAG, "strip placeholder chars failed", it) }
            while (isActive) {
                try {
                    val res = json(ImessageNative.nativePoll(30_000))
                    if (res.optBoolean("ok")) {
                        if (!res.optBoolean("empty", true)) {
                            Log.i(TAG, "poll msg: $res")
                            handleIncoming(res)
                        }
                    } else {
                        Log.w(TAG, "poll error: ${res.optString("error")}")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "poll loop", t)
                }
            }
        }
    }

    private suspend fun handleIncoming(msg: JSONObject) {
        // Message edited or unsent by the other party.
        if (msg.optBoolean("edit", false)) {
            val target = msg.optString("target").ifBlank { return }
            repo.editMessageByExternal(id, target, msg.optString("text"))
            return
        }
        if (msg.optBoolean("unsend", false)) {
            val target = msg.optString("target").ifBlank { return }
            repo.deleteMessageByExternal(id, target)
            return
        }
        // Typing indicator from the other party.
        if (msg.has("typing")) {
            val chat = msg.optString("chat").ifBlank { return }
            val address = chat.substringBefore(";")
            val convoKey = if (!chat.contains(";"))
                (resolveContact(address).third?.let { "imc:$it" } ?: chat) else chat
            repo.conversationByExternal(id, convoKey)?.let { c ->
                (context.applicationContext as? com.sidephone.aviary.RelayApp)
                    ?.typing?.set(c.id, msg.optBoolean("typing"))
            }
            return
        }
        // Delivery/read receipt. For OUR sent messages this updates the bubble's status; a
        // "read" receipt whose guid is an INCOMING message means we read that thread on another
        // Apple device — so clear its notification + unread here too (read-sync across devices).
        val receipt = msg.optString("receipt")
        if (receipt.isNotBlank()) {
            val guid = msg.optString("guid").ifBlank { return }
            if (receipt == "read") {
                val m = repo.messageByExternal(id, guid)
                if (m != null && !m.outgoing) {
                    repo.markRead(m.conversationId)
                    com.sidephone.aviary.data.Notifier.cancel(context, m.conversationId)
                    return
                }
            }
            // Forward-only so a late Delivered never overwrites a Read (label was flickery before).
            repo.advanceStatusByExternal(
                id, guid,
                if (receipt == "read") MessageStatus.READ else MessageStatus.DELIVERED,
            )
            return
        }
        // Tapback/reaction on an existing message.
        if (msg.optBoolean("reaction", false)) {
            val target = msg.optString("target").ifBlank { return }
            val from = msg.optString("from").let { if (it == "me") "me" else cleanHandle(it) }
            repo.applyReaction(
                id, target, from,
                emoji = msg.optString("emoji").ifBlank { null },
                remove = !msg.optBoolean("enable", true),
            )
            return
        }
        // A message you send to yourself (e.g. from your Mac) has no "other" participant, so the
        // native chat id is blank — fall back to the address so it lands in a self thread instead
        // of being dropped.
        val address = msg.optString("address").ifBlank { msg.optString("chat") }
        val chat = msg.optString("chat").ifBlank { address }.ifBlank { return }
        val fromMe = msg.optBoolean("from_me", false)
        val sender = msg.optString("sender")
        val text = msg.optString("text", "")
        val guid = msg.optString("guid").ifBlank { null }
        if (guid != null && !seenGuids.add(guid)) return // already handled this session
        // Use the actual iMessage send time (Unix ms) so ordering is chronological.
        val timestamp = msg.optLong("timestamp", 0L).let { if (it > 0) it else System.currentTimeMillis() }

        val (name, photo, contactId) = resolveContact(address)
        val desiredTitle = name ?: cleanHandle(address)
        // Key 1:1 chats by the resolved contact so a person reached by both email and phone
        // stays a single thread; fall back to the raw chat id when there's no contact match.
        val convoKey = if (!chat.contains(";") && contactId != null) "imc:$contactId" else chat
        // Prefer this message's NATURAL iMessage thread if it already exists; only when it doesn't
        // yet do we fold into an existing SMS thread for the same number (the "SMS thread converts
        // to iMessage" case). This avoids diverting an active iMessage thread onto a stray SMS one.
        val natural = repo.conversationByExternal(id, convoKey)
        val folded = if (natural == null && !chat.contains(";") && address.startsWith("tel:"))
            repo.conversationForPhone(cleanHandle(address))?.takeIf { it.transportId != id } else null
        val convo = natural ?: folded ?: repo.upsertConversation(
            transportId = id,
            externalId = convoKey,
            address = address,
            title = desiredTitle,
            category = InboxCategory.PRIMARY,
        )
        // Reconcile the title (upgrades old "mailto:" titles and resolves the name), but don't
        // clobber a good SMS-thread title we've folded into.
        if (folded == null && convo.title != desiredTitle) repo.setConversationTitle(convo.id, desiredTitle)
        val avatarKey = convo.externalId
        if (photo != null && !avatarStore.has(avatarKey)) avatarStore.save(avatarKey, photo)
        val replyTo = msg.optStringOrNull("reply_to")
        val replyPreview = replyTo?.let { repo.bodyForExternal(id, it) }
        // Attachment downloaded by the native poll (MMCS), if any.
        val mediaPath = msg.optStringOrNull("media_path")
        val mediaMime = msg.optStringOrNull("media_mime")
        val rowId = repo.recordMessage(
            MessageEntity(
                conversationId = convo.id,
                transportId = id,
                externalId = guid,
                sender = if (fromMe) "me" else cleanHandle(sender),
                body = text,
                timestamp = timestamp,
                outgoing = fromMe,
                status = if (fromMe) MessageStatus.SENT else MessageStatus.RECEIVED,
                replyToExternalId = replyTo,
                replyToPreview = replyPreview,
                mediaPath = mediaPath,
                mediaType = mediaMime,
            )
        )
        if (rowId > 0 && !fromMe) {
            val isGroup = chat.contains(";")
            val mentioned = msg.optBoolean("mentioned", false)
            // Secondary groups notify only when you're @mentioned; secondary 1:1 threads still
            // notify for every message; primary always notifies.
            val muted = convo.muted ||
                (convo.category == InboxCategory.SECONDARY && isGroup && !mentioned)
            val senderName = if (isGroup) (resolveContact(sender).first ?: cleanHandle(sender)) else desiredTitle
            com.sidephone.aviary.data.Notifier.post(
                context, convo.id,
                sender = senderName,
                body = text.ifBlank { "📎 Attachment" },
                avatarPath = avatarStore.path(avatarKey),
                timestamp = timestamp,
                isGroup = isGroup,
                groupTitle = convo.title,
                muted = muted,
            )
        }
    }

    /** Strip the iMessage handle scheme, e.g. "mailto:a@b.com" -> "a@b.com", "tel:+1..." -> "+1...". */
    private fun cleanHandle(h: String): String =
        h.removePrefix("mailto:").removePrefix("tel:")

    /** Normalize an email for matching: lowercase; for Gmail, drop dots and +tags in the local part. */
    private fun normalizeEmail(email: String): String {
        val e = email.trim().lowercase()
        val at = e.indexOf('@')
        if (at <= 0) return e
        var local = e.substring(0, at)
        val domain = e.substring(at + 1)
        local = local.substringBefore('+')
        if (domain == "gmail.com" || domain == "googlemail.com") local = local.replace(".", "")
        return "$local@$domain"
    }

    /**
     * Resolve an iMessage handle against the phone's contacts (by email or phone number),
     * returning (displayName, photoJpegBytes). Either may be null.
     */
    private fun resolveContact(handle: String): Triple<String?, ByteArray?, Long?> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return Triple(null, null, null)
        val clean = cleanHandle(handle)
        val cr = context.contentResolver
        return runCatching {
            var name: String? = null
            var contactId: Long = -1

            if (clean.contains("@")) {
                // Fast path: the exact-match filter URI.
                val uri = android.net.Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,
                    android.net.Uri.encode(clean),
                )
                cr.query(
                    uri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) { name = c.getString(0); contactId = c.getLong(1) } }
                // Fallback: scan the email table and compare normalized (case + Gmail dots/+tags).
                if (name == null) {
                    val target = normalizeEmail(clean)
                    cr.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Email.ADDRESS,
                            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                        ),
                        null, null, null,
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val addr = c.getString(0) ?: continue
                            if (normalizeEmail(addr) == target) {
                                name = c.getString(1); contactId = c.getLong(2); break
                            }
                        }
                    }
                }
            } else {
                val uri = android.net.Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(clean),
                )
                cr.query(
                    uri,
                    arrayOf(
                        ContactsContract.PhoneLookup.DISPLAY_NAME,
                        ContactsContract.PhoneLookup.CONTACT_ID,
                    ),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) { name = c.getString(0); contactId = c.getLong(1) } }
            }

            // The email fallback scan doesn't join the contact's name, so fetch it directly.
            if (name.isNullOrBlank() && contactId >= 0) {
                val contactUri = android.content.ContentUris.withAppendedId(
                    ContactsContract.Contacts.CONTENT_URI, contactId
                )
                cr.query(
                    contactUri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null
                )?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
            }

            val photo = if (contactId >= 0) {
                val contactUri = android.content.ContentUris.withAppendedId(
                    android.provider.ContactsContract.Contacts.CONTENT_URI, contactId
                )
                android.provider.ContactsContract.Contacts
                    .openContactPhotoInputStream(cr, contactUri, true)
                    ?.use { it.readBytes() }
            } else null
            Triple(name, photo, if (contactId >= 0) contactId else null)
        }.getOrDefault(Triple(null, null, null))
    }

    /**
     * Stable conversation key for a 1:1 iMessage chat: the resolved contact when the handle is
     * in the address book (so a person reached by both email and phone folds into ONE thread),
     * otherwise the raw handle. Group chats keep their participant-list key.
     */
    private fun conversationKey(chat: String, address: String): String {
        if (chat.contains(";")) return chat // group chat — leave as-is
        val contactId = resolveContact(address).third ?: return chat
        return "imc:$contactId"
    }

    /**
     * Fold existing duplicate iMessage threads for the same contact into one contact-keyed
     * thread. Handles the case where a person messaged from two handles (email + phone) before
     * contact-based keying existed. Idempotent: safe to run on every launch.
     */
    private suspend fun mergeDuplicateContactThreads() {
        val convos = repo.conversationsForTransport(id)
        val byKey = LinkedHashMap<String, MutableList<com.sidephone.aviary.data.ConversationEntity>>()
        for (c in convos) {
            val key = if (c.externalId.startsWith("imc:")) c.externalId
            else conversationKey(c.externalId, c.address)
            byKey.getOrPut(key) { mutableListOf() }.add(c)
        }
        for ((key, group) in byKey) {
            if (group.size == 1 && group[0].externalId == key) continue // nothing to do
            val target = group.firstOrNull { it.externalId == key }
                ?: group.maxByOrNull { it.lastMessageAt } ?: continue
            for (c in group) if (c.id != target.id) {
                Log.i(TAG, "merging convo ${c.id} ('${c.externalId}') into ${target.id} ('$key')")
                repo.mergeConversations(target.id, c.id)
            }
            if (target.externalId != key) repo.setConversationExternalId(target.id, key)
        }
    }

    // ---- native init helper ------------------------------------------------

    private sealed class InitResult {
        data object Registered : InitResult()
        data object NotRegistered : InitResult()
        data class Error(val message: String) : InitResult()
    }

    private fun ensureInit(macConfig: String): InitResult {
        return try {
            val libDir = ImessageNative.prepareLibDir(context)
            val configDir = ImessageNative.configDir(context)
            val res = json(ImessageNative.nativeInit(macConfig, libDir, configDir))
            initialized = res.optBoolean("ok")
            if (initialized) {
                try { Log.i(TAG, "nativeTestAnisette => ${ImessageNative.nativeTestAnisette()}") }
                catch (t: Throwable) { Log.e(TAG, "anisette test", t) }
            }
            when {
                !res.optBoolean("ok") -> InitResult.Error(res.optString("error"))
                res.optBoolean("registered") -> InitResult.Registered
                else -> InitResult.NotRegistered
            }
        } catch (t: Throwable) {
            Log.e(TAG, "nativeInit", t)
            InitResult.Error(t.message ?: "init failed")
        }
    }

    private fun fail(message: String): TransportStatus.Error {
        val s = TransportStatus.Error(message)
        _status.value = s
        _setup.value = SetupState.Failed(message)
        return s
    }

    private fun json(s: String): JSONObject = try {
        JSONObject(s)
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", "bad native response: $s")
    }

    /**
     * optString returns the literal string "null" for a JSON null value (not "" and not null),
     * so use this to get a real null instead — otherwise every text message picks up a bogus
     * media_path/reply_to of "null".
     */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    companion object {
        const val ID = "imessage"
        private const val TAG = "IMessageTransport"
        private const val KEY_CONFIG = "mac_config"
        private const val KEY_CLEANED = "cleaned_keys_v3"
        private const val KEY_REGISTERED = "imessage_registered"
    }
}
