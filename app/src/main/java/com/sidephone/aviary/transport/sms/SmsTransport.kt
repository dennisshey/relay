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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmsTransport(
    private val context: Context,
    private val repo: UnifiedRepository,
    private val mediaStore: com.sidephone.aviary.data.MediaStore,
    private val avatarStore: com.sidephone.aviary.data.AvatarStore,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : MessageTransport {

    override val id = ID
    override val protocol = Protocol.SMS
    override val canStartConversations = true

    // Re-sync contact names/photos whenever the address book changes, so a number you just saved
    // stops showing as a bare number without needing an app restart.
    @Volatile private var contactsObserver: android.database.ContentObserver? = null

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
        runCatching { importMmsThreads() }
        runCatching { syncContacts() }
        registerContactsObserver()
        _status.value = TransportStatus.Ready
    }

    /** Import existing MMS from the system provider — including group MMS, which the SMS-table
     *  import misses entirely — so group threads that predate the app show up. Each MMS's recipient
     *  set comes from its addr sub-table; a set of 3+ becomes a group thread. */
    private suspend fun importMmsThreads() = withContext(Dispatchers.IO) {
        val known = repo.knownExternalIds(ID)
        val cursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.SUBJECT),
            null, null, "${Telephony.Mms.DATE} ASC",
        ) ?: return@withContext
        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Mms._ID)
            val iDate = c.getColumnIndexOrThrow(Telephony.Mms.DATE)
            val iBox = c.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
            while (c.moveToNext()) {
                val mmsId = c.getLong(iId)
                val extId = "mms:$mmsId"
                if (extId in known) continue
                val outgoing = c.getInt(iBox) == Telephony.Mms.MESSAGE_BOX_SENT
                val dateMs = c.getLong(iDate) * 1000L // MMS date is in seconds
                val addrs = mmsAddresses(mmsId) // sender + recipients (excludes null/blank)
                val sender = mmsSender(mmsId) ?: addrs.firstOrNull() ?: continue
                val participants = addrs.distinctBy { numKey(it) }
                val convo = if (participants.count { numKey(it).length >= 7 } >= 3)
                    upsertGroupMms(participants)
                else {
                    // 1:1: key by the OTHER party (for an outgoing MMS the sender is us).
                    val self = selfNumbers()
                    val partner = participants.firstOrNull { numKey(it) !in self }
                        ?: if (outgoing) addrs.firstOrNull { numKey(it) != numKey(sender) } ?: sender else sender
                    upsertSmsConversation(
                        Telephony.Threads.getOrCreateThreadId(context, partner).toString(), partner,
                    )
                }
                val (text, image) = mmsBody(mmsId)
                val mediaPath = image?.let { (mime, bytes) -> mediaStore.save(bytes, mime) }
                repo.recordMessage(
                    MessageEntity(
                        conversationId = convo.id, transportId = ID, externalId = extId,
                        sender = if (outgoing) "me" else sender,
                        body = text.ifBlank { if (mediaPath != null) "" else "📎 MMS" },
                        timestamp = dateMs, outgoing = outgoing,
                        status = if (outgoing) MessageStatus.SENT else MessageStatus.RECEIVED,
                        mediaPath = mediaPath, mediaType = image?.first,
                    ),
                    countUnread = false,
                )
            }
        }
    }

    /** All addresses (sender + recipients) on a stored MMS, from its addr sub-table. */
    private fun mmsAddresses(mmsId: Long): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address", "type"), null, null, null,
            )?.use { a ->
                val iAddr = a.getColumnIndexOrThrow("address")
                while (a.moveToNext()) {
                    val addr = a.getString(iAddr)?.trim() ?: continue
                    if (addr.isNotEmpty() && !addr.equals("insert-address-token", true)) out += addr
                }
            }
        }
        return out
    }

    /** The FROM address (type 137) of a stored MMS. */
    private fun mmsSender(mmsId: Long): String? = runCatching {
        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"), "type=137", null, null,
        )?.use { a ->
            val iAddr = a.getColumnIndexOrThrow("address")
            if (a.moveToFirst()) a.getString(iAddr)?.trim()?.takeIf { it.isNotEmpty() } else null
        }
    }.getOrNull()

    /** Text and (first) image part of a stored MMS, read from its part sub-table. */
    private fun mmsBody(mmsId: Long): Pair<String, Pair<String, ByteArray>?> {
        var text = ""
        var image: Pair<String, ByteArray>? = null
        runCatching {
            context.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "ct", "text"), "mid=$mmsId", null, null,
            )?.use { p ->
                val iPid = p.getColumnIndexOrThrow("_id")
                val iCt = p.getColumnIndexOrThrow("ct")
                val iText = p.getColumnIndexOrThrow("text")
                while (p.moveToNext()) {
                    val ct = p.getString(iCt) ?: continue
                    when {
                        ct.startsWith("text/") ->
                            p.getString(iText)?.let { text = (text + "\n" + it).trim() }
                        ct.startsWith("image/") && image == null -> {
                            val bytes = runCatching {
                                context.contentResolver.openInputStream(
                                    Uri.parse("content://mms/part/${p.getLong(iPid)}")
                                )?.use { it.readBytes() }
                            }.getOrNull()
                            if (bytes != null) image = ct to bytes
                        }
                    }
                }
            }
        }
        return text to image
    }

    /** Re-resolve every SMS thread against the address book: fill in a newly-saved contact's name
     *  (moving it from Secondary to Primary) and photo. Runs on start and whenever contacts change,
     *  so saving a number that's been texting you updates the thread without an app restart. Never
     *  overwrites a resolved name with a bare number (e.g. if contacts read transiently fails). */
    private suspend fun syncContacts() = withContext(Dispatchers.IO) {
        for (c in repo.conversationsForTransport(ID)) {
            if (c.externalId.startsWith("group:") || c.address.equals("MMS", true)) continue
            val (name, photo) = lookupContact(c.address)
            if (name != null && name != c.title) {
                repo.setConversationTitle(c.id, name)
                if (c.category == InboxCategory.SECONDARY) repo.setCategory(c.id, InboxCategory.PRIMARY)
            }
            if (photo != null && !avatarStore.has(c.externalId)) avatarStore.save(c.externalId, photo)
        }
    }

    private fun registerContactsObserver() {
        if (contactsObserver != null) return
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scope.launch { runCatching { syncContacts() } }
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true, obs,
            )
            contactsObserver = obs
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
            // A group thread has no single SMS destination; group texts go as an MMS to everyone.
            if (conversation.externalId.startsWith("group:")) return@withContext runCatching {
                val rowId = repo.recordMessage(
                    MessageEntity(
                        conversationId = conversation.id, transportId = ID, externalId = null,
                        sender = "me", body = body, timestamp = System.currentTimeMillis(),
                        outgoing = true, status = MessageStatus.PENDING,
                    )
                )
                dispatchMms(conversation, rowId, body.ifBlank { null }, emptyList())
            }
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

    /** Start (or reopen) a group MMS thread with [addresses] (two or more other people). */
    suspend fun startGroup(addresses: List<String>): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val members = addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { numKey(it) }
            require(members.size >= 2) { "a group needs at least two people" }
            upsertGroupMms(members).id
        }
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

    private fun dispatchMms(
        conversation: ConversationEntity, rowId: Long, media: ByteArray,
        mime: String, fileName: String, caption: String,
    ) = dispatchMms(conversation, rowId, caption.ifBlank { null }, listOf(MmsCompose.Image(mime, media, fileName)))

    /** Build an MMS PDU (text and/or images) and hand it to the platform, wiring the result back to
     *  [rowId]. Recipients come from the conversation address (comma/semicolon-separated for groups),
     *  minus our own number so a group MMS isn't also sent back to us. */
    private fun dispatchMms(
        conversation: ConversationEntity, rowId: Long, text: String?, images: List<MmsCompose.Image>,
    ) {
        val self = selfNumbers()
        val recipients = conversation.address.split(",", ";").map { it.trim() }
            .filter { it.isNotEmpty() && numKey(it) !in self }
        val txn = java.util.UUID.randomUUID().toString()
        val pdu = MmsCompose.sendReq(
            transactionId = txn,
            recipients = recipients,
            text = text,
            images = images,
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
            recordMms(from, emptyList(), "📎 MMS received (no download location)", emptyList())
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
            }.onFailure { recordMms(from, emptyList(), "📎 MMS received (download failed)", emptyList()) }
        }
    }

    /** A queued MMS download finished: parse the retrieved PDU and record text + images. */
    suspend fun onMmsDownloaded(filePath: String, from: String?, ok: Boolean) {
        val bytes = withContext(Dispatchers.IO) {
            val f = File(filePath)
            (if (ok && f.exists()) runCatching { f.readBytes() }.getOrNull() else null).also { f.delete() }
        }
        if (bytes == null) {
            recordMms(from, emptyList(), "📎 MMS received (couldn't download)", emptyList()); return
        }
        val r = runCatching { MmsPdu.parseRetrieveConf(bytes) }.getOrNull()
        recordMms(r?.from ?: from, r?.to ?: emptyList(), r?.text.orEmpty(), r?.images ?: emptyList())
    }

    /** Persist a received MMS (image parts as media rows, plus any text) and notify. Reconstructs a
     *  group thread when the message was addressed to multiple people (From + To/Cc). */
    private suspend fun recordMms(
        from: String?, to: List<String>, text: String, images: List<MmsPdu.Part>,
    ) {
        val address = from ?: "MMS"
        // Distinct participants across sender + all recipients. Three or more (you + two others)
        // means a group; two (you + sender) is an ordinary 1:1.
        val participants = (listOf(address) + to).map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { numKey(it) }
        val convo = if (participants.count { numKey(it).length >= 7 } >= 3)
            upsertGroupMms(participants)
        else {
            val threadId = withContext(Dispatchers.IO) {
                Telephony.Threads.getOrCreateThreadId(context, address)
            }
            upsertSmsConversation(threadId.toString(), address)
        }
        // Cache the sender's contact name so the group bubble can label them.
        if (convo.externalId.startsWith("group:")) {
            lookupContactName(address)?.let {
                (context as? com.sidephone.aviary.RelayApp)?.contactNames?.put(address, it)
            }
        }
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
        notifyIncoming(convo, preview, sender = address)
    }

    // ---- group MMS helpers -------------------------------------------------

    /** Last-10-digit key for a phone number, so formatting/country-code differences collapse. */
    private fun numKey(addr: String): String = addr.filter { it.isDigit() }.takeLast(10)

    /** Best-effort set of this device's own numbers, to exclude us from group membership/sends. */
    private fun selfNumbers(): Set<String> {
        val out = HashSet<String>()
        runCatching {
            context.getSystemService(android.telephony.TelephonyManager::class.java)
                ?.line1Number?.let { numKey(it).takeIf { k -> k.length >= 7 }?.let(out::add) }
        }
        runCatching {
            @Suppress("MissingPermission")
            context.getSystemService(android.telephony.SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList?.forEach { info ->
                    info.number?.let { numKey(it).takeIf { k -> k.length >= 7 }?.let(out::add) }
                }
        }
        return out
    }

    /** Find or create the group thread for a participant set, keyed by the sorted member numbers so
     *  every message among the same people lands in one thread. Excludes our own number from the
     *  send list and title when we can detect it. */
    private suspend fun upsertGroupMms(participants: List<String>): ConversationEntity {
        val self = selfNumbers()
        val others = participants.filter { numKey(it) !in self }.ifEmpty { participants }
        val key = "group:mms:" + others.map { numKey(it) }.sorted().joinToString(",")
        val names = others.map { lookupContactName(it) ?: it }
        val title = when {
            names.size <= 3 -> names.joinToString(", ")
            else -> names.take(2).joinToString(", ") + " +${names.size - 2}"
        }
        val anyKnown = others.any { lookupContactName(it) != null }
        return repo.upsertConversation(
            transportId = ID,
            externalId = key,
            address = others.joinToString(";"),
            title = title,
            category = if (anyKnown) InboxCategory.PRIMARY else InboxCategory.SECONDARY,
        )
    }

    private fun notifyIncoming(convo: ConversationEntity, body: String, sender: String? = null) {
        // SMS/MMS threads are direct person-to-person, so they notify even in Secondary
        // (the "direct threads in Secondary still notify" rule); only muted-group chats stay quiet.
        val isGroup = convo.externalId.startsWith("group:")
        val senderName = sender?.let { lookupContactName(it) ?: it } ?: convo.title
        com.sidephone.aviary.data.Notifier.post(
            context, convo.id,
            sender = if (isGroup) senderName else convo.title,
            body = body,
            avatarPath = avatarStore.path(convo.externalId),
            isGroup = isGroup,
            groupTitle = if (isGroup) convo.title else null,
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
