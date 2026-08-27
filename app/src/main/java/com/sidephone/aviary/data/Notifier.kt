package com.sidephone.aviary.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.sidephone.aviary.RelayApp
import com.sidephone.aviary.MainActivity
import com.sidephone.aviary.R

/**
 * One place every transport posts incoming-message notifications, so SMS, Signal, and
 * iMessage all behave identically: a MessagingStyle notification per conversation, named
 * by the sender with their avatar, tapping opens the thread. Suppressed for the thread
 * that's currently on screen, and cleared when a thread is read.
 */
object Notifier {

    private const val MAX_LINES = 6
    private data class Line(val text: String, val ts: Long, val person: Person)
    // Per-conversation recent messages, so we can rebuild the style with icons intact.
    private val history = java.util.concurrent.ConcurrentHashMap<Long, MutableList<Line>>()

    /**
     * Post (or update) the notification for [conversationId]. No-op when that thread is
     * already open. [avatarPath] is an optional avatar image file for the sender icon.
     */
    fun post(
        context: Context,
        conversationId: Long,
        sender: String,
        body: String,
        avatarPath: String?,
        timestamp: Long = System.currentTimeMillis(),
        isGroup: Boolean = false,
        groupTitle: String? = null,
        muted: Boolean = false,
    ) {
        if (muted) return // Secondary-inbox conversations are silent
        val app = context.applicationContext as? RelayApp
        if (app?.foregroundConversationId == conversationId) return // thread is on screen

        // Always show an avatar: the real photo (downsampled + cached, not a full-size decode on
        // every post), or a colored initials circle like iMessage.
        val roundAvatar = avatarPath?.let { loadCircleAvatar(it) } ?: initialsAvatar(sender)
        // Stable key so Android keeps each sender's avatar distinct across messages.
        val person = Person.Builder()
            .setName(sender)
            .setKey(sender)
            .setIcon(IconCompat.createWithBitmap(roundAvatar))
            .build()

        // Keep our OWN short history and rebuild the style each time: extracting it back
        // from the posted notification loses every Person's icon, which is why older
        // senders showed a generic group glyph instead of their avatar.
        val list = history.getOrPut(conversationId) { mutableListOf() }
        val style: NotificationCompat.MessagingStyle
        synchronized(list) {
            list.add(Line(body, timestamp, person))
            while (list.size > MAX_LINES) list.removeAt(0)
            style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            style.isGroupConversation = isGroup
            if (isGroup) style.conversationTitle = groupTitle
            list.forEach { style.addMessage(it.text, it.ts, it.person) }
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = android.app.PendingIntent.getActivity(
            context, conversationId.toInt(), tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        // Publish a long-lived conversation shortcut and link it to the notification. This
        // is what makes Android render the rich Conversation UI (prominent avatar + name +
        // People section), like iMessage/BlueBubbles, instead of the plain fallback.
        val shortcutId = "chat_$conversationId"
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(if (isGroup) (groupTitle ?: sender) else sender)
                .setLongLived(true)
                .setPerson(person)
                .setIcon(IconCompat.createWithBitmap(roundAvatar))
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId),
                )
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }

        val markReadPi = android.app.PendingIntent.getBroadcast(
            context, conversationId.toInt() * 3 + 1,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_MARK_READ)
                .putExtra(NotificationActionReceiver.EXTRA_CONVERSATION, conversationId),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val markReadAction = NotificationCompat.Action.Builder(0, "Mark As Read", markReadPi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val replyPi = android.app.PendingIntent.getBroadcast(
            context, conversationId.toInt() * 3 + 2,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_REPLY)
                .putExtra(NotificationActionReceiver.EXTRA_CONVERSATION, conversationId),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(0, "Reply", replyPi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .addRemoteInput(RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel("Reply").build())
            .build()

        val notification = NotificationCompat.Builder(context, RelayApp.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setColor(0xFF007AFF.toInt())
            .setWhen(timestamp)
            .setShortcutId(shortcutId)
            .addPerson(person)
            .setLargeIcon(roundAvatar) // avatar shown collapsed
            .addAction(markReadAction)
            .addAction(replyAction)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(conversationId.toInt(), notification)
        }
    }

    /** Clear a conversation's notification (called when it's opened / marked read). */
    fun cancel(context: Context, conversationId: Long) {
        history.remove(conversationId)
        runCatching {
            NotificationManagerCompat.from(context).cancel(conversationId.toInt())
        }
    }

    // A pleasant, deterministic palette (indexed by name) for initials avatars.
    private val PALETTE = intArrayOf(
        0xFF3A76F0.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt(), 0xFFAF52DE.toInt(),
        0xFFFF2D55.toInt(), 0xFF5AC8FA.toInt(), 0xFFFF6482.toInt(), 0xFF30B0C7.toInt(),
    )

    /** A colored circle with the sender's initial — the fallback when there's no photo. */
    private fun initialsAvatar(name: String): Bitmap {
        val size = 128
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = PALETTE[(name.hashCode() and 0x7FFFFFFF) % PALETTE.size]
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = size * 0.5f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val fm = tp.fontMetrics
        canvas.drawText(initial, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, tp)
        return out
    }

    private const val AVATAR_PX = 128
    // Small LRU of circle-cropped avatars so we don't decode + crop the file on every post.
    private val avatarCache = android.util.LruCache<String, Bitmap>(24)

    /** Load an avatar file as a downsampled, circle-cropped bitmap, cached by path+mtime. */
    private fun loadCircleAvatar(path: String): Bitmap? {
        val key = "$path:${runCatching { java.io.File(path).lastModified() }.getOrDefault(0L)}"
        avatarCache.get(key)?.let { return it }
        val decoded = decodeSampled(path, AVATAR_PX) ?: return null
        val circ = circleCrop(decoded)
        avatarCache.put(key, circ)
        return circ
    }

    /** Decode [path] downsampled so its larger side is ~[target] px — avatars don't need full res. */
    private fun decodeSampled(path: String, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(path, bounds) }
        val larger = maxOf(bounds.outWidth, bounds.outHeight)
        if (larger <= 0) return null
        var sample = 1
        while (larger / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }

    private fun circleCrop(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val left = (size - src.width) / 2f
        val top = (size - src.height) / 2f
        canvas.drawBitmap(src, left, top, paint)
        return out
    }
}
