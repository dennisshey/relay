package com.sidephone.aviary.data

import kotlinx.coroutines.flow.Flow

/**
 * The single store behind the unified inbox. Transports feed it; the UI reads it.
 */
class UnifiedRepository(private val db: AviaryDatabase) {

    fun conversations(): Flow<List<ConversationEntity>> = db.conversations().all()
    fun conversation(id: Long): Flow<ConversationEntity?> = db.conversations().byId(id)
    fun messages(conversationId: Long): Flow<List<MessageEntity>> =
        db.messages().forConversation(conversationId)

    /** The latest [limit] messages (oldest-first) — a growing window for thread pagination. */
    fun messagesPaged(conversationId: Long, limit: Int): Flow<List<MessageEntity>> =
        db.messages().forConversationPaged(conversationId, limit)

    suspend fun getConversation(id: Long): ConversationEntity? = db.conversations().get(id)

    /**
     * An existing (non-group, non-hidden) conversation whose address is the same phone number,
     * matched on the last 10 digits. Used to fold an incoming iMessage into the person's existing
     * SMS thread instead of spawning a duplicate — so the thread converts to iMessage in place.
     */
    suspend fun conversationForPhone(number: String): ConversationEntity? {
        val key = number.filter { it.isDigit() }.takeLast(10)
        if (key.length < 7) return null
        return db.conversations().allList().firstOrNull {
            !it.externalId.startsWith("group:") &&
                it.address.filter { c -> c.isDigit() }.takeLast(10) == key
        }
    }

    suspend fun markRead(conversationId: Long) = db.conversations().markRead(conversationId)

    suspend fun markUnread(conversationId: Long) = db.conversations().markUnread(conversationId)

    /**
     * Delete a conversation (local only): drop its messages and TOMBSTONE it (hidden) rather than
     * removing the row, so a re-delivered/re-synced OLD message can't resurrect it. A genuinely new
     * message (timestamp after the deletion) un-hides it, matching how Messages/Signal behave.
     */
    suspend fun deleteConversation(conversationId: Long) {
        db.messages().deleteForConversation(conversationId)
        db.conversations().hide(conversationId, System.currentTimeMillis())
    }

    /** Delete a single message (local only). */
    suspend fun deleteMessage(messageId: Long) = db.messages().deleteById(messageId)

    suspend fun setCategory(conversationId: Long, category: InboxCategory) =
        db.conversations().setCategory(conversationId, category)

    /** Find or create the conversation row for a transport-scoped external id. */
    /** Remove all conversations + messages for a transport (used to reset a source). */
    suspend fun clearTransport(transportId: String) {
        db.messages().deleteTransport(transportId)
        db.conversations().deleteTransport(transportId)
    }

    suspend fun setConversationTitle(id: Long, title: String) =
        db.conversations().setTitle(id, title)

    suspend fun conversationByExternal(transportId: String, externalId: String): ConversationEntity? =
        db.conversations().byExternal(transportId, externalId)

    suspend fun conversationsForTransport(transportId: String): List<ConversationEntity> =
        db.conversations().forTransport(transportId)

    suspend fun setConversationExternalId(id: Long, externalId: String) =
        db.conversations().setExternalId(id, externalId)

    suspend fun messageByExternal(transportId: String, externalId: String): MessageEntity? =
        db.messages().getByExternal(transportId, externalId)

    suspend fun latestIncomingExternalId(conversationId: Long): String? =
        db.messages().latestIncomingExternalId(conversationId)

    /** Upgrade a still-PENDING message to [status] (usually SENT), without clobbering a
     *  DELIVERED/READ that raced in before the send call returned. */
    suspend fun upgradeFromPending(messageId: Long, status: MessageStatus) {
        if (db.messages().statusOf(messageId) == MessageStatus.PENDING)
            db.messages().setStatus(messageId, status)
    }

    suspend fun editMessageByExternal(transportId: String, externalId: String, body: String) =
        db.messages().updateBodyByExternal(transportId, externalId, body)

    suspend fun deleteMessageByExternal(transportId: String, externalId: String) =
        db.messages().deleteByExternal(transportId, externalId)

