package com.sidephone.aviary.transport.instagram

import android.content.Context
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Instagram DMs over the private mobile API. Login is username/password (+2FA); receiving is
 * inbox polling for now (Facebook-MQTT realtime is a later milestone). Meta offers no official
 * personal-DM API, so this is an unofficial client — there is a real account-ban risk.
 */
class InstagramTransport(
    private val context: Context,
    private val repo: UnifiedRepository,
    private val scope: CoroutineScope,
    private val avatarStore: com.sidephone.aviary.data.AvatarStore,
    private val mediaStore: com.sidephone.aviary.data.MediaStore,
) : MessageTransport {

    override val id = ID
    override val protocol = Protocol.INSTAGRAM

    private val device = InstagramDevice(context)
    private val account = InstagramAccount(context)
    private val api = InstagramApi(device, account)

    private val _status = MutableStateFlow<TransportStatus>(TransportStatus.NeedsSetup("Log in to Instagram"))
    override val status: StateFlow<TransportStatus> = _status

    /** Drives the login UI. */
    sealed class SetupState {
        data object LoggedOut : SetupState()
        data class AwaitingTwoFactor(val identifier: String, val username: String) : SetupState()
        data class Challenge(val note: String) : SetupState()
        data object Ready : SetupState()
    }
    private val _setup = MutableStateFlow<SetupState>(SetupState.LoggedOut)
    val setup: StateFlow<SetupState> = _setup

    private var pollJob: Job? = null

    override suspend fun start() {
        if (account.isLoggedIn) {
            _setup.value = SetupState.Ready
            _status.value = TransportStatus.Ready
            startPolling()
        } else {
            _setup.value = SetupState.LoggedOut
            _status.value = TransportStatus.NeedsSetup("Log in to Instagram")
        }
    }

    suspend fun login(username: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        _status.value = TransportStatus.Linking("Logging in…")
        when (val r = api.login(username, password)) {
            is InstagramApi.LoginResult.Success -> { onLoggedIn(); Result.success(Unit) }
            is InstagramApi.LoginResult.TwoFactor -> {
                _setup.value = SetupState.AwaitingTwoFactor(r.identifier, r.username)
                _status.value = TransportStatus.Linking("Enter your 2FA code")
                Result.failure(TwoFactorNeeded)
            }
            is InstagramApi.LoginResult.Challenge -> {
                _setup.value = SetupState.Challenge("Instagram wants to verify this login in its app, then try again.")
                _status.value = TransportStatus.NeedsSetup("Verify in the Instagram app")
                Result.failure(IllegalStateException("challenge_required"))
            }
            is InstagramApi.LoginResult.Error -> {
                _status.value = TransportStatus.NeedsSetup(r.message)
                Result.failure(IllegalStateException(r.message))
            }
        }
    }

    suspend fun submitTwoFactor(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val s = _setup.value as? SetupState.AwaitingTwoFactor
            ?: return@withContext Result.failure(IllegalStateException("not awaiting 2FA"))
        when (val r = api.submitTwoFactor(code, s.identifier, s.username)) {
            is InstagramApi.LoginResult.Success -> { onLoggedIn(); Result.success(Unit) }
            is InstagramApi.LoginResult.Error -> Result.failure(IllegalStateException(r.message))
            else -> Result.failure(IllegalStateException("2FA failed"))
        }
    }

    private fun onLoggedIn() {
        _setup.value = SetupState.Ready
        _status.value = TransportStatus.Ready
        startPolling()
    }

    fun logout() {
        pollJob?.cancel()
        account.clear()
        _setup.value = SetupState.LoggedOut
        _status.value = TransportStatus.NeedsSetup("Log in to Instagram")
    }

    // item_id -> client_context, captured while polling; used to thread outgoing replies.
    private val replyContexts = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ---- receiving (inbox polling) ----------------------------------------

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        val app = context.applicationContext as? com.sidephone.aviary.RelayApp
        pollJob = scope.launch(Dispatchers.IO) {
            // One-time cleanup of the pre-reconciliation duplicates (optimistic rows that had no
            // server id, so the poll inserted a second copy). Safe: only deletes twinned rows.
            runCatching { repo.deleteOrphanOptimisticDuplicates(ID) }
                .onFailure { Log.w(TAG, "dup cleanup", it) }
            while (isActive) {
                runCatching { syncInbox() }.onFailure { Log.w(TAG, "inbox sync", it) }
                // Poll fast while open; a 30s background poll keeps DM latency usable without much
                // battery cost (one small HTTPS GET). Realtime would need Instagram's MQTT stack.
                delay(if (app?.isForeground == true) 12_000 else 30_000)
            }
        }
    }

    private suspend fun syncInbox() {
        val inbox = api.fetchInbox()?.optJSONObject("inbox") ?: return
        val threads = inbox.optJSONArray("threads") ?: return
        val me = account.userId
        for (i in 0 until threads.length()) {
            val thread = threads.getJSONObject(i)
            val threadId = thread.optString("thread_id")
            if (threadId.isBlank()) continue
            // The other participant(s) name the conversation.
            val users = thread.optJSONArray("users")
            val other = (0 until (users?.length() ?: 0)).map { users!!.getJSONObject(it) }
                .firstOrNull { it.get("pk").toString() != me }
            val title = thread.optString("thread_title").ifBlank {
                other?.optString("full_name").orEmptyIf() ?: other?.optString("username") ?: "Instagram"
            }
            val address = other?.optString("username") ?: threadId

            val convo = repo.upsertConversation(
                transportId = ID, externalId = threadId, address = address,
                title = title, category = InboxCategory.PRIMARY,
            )
            // Profile picture: download once per thread (keyed by thread id, like the inbox reads).
            if (other != null && !avatarStore.has(threadId)) {
                other.optString("profile_pic_url").ifBlank { null }
                    ?.let { url -> api.download(url)?.let { avatarStore.save(threadId, it) } }
            }

            val items = thread.optJSONArray("items") ?: continue
            // Items come newest-first; insert oldest-first so ordering is chronological.
            for (k in items.length() - 1 downTo 0) {
                recordItem(convo, items.getJSONObject(k), me)
            }

            // Read-sync across devices: if THIS account has already seen the newest item (e.g. we
            // opened the DM on our phone), clear the local unread dot + notification here too.
            val lastSeenAt = thread.optJSONObject("last_seen_at")
            val lastItemTs = thread.optJSONObject("last_permanent_item")?.optLong("timestamp") ?: 0L
            val mySeenTs = me?.let {
                lastSeenAt?.optJSONObject(it)?.optString("timestamp")?.toLongOrNull()
            } ?: 0L
            if (lastItemTs > 0 && mySeenTs >= lastItemTs) {
                repo.markRead(convo.id)
                com.sidephone.aviary.data.Notifier.cancel(context, convo.id)
            }
            // Seen receipts: the OTHER participant's last_seen_at marks our outgoing messages
            // (timestamps ≤ it) as Seen, so the thread shows "Sent" → "Seen" like Instagram.
            val otherPk = other?.get("pk")?.toString()
            val otherSeenMicros = otherPk?.let {
                lastSeenAt?.optJSONObject(it)?.optString("timestamp")?.toLongOrNull()
            } ?: 0L
            if (otherSeenMicros > 0) {
                repo.markOutgoingReadUpTo(convo.id, otherSeenMicros / 1000)
            }
        }
    }

    private suspend fun recordItem(convo: ConversationEntity, item: JSONObject, me: String?) {
        val itemId = item.optString("item_id").ifBlank { return }
        // Cache the item's client_context so a later reply to it can thread server-side.
        item.optString("client_context").ifBlank { null }?.let { replyContexts[itemId] = it }
        // Reactions live on the item and can change on already-stored messages, so reconcile them
        // every poll (before the dedup return below).
        reconcileReactions(itemId, item, me)
        // Skip items we've already stored, so we don't re-download media every poll.
        if (repo.messageByExternal(ID, itemId) != null) return
        val fromMe = item.get("user_id").toString() == me
        val r = render(item) ?: return
        val tsMicros = item.optLong("timestamp", 0L)
        val timestamp = if (tsMicros > 0) tsMicros / 1000 else System.currentTimeMillis()

        // Instagram swipe-to-reply carries the original message inline as replied_to_message.
        val repliedTo = item.optJSONObject("replied_to_message")
        val replyToExternalId = repliedTo?.optString("item_id")?.ifBlank { null }
        val replyToPreview = repliedTo?.let { rt ->
            rt.optString("text").ifBlank { null } ?: render(rt)?.body?.ifBlank { "📷 Media" }
        }?.take(90)
        if (repliedTo == null) {
            val replyKeys = item.keys().asSequence().filter { it.contains("repl", true) }.toList()
            if (replyKeys.isNotEmpty()) Log.i(TAG, "reply-ish item keys=$replyKeys")
        }

        // Reels/videos stream from the CDN url (no big download); we only pull the small thumbnail
        // as a poster. Photos download as a still. mediaUrl carries the streamable video.
        var mediaPath: String? = null   // local poster/still
        var mediaType: String? = null
        var mediaUrl: String? = null    // remote streamable video
        r.imageUrl?.let { url -> api.download(url)?.let { mediaPath = mediaStore.save(it, "image/jpeg") } }
        if (r.videoUrl != null) {
            mediaType = "video/mp4"; mediaUrl = r.videoUrl
        } else if (mediaPath != null) {
            mediaType = "image/jpeg"
        }

        val rowId = repo.recordMessage(
            MessageEntity(
                conversationId = convo.id, transportId = ID, externalId = itemId,
                sender = if (fromMe) "me" else convo.address,
                body = r.body, timestamp = timestamp, outgoing = fromMe,
                status = if (fromMe) MessageStatus.SENT else MessageStatus.RECEIVED,
                mediaPath = mediaPath, mediaType = mediaType, mediaUrl = mediaUrl,
                replyToExternalId = replyToExternalId, replyToPreview = replyToPreview,
            )
        )
        if (rowId > 0 && !fromMe) {
            com.sidephone.aviary.data.Notifier.post(
                context, convo.id, sender = convo.title,
                body = r.body.ifBlank { if (mediaPath != null) "📷 Photo" else "New message" },
                avatarPath = avatarStore.path(convo.externalId),
                timestamp = timestamp,
                muted = convo.muted || convo.category == InboxCategory.SECONDARY,
            )
        }
    }

    /**
     * Mirror the reactions Instagram reports on [item] onto our stored message. Handles emoji
     * reactions and the classic ❤️ "like"; our own reactions map to "me" so they don't duplicate
     * the optimistic one. Replacing the whole map also picks up removals.
     */
    private suspend fun reconcileReactions(itemId: String, item: JSONObject, me: String?) {
        if (!item.has("reactions")) return // no reaction info in this item — leave as-is
        val reactions = item.optJSONObject("reactions")
        val map = JSONObject()
        reactions?.optJSONArray("emojis")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val emoji = r.optString("emoji")
                if (emoji.isBlank()) continue
                val sid = r.opt("sender_id")?.toString() ?: continue
                map.put(if (sid == me) "me" else sid, emoji)
            }
        }
        reactions?.optJSONArray("likes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val sid = r.opt("sender_id")?.toString() ?: continue
                val key = if (sid == me) "me" else sid
                if (!map.has(key)) map.put(key, "❤️")
            }
        }
        repo.setReactionsByExternal(ID, itemId, if (map.length() == 0) null else map.toString())
    }

    private data class Rendered(val body: String, val imageUrl: String? = null, val videoUrl: String? = null)

    /** Map an inbox item to text + an optional thumbnail/video URL, or null to skip system items. */
    private fun render(item: JSONObject): Rendered? = when (item.optString("item_type")) {
        "text" -> Rendered(item.optString("text"))
        "link" -> Rendered(item.optJSONObject("link")?.optString("text").orEmptyIf() ?: "🔗 Link")
        "like" -> Rendered("❤️")
        "media" -> item.optJSONObject("media").let { Rendered("", imageOf(it), videoOf(it)) }
        "animated_media" -> Rendered(
            "GIF",
            item.optJSONObject("animated_media")?.optJSONObject("images")
                ?.optJSONObject("fixed_height")?.optString("url").orEmptyIf(),
        )
        "voice_media" -> Rendered("🎤 Voice message")
        "media_share" -> shared(item.optJSONObject("media_share"), "📷 Shared a post")
        "clip" -> shared(item.optJSONObject("clip")?.optJSONObject("clip"), "🎬 Reel")
        "story_share" -> shared(item.optJSONObject("story_share")?.optJSONObject("media"), "📖 Shared a story")
        "reel_share" -> {
            val rs = item.optJSONObject("reel_share")
            val note = rs?.optString("text").orEmptyIf() ?: "Replied to a story"
            Rendered("↩️ $note", imageOf(rs?.optJSONObject("media")), videoOf(rs?.optJSONObject("media")))
        }
        "xma_media_share", "xma_reel_share", "xma_story_share", "clip_share" -> xma(item)
        else -> null
    }

    /** Best thumbnail URL from a media node (photo, reel, shared post, carousel). */
    private fun imageOf(node: JSONObject?): String? {
        node ?: return null
        node.optJSONObject("image_versions2")?.optJSONArray("candidates")?.let {
            if (it.length() > 0) return it.getJSONObject(0).optString("url").orEmptyIf()
        }
        node.optJSONArray("carousel_media")?.let { if (it.length() > 0) return imageOf(it.getJSONObject(0)) }
        return null
    }

    /** Playable video URL from a media node (reels/videos carry video_versions), if any. */
    private fun videoOf(node: JSONObject?): String? {
        node ?: return null
        node.optJSONArray("video_versions")?.let {
            if (it.length() > 0) return it.getJSONObject(0).optString("url").orEmptyIf()
        }
        node.optJSONArray("carousel_media")?.let { if (it.length() > 0) return videoOf(it.getJSONObject(0)) }
        return null
    }

    private fun shared(media: JSONObject?, label: String): Rendered {
        val caption = media?.optJSONObject("caption")?.optString("text").orEmptyIf()
        val owner = media?.optJSONObject("user")?.optString("username").orEmptyIf()
        val body = buildString {
            append(label)
            if (owner != null) append(" · @$owner")
            if (caption != null) append("\n“").append(caption.take(140)).append("”")
        }
        return Rendered(body, imageOf(media), videoOf(media))
    }

    /** Newer "xma_*" shares wrap the preview differently. */
    private fun xma(item: JSONObject): Rendered {
        val arr = item.optJSONArray(item.optString("item_type")) ?: item.optJSONArray("xma_media_share")
        val x = if (arr != null && arr.length() > 0) arr.getJSONObject(0) else null
        val url = x?.optJSONObject("preview_url_info")?.optString("url").orEmptyIf() ?: imageOf(x)
        val title = x?.optString("header_title_text").orEmptyIf()
            ?: x?.optString("title_text").orEmptyIf() ?: "Shared a reel"
        return Rendered("🎬 $title", url)
    }

    // ---- sending -----------------------------------------------------------

    override suspend fun sendText(
        conversation: ConversationEntity, body: String, replyTo: MessageEntity?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val rowId = repo.recordMessage(
            MessageEntity(
                conversationId = conversation.id, transportId = ID, externalId = null,
                sender = "me", body = body, timestamp = System.currentTimeMillis(),
                outgoing = true, status = MessageStatus.PENDING,
                replyToExternalId = replyTo?.externalId,
                replyToPreview = replyTo?.body?.ifBlank { null }?.take(90),
            )
        )
        // A brand-new conversation (started from the compose screen) has no thread yet — its
        // externalId is a "user:<pk>" marker. The first send creates the thread via recipient_users;
        // afterwards we swap in the real thread_id so later sends/polling use it.
        if (conversation.externalId.startsWith("user:")) {
            val userId = conversation.externalId.removePrefix("user:")
            val res = api.sendTextToUser(userId, body)
            // Broadcast usually returns the new thread_id; if it didn't, resolve it so later
            // sends + inbox polling attach to the real thread instead of the "user:" placeholder.
            val threadId = res.threadId ?: (if (res.itemId != null) api.threadIdForUser(userId) else null)
            threadId?.let { repo.setConversationExternalId(conversation.id, it) }
            repo.setSentResult(rowId, res.itemId, if (res.itemId != null) MessageStatus.SENT else MessageStatus.FAILED)
            return@withContext if (res.itemId != null) Result.success(Unit)
            else Result.failure(IllegalStateException("Instagram send failed"))
        }
        // For a reply, Instagram needs the quoted message's client_context too; use the cached
        // one (from polling) or look it up live so the reply threads on the other person's app.
        val replyItemId = replyTo?.externalId
        val replyContext = replyItemId?.let { replyContexts[it] ?: api.clientContextFor(it) }
        // Stamp the server item_id onto our row so the inbox poll recognizes this as the
        // same message (getByExternal) instead of inserting a second copy.
        val itemId = api.sendText(conversation.externalId, body, replyItemId, replyContext)
        repo.setSentResult(rowId, itemId, if (itemId != null) MessageStatus.SENT else MessageStatus.FAILED)
        if (itemId != null) Result.success(Unit)
        else Result.failure(IllegalStateException("Instagram send failed"))
    }

    override val canStartConversations: Boolean get() = account.isLoggedIn

    suspend fun searchUsers(query: String): List<InstagramApi.IgUser> = withContext(Dispatchers.IO) {
        if (!account.isLoggedIn) emptyList()
        else runCatching { api.searchUsers(query) }.getOrDefault(emptyList())
    }

    /** Start a chat with a user picked from search (we already have their id — no re-resolve). */
    suspend fun startConversationWithUser(userId: String, username: String): Result<Long> = withContext(Dispatchers.IO) {
        val externalId = api.threadIdForUser(userId) ?: "user:$userId"
        val convo = repo.upsertConversation(
            transportId = ID, externalId = externalId,
            address = username, title = username, category = InboxCategory.PRIMARY,
        )
        Result.success(convo.id)
    }

    override suspend fun startConversation(address: String): Result<Long> = withContext(Dispatchers.IO) {
        val userId = api.resolveUserId(address)
            ?: return@withContext Result.failure(IllegalStateException("No Instagram user “$address”"))
        // Prefer an existing thread id; otherwise mark it pending so the first send creates it.
        val externalId = api.threadIdForUser(userId) ?: "user:$userId"
        val convo = repo.upsertConversation(
            transportId = ID, externalId = externalId,
            address = address.trim().removePrefix("@"),
            title = address.trim().removePrefix("@"),
            category = InboxCategory.PRIMARY,
        )
        Result.success(convo.id)
    }

    override suspend fun sendMedia(
        conversation: ConversationEntity,
        media: ByteArray,
        contentType: String?,
        fileName: String?,
        caption: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val mime = contentType ?: "image/jpeg"
        if (!mime.startsWith("image/"))
            return@withContext Result.failure(UnsupportedOperationException("Instagram only supports sending photos for now"))
        // Optimistic local echo (keep original bytes for the bubble).
        val localPath = runCatching { mediaStore.save(media, mime) }.getOrNull()
        val rowId = repo.recordMessage(
            MessageEntity(
                conversationId = conversation.id, transportId = ID, externalId = null,
                sender = "me", body = caption, timestamp = System.currentTimeMillis(),
                outgoing = true, status = MessageStatus.PENDING,
                mediaPath = localPath, mediaType = mime,
            )
        )
        val itemId = api.sendPhoto(conversation.externalId, toJpeg(media))
        repo.setSentResult(rowId, itemId, if (itemId != null) MessageStatus.SENT else MessageStatus.FAILED)
        // Instagram photos carry no caption; send any caption as a follow-up text message.
        if (itemId != null && caption.isNotBlank()) api.sendText(conversation.externalId, caption)
        if (itemId != null) Result.success(Unit)
        else Result.failure(IllegalStateException("Instagram photo send failed"))
    }

    override suspend fun sendReaction(
        conversation: ConversationEntity, message: MessageEntity, emoji: String, add: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val itemId = message.externalId
            ?: return@withContext Result.failure(IllegalStateException("message not sent yet"))
        val ok = api.reactToMessage(conversation.externalId, itemId, emoji, add)
        if (ok) {
            repo.applyReaction(ID, itemId, "me", if (add) emoji else null, remove = !add)
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Instagram reaction failed"))
        }
    }

    override suspend fun resend(
        conversation: ConversationEntity, message: MessageEntity,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        repo.setMessageStatus(message.id, MessageStatus.PENDING)
        val itemId = if (message.mediaPath != null) {
            val bytes = runCatching { java.io.File(message.mediaPath).readBytes() }.getOrNull()
            if (bytes == null) {
                repo.setMessageStatus(message.id, MessageStatus.FAILED)
                return@withContext Result.failure(IllegalStateException("attachment file missing"))
            }
            api.sendPhoto(conversation.externalId, toJpeg(bytes))
        } else {
            val replyItemId = message.replyToExternalId
            val replyContext = replyItemId?.let { replyContexts[it] ?: api.clientContextFor(it) }
            api.sendText(conversation.externalId, message.body, replyItemId, replyContext)
        }
        repo.setSentResult(message.id, itemId, if (itemId != null) MessageStatus.SENT else MessageStatus.FAILED)
        if (itemId != null) Result.success(Unit)
        else Result.failure(IllegalStateException("Instagram send failed"))
    }

    /** Re-encode arbitrary image bytes to JPEG (Instagram's photo upload expects JPEG). */
    private fun toJpeg(bytes: ByteArray): ByteArray {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    private fun String?.orEmptyIf(): String? = this?.takeIf { it.isNotBlank() }

    companion object {
        const val ID = "instagram"
        private const val TAG = "InstagramTransport"
        val TwoFactorNeeded = IllegalStateException("two_factor_required")
    }
}
