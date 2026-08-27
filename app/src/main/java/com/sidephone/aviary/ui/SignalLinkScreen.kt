package com.sidephone.aviary.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.sidephone.aviary.RelayApp
import com.sidephone.aviary.transport.signal.SignalTransport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalLinkScreen(app: RelayApp, onBack: () -> Unit) {
    val state by app.signalTransport.linkState.collectAsState()

    LaunchedEffect(Unit) { app.signalTransport.beginLinking() }
    DisposableEffect(Unit) {
        onDispose {
            if (state !is SignalTransport.LinkState.Linked) {
                app.signalTransport.cancelLinking()
            }
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
                title = { Text("Link Signal") }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val s = state) {
                is SignalTransport.LinkState.Idle,
                is SignalTransport.LinkState.Connecting -> {
                    Spacer(Modifier.size(48.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.size(16.dp))
                    Text("Contacting Signal's provisioning service…")
                }

                is SignalTransport.LinkState.AwaitingScan -> {
                    Text(
                        "On your primary Signal phone:\nSettings → Linked devices → Link new device",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.size(24.dp))
                    val qr = remember(s.uri) { qrBitmap(s.uri, 800) }
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "Signal linking QR code",
                        modifier = Modifier
                            .size(280.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    )
                    Spacer(Modifier.size(16.dp))
                    Text(
                        "This QR code contains a one-time key generated on this phone. " +
                            "Nothing is sent anywhere except to Signal's own servers.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                is SignalTransport.LinkState.Registering -> {
                    Spacer(Modifier.size(48.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.size(16.dp))
                    Text(
                        "QR scanned — registering this device with Signal…",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                is SignalTransport.LinkState.Linked -> {
                    Spacer(Modifier.size(48.dp))
                    Text("✅", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Linked as device ${s.deviceId}",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Relay now appears in Signal → Linked devices on ${s.number}. " +
                            "Identity keys and credentials are stored encrypted on this " +
                            "device. Message sync is the next milestone (experimental).",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                is SignalTransport.LinkState.Failed -> {
                    Spacer(Modifier.size(48.dp))
                    Text(
                        "Linking failed",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(s.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = { app.signalTransport.beginLinking() }) { Text("Retry") }
                }
            }
        }
    }
}

private fun qrBitmap(content: String, sizePx: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(
                x, y,
                if (matrix.get(x, y)) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            )
        }
    }
    return bitmap
}
