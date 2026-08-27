package com.sidephone.aviary.transport

import com.sidephone.aviary.data.ConversationEntity
import com.sidephone.aviary.data.MessageEntity
import com.sidephone.aviary.data.Protocol
import kotlinx.coroutines.flow.StateFlow

sealed class TransportStatus {
    /** Connected / operational. */
    data object Ready : TransportStatus()

    /** Needs user action before it can run (permissions, linking, login). */
    data class NeedsSetup(val reason: String) : TransportStatus()

    /** Mid-way through an interactive setup flow. */
    data class Linking(val step: String) : TransportStatus()

    /** Present in the UI but intentionally not functional yet. */
    data class Planned(val note: String) : TransportStatus()

    data class Error(val message: String) : TransportStatus()
}

/**
 * One protocol plugged into the unified inbox. Implementations write incoming
 * messages into the shared Room store via UnifiedRepository; the UI never talks
 * to a protocol directly.
 */
interface MessageTransport {
    val id: String
    val protocol: Protocol
    val status: StateFlow<TransportStatus>

    /** Called on app start and after setup state changes; must be idempotent. */
    suspend fun start()

    /** Send [body]; if [replyTo] is set, send it as an inline reply to that message. */
    suspend fun sendText(
        conversation: ConversationEntity,
        body: String,
        replyTo: MessageEntity? = null,
    ): Result<Unit>

    /** Send a media attachment (with an optional text caption). */
    suspend fun sendMedia(
        conversation: ConversationEntity,
        media: ByteArray,
        contentType: String?,
        fileName: String?,
        caption: String,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("$id doesn't support attachments yet"))

    /**
     * Re-attempt an already-recorded outgoing [message] that previously failed (used by the
     * offline outbox on reconnect, and by manual "Try again"). Re-sends IN PLACE — sets the
     * existing row PENDING, attempts, then SENT/FAILED — so it keeps its position and never
     * creates a duplicate row. Reconstructs text or media from the stored row.
     */
    suspend fun resend(
        conversation: ConversationEntity,
        message: MessageEntity,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("$id doesn't support resend"))

    /** Send/remove a tapback reaction on [message]. [emoji] is a tapback emoji (❤️👍👎😂‼️❓ or any). */
    suspend fun sendReaction(
        conversation: ConversationEntity,
        message: MessageEntity,
        emoji: String,
        add: Boolean,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("$id doesn't support reactions"))

    /** Tell the other side we're typing (or stopped). Best-effort; failures are ignored. */
    suspend fun sendTyping(conversation: ConversationEntity, isTyping: Boolean) {}

    /** Edit a previously-sent message to [newBody]. */
    suspend fun editMessage(
        conversation: ConversationEntity, message: MessageEntity, newBody: String,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("$id doesn't support editing"))

    /** Unsend (retract) a previously-sent message for everyone. */
    suspend fun unsendMessage(
        conversation: ConversationEntity, message: MessageEntity,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("$id doesn't support unsending"))

    /** Send read receipts for this conversation (called when the user opens/reads it). */
    suspend fun markConversationRead(conversation: ConversationEntity) {}

    /** Whether this transport can create a conversation to a new address. */
    val canStartConversations: Boolean get() = false

    /** Start (or find) a conversation for a raw address. Returns conversation row id. */
    suspend fun startConversation(address: String): Result<Long> =
        Result.failure(UnsupportedOperationException("$id cannot start conversations"))
}

/**
 * A transport that can say whether a given address is reachable on its network
 * (e.g. an iMessage IDS lookup). Used for iMessage-first routing with SMS fallback.
 */
interface ReachabilityAware {
    suspend fun canReach(address: String): Boolean
}

class TransportRegistry(private val transports: List<MessageTransport>) {
    fun all(): List<MessageTransport> = transports
    fun byId(id: String): MessageTransport? = transports.find { it.id == id }
    suspend fun startAll() = transports.forEach { it.start() }
}
