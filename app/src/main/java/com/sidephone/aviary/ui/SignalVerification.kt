package com.sidephone.aviary.ui

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sidephone.aviary.RelayApp
import kotlinx.coroutines.launch

@Composable
fun SignalVerification(app: RelayApp) {
    val challenge by app.signalTransport.verificationChallenge.collectAsState()
    val scope = rememberCoroutineScope()
    if (challenge == null) return
    var open by remember(challenge) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    if (!open) {
        // Keep the action available without blocking access to other messaging transports.
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            Surface(tonalElevation = 8.dp, modifier = Modifier.navigationBarsPadding().padding(8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Signal sending paused for verification", Modifier.weight(1f))
                    TextButton(onClick = { open = true }) { Text("Verify Signal") }
                }
            }
        }
        return
    }
    Dialog(onDismissRequest = { if (!busy) open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().padding(12.dp)) {
                Text("Verify Signal", style = MaterialTheme.typography.titleLarge)
                Text("Complete Signal’s CAPTCHA below. Pending messages resume after verification succeeds.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                key(generation) {
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val url = request.url.toString()
                                        if (url.startsWith("signalcaptcha://")) {
                                            if (!busy) {
                                                busy = true; error = null
                                                scope.launch {
                                                    try {
                                                        app.signalTransport.verifyCaptcha(url)
                                                            .onSuccess { app.outbox.resumeAfterVerification("signal") }
                                                            .onFailure { error = it.message ?: "Verification failed. Please try again." }
                                                    } finally { busy = false }
                                                }
                                            }
                                            return true
                                        }
                                        return request.url.scheme != "https"
                                    }
                                }
                                loadUrl("https://signalcaptchas.org/challenge/generate.html")
                            }
                        },
                        onRelease = { it.stopLoading(); it.destroy() },
                    )
                }
                Row {
                    TextButton(enabled = !busy, onClick = { generation++; error = null }) { Text("Reload CAPTCHA") }
                    TextButton(enabled = !busy, onClick = { open = false }) { Text("Later") }
                }
            }
        }
    }
}
