package com.sidephone.aviary.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A message source wired into the unified inbox. */
/** Bubble colors follow the iMessage convention: blue = iMessage, green = SMS. */
enum class Protocol(val displayName: String, val colorArgb: Long) {
    SMS("SMS", 0xFF34C759),
    SIGNAL("Signal", 0xFF1F51C9),   // deeper blue
    IMESSAGE("iMessage", 0xFF3D9BFF), // lighter/sky blue
    INSTAGRAM("Instagram", 0xFFE1306C),
}

/** Sunbird-style priority inbox buckets. */
enum class InboxCategory { PRIMARY, SECONDARY }

// Append-only: Room may persist by ordinal, so never reorder/insert — add at the end.
enum class MessageStatus { PENDING, SENT, FAILED, RECEIVED, DELIVERED, READ }

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["transportId", "externalId"], unique = true)]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transportId: String,
    /** Stable id within the source protocol, e.g. Telephony thread_id. */
    val externalId: String,
    /** Destination address (phone number for SMS/Signal). */
    val address: String,
    val title: String,
    val category: InboxCategory = InboxCategory.PRIMARY,
    val lastMessageAt: Long = 0,
    val lastPreview: String = "",
    val unreadCount: Int = 0,
    /** User-set: silence notifications for this conversation regardless of inbox category. */
    val muted: Boolean = false,
    /** Unsent draft text, persisted so it survives leaving the thread. */
    val draft: String = "",
    /** Deleted by the user: hidden from the inbox. A NEW message (ts > hiddenAt) un-hides it,
     *  but re-delivered/re-synced OLD messages don't, so deletion sticks. */
    val hidden: Boolean = false,
    val hiddenAt: Long = 0,
    /** Transport of the most recent message — so the inbox dot shows blue after an SMS thread's
     *  last message went out over iMessage, even though the conversation itself is SMS-keyed. */
    val lastTransportId: String? = null,
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "timestamp"]),
        Index(value = ["transportId", "externalId"], unique = true),
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val transportId: String,
    /** Id in the source store (e.g. Telephony _id), null for not-yet-persisted outgoing. */
    val externalId: String?,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val status: MessageStatus,
    /** Local path to a decrypted attachment, if this message has one. */
    val mediaPath: String? = null,
    /** MIME type of the attachment (e.g. "image/jpeg", "video/mp4"). */
    val mediaType: String? = null,
    /** Remote streamable URL (e.g. an Instagram reel), so video plays without downloading it. */
    val mediaUrl: String? = null,
    /** Tapbacks/reactions as JSON: {"sender":"emoji",...}. One per person, iMessage-style. */
    val reactions: String? = null,
    /** External id (guid/timestamp) of the message this one replies to, if any. */
    val replyToExternalId: String? = null,
    /** Short preview of the replied-to message, for the quoted line. */
    val replyToPreview: String? = null,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE hidden = 0 ORDER BY lastMessageAt DESC")
    fun all(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE hidden = 0")
    suspend fun allList(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun byId(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE transportId = :transportId AND externalId = :externalId")
    suspend fun byExternal(transportId: String, externalId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE transportId = :transportId")
    suspend fun forTransport(transportId: String): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: ConversationEntity): Long

    @Update
    suspend fun update(c: ConversationEntity)

    @Query("UPDATE conversations SET category = :category WHERE id = :id")
    suspend fun setCategory(id: Long, category: InboxCategory)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE conversations SET unreadCount = 1 WHERE id = :id")
    suspend fun markUnread(id: Long)

    @Query("UPDATE conversations SET lastMessageAt = :at, lastPreview = :preview, unreadCount = unreadCount + :unreadDelta WHERE id = :id")
    suspend fun touch(id: Long, at: Long, preview: String, unreadDelta: Int)

    @Query("UPDATE conversations SET externalId = :externalId WHERE id = :id")
    suspend fun setExternalId(id: Long, externalId: String)

    @Query("UPDATE conversations SET muted = :muted WHERE id = :id")
    suspend fun setMuted(id: Long, muted: Boolean)

    @Query("UPDATE conversations SET draft = :draft WHERE id = :id")
    suspend fun setDraft(id: Long, draft: String)

    @Query("UPDATE conversations SET lastTransportId = :transportId WHERE id = :id")
    suspend fun setLastTransport(id: Long, transportId: String)

    @Query("UPDATE conversations SET hidden = 1, hiddenAt = :at WHERE id = :id")
    suspend fun hide(id: Long, at: Long)

    @Query("UPDATE conversations SET hidden = 0 WHERE id = :id")
    suspend fun unhide(id: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations WHERE transportId = :transportId")
    suspend fun deleteTransport(transportId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    fun forConversation(conversationId: Long): Flow<List<MessageEntity>>

    /**
     * The most recent [limit] messages, returned oldest-first for display. A growing window: the
     * thread opens with a small page instead of loading an entire (possibly huge) history, and the
     * UI raises [limit] as the user scrolls up. Ordering matches [forConversation].
     */
    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit) ORDER BY timestamp ASC, id ASC"
    )
    fun forConversationPaged(conversationId: Long, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT externalId FROM messages WHERE transportId = :transportId AND externalId IS NOT NULL")
    suspend fun knownExternalIds(transportId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: MessageEntity): Long

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: MessageStatus)

    @Query("UPDATE messages SET status = :status, externalId = :externalId WHERE id = :id")
    suspend fun setSentResult(id: Long, externalId: String?, status: MessageStatus)

    /**
     * One-time cleanup: delete optimistic outgoing rows that never got a server id but whose
     * poll-inserted twin (same conversation + body, with an externalId) exists — i.e. the
     * pre-reconciliation duplicates. Guarded by EXISTS so we never delete an un-twinned send.
     */
    @Query("""
        DELETE FROM messages
        WHERE transportId = :transportId
          AND externalId IS NULL
          AND outgoing = 1
          AND EXISTS (
            SELECT 1 FROM messages twin
            WHERE twin.conversationId = messages.conversationId
              AND twin.transportId = messages.transportId
              AND twin.outgoing = 1
              AND twin.externalId IS NOT NULL
              AND twin.body = messages.body
          )
    """)
    suspend fun deleteOrphanOptimisticDuplicates(transportId: String)


    @Query("SELECT * FROM messages WHERE transportId = :transportId AND externalId = :externalId LIMIT 1")
    suspend fun getByExternal(transportId: String, externalId: String): MessageEntity?

    @Query("UPDATE messages SET reactions = :reactions WHERE id = :id")
    suspend fun setReactions(id: Long, reactions: String?)

    @Query("UPDATE messages SET status = :status WHERE transportId = :transportId AND externalId = :externalId")
    suspend fun setStatusByExternal(transportId: String, externalId: String, status: MessageStatus)

    @Query("SELECT status FROM messages WHERE id = :id")
    suspend fun statusOf(id: Long): MessageStatus?

    @Query("SELECT status FROM messages WHERE transportId = :transportId AND externalId = :externalId LIMIT 1")
    suspend fun statusOfExternal(transportId: String, externalId: String): MessageStatus?

    /** Mark outgoing messages in a conversation as READ up to [timestamp] — a recipient read-receipt
     *  sweep (e.g. Instagram's per-user last_seen_at). Never downgrades a FAILED/READ row. */
    @Query(
        "UPDATE messages SET status = :read WHERE conversationId = :conversationId AND outgoing = 1 " +
            "AND timestamp <= :timestamp AND status IN (:sent, :delivered)"
    )
    suspend fun markOutgoingReadUpTo(
        conversationId: Long,
        timestamp: Long,
        read: MessageStatus = MessageStatus.READ,
        sent: MessageStatus = MessageStatus.SENT,
        delivered: MessageStatus = MessageStatus.DELIVERED,
    )

    @Query("UPDATE messages SET conversationId = :toId WHERE conversationId = :fromId")
    suspend fun reassignConversation(fromId: Long, toId: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT externalId FROM messages WHERE conversationId = :conversationId AND outgoing = 0 AND externalId IS NOT NULL ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun latestIncomingExternalId(conversationId: Long): String?

    /** The offline outbox: outgoing messages that failed to send, oldest first, for auto-retry. */
    @Query("SELECT * FROM messages WHERE outgoing = 1 AND status = :status ORDER BY timestamp ASC, id ASC")
    suspend fun outgoingWithStatus(status: MessageStatus): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    /** Conversations containing a message whose body matches [q] — content search for the inbox. */
    @Query("SELECT DISTINCT conversationId FROM messages WHERE body LIKE '%' || :q || '%'")
    suspend fun conversationIdsMatching(q: String): List<Long>

    /** Distinct ACIs of people seen in your Signal group chats — to start a 1:1 with a member. */
    @Query(
        "SELECT DISTINCT m.sender FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE m.transportId = 'signal' AND c.externalId LIKE 'group:%' " +
            "AND m.outgoing = 0 AND m.sender IS NOT NULL AND m.sender != 'me'"
    )
    suspend fun signalGroupMemberAcis(): List<String>

    /**
     * One-time repair: earlier builds stored the literal string "null" (from JSONObject.optString
     * on a JSON null) into mediaPath/replyToExternalId, which made every text message render an
     * "Attachment" label. Reset those bogus values to real NULLs.
     */
    @Query("""
        UPDATE messages SET
            mediaPath = CASE WHEN mediaPath = 'null' THEN NULL ELSE mediaPath END,
            mediaType = CASE WHEN mediaType = 'null' THEN NULL ELSE mediaType END,
            replyToExternalId = CASE WHEN replyToExternalId = 'null' THEN NULL ELSE replyToExternalId END,
            replyToPreview = CASE WHEN replyToExternalId = 'null' THEN NULL ELSE replyToPreview END
        WHERE transportId = :transportId
          AND (mediaPath = 'null' OR mediaType = 'null' OR replyToExternalId = 'null')
    """)
    suspend fun repairLiteralNulls(transportId: String)

    /**
     * Strip the object-replacement (U+FFFC) and replacement (U+FFFD) placeholder glyphs that
     * older builds left in attachment-message captions, so old rows read cleanly.
     */
    @Query(
        "UPDATE messages SET body = REPLACE(REPLACE(body, char(65532), ''), char(65533), '') " +
            "WHERE body LIKE '%' || char(65532) || '%' OR body LIKE '%' || char(65533) || '%'"
    )
    suspend fun stripPlaceholderChars()

    @Query("UPDATE messages SET body = :body WHERE transportId = :transportId AND externalId = :externalId")
    suspend fun updateBodyByExternal(transportId: String, externalId: String, body: String)

    @Query("DELETE FROM messages WHERE transportId = :transportId AND externalId = :externalId")
    suspend fun deleteByExternal(transportId: String, externalId: String)

    @Query("DELETE FROM messages WHERE transportId = :transportId")
    suspend fun deleteTransport(transportId: String)
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AviaryDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
}

/** v2 adds attachment columns to messages. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN mediaPath TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN mediaType TEXT")
    }
}

/** v3 adds reactions (tapbacks) and reply/quote columns to messages. */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN reactions TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN replyToExternalId TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN replyToPreview TEXT")
    }
}

/** v4 adds a streamable media URL (Instagram reels play from source, no download). */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN mediaUrl TEXT")
    }
}

/** v5 adds per-conversation mute + persisted draft text. */
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN muted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN draft TEXT NOT NULL DEFAULT ''")
    }
}

/** v6 adds delete-as-hide tombstone columns so a deleted chat isn't resurrected by old messages. */
val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN hiddenAt INTEGER NOT NULL DEFAULT 0")
    }
}

/** v7 tracks the last message's transport so the inbox dot follows iMessage/SMS conversion. */
val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN lastTransportId TEXT")
        // Backfill from each conversation's most recent message so dots are correct immediately.
        db.execSQL(
            "UPDATE conversations SET lastTransportId = (" +
                "SELECT m.transportId FROM messages m WHERE m.conversationId = conversations.id " +
                "ORDER BY m.timestamp DESC, m.id DESC LIMIT 1)"
        )
    }
}
