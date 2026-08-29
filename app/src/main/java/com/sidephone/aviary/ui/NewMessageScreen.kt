package com.sidephone.aviary.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.sidephone.aviary.RelayApp
import com.sidephone.aviary.data.Contacts
import com.sidephone.aviary.data.DeviceContact
import com.sidephone.aviary.transport.instagram.InstagramApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class NewChatNetwork(val label: String) { MESSAGES("Messages"), SIGNAL("Signal"), INSTAGRAM("Instagram") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(app: RelayApp, onOpenThread: (Long) -> Unit, onBack: () -> Unit) {
    var network by remember { mutableStateOf(NewChatNetwork.MESSAGES) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun run(block: suspend () -> Result<Long>) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            block().onSuccess { onOpenThread(it) }.onFailure { error = it.message ?: "Couldn't start conversation" }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("New message") },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in NewChatNetwork.entries) {
                    FilterChip(selected = network == n, onClick = { network = n; error = null; query = "" }, label = { Text(n.label) })
                }
            }
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        when (network) {
                            NewChatNetwork.MESSAGES -> "Name or phone number"
                            NewChatNetwork.SIGNAL -> "Name, number, or @username"
                            NewChatNetwork.INSTAGRAM -> "Search Instagram"
                        }
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
            )
            Spacer(Modifier.size(8.dp))

            when (network) {
                NewChatNetwork.MESSAGES -> MessagesPicker(app, context, query, busy) { addr ->
                    // An email routes to iMessage (blue); a phone number starts an SMS thread that
                    // the router upgrades to iMessage when the number is reachable.
                    run {
                        if (addr.contains("@")) app.imessageTransport.startConversation(addr)
                        else app.smsTransport.startConversation(addr)
                    }
                }
                NewChatNetwork.SIGNAL -> SignalPicker(app, query, busy, onOpenThread,
                    onStartNew = { run { app.signalTransport.startConversation(it) } },
                    onStartAci = { aci, name -> run { app.signalTransport.startWithAci(aci, name) } })
                NewChatNetwork.INSTAGRAM -> InstagramPicker(app, query, busy) { u ->
                    run { app.instagramTransport.startConversationWithUser(u.pk, u.username) }
                }
            }
        }
    }
}

@Composable
private fun MessagesPicker(
    app: RelayApp, context: android.content.Context, query: String, busy: Boolean, onStart: (String) -> Unit,
) {
    var hasContacts by remember { mutableStateOf(Contacts.hasPermission(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasContacts = it }
    val contacts by produceState(emptyList<DeviceContact>(), hasContacts) {
        value = if (hasContacts) withContext(Dispatchers.IO) { Contacts.all(context) } else emptyList()
    }
    val emails by produceState(emptyList<DeviceContact>(), hasContacts) {
        value = if (hasContacts) withContext(Dispatchers.IO) { Contacts.emails(context) } else emptyList()
    }
    val filtered = remember(contacts, query) {
        val q = query.trim()
        if (q.isBlank()) contacts else {
            val d = q.filter { it.isDigit() }
            contacts.filter { it.name.contains(q, true) || (d.length >= 3 && it.number.filter { c -> c.isDigit() }.contains(d)) }
        }
    }
    val filteredEmails = remember(emails, query) {
        val q = query.trim()
        if (q.isBlank()) emails else emails.filter { it.name.contains(q, true) || it.number.contains(q, true) }
    }
    val looksLikeNumber = query.isNotBlank() && query.any { it.isDigit() } && query.all { it.isDigit() || it in "+-() ." }
    val looksLikeEmail = query.trim().let { it.contains("@") && it.substringAfter("@").contains(".") }
    if (!hasContacts) TextButton(onClick = { permLauncher.launch(android.Manifest.permission.READ_CONTACTS) }) {
        Text("Allow contacts to search people")
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (looksLikeNumber) item("raw") { ContactRow("Message ${query.trim()}", null, !busy) { onStart(query) }; Divider() }
        if (looksLikeEmail) item("rawEmail") {
            ContactRow("iMessage ${query.trim()}", null, !busy) { onStart(query.trim()) }; Divider()
        }
        items(filtered, key = { "n|" + it.name + "|" + it.number }) { c -> ContactRow(c.name, c.number, !busy) { onStart(c.number) }; Divider() }
        // Contact email addresses, shown as iMessage targets (blue).
        items(filteredEmails, key = { "e|" + it.name + "|" + it.number }) { c ->
            ContactRow(c.name, "${c.number} · iMessage", !busy) { onStart(c.number) }; Divider()
        }
    }
}

@Composable
private fun SignalPicker(
    app: RelayApp, query: String, busy: Boolean, onOpenThread: (Long) -> Unit,
    onStartNew: (String) -> Unit, onStartAci: (String, String) -> Unit,
) {
    val allConvos by app.repository.conversations().collectAsState(initial = emptyList())
    val convos = remember(allConvos, query) {
        allConvos.filter { it.transportId == "signal" && !it.externalId.startsWith("group:") }
            .filter { query.isBlank() || it.title.contains(query.trim(), true) }
    }
    val existingAcis = remember(allConvos) {
        allConvos.filter { it.transportId == "signal" }.map { it.externalId }.toSet()
    }
    val groupMemberAcis by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) { runCatching { app.repository.signalGroupMemberAcis() }.getOrDefault(emptyList()) }
    }
    val members = remember(groupMemberAcis, existingAcis, query) {
        groupMemberAcis.filter { it !in existingAcis }
            .map { aci -> aci to (app.contactNames.get(aci) ?: "Signal user") }
            .filter { query.isBlank() || it.second.contains(query.trim(), true) }
            .sortedBy { it.second }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(convos, key = { "c${it.id}" }) { c -> ContactRow(c.title, null, !busy) { onOpenThread(c.id) }; Divider() }
        if (members.isNotEmpty()) item("hdr") {
            Text("From your groups", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
        }
        items(members, key = { "m${it.first}" }) { (aci, name) -> ContactRow(name, "In your groups", !busy) { onStartAci(aci, name) }; Divider() }
        if (query.isNotBlank()) item("new") {
            ContactRow("New Signal chat: ${query.trim()}", null, !busy) { onStartNew(query) }
        }
    }
}

@Composable
private fun InstagramPicker(app: RelayApp, query: String, busy: Boolean, onPick: (InstagramApi.IgUser) -> Unit) {
    val results by produceState(emptyList<InstagramApi.IgUser>(), query) {
        if (query.isBlank()) { value = emptyList(); return@produceState }
        delay(350) // debounce keystrokes
        value = app.instagramTransport.searchUsers(query.trim())
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { it.pk }) { u ->
            ContactRow(u.fullName.ifBlank { u.username }, "@${u.username}", !busy) { onPick(u) }
            Divider()
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

@Composable
private fun ContactRow(title: String, subtitle: String?, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
