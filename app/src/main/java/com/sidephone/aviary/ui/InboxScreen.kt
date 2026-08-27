package com.sidephone.aviary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sidephone.aviary.RelayApp
import com.sidephone.aviary.data.ConversationEntity
import com.sidephone.aviary.data.InboxCategory
import com.sidephone.aviary.data.Protocol
import com.sidephone.aviary.transport.TransportStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(
    app: RelayApp,
    onOpenThread: (Long) -> Unit,
    onNewMessage: () -> Unit,
    onOpenAccounts: () -> Unit,
    onRequestDefaultSms: () -> Unit,
) {
    val conversations by app.repository.conversations().collectAsState(initial = emptyList())
    val smsStatus by app.smsTransport.status.collectAsState()
    var tab by remember { mutableStateOf(InboxCategory.PRIMARY) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Stable row callbacks (keyed by conversation) so ConversationRow stays skippable — otherwise
    // every inbox re-emit (each incoming receipt/poll) recomposes every visible row mid-scroll.
    val onOpen = remember(onOpenThread) { { id: Long -> onOpenThread(id) } }
    val onMove = remember(scope, app) {
        { id: Long, cat: InboxCategory -> scope.launch { app.repository.setCategory(id, cat) }; Unit }
    }
    val onToggleRead = remember(scope, app) {
        { c: ConversationEntity ->
            scope.launch {
                if (c.unreadCount > 0) app.repository.markRead(c.id) else app.repository.markUnread(c.id)
            }; Unit
        }
    }
    val onToggleMute = remember(scope, app) {
        { c: ConversationEntity -> scope.launch { app.repository.setMuted(c.id, !c.muted) }; Unit }
    }
    val onDelete = remember(scope, app) {
        { id: Long -> scope.launch { app.repository.deleteConversation(id) }; Unit }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenAccounts) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Accounts",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewMessage,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Edit, contentDescription = "New message")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (smsStatus is TransportStatus.NeedsSetup) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Relay isn't your SMS app yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            (smsStatus as TransportStatus.NeedsSetup).reason,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = onRequestDefaultSms) { Text("Make default") }
                    }
                }
            }

            val searching = query.isNotBlank()
            // Content search: also match conversations whose message bodies contain the query.
            val contentMatches by androidx.compose.runtime.produceState(emptySet<Long>(), query) {
                value = if (query.isBlank()) emptySet()
                else runCatching { app.repository.searchMessageConversations(query).toSet() }
                    .getOrDefault(emptySet())
            }
            val shown = if (searching) {
                conversations.filter {
                    it.id in contentMatches ||
                        it.title.contains(query, true) || it.lastPreview.contains(query, true) ||
                        it.address.contains(query, true)
                }
            } else {
                conversations.filter { it.category == tab }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                // Compact search field lives inside the list so it scrolls away (iOS-style).
                item(key = "search") {
                    SearchField(
                        query = query,
                        onChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                if (!searching) {
                    item(key = "tabs") {
                        TabRow(
                            selectedTabIndex = if (tab == InboxCategory.PRIMARY) 0 else 1,
                            containerColor = MaterialTheme.colorScheme.background,
                        ) {
                            Tab(
                                selected = tab == InboxCategory.PRIMARY,
                                onClick = { tab = InboxCategory.PRIMARY },
                                text = { Text("Primary") }
                            )
                            Tab(
                                selected = tab == InboxCategory.SECONDARY,
                                onClick = { tab = InboxCategory.SECONDARY },
                                text = { Text("Secondary") }
                            )
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                            Text(
                                when {
                                    searching -> "No matches"
                                    conversations.isEmpty() -> "No messages yet"
                                    else -> "Nothing in ${if (tab == InboxCategory.PRIMARY) "Primary" else "Secondary"}"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else {
                    items(shown, key = { it.id }) { convo ->
                        val avatarPath = remember(convo.externalId, convo.lastMessageAt) {
                            app.avatarStore.path(convo.externalId)
                        }
                        ConversationRow(
                            convo = convo,
                            protocol = protocolFor(app, convo),
                            avatarPath = avatarPath,
                            onClick = onOpen,
                            onMove = onMove,
                            onToggleRead = onToggleRead,
                            onToggleMute = onToggleMute,
                            onDelete = onDelete,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 84.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )
                    }
                }
            }
        }
    }
}

private fun protocolFor(app: RelayApp, convo: ConversationEntity): Protocol =
    // Follow the last message's transport (an SMS thread that iMessaged out shows blue), falling
    // back to the conversation's own transport.
    app.transports.byId(convo.lastTransportId ?: convo.transportId)?.protocol
        ?: app.transports.byId(convo.transportId)?.protocol ?: Protocol.SMS

/** Compact iOS-style search field: a short rounded pill, not a full-height text field. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search, contentDescription = null,
                    modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.size(6.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(
                        "Search",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    inner()
                }
                if (query.isNotEmpty()) Icon(
                    Icons.Filled.Close, contentDescription = "Clear",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onChange("") },
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        },
    )
}

/** iOS Messages-style row: blue unread dot, gray avatar, name/preview, time + chevron. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    convo: ConversationEntity,
    protocol: Protocol,
    avatarPath: String?,
    onClick: (Long) -> Unit,
    onMove: (Long, InboxCategory) -> Unit,
    onToggleRead: (ConversationEntity) -> Unit,
    onToggleMute: (ConversationEntity) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { onClick(convo.id) }, onLongClick = { menuOpen = true })
                .padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                if (convo.unreadCount > 0) {
                    Box(
                        Modifier.size(9.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            }
            ConversationAvatar(convo, 46.dp, avatarPath)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        convo.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.size(6.dp))
                    Box(Modifier.size(7.dp).background(protocol.color, CircleShape))
                    if (convo.muted) {
                        Spacer(Modifier.size(6.dp))
                        Icon(
                            Icons.Filled.NotificationsOff, contentDescription = "Muted",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    convo.lastPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTime(convo.lastMessageAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val target = if (convo.category == InboxCategory.PRIMARY)
                InboxCategory.SECONDARY else InboxCategory.PRIMARY
            DropdownMenuItem(
                text = {
                    Text("Move to ${if (target == InboxCategory.PRIMARY) "Primary" else "Secondary"}")
                },
                onClick = { menuOpen = false; onMove(convo.id, target) }
            )
            DropdownMenuItem(
                text = { Text(if (convo.unreadCount > 0) "Mark as read" else "Mark as unread") },
                onClick = { menuOpen = false; onToggleRead(convo) },
            )
            DropdownMenuItem(
                text = { Text(if (convo.muted) "Unmute" else "Mute") },
                onClick = { menuOpen = false; onToggleMute(convo) },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; onDelete(convo.id) },
            )
        }
    }
}

// Cached formatters — DateFormat.getXInstance() allocates, and formatTime runs per inbox row
// during scroll. UI-thread only, so single instances are fine.
private val timeFormat by lazy(LazyThreadSafetyMode.NONE) { DateFormat.getTimeInstance(DateFormat.SHORT) }
private val dateFormat by lazy(LazyThreadSafetyMode.NONE) { DateFormat.getDateInstance(DateFormat.SHORT) }

/** Today → time; otherwise short date, like iMessage. */
private fun formatTime(millis: Long): String {
    if (millis <= 0) return ""
    return if (android.text.format.DateUtils.isToday(millis)) timeFormat.format(Date(millis))
    else dateFormat.format(Date(millis))
}
