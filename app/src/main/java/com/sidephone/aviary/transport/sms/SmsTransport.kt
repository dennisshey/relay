package com.sidephone.aviary.transport.sms

import android.Manifest
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sidephone.aviary.RelayApp
import com.sidephone.aviary.MainActivity
import com.sidephone.aviary.R
import com.sidephone.aviary.data.ConversationEntity
import com.sidephone.aviary.data.InboxCategory
import com.sidephone.aviary.data.MessageEntity
import com.sidephone.aviary.data.MessageStatus
import com.sidephone.aviary.data.Protocol
import com.sidephone.aviary.data.UnifiedRepository
import com.sidephone.aviary.transport.MessageTransport
import com.sidephone.aviary.transport.TransportStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class SmsTransport(
    private val context: Context,
    private val repo: UnifiedRepository,
    private val mediaStore: com.sidephone.aviary.data.MediaStore,
    private val avatarStore: com.sidephone.aviary.data.AvatarStore,
) : MessageTransport {

    override val id = ID
    override val protocol = Protocol.SMS
    override val canStartConversations = true

    private val _status = MutableStateFlow<TransportStatus>(
        TransportStatus.NeedsSetup("Set Relay as the default SMS app")
    )
    override val status: StateFlow<TransportStatus> = _status

    fun isDefaultSmsApp(): Boolean =
        context.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_SMS) == true

    private fun hasSmsPermissions(): Boolean =
        listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    override suspend fun start() {
        if (!isDefaultSmsApp()) {
            _status.value = TransportStatus.NeedsSetup("Set Relay as the default SMS app")
            return
        }
        if (!hasSmsPermissions()) {
            _status.value = TransportStatus.NeedsSetup("Grant SMS permissions")
            return
        }
        syncFromTelephony()
        runCatching { backfillContactPhotos() }
        _status.value = TransportStatus.Ready
    }

    /** Pull Android contact photos for existing SMS threads that don't have one cached yet, so
     *  threads created before photo sync existed also show the contact's picture. */
    private suspend fun backfillContactPhotos() = withContext(Dispatchers.IO) {
        for (c in repo.conversationsForTransport(ID)) {
            if (avatarStore.has(c.externalId)) continue
            val photo = lookupContact(c.address).second ?: continue
            avatarStore.save(c.externalId, photo)
        }
    }

    /** Import existing SMS threads from the system Telephony provider into the unified store. */
    private suspend fun syncFromTelephony() = withContext(Dispatchers.IO) {
        val known = repo.knownExternalIds(ID)
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ
            ),
            null, null,
            "${Telephony.Sms.DATE} ASC"
        ) ?: return@withContext

        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iThread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (c.moveToNext()) {
                val extId = c.getLong(iId).toString()
                if (extId in known) continue
                val address = c.getString(iAddr) ?: continue
                val threadId = c.getLong(iThread).toString()
                val body = c.getString(iBody) ?: ""
                val date = c.getLong(iDate)
                val outgoing = c.getInt(iType) != Telephony.Sms.MESSAGE_TYPE_INBOX

                val convo = upsertSmsConversation(threadId, address)
                repo.recordMessage(
                    MessageEntity(
                        conversationId = convo.id,
                        transportId = ID,
                        externalId = extId,
                        sender = if (outgoing) "me" else address,
                        body = body,
                        timestamp = date,
                        outgoing = outgoing,
                        status = if (outgoing) MessageStatus.SENT else MessageStatus.RECEIVED,
                    ),
                    countUnread = false, // don't re-badge history on import
                )
            }
        }
    }

    private suspend fun upsertSmsConversation(threadId: String, address: String): ConversationEntity {
        val (contactName, photo) = lookupContact(address)
        val convo = repo.upsertConversation(
            transportId = ID,
            externalId = threadId,
            address = address,
            title = contactName ?: address,
            // Sunbird-style priority inbox: known contacts land in Primary
            category = if (contactName != null) InboxCategory.PRIMARY else InboxCategory.SECONDARY,
        )
        // Sync the Android contact photo into the avatar store (keyed by conversation externalId,
        // the way the inbox looks it up) so SMS threads show contact photos like iMessage does.
        if (photo != null && !avatarStore.has(convo.externalId)) avatarStore.save(convo.externalId, photo)
        return convo
    }

    override suspend fun sendText(
        conversation: ConversationEntity, body: String, replyTo: MessageEntity?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { // SMS has no inline-reply concept; replyTo is ignored.
                val smsManager = context.getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(body)
                smsManager.sendMultipartTextMessage(
                    conversation.address, null, parts, null, null
                )
                // As the default SMS app we are responsible for writing our own sent messages.
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, conversation.address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.READ, 1)
                }
                val uri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
                repo.recordMessage(
                    MessageEntity(
                        conversationId = conversation.id,
                        transportId = ID,
                        externalId = uri?.lastPathSegment,
                        sender = "me",
                        body = body,
                        timestamp = System.currentTimeMillis(),
                        outgoing = true,
                        status = MessageStatus.SENT,
                    )
                )
                Unit
            }
        }

    override suspend fun startConversation(address: String): Result<Long> = runCatching {
        val threadId = withContext(Dispatchers.IO) {
            Telephony.Threads.getOrCreateThreadId(context, address)
        }
        upsertSmsConversation(threadId.toString(), address).id
    }

    /** Entry point for SmsDeliverReceiver. */
    suspend fun onSmsReceived(messages: List<SmsMessage>) {
        if (messages.isEmpty()) return
        val address = messages.first().displayOriginatingAddress ?: return
        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        val timestamp = messages.first().timestampMillis

        // The default SMS app must persist incoming messages to the Telephony provider itself.
        val uri = withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.READ, 0)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        }

        val threadId = withContext(Dispatchers.IO) {
            Telephony.Threads.getOrCreateThreadId(context, address)
        }
        val convo = upsertSmsConversation(threadId.toString(), address)
        val rowId = repo.recordMessage(
            MessageEntity(
                conversationId = convo.id,
                transportId = ID,
                externalId = uri?.lastPathSegment,
                sender = address,
                body = body,
                timestamp = System.currentTimeMillis(),
                outgoing = false,
                status = MessageStatus.RECEIVED,
            )
        )
        if (rowId > 0) notifyIncoming(convo, body)
    }

    /** Send a picture (and optional caption) as an MMS to the conversation's address. */
    override suspend fun sendMedia(
        conversation: ConversationEntity,
        media: ByteArray,
        contentType: String?,
        fileName: String?,
        caption: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = contentType ?: "image/jpeg"
            // Optimistic local echo so the sent bubble + image show immediately.
            val localPath = mediaStore.save(media, mime)
            val rowId = repo.recordMessage(
                MessageEntity(
                    conversationId = conversation.id, transportId = ID, externalId = null,
                    sender = "me", body = caption, timestamp = System.currentTimeMillis(),
                    outgoing = true, status = MessageStatus.PENDING,
                    mediaPath = localPath, mediaType = mime,
                )
            )
            dispatchMms(conversation, rowId, media, mime, fileName ?: "image", caption)
        }
    }

    override suspend fun resend(
        conversation: ConversationEntity, message: MessageEntity,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        repo.setMessageStatus(message.id, MessageStatus.PENDING)
        runCatching {
            if (message.mediaPath != null) {
                val bytes = File(message.mediaPath).readBytes()
                dispatchMms(conversation, message.id, bytes, message.mediaType ?: "image/jpeg", "image", message.body)
                // MmsSentReceiver flips the row to SENT/FAILED when the send resolves.
            } else {
                val smsManager = context.getSystemService(SmsManager::class.java)
                smsManager.sendMultipartTextMessage(
                    conversation.address, null, smsManager.divideMessage(message.body), null, null,
                )
                repo.setMessageStatus(message.id, MessageStatus.SENT)
            }
        }.onFailure { repo.setMessageStatus(message.id, MessageStatus.FAILED) }
    }

    /** Build an MMS PDU for [media] and hand it to the platform, wiring the result back to [rowId]. */
    private fun dispatchMms(
        conversation: ConversationEntity, rowId: Long, media: ByteArray,
        mime: String, fileName: String, caption: String,
    ) {
        val txn = java.util.UUID.randomUUID().toString()
        val pdu = MmsCompose.sendReq(
            transactionId = txn,
            recipients = conversation.address.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() },
            text = caption.ifBlank { null },
            images = listOf(MmsCompose.Image(mime, media, fileName)),
        )
        val dir = File(context.cacheDir, "mms").apply { mkdirs() }
        val file = File(dir, "$txn.send.pdu").apply { writeBytes(pdu) }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.mmsfiles", file,
        )
        context.grantUriPermission(
            "com.android.phone", uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val sent = Intent(MmsSentReceiver.ACTION).apply {
            setClass(context, MmsSentReceiver::class.java)
            putExtra(MmsSentReceiver.EXTRA_ROW, rowId)
            putExtra(MmsSentReceiver.EXTRA_FILE, file.absolutePath)
        }
        val pi = PendingIntent.getBroadcast(
            context, file.hashCode(), sent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        context.getSystemService(SmsManager::class.java)
            .sendMultimediaMessage(context, uri, null, null, pi)
    }

    /** Result of an outbound MMS send: flip the pending row to SENT or FAILED. */
    suspend fun onMmsSent(rowId: Long, filePath: String?, ok: Boolean) {
        filePath?.let { withContext(Dispatchers.IO) { runCatching { File(it).delete() } } }
        repo.setMessageStatus(rowId, if (ok) MessageStatus.SENT else MessageStatus.FAILED)
    }

    /**
     * Handle an inbound MMS notification (M-Notification.ind): parse the sender +
     * content-location, then ask the platform to download the message body. The result
     * arrives at [onMmsDownloaded]. Falls back to a placeholder — now with the real
     * sender — if there's no location or the request can't be started.
     */
    suspend fun onWapPush(pdu: ByteArray?) {
        val notif = pdu?.let { runCatching { MmsPdu.parseNotification(it) }.getOrNull() }
        val from = notif?.from
        val location = notif?.contentLocation
        if (location.isNullOrBlank()) {
            recordMms(from, "📎 MMS received (no download location)", emptyList())
            return
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "mms").apply { mkdirs() }
                val file = File(dir, "${java.util.UUID.randomUUID()}.pdu").apply { createNewFile() }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.mmsfiles", file,
                )
                // The platform's MMS service (com.android.phone) writes the PDU here.
                context.grantUriPermission(
                    "com.android.phone", uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                val downloaded = Intent(MmsDownloadedReceiver.ACTION).apply {
                    setClass(context, MmsDownloadedReceiver::class.java)
                    putExtra(MmsDownloadedReceiver.EXTRA_FILE, file.absolutePath)
                    putExtra(MmsDownloadedReceiver.EXTRA_FROM, from)
                }
                val pi = PendingIntent.getBroadcast(
                    context, file.hashCode(), downloaded,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                context.getSystemService(SmsManager::class.java)
                    .downloadMultimediaMessage(context, location, uri, null, pi)
            }.onFailure { recordMms(from, "📎 MMS received (download failed)", emptyList()) }
        }
    }

    /** A queued MMS download finished: parse the retrieved PDU and record text + images. */
    suspend fun onMmsDownloaded(filePath: String, from: String?, ok: Boolean) {
        val bytes = withContext(Dispatchers.IO) {
            val f = File(filePath)
            (if (ok && f.exists()) runCatching { f.readBytes() }.getOrNull() else null).also { f.delete() }
        }
        if (bytes == null) {
            recordMms(from, "📎 MMS received (couldn't download)", emptyList()); return
        }
        val r = runCatching { MmsPdu.parseRetrieveConf(bytes) }.getOrNull()
        recordMms(r?.from ?: from, r?.text.orEmpty(), r?.images ?: emptyList())
    }

    /** Persist a received MMS (image parts as media rows, plus any text) and notify. */
    private suspend fun recordMms(from: String?, text: String, images: List<MmsPdu.Part>) {
        val address = from ?: "MMS"
        val threadId = withContext(Dispatchers.IO) {
            Telephony.Threads.getOrCreateThreadId(context, address)
        }
        val convo = upsertSmsConversation(threadId.toString(), address)
        val now = System.currentTimeMillis()
        images.forEachIndexed { i, part ->
            val path = withContext(Dispatchers.IO) { mediaStore.save(part.data, part.contentType) }
            repo.recordMessage(
                MessageEntity(
                    conversationId = convo.id, transportId = ID, externalId = null,
                    sender = address, body = if (i == 0) text else "",
                    timestamp = now + i, outgoing = false, status = MessageStatus.RECEIVED,
                    mediaPath = path, mediaType = part.contentType,
                )
            )
        }
        if (images.isEmpty()) {
            repo.recordMessage(
                MessageEntity(
                    conversationId = convo.id, transportId = ID, externalId = null,
                    sender = address, body = text.ifBlank { "📎 MMS" }, timestamp = now,
                    outgoing = false, status = MessageStatus.RECEIVED,
                )
            )
        }
        val preview = when {
            text.isNotBlank() -> text
            images.isNotEmpty() -> "📷 Photo"
            else -> "📎 MMS"
        }
        notifyIncoming(convo, preview)
    }

    private fun notifyIncoming(convo: ConversationEntity, body: String) {
        // SMS/MMS threads are direct person-to-person, so they notify even in Secondary
        // (the "direct threads in Secondary still notify" rule); only muted-group chats stay quiet.
        com.sidephone.aviary.data.Notifier.post(
            context, convo.id, sender = convo.title, body = body,
            avatarPath = avatarStore.path(convo.externalId),
            muted = convo.muted,
        )
    }

    private fun lookupContactName(address: String): String? = lookupContact(address).first

    /** Resolve an SMS address to its Android contact name and photo (JPEG bytes), or nulls. */
    private fun lookupContact(address: String): Pair<String?, ByteArray?> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null to null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI,
                    ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
                ),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null to null
                val name = c.getString(0)
                val photoUri = c.getString(1) ?: c.getString(2)
                val photo = photoUri?.let { u ->
                    runCatching {
                        context.contentResolver.openInputStream(Uri.parse(u))?.use { it.readBytes() }
                    }.getOrNull()
                }
                name to photo
            } ?: (null to null)
        }.getOrDefault(null to null)
    }

    companion object {
        const val ID = "sms"
    }
}
