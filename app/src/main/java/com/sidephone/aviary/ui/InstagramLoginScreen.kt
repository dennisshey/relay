package com.sidephone.aviary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sidephone.aviary.RelayApp
import com.sidephone.aviary.transport.instagram.InstagramTransport
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstagramLoginScreen(app: RelayApp, onBack: () -> Unit) {
    val transport = app.instagramTransport
    val setup by transport.setup.collectAsState()
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Instagram") },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val s = setup) {
                is InstagramTransport.SetupState.Ready -> {
                    Text("✅", style = MaterialTheme.typography.displayMedium)
                    Text("Instagram is connected", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Your DMs appear in the unified inbox. This is an unofficial client — " +
                            "use a real login only if you accept the account-ban risk.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { transport.logout() }) { Text("Log out") }
                }

                is InstagramTransport.SetupState.AwaitingTwoFactor -> {
                    Text("Enter the code", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Instagram sent a 2FA code to ${s.username}'s trusted method.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.filter(Char::isDigit).take(8) },
                        label = { Text("6-digit code") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (busy) CircularProgressIndicator() else Button(
                        enabled = code.length >= 6,
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                val r = transport.submitTwoFactor(code)
                                busy = false
                                error = r.exceptionOrNull()?.message
                            }
                        },
                    ) { Text("Verify") }
                }

                is InstagramTransport.SetupState.Challenge -> {
                    Text("Verification needed", style = MaterialTheme.typography.titleLarge)
                    Text(s.note, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { transport.logout() }) { Text("Start over") }
                }

                is InstagramTransport.SetupState.LoggedOut -> {
                    Text("Log in to Instagram", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Meta has no official DM API, so this signs in as the mobile app. " +
                            "Credentials are encrypted on-device and never sent to a relay.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = username, onValueChange = { username = it.trim() },
                        label = { Text("Username") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                    if (busy) CircularProgressIndicator() else Button(
                        enabled = username.isNotBlank() && password.isNotBlank(),
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                val r = transport.login(username, password)
                                busy = false
                                // 2FA switches the screen via setup state; only show real errors.
                                if (r.isFailure && setup is InstagramTransport.SetupState.LoggedOut) {
                                    error = r.exceptionOrNull()?.message
                                }
                            }
                        },
                    ) { Text("Log in") }
                }
            }
        }
    }
}
