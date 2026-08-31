package com.sidephone.aviary.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.sidephone.aviary.RelayApp
import com.sidephone.aviary.data.ConversationEntity
import com.sidephone.aviary.data.MessageEntity
import com.sidephone.aviary.data.MessageStatus
import com.sidephone.aviary.data.Protocol
import com.sidephone.aviary.data.mediaLabel
import kotlinx.coroutines.launch

/** How many messages a thread loads at a time (initial page + each scroll-up expansion). */
private const val PAGE_SIZE = 50

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(app: RelayApp, conversationId: Long, onBack: () -> Unit, onOpenThread: (Long) -> Unit = {}) {
    val convo by app.repository.conversation(conversationId).collectAsState(initial = null)
    // Growing window: open with a small page, load older history as the user scrolls to the top.
    var pageLimit by remember(conversationId) { mutableStateOf(PAGE_SIZE) }
    val messages by remember(conversationId, pageLimit) {
        app.repository.messagesPaged(conversationId, pageLimit)
    }.collectAsState(initial = emptyList())
    val typingSet by app.typing.typing.collectAsState()
    val otherTyping = conversationId in typingSet
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var draft by remember(conversationId) { mutableStateOf("") }
    // @-mentions inserted via the group picker for the current draft (Signal groups only).
    val draftMentions = remember(conversationId) {
        androidx.compose.runtime.mutableStateListOf<com.sidephone.aviary.transport.signal.SignalTransport.Mention>()
    }
    var draftSeeded by remember(conversationId) { mutableStateOf(false) }
    // Seed the composer from the persisted draft once, then keep it saved (debounced) so it
    // survives leaving the thread or the process being killed.
    LaunchedEffect(convo?.id) {
        if (convo != null && !draftSeeded) {
            if (draft.isEmpty()) draft = convo!!.draft
            draftSeeded = true
        }
    }
    LaunchedEffect(conversationId, draft, draftSeeded) {
        if (!draftSeeded) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        app.repository.setDraft(conversationId, draft)
    }
    var replyingTo by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var forwarding by remember { mutableStateOf<MessageEntity?>(null) }

    // iMessage-style routing: which transport would a send use right now?
    val sendProtocol by produceState(initialValue = Protocol.SMS, convo) {
        val c = convo ?: return@produceState
        // Show the thread's last-used transport immediately (no green→blue flip while the async
        // iMessage-reachability lookup runs), then confirm/refine it via the router.
        app.transports.byId(c.lastTransportId ?: c.transportId)?.protocol?.let { value = it }
        value = app.router.resolve(c).protocol
    }

    // Clear unread on open AND whenever a new message arrives while the thread is on screen,
    // so the inbox's blue dot never lingers after you've actually seen the message.
    LaunchedEffect(conversationId, messages.lastOrNull()?.id) { app.repository.markRead(conversationId) }
    // Read receipts + typing follow the conversation's OWN transport — never the send-time route.
    // Routing an SMS thread through iMessage (because the number is iMessage-reachable) would send
    // an iMessage read receipt for a non-iMessage chat, which isn't valid.
    LaunchedEffect(convo?.id, messages.lastOrNull()?.id) {
        convo?.let { c -> runCatching { app.transports.byId(c.transportId)?.markConversationRead(c) } }
    }
    // Tell the other side we're typing while there's a non-empty draft.
    LaunchedEffect(draft.isNotBlank(), convo?.id) {
        convo?.let { c -> runCatching { app.transports.byId(c.transportId)?.sendTyping(c, draft.isNotBlank()) } }
    }
    // While this thread is open, suppress its notifications and clear any already shown.
    androidx.compose.runtime.DisposableEffect(conversationId) {
        app.foregroundConversationId = conversationId
        com.sidephone.aviary.data.Notifier.cancel(app, conversationId)
        onDispose { if (app.foregroundConversationId == conversationId) app.foregroundConversationId = null }
    }
    // Jump to the newest message when the thread first opens.
    LaunchedEffect(conversationId, messages.isNotEmpty()) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }
    // Grow the window when scrolled near the top and the current page is full (more may exist).
    val shouldLoadOlder by remember {
        androidx.compose.runtime.derivedStateOf {
            listState.firstVisibleItemIndex <= 3 && messages.size >= pageLimit
        }
    }
    LaunchedEffect(shouldLoadOlder) { if (shouldLoadOlder) pageLimit += PAGE_SIZE }
    // Follow new messages AND status changes (so the "Delivered"/"Read" label is never cut off),
    // but only when already at the bottom — don't yank the user who scrolled up to read history.
    val lastMsgKey = messages.lastOrNull()?.let { "${it.id}:${it.status}" }
    LaunchedEffect(lastMsgKey, otherTyping) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastIndex = messages.size - 1 + (if (otherTyping) 1 else 0)
        val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            ?.let { it >= messages.size - 2 } ?: true
        if (atBottom) listState.scrollToItem(lastIndex)
    }
    // Keep the newest message visible when the keyboard opens: adjustResize shrinks the list
    // viewport, but the list keeps its scroll offset, so the last message slides behind the
    // composer. When the viewport shrinks while we were at the bottom, stick to the last message.
    LaunchedEffect(listState) {
        var prev = 0
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.viewportSize.height }
            .collect { h ->
                val shrank = prev != 0 && h in 1 until prev
                prev = h
                val total = listState.layoutInfo.totalItemsCount
                if (shrank && total > 0) {
                    val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        ?.let { it >= total - 2 } ?: true
                    if (atBottom) listState.scrollToItem(total - 1)
                }
            }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val pickMedia = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val c = convo ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (bytes, type) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                data to context.contentResolver.getType(uri)
            }
            if (bytes == null) return@launch
            // Route media the same way as text: iMessage when the number is reachable, else SMS/MMS.
            app.router.resolve(c)
                .sendMedia(c, bytes, type, null, draft.trim())
                .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't send attachment") }
            draft = ""
        }
    }
    val pickFile = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        val c = convo ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (bytes, type, name) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                Triple(data, context.contentResolver.getType(uri), displayName(context, uri))
            }
            if (bytes == null) return@launch
            app.router.resolve(c)
                .sendMedia(c, bytes, type, name, draft.trim())
                .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't send attachment") }
            draft = ""
        }
    }
    var attachMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        convo?.let {
                            val avatarPath = remember(it.externalId, it.lastMessageAt) {
                                app.avatarStore.path(it.externalId)
                            }
                            ConversationAvatar(it, 30.dp, avatarPath)
                        }
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(convo?.title ?: "", style = MaterialTheme.typography.titleMedium)
                            Text(
                                sendProtocol.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = sendProtocol.color
                            )
                        }
                    }
                },
                actions = {
                    // Offer "add to contacts" for an unknown phone/email 1:1 (e.g. a new number that's
                    // been texting you). Hidden once the address resolves to a saved contact.
                    val addable = convo?.takeIf {
                        !it.isGroup && (it.transportId == "sms" || it.transportId == "imessage")
                    }
                    if (addable != null) {
                        val known by produceState(true, addable.address) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                isKnownContact(context, addable.address)
                            }
                        }
                        if (!known) IconButton(onClick = { addToContacts(context, addable.address) }) {
                            Icon(
                                Icons.Filled.PersonAdd,
                                contentDescription = "Add to contacts",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            // The Delivered/Read label sits under the most recent DELIVERED/READ outgoing message,
            // so sending a new (not-yet-delivered) message leaves the old label in place until the
            // new one truly delivers — then it moves down. Failed messages label themselves.
            // Instagram and Signal show "Sent" under your latest message even before it's delivered;
            // iMessage/SMS only surface a label once Delivered/Read, so include SENT only for those.
            val lastReceiptId = messages.lastOrNull {
                it.outgoing && (it.status == MessageStatus.DELIVERED || it.status == MessageStatus.READ ||
                    ((it.transportId == "instagram" || it.transportId == "signal") &&
                        it.status == MessageStatus.SENT))
            }?.id
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val isGroup = convo?.isGroup == true
                itemsIndexed(messages, key = { _, m -> m.id }) { index, msg ->
                    val prev = messages.getOrNull(index - 1)
                    val next = messages.getOrNull(index + 1)
                    // A "block" is a run of same-direction, same-sender messages close in time.
                    val newBlock = prev == null || prev.outgoing != msg.outgoing ||
                        prev.sender != msg.sender || (msg.timestamp - prev.timestamp) > BLOCK_GAP_MS
                    val lastInBlock = next == null || next.outgoing != msg.outgoing ||
                        next.sender != msg.sender || (next.timestamp - msg.timestamp) > BLOCK_GAP_MS
                    val separator = timeSeparator(prev, msg)
                    Column(Modifier.fillMaxWidth().animateItemPlacement()) {
                        if (separator != null) {
                            Text(
                                separator,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        // The quoted (replied-to) message, if it's loaded in this thread.
                        // Exact id match first; then, for Signal, fall back to the sent-ts
                        // after the last ':' so we still find it if the author half differs
                        // (e.g. mine stored "out:<ts>" vs a quote that named it "<aci>:<ts>").
                        val quoted = msg.replyToExternalId?.let { ext ->
                            messages.firstOrNull { it.externalId == ext }
                                ?: ext.substringAfterLast(':', "").takeIf { it.isNotBlank() && it.all(Char::isDigit) }
                                    ?.let { ts -> messages.firstOrNull { it.externalId?.substringAfterLast(':', "") == ts } }
                        }
                        // Ghost bubble sits on the quoted author's side: right if it's mine,
                        // left if it's theirs. Fall back to the Signal "out:" id when the
                        // quoted message isn't loaded (iMessage guids aren't self-describing).
                        val quotedIsMine = quoted?.outgoing
                            ?: (msg.replyToExternalId?.startsWith("out:") == true)
                        // In group threads, name the author of the quoted message.
                        val replyToSender = if (isGroup && !msg.replyToExternalId.isNullOrBlank()) {
                            when {
                                quoted == null -> null
                                quoted.outgoing -> "You"
                                else -> app.contactNames.get(quoted.sender)
                                    ?: quoted.sender.substringAfterLast(":").ifBlank { quoted.sender }
                            }
                        } else null
                        MessageBubble(
                            msg = msg,
                            protocol = protocolOf(app, msg),
                            showReceipt = msg.id == lastReceiptId,
                            lastInBlock = lastInBlock,
                            senderLabel = if (isGroup && !msg.outgoing && newBlock)
                                (app.contactNames.get(msg.sender)
                                    ?: if (msg.transportId == "signal") "Signal member"
                                    else msg.sender.removePrefix("tel:").removePrefix("mailto:"))
                                else null,
                            // Tap a group member's name to start a 1:1 with them.
                            onSenderClick = if (isGroup && !msg.outgoing && msg.transportId == "signal") {
                                {
                                    scope.launch {
                                        app.signalTransport.startWithAci(
                                            msg.sender, app.contactNames.get(msg.sender),
                                        ).onSuccess { onOpenThread(it) }
                                    }
                                }
                            } else null,
                            replyToSender = replyToSender,
                            quotedIsMine = quotedIsMine,
                            canEditUnsend = msg.outgoing && msg.externalId != null &&
                                msg.transportId == "imessage",
                            onEdit = { editingMessage = msg; replyingTo = null; draft = msg.body },
                            onUnsend = {
                                scope.launch {
                                    app.transports.byId(msg.transportId)
                                        ?.unsendMessage(convo ?: return@launch, msg)
                                        ?.onFailure { e -> snackbar.showSnackbar(e.message ?: "Unsend failed") }
                                }
                            },
                            onReply = { replyingTo = msg },
                            onForward = { forwarding = msg },
                            onReact = { emoji ->
                                scope.launch {
                                    app.transports.byId(msg.transportId)
                                        ?.sendReaction(convo ?: return@launch, msg, emoji, add = true)
                                        ?.onFailure { e -> snackbar.showSnackbar(e.message ?: "Reaction failed") }
                                }
                            },
                            onDelete = { scope.launch { app.repository.deleteMessage(msg.id) } },
                            onRetry = {
                                scope.launch {
                                    convo?.let { c ->
                                        // Resend the existing row in place (keeps its spot, media, reply).
                                        app.transports.byId(msg.transportId)?.resend(c, msg)?.onFailure { e ->
                                            snackbar.showSnackbar(e.message ?: "Send failed")
                                        }
                                    }
                                }
                            },
                            topSpacing = if (separator == null && newBlock && index > 0) 8.dp else 0.dp,
                        )
                    }
                }
                if (otherTyping) {
                    item("typing") { TypingBubble() }
                }
            }
            replyingTo?.let { target ->
                ReplyPreviewBar(
                    target = target,
                    senderName = if (target.outgoing) "You"
                    else app.contactNames.get(target.sender)
                        ?: target.sender.substringAfterLast(":").ifBlank { target.sender },
                    onCancel = { replyingTo = null },
                )
            }
            editingMessage?.let {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Editing message",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { editingMessage = null; draft = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel edit")
                    }
                }
            }
            // @-mention candidates: only for Signal group threads — distinct members who've spoken,
            // paired with the name we know for them.
            val mentionCandidates = if (convo?.isGroup == true && sendProtocol == Protocol.SIGNAL) {
                messages.asSequence()
                    .filter { !it.outgoing && it.transportId == "signal" && !it.sender.isNullOrBlank() }
                    .map { it.sender!! }.distinct()
                    .mapNotNull { aci -> app.contactNames.get(aci)?.let { aci to it } }
                    .toList()
            } else emptyList()
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                sendProtocol = sendProtocol,
                mentionCandidates = mentionCandidates,
                onMentionInserted = { aci, token ->
                    draftMentions.add(com.sidephone.aviary.transport.signal.SignalTransport.Mention(aci, token))
                },
                onAttach = { attachMenuOpen = true },
                onSendVoice = { bytes ->
                    scope.launch {
                        val c = convo ?: return@launch
                        app.router.resolve(c)
                            .sendMedia(c, bytes, "audio/mp4", "voice.m4a", "")
                            .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't send voice message") }
                    }
                },
                onSend = {
                    val text = draft.trim()
                    val mentions = draftMentions.toList()
                    draft = ""
                    draftMentions.clear()
                    val reply = replyingTo
                    val editing = editingMessage
                    replyingTo = null
                    editingMessage = null
                    scope.launch {
                        val c = convo ?: return@launch
                        if (editing != null) {
                            app.transports.byId(editing.transportId)?.editMessage(c, editing, text)
                                ?.onFailure { e -> snackbar.showSnackbar(e.message ?: "Edit failed") }
                        } else {
                            val t = app.router.resolve(c)
                            val result = if (t is com.sidephone.aviary.transport.signal.SignalTransport
                                && mentions.isNotEmpty()
                            ) t.sendTextWithMentions(c, text, reply, mentions)
                            else t.sendText(c, text, reply)
                            result.onFailure { e -> snackbar.showSnackbar(e.message ?: "Send failed") }
                        }
                    }
                }
            )
            if (attachMenuOpen) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { attachMenuOpen = false },
                    confirmButton = {},
                    title = { Text("Attach") },
                    text = {
                        Column {
                            androidx.compose.material3.TextButton(onClick = {
                                attachMenuOpen = false
                                pickMedia.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts
                                            .PickVisualMedia.ImageAndVideo
                                    )
                                )
                            }) { Text("Photo or Video") }
                            androidx.compose.material3.TextButton(onClick = {
                                attachMenuOpen = false
                                pickFile.launch("*/*")
                            }) { Text("File") }
                        }
                    },
                )
            }
            forwarding?.let { fwdMsg ->
                val allConvos by app.repository.conversations().collectAsState(initial = emptyList())
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { forwarding = null },
                    confirmButton = {},
                    title = { Text("Forward to…") },
                    text = {
                        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(allConvos, key = { it.id }) { target ->
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        forwarding = null
                                        scope.launch {
                                            if (fwdMsg.mediaPath != null) {
                                                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    runCatching { java.io.File(fwdMsg.mediaPath).readBytes() }.getOrNull()
                                                }
                                                if (bytes != null) app.router.resolve(target)
                                                    .sendMedia(target, bytes, fwdMsg.mediaType, null, fwdMsg.body)
                                                    .onFailure { snackbar.showSnackbar(it.message ?: "Forward failed") }
                                            } else {
                                                app.router.resolve(target).sendText(target, fwdMsg.body)
                                                    .onFailure { snackbar.showSnackbar(it.message ?: "Forward failed") }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        target.title,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** A voice-note bubble: play/pause an audio attachment from its local file. */
@Composable
private fun VoiceMessage(path: String, tint: Color) {
    var playing by remember(path) { mutableStateOf(false) }
    val player = remember(path) { android.media.MediaPlayer() }
    androidx.compose.runtime.DisposableEffect(path) {
        runCatching { player.setDataSource(path); player.prepare() }
        player.setOnCompletionListener { playing = false; runCatching { player.seekTo(0) } }
        onDispose { runCatching { player.release() } }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.size(28.dp).clickable {
                if (playing) { runCatching { player.pause() }; playing = false }
                else runCatching { player.start(); playing = true }
            },
        )
        Spacer(Modifier.size(6.dp))
        Text("Voice message", color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}

/** True if the file at [path] starts with a known image magic number (JPEG/PNG/GIF/WEBP/HEIC). */
private fun isImageFile(path: String): Boolean = runCatching {
    java.io.File(path).inputStream().use { s ->
        val b = ByteArray(12)
        val n = s.read(b)
        if (n < 4) return false
        val u = { i: Int -> b[i].toInt() and 0xFF }
        (u(0) == 0xFF && u(1) == 0xD8) || // JPEG
            (u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47) || // PNG
            (u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46) || // GIF
            (n >= 12 && u(8) == 0x57 && u(9) == 0x45 && u(10) == 0x42 && u(11) == 0x50) || // WEBP (RIFF....WEBP)
            (n >= 12 && u(4) == 0x66 && u(5) == 0x74 && u(6) == 0x79 && u(7) == 0x70) // HEIC/ISO-BMFF (ftyp)
    }
}.getOrDefault(false)

/** Share an image via the system share sheet (FileProvider uri). */
private fun shareImage(context: android.content.Context, path: String) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.attachments", java.io.File(path),
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "Share")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Save an image into the device Photos (Pictures/Messenger) via MediaStore — no permission on API 30+. */
private fun saveImageToGallery(context: android.content.Context, path: String) {
    runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Messenger_${path.hashCode()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Messenger")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return
        resolver.openOutputStream(uri)?.use { out -> java.io.File(path).inputStream().use { it.copyTo(out) } }
        android.widget.Toast.makeText(context, "Saved to Photos", android.widget.Toast.LENGTH_SHORT).show()
    }.onFailure {
        android.widget.Toast.makeText(context, "Couldn't save", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Open a downloaded attachment (PDF, doc, etc.) in an external viewer via a FileProvider uri. */
private fun openAttachment(context: android.content.Context, path: String, mime: String?) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.attachments", java.io.File(path),
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime ?: "*/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Open with"))
    }
}

/** Best-effort human display name for a content Uri, for attachment file names. */
private fun displayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

private fun protocolOf(app: RelayApp, msg: MessageEntity): Protocol =
    app.transports.byId(msg.transportId)?.protocol ?: Protocol.SMS

/** Strip a "tel:"/"mailto:" scheme so we hand a plain number/email to contacts. */
private fun bareHandle(address: String): String =
    address.removePrefix("tel:").removePrefix("mailto:").trim()

/** Whether a phone number or email already resolves to a saved Android contact. */
private fun isKnownContact(context: android.content.Context, address: String): Boolean {
    if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
        != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) return true // no permission: don't nag with an add button we can't verify
    val handle = bareHandle(address)
    val uri = if (handle.contains("@"))
        android.net.Uri.withAppendedPath(
            android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,
            android.net.Uri.encode(handle),
        )
    else
        android.net.Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(handle),
        )
    return runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.BaseColumns._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(false)
}

/** Open the system "add contact" screen with the number/email prefilled. */
private fun addToContacts(context: android.content.Context, address: String) {
    val handle = bareHandle(address)
    val intent = android.content.Intent(android.content.Intent.ACTION_INSERT_OR_EDIT).apply {
        type = android.provider.ContactsContract.Contacts.CONTENT_ITEM_TYPE
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (handle.contains("@")) putExtra(android.provider.ContactsContract.Intents.Insert.EMAIL, handle)
        else putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, handle)
    }
    runCatching { context.startActivity(intent) }
}