    /**
     * Fold [sourceId] into [targetId]: move its messages over, carry across the later
     * activity/preview/unread, and delete the source row. Used to collapse duplicate threads
     * that resolve to the same contact (e.g. a person reached by both email and phone).
     */
    suspend fun mergeConversations(targetId: Long, sourceId: Long) {
        if (targetId == sourceId) return
        val target = db.conversations().get(targetId) ?: return
        val source = db.conversations().get(sourceId) ?: return
        db.messages().reassignConversation(sourceId, targetId)
        if (source.lastMessageAt > target.lastMessageAt) {
            db.conversations().touch(targetId, source.lastMessageAt, source.lastPreview, source.unreadCount)
        } else if (source.unreadCount > 0) {
            db.conversations().touch(targetId, target.lastMessageAt, target.lastPreview, source.unreadCount)
        }
        db.conversations().deleteById(sourceId)
    }

    /**
     * Collapse duplicate 1:1 threads for the same phone number across transports (e.g. an SMS
     * thread and an iMessage thread for one person) into a single thread — no message is lost.
     * The SMS thread is preferred as the survivor because both SMS and folded-in iMessages route
     * back to it, keeping the conversation unified going forward.
     */
    suspend fun mergePhoneDuplicates() {
        val convos = db.conversations().allList().filter { !it.externalId.startsWith("group:") }
        val byNumber = LinkedHashMap<String, MutableList<ConversationEntity>>()
        for (c in convos) {
            val key = c.address.filter { it.isDigit() }.takeLast(10)
            if (key.length < 10) continue // only merge real phone numbers
            byNumber.getOrPut(key) { mutableListOf() }.add(c)
        }
        for ((_, group) in byNumber) {
            if (group.size < 2) continue
            val target = group.filter { it.transportId == "sms" }.maxByOrNull { it.lastMessageAt }
                ?: group.maxByOrNull { it.lastMessageAt } ?: continue
            for (c in group) if (c.id != target.id) mergeConversations(target.id, c.id)
        }
    }

    suspend fun upsertConversation(
        transportId: String,
        externalId: String,
        address: String,
        title: String,
        category: InboxCategory,
    ): ConversationEntity {
        // Title is set on creation only; later renames go through setConversationTitle
        // so a resolved contact/group name isn't clobbered by the next message's placeholder.
        db.conversations().byExternal(transportId, externalId)?.let { return it }
        val id = db.conversations().insert(
            ConversationEntity(
                transportId = transportId,
                externalId = externalId,
                address = address,
                title = title,
                category = category,
            )
        )
        // Insert may IGNORE on a concurrent race; re-read either way.
        return if (id > 0) db.conversations().get(id)!!
        else db.conversations().byExternal(transportId, externalId)!!
    }

    /**
     * Record a message. Deduplicates on (transportId, externalId) so transport
     * re-syncs are safe. Bumps the conversation preview/unread counters.
     */
    suspend fun recordMessage(m: MessageEntity, countUnread: Boolean = !m.outgoing): Long {
        val rowId = db.messages().insert(m)
        if (rowId <= 0) return rowId // duplicate from a re-sync
        val convo = db.conversations().get(m.conversationId) ?: return rowId
        // A deleted (hidden) thread stays hidden for old re-deliveries, but a genuinely new
        // message (after the deletion) brings it back.
        if (convo.hidden && m.timestamp > convo.hiddenAt) db.conversations().unhide(convo.id)
        val preview = m.body.ifBlank { mediaLabel(m.mediaType) }
        if (m.timestamp >= convo.lastMessageAt) {
            db.conversations().touch(
                id = m.conversationId,
                at = m.timestamp,
                preview = preview.take(120),
                unreadDelta = if (countUnread) 1 else 0,
            )
            // The inbox dot follows the newest message's transport (e.g. an SMS thread whose last
            // message went out over iMessage shows blue).
            if (m.transportId != convo.lastTransportId) {
                db.conversations().setLastTransport(m.conversationId, m.transportId)
            }
        } else if (countUnread) {
            db.conversations().touch(convo.id, convo.lastMessageAt, convo.lastPreview, 1)
        }
        return rowId
    }

    suspend fun setSentResult(id: Long, externalId: String?, status: MessageStatus) =
        db.messages().setSentResult(id, externalId, status)

