package com.sidephone.aviary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sidephone.aviary.RelayApp
import com.sidephone.aviary.transport.imessage.IMessageTransport
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IMessageSetupScreen(app: RelayApp, onBack: () -> Unit) {
    val transport = app.imessageTransport
    val state by transport.setup.collectAsState()
    val scope = rememberCoroutineScope()

    // Pre-fill the saved Mac config so the user never has to re-paste it (re-pasting is the main
    // source of "bad Mac config" errors — a mangled/wrapped copy).
    var macConfig by remember { mutableStateOf(transport.savedMacConfig()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var twoFa by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Set up iMessage") }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                is IMessageTransport.SetupState.Registering -> {
                    Spacer(Modifier.size(32.dp))
                    CircularProgressIndicator()
                    Text("Registering with Apple IDS…", textAlign = TextAlign.Center)
                }

                is IMessageTransport.SetupState.LoggingIn -> {
                    Spacer(Modifier.size(32.dp))
                    CircularProgressIndicator()
                    Text("Signing in to Apple…", textAlign = TextAlign.Center)
                }

                is IMessageTransport.SetupState.Ready -> {
                    Spacer(Modifier.size(32.dp))
                    Text("✅", style = MaterialTheme.typography.displayMedium)
                    Text("iMessage is set up", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Messages to iMessage users will now go blue. Everything is signed " +
                            "and encrypted on this phone.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is IMessageTransport.SetupState.Needs2FA -> {
                    Text(
                        "Enter the 6-digit verification code Apple just sent to your " +
                            "trusted devices (${s.kind}).",
                        textAlign = TextAlign.Center,
                    )
                    OutlinedTextField(
                        value = twoFa,
                        onValueChange = { twoFa = it },
                        label = { Text("2FA code") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { scope.launch { transport.submit2fa(twoFa.trim()) } },
                        enabled = twoFa.trim().length >= 6,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Verify") }
                }

                else -> {
                    // Idle / NeedsConfig / Failed → the credential form.
                    Text(
                        "iMessage runs entirely on this phone. It needs your Apple ID and a " +
                            "one-time hardware code from a Mac you own: run OpenBubbles' " +
                            "“Mac Hardware Info” app, copy the code, and paste it below. " +
                            "Nothing runs on a server afterward.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedTextField(
                        value = macConfig, onValueChange = { macConfig = it },
                        label = { Text("Mac hardware code") },
                        minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(4.dp))
                    OutlinedTextField(
                        value = email, onValueChange = { email = it },
                        label = { Text("Apple ID email") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Apple ID password") },
                        visualTransformation = if (showPassword)
                            androidx.compose.ui.text.input.VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (s is IMessageTransport.SetupState.Failed) {
                        Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                transport.configure(macConfig.trim())
                                transport.login(email.trim(), password)
                            }
                        },
                        enabled = macConfig.isNotBlank() &&
                            email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Sign in") }

                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Stuck on activation, or switching accounts? Reset wipes the saved " +
                            "iMessage keys for a clean sign-in (your Mac code is kept).",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    androidx.compose.material3.OutlinedButton(
                        onClick = { transport.resetAccountAndRestart() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Reset iMessage & restart") }
                }
            }
        }
    }
}