/** iMessage look: colored bubble by protocol for outgoing, gray for incoming. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: MessageEntity,
    protocol: Protocol,
    showReceipt: Boolean,
    lastInBlock: Boolean = true,
    senderLabel: String? = null,
    onSenderClick: (() -> Unit)? = null,
    replyToSender: String? = null,
    quotedIsMine: Boolean = false,
    onReply: () -> Unit = {},
    onForward: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRetry: () -> Unit = {},
    onReact: (String) -> Unit = {},
    onEdit: () -> Unit = {},
    onUnsend: () -> Unit = {},
    canEditUnsend: Boolean = false,
    topSpacing: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = topSpacing),
        horizontalAlignment = if (msg.outgoing) Alignment.End else Alignment.Start
    ) {
        if (senderLabel != null) {
            Text(
                senderLabel,
                style = MaterialTheme.typography.labelSmall,
                color = protocol.color,
                modifier = Modifier
                    .padding(start = 14.dp, bottom = 1.dp)
                    .then(if (onSenderClick != null) Modifier.clickable(onClick = onSenderClick) else Modifier),
            )
        }
        // iMessage inline reply: the quoted message is an outlined "ghost" bubble on the
        // QUOTED author's side (left for their message, right for yours), with a curved
        // connector line sweeping down to this reply bubble.
        if (!msg.replyToPreview.isNullOrBlank()) {
            val ghostOnRight = quotedIsMine
            val quoteShape = RoundedCornerShape(
                topStart = 15.dp, topEnd = 15.dp,
                bottomStart = if (ghostOnRight) 15.dp else 5.dp,
                bottomEnd = if (ghostOnRight) 5.dp else 15.dp,
            )
            val ghostColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            Box(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.align(if (ghostOnRight) Alignment.TopEnd else Alignment.TopStart),
                    horizontalAlignment = if (ghostOnRight) Alignment.End else Alignment.Start,
                ) {
                    // Group threads: the quoted author's name sits above the ghost bubble.
                    if (replyToSender != null) {
                        Text(
                            replyToSender,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(
                                start = if (ghostOnRight) 0.dp else 16.dp,
                                end = if (ghostOnRight) 16.dp else 0.dp,
                                bottom = 1.dp,
                            ),
                        )
                    }
                    Box(
                        Modifier
                            .widthIn(max = 215.dp)
                            .clip(quoteShape)
                            .border(1.5.dp, ghostColor, quoteShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            msg.replyToPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Curved connector from the ghost bubble's tail down toward the reply bubble.
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val edge = 10.dp.toPx()
                val startX = if (ghostOnRight) size.width - edge else edge
                val dir = if (ghostOnRight) -1f else 1f
                val path = Path().apply {
                    moveTo(startX, 0f)
                    quadraticBezierTo(startX, size.height, startX + dir * 12.dp.toPx(), size.height)
                }
                drawPath(path, ghostColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        val isVideo = msg.mediaType?.startsWith("video/") == true
        val isAudio = msg.mediaType?.startsWith("audio/") == true
        // A video plays from a remote URL (IG reel) OR a downloaded local file (Signal/iMessage).
        val videoSource = when {
            !msg.mediaUrl.isNullOrBlank() -> msg.mediaUrl
            isVideo -> msg.mediaPath
            else -> null
        }
        // Poster/still: images and IG-reel thumbnails live in mediaPath; a downloaded video has
        // no separate poster frame (mediaUrl is null), so it shows a play button on a dark tile.
        val posterPath = when {
            !isVideo -> msg.mediaPath
            !msg.mediaUrl.isNullOrBlank() -> msg.mediaPath
            else -> null
        }
        // Treat the attachment as an image when the mediaType says so OR — when the transport gave us
        // no/opaque type (e.g. a Signal pointer with no contentType) — when the file's magic bytes are
        // an image. Without this, such photos fall through to a plain "Attachment" label with no viewer.
        val isImage = remember(msg.mediaPath, msg.mediaType) {
            msg.mediaType?.startsWith("image/") == true ||
                ((msg.mediaType.isNullOrBlank() || msg.mediaType == "application/octet-stream") &&
                    msg.mediaPath != null && !isVideo && isImageFile(msg.mediaPath!!))
        }
        val hasThumb = msg.mediaPath != null && (isImage || isVideo)
        // Fully rounded within a block; only the last bubble gets the tail corner.
        val bubbleShape = RoundedCornerShape(
            topStart = 18.dp, topEnd = 18.dp,
            bottomStart = if (!msg.outgoing && lastInBlock) 5.dp else 18.dp,
            bottomEnd = if (msg.outgoing && lastInBlock) 5.dp else 18.dp,
        )
        val reactions = remember(msg.reactions) { reactionEmojis(msg.reactions) }
        Box {
            if (hasThumb && msg.body.isBlank()) {
                // Bare image/reel: no bubble background.
                MediaThumb(
                    imagePath = posterPath,
                    videoSource = videoSource,
                    shape = bubbleShape,
                    modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp),
                )
            } else {
                Box(
                    Modifier
                        .widthIn(max = 280.dp)
                        .clip(bubbleShape)
                        .background(
                            color = if (msg.outgoing) protocol.color
                            else MaterialTheme.colorScheme.secondaryContainer,
                        )
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { menuOpen = true },
                        )
                        .padding(horizontal = 13.dp, vertical = 8.dp)
                ) {
                    Column {
                        when {
                            hasThumb -> MediaThumb(
                                imagePath = posterPath,
                                videoSource = videoSource,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp),
                            )
                            isAudio && msg.mediaPath != null -> VoiceMessage(
                                path = msg.mediaPath!!,
                                tint = if (msg.outgoing) Color.White
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            msg.mediaPath != null -> {
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                Text(
                                    mediaLabel(msg.mediaType),
                                    color = if (msg.outgoing) Color.White
                                    else MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    modifier = Modifier.clickable { openAttachment(ctx, msg.mediaPath!!, msg.mediaType) },
                                )
                            }
                        }
                        if (msg.body.isNotBlank()) {
                            Text(
                                msg.body,
                                color = if (msg.outgoing) Color.White
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = if (msg.mediaPath != null) Modifier.padding(top = 6.dp) else Modifier,
                            )
                        }
                    }
                }
            }
            // Tapback reactions perch on the bubble's outer top corner, iMessage-style.
            if (reactions.isNotEmpty()) {
                Row(
                    Modifier
                        .align(if (msg.outgoing) Alignment.TopStart else Alignment.TopEnd)
                        .offset(x = if (msg.outgoing) (-8).dp else 8.dp, y = (-12).dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    reactions.take(4).forEach { Text(it, style = MaterialTheme.typography.labelMedium) }
                }
            }
            // Long-press menu: a tapback row, then reply/copy/etc.
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    for (e in listOf("❤️", "👍", "👎", "😂", "‼️", "❓")) {
                        Text(
                            e,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { menuOpen = false; onReact(e) }
                                .padding(6.dp),
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text("Reply") },
                    onClick = { menuOpen = false; onReply() },
                )
                DropdownMenuItem(
                    text = { Text("Forward") },
                    onClick = { menuOpen = false; onForward() },
                )
                if (msg.body.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = { menuOpen = false; clipboard.setText(AnnotatedString(msg.body)) },
                    )
                }
                if (msg.outgoing && msg.status == MessageStatus.FAILED) {
                    DropdownMenuItem(
                        text = { Text("Try again") },
                        onClick = { menuOpen = false; onRetry() },
                    )
                }
                if (canEditUnsend && msg.body.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { menuOpen = false; onEdit() },
                    )
                }
                if (canEditUnsend) {
                    DropdownMenuItem(
                        text = { Text("Unsend") },
                        onClick = { menuOpen = false; onUnsend() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
        // Classic iMessage: nothing under a sent/pending bubble — a small "Delivered" (or
        // "Read"/"Not Delivered") appears only under the most recent message once it happens.
        // Per-transport wording: Instagram says "Sent"/"Seen"; Signal shows the full
        // "Sent"→"Delivered"→"Read" progression; iMessage/SMS only "Delivered"/"Read".
        val ig = protocol == Protocol.INSTAGRAM
        val signal = protocol == Protocol.SIGNAL
        val receiptText = when (msg.status) {
            MessageStatus.FAILED -> "Not Delivered"
            MessageStatus.READ -> if (ig) "Seen" else "Read"
            MessageStatus.DELIVERED -> if (ig) "Sent" else "Delivered"
            MessageStatus.SENT -> if (ig || signal) "Sent" else null
            else -> null // PENDING -> show nothing
        }
        if (msg.outgoing && receiptText != null && (showReceipt || msg.status == MessageStatus.FAILED)) {
            Text(
                receiptText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (msg.status == MessageStatus.READ) FontWeight.SemiBold else FontWeight.Normal,
                color = if (msg.status == MessageStatus.FAILED)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

/** Distinct emoji from the reactions JSON ({"sender":"emoji",...}), most-recent-ish order. */
private fun reactionEmojis(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val o = org.json.JSONObject(json)
        o.keys().asSequence().map { o.getString(it) }.distinct().toList()
    }.getOrDefault(emptyList())
}