    suspend fun deleteOrphanOptimisticDuplicates(transportId: String) =
        db.messages().deleteOrphanOptimisticDuplicates(transportId)

    suspend fun repairLiteralNulls(transportId: String) =
        db.messages().repairLiteralNulls(transportId)

    suspend fun stripPlaceholderChars() = db.messages().stripPlaceholderChars()

    /** Replace a message's whole reaction map (JSON {"sender":"emoji"}); no-op if unchanged. */
    suspend fun setReactionsByExternal(transportId: String, externalId: String, reactions: String?) {
        val msg = db.messages().getByExternal(transportId, externalId) ?: return
        if (msg.reactions == reactions) return
        db.messages().setReactions(msg.id, reactions)
    }

    /** Outgoing messages that failed to send — the offline outbox, oldest first. */
    suspend fun failedOutgoing(): List<MessageEntity> =
        db.messages().outgoingWithStatus(MessageStatus.FAILED)

    suspend fun message(id: Long): MessageEntity? = db.messages().byId(id)

    /** ACIs of people seen in your Signal group chats (for starting a 1:1 with a member). */
    suspend fun signalGroupMemberAcis(): List<String> = db.messages().signalGroupMemberAcis()

    suspend fun setMuted(conversationId: Long, muted: Boolean) =
        db.conversations().setMuted(conversationId, muted)

    suspend fun setDraft(conversationId: Long, draft: String) =
        db.conversations().setDraft(conversationId, draft)

    /** Conversation ids whose messages contain [query] — content search for the inbox. */
    suspend fun searchMessageConversations(query: String): List<Long> =
        db.messages().conversationIdsMatching(query)

    /** Body of a message identified by its transport-local id, for reply previews. */
    suspend fun bodyForExternal(transportId: String, externalId: String): String? =
        db.messages().getByExternal(transportId, externalId)?.let { it.body.ifBlank { mediaLabel(it.mediaType) } }

    /** Add or remove a tapback/reaction on a target message (one per person). */
    suspend fun applyReaction(
        transportId: String, targetExternalId: String, from: String, emoji: String?, remove: Boolean,
    ) {
        val msg = db.messages().getByExternal(transportId, targetExternalId) ?: return
        val map = org.json.JSONObject(msg.reactions ?: "{}")
        if (remove || emoji.isNullOrEmpty()) map.remove(from) else map.put(from, emoji)
        db.messages().setReactions(msg.id, if (map.length() == 0) null else map.toString())
    }

    suspend fun setMessageStatus(id: Long, status: MessageStatus) =
        db.messages().setStatus(id, status)

    /** Recipient read-receipt sweep: mark outgoing messages up to [timestamp] as Seen/Read. */
    suspend fun markOutgoingReadUpTo(conversationId: Long, timestamp: Long) =
        db.messages().markOutgoingReadUpTo(conversationId, timestamp)

    /** Marks an outgoing message delivered (forward-only; never downgrades a Read). */
    suspend fun markDelivered(transportId: String, externalId: String) =
        advanceStatusByExternal(transportId, externalId, MessageStatus.DELIVERED)

    suspend fun setStatusByExternal(transportId: String, externalId: String, status: MessageStatus) =
        db.messages().setStatusByExternal(transportId, externalId, status)

    /** Advance a sent message's delivery status FORWARD only (SENT→DELIVERED→READ by ordinal),
     *  so a late/out-of-order receipt never downgrades it. Compared in Kotlin because Room
     *  persists the enum by name, not ordinal. */
    suspend fun advanceStatusByExternal(transportId: String, externalId: String, status: MessageStatus) {
        val current = db.messages().statusOfExternal(transportId, externalId) ?: return
        if (current.ordinal < status.ordinal)
            db.messages().setStatusByExternal(transportId, externalId, status)
    }

    suspend fun knownExternalIds(transportId: String): Set<String> =
        db.messages().knownExternalIds(transportId).toSet()
}

/** Inbox preview label for an attachment-only message. */
fun mediaLabel(mediaType: String?): String = when {
    mediaType == null -> ""
    mediaType.startsWith("image/") -> "📷 Photo"
    mediaType.startsWith("video/") -> "🎥 Video"
    mediaType.startsWith("audio/") -> "🎤 Voice message"
    else -> "📎 Attachment"
}
