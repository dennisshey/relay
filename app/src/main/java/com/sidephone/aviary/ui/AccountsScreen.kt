package com.sidephone.aviary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sidephone.aviary.RelayApp
import com.sidephone.aviary.transport.BatteryOptimization
import com.sidephone.aviary.transport.MessageTransport
import com.sidephone.aviary.transport.TransportStatus
import com.sidephone.aviary.data.Protocol
import com.sidephone.aviary.transport.imessage.IMessageTransport
import com.sidephone.aviary.transport.signal.SignalTransport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    app: RelayApp,
    onLinkSignal: () -> Unit,
    onSetupImessage: () -> Unit,
    onLoginInstagram: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Accounts") }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Everything stays on this phone: credentials and messages are stored " +
                    "encrypted on-device and never pass through a relay server.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // Battery-optimization exemption — the biggest lever for reliable background delivery.
            val context = LocalContext.current
            var batteryOk by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
            val batteryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { batteryOk = BatteryOptimization.isIgnoring(context) }
            if (!batteryOk) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Allow unrestricted background", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Android may kill Messenger in the background, which delays or drops " +
                                "notifications. Exempt it from battery optimization for reliable delivery.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = { batteryLauncher.launch(BatteryOptimization.requestIntent(context)) }) {
                            Text("Allow")
                        }
                    }
                }
            }

            app.transports.all().forEach { transport ->
                val isSignal = transport is SignalTransport
                val isImessage = transport is IMessageTransport
                val isInstagram = transport is com.sidephone.aviary.transport.instagram.InstagramTransport
                TransportCard(
                    transport = transport,
                    action = when {
                        isSignal -> onLinkSignal
                        isImessage -> onSetupImessage
                        isInstagram -> onLoginInstagram
                        else -> null
                    },
                    actionLabel = when {
                        isSignal && app.signalTransport.isRegistered() -> "Re-link device"
                        isSignal -> "Link device"
                        isImessage -> "Set up iMessage"
                        isInstagram -> "Log in"
                        else -> "Link device"
                    },
                    alwaysShowAction = isSignal || isImessage || isInstagram,
                )
            }

            Spacer(Modifier.size(16.dp))
            Text(
                "i ♥ elliana",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TransportCard(
    transport: MessageTransport,
    action: (() -> Unit)?,
    actionLabel: String = "Link device",
    alwaysShowAction: Boolean = false,
) {
    val status by transport.status.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(12.dp)
                        .background(transport.protocol.color, CircleShape)
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    transport.protocol.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.size(6.dp))
            val (label, detail) = when (val s = status) {
                is TransportStatus.Ready -> "Connected" to "Working normally."
                is TransportStatus.NeedsSetup -> "Needs setup" to s.reason
                is TransportStatus.Linking -> "Linking" to s.step
                is TransportStatus.Planned -> "Planned" to s.note
                is TransportStatus.Error -> "Error" to s.message
            }
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = if (status is TransportStatus.Ready)
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            if (action != null && (alwaysShowAction || status !is TransportStatus.Ready)) {
                Spacer(Modifier.size(10.dp))
                Button(onClick = action) { Text(actionLabel) }
            }
        }
    }
}