private const val BLOCK_GAP_MS = 3 * 60 * 1000L   // >3 min starts a new bubble group
private const val SEP_GAP_MS = 60 * 60 * 1000L    // >1 hr (or a new day) shows a time separator

/** Centered "Today 3:45 PM"-style separator, or null if this message continues the last cluster. */
private fun timeSeparator(prev: MessageEntity?, msg: MessageEntity): String? {
    val show = prev == null ||
        (msg.timestamp - prev.timestamp) >= SEP_GAP_MS ||
        !sameDay(prev.timestamp, msg.timestamp)
    return if (show) formatSeparator(msg.timestamp) else null
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

private fun formatSeparator(ts: Long): String {
    val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(ts))
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val sameYear = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
    val dayDiff = now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR)
    val prefix = when {
        sameYear && dayDiff == 0 -> "Today"
        sameYear && dayDiff == 1 -> "Yesterday"
        sameYear -> java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date(ts))
        else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
    return "$prefix  $time"
}

/** Bar above the composer while composing an inline reply; shows the quoted message. */
@Composable
private fun ReplyPreviewBar(
    target: MessageEntity,
    senderName: String,
    onCancel: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A colored accent rule, iMessage/Mail reply style.
        Box(
            Modifier
                .padding(end = 10.dp)
                .width(3.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Column(Modifier.weight(1f)) {
            Text(
                "Replying to $senderName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                target.body.ifBlank { mediaLabel(target.mediaType) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Cancel reply",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** iMessage-style pill composer with the circular ↑ send button. */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sendProtocol: Protocol,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onSendVoice: (ByteArray) -> Unit,
    mentionCandidates: List<Pair<String, String>> = emptyList(), // (aci, display name)
    onMentionInserted: (aci: String, token: String) -> Unit = { _, _ -> },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val recorder = remember { com.sidephone.aviary.data.VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0) }
    val micPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted && recorder.start()) recording = true }
    LaunchedEffect(recording) {
        elapsed = 0
        while (recording) { kotlinx.coroutines.delay(1000); elapsed++ }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (recorder.isRecording) recorder.cancel() }
    }

    // While recording, the composer becomes a record bar: cancel · timer · send.
    if (recording) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { recorder.cancel(); recording = false }) {
                Icon(Icons.Default.Delete, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
            }
            Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            Spacer(Modifier.size(10.dp))
            Text(
                "%d:%02d".format(elapsed / 60, elapsed % 60),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(40.dp).background(sendProtocol.color, CircleShape).clickable {
                    val bytes = recorder.stop()
                    recording = false
                    if (bytes != null && bytes.isNotEmpty()) onSendVoice(bytes)
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        return
    }

    // Active @-mention query: a trailing "@word" (start- or space-anchored) with no space after it.
    // Only in group chats that supply candidates (Signal). Suggests matching members to insert.
    val atMatch = if (mentionCandidates.isNotEmpty())
        Regex("(?:^|\\s)@([\\p{L}0-9._-]*)$").find(draft) else null
    val mentionQuery = atMatch?.groupValues?.get(1)
    val suggestions = if (mentionQuery != null)
        mentionCandidates.filter { it.second.contains(mentionQuery, ignoreCase = true) }.take(6)
    else emptyList()

    Column(Modifier.fillMaxWidth()) {
        if (suggestions.isNotEmpty()) {
            androidx.compose.material3.Surface(
                tonalElevation = 3.dp, shadowElevation = 4.dp,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
            ) {
                Column {
                    suggestions.forEach { (aci, name) ->
                        Text(
                            "@$name",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable {
                                val at = atMatch ?: return@clickable
                                val atIndex = at.range.first + at.value.indexOf('@')
                                onDraftChange(draft.substring(0, atIndex) + "@" + name + " ")
                                onMentionInserted(aci, "@$name")
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            Modifier.padding(bottom = 6.dp).size(40.dp).clickable(onClick = onAttach),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Attach",
                tint = sendProtocol.color,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.size(4.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    when (sendProtocol) {
                        Protocol.IMESSAGE -> "iMessage"
                        Protocol.SMS -> "Text Message · SMS"
                        else -> sendProtocol.displayName
                    },
                    color = MaterialTheme.colorScheme.outline
                )
            },
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            ),
            maxLines = 4,
        )
        Spacer(Modifier.size(8.dp))
        // Send button when there's text; otherwise a mic to record a voice note.
        if (draft.isNotBlank()) {
            Box(
                Modifier.padding(bottom = 6.dp).size(40.dp)
                    .background(sendProtocol.color, CircleShape)
                    .clickable(onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        } else {
            Box(
                Modifier.padding(bottom = 6.dp).size(40.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
                    .clickable {
                        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            if (recorder.start()) recording = true
                        } else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Record voice", tint = sendProtocol.color, modifier = Modifier.size(22.dp))
            }
        }
    }
    } // Column (mention dropdown + input row)
}

/**
 * A media thumbnail. For an Instagram reel/video it overlays a ▶ badge and, on tap, streams the
 * clip straight from its source URL in a fullscreen player — no video download.
 */
@Composable
private fun MediaThumb(
    imagePath: String?,
    videoSource: String?,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
) {
    var playing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                when {
                    videoSource != null -> Modifier.clickable { playing = true }
                    imagePath != null -> Modifier.clickable { viewing = true } // tap image → viewer
                    else -> Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (imagePath != null) {
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(java.io.File(imagePath)).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
            )
        } else {
            // Downloaded video with no poster frame — a dark tile sized for the play button.
            Box(Modifier.size(width = 220.dp, height = 150.dp).background(Color.Black))
        }
        if (videoSource != null) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }
    if (playing && videoSource != null) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val uri = remember(videoSource) {
            if (videoSource.startsWith("http")) android.net.Uri.parse(videoSource)
            else android.net.Uri.fromFile(java.io.File(videoSource))
        }
        val player = remember(videoSource) {
            androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                playWhenReady = true
                prepare()
            }
        }
        androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }
        Dialog(onDismissRequest = { playing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { c ->
                        androidx.media3.ui.PlayerView(c).apply {
                            this.player = player
                            // Letterbox to the video's real aspect ratio instead of stretching.
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                            useController = true
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(onClick = { playing = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
    if (viewing && imagePath != null) {
        FullScreenImage(imagePath) { viewing = false }
    }
}

/** Full-screen image viewer: pinch/double-tap to zoom, drag to pan, share or save. */
@Composable
private fun FullScreenImage(path: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            coil.compose.AsyncImage(
                model = java.io.File(path),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }
                                else scale = 2.5f
                            },
                        )
                    }
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y,
                    ),
            )
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                IconButton(onClick = { shareImage(context, path) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                }
                IconButton(onClick = { saveImageToGallery(context, path) }) {
                    Icon(Icons.Filled.Download, contentDescription = "Save", tint = Color.White)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

/** The "other person is typing" bubble shown at the bottom of a thread. */
@Composable
private fun TypingBubble() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Box(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "•  •  •",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
