package com.sidephone.aviary

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sidephone.aviary.ui.AccountsScreen
import com.sidephone.aviary.ui.AviaryTheme
import com.sidephone.aviary.ui.InboxScreen
import com.sidephone.aviary.ui.NewMessageScreen
import com.sidephone.aviary.ui.IMessageSetupScreen
import com.sidephone.aviary.ui.SignalLinkScreen
import com.sidephone.aviary.ui.ThreadScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as RelayApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure the background receive service is up (foreground context — always allowed),
        // so notifications keep arriving after the app is backgrounded.
        com.sidephone.aviary.transport.ReceiveService.start(this)
        app.signalTransport.startReceiving()

        setContent {
            AviaryTheme {
                AviaryNav(app, intentConversationId(intent), intentSendToAddress(intent))
            }
        }
    }

    private fun intentConversationId(intent: Intent?): Long? =
        intent?.getLongExtra(EXTRA_CONVERSATION_ID, -1L)?.takeIf { it > 0 }

    private fun intentSendToAddress(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SENDTO || intent?.action == Intent.ACTION_VIEW) {
            intent.data?.schemeSpecificPart?.substringBefore('?')?.takeIf { it.isNotBlank() }
        } else null

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}

@Composable
private fun AviaryNav(app: RelayApp, openConversationId: Long?, sendToAddress: String?) {
    val nav = rememberNavController()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Ask for runtime permissions up front, then (re)start transports.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        scope.launch { app.transports.startAll() }
    }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        scope.launch { app.transports.startAll() }
    }
    var permissionsAsked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!permissionsAsked) {
            permissionsAsked = true
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.SEND_SMS,
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                )
            )
        }
    }

    // Deep links: notification tap or an external sms: intent.
    LaunchedEffect(openConversationId, sendToAddress) {
        when {
            openConversationId != null -> nav.navigate("thread/$openConversationId")
            sendToAddress != null -> {
                app.smsTransport.startConversation(sendToAddress).onSuccess {
                    nav.navigate("thread/$it")
                }
            }
        }
    }

    NavHost(navController = nav, startDestination = "inbox") {
        composable("inbox") {
            InboxScreen(
                app = app,
                onOpenThread = { nav.navigate("thread/$it") },
                onNewMessage = { nav.navigate("new") },
                onOpenAccounts = { nav.navigate("accounts") },
                onRequestDefaultSms = {
                    val rm = app.getSystemService(RoleManager::class.java)
                    if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                        roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                    }
                },
            )
        }
        composable(
            "thread/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            ThreadScreen(
                app = app,
                conversationId = entry.arguments!!.getLong("id"),
                onBack = { nav.popBackStack() },
                onOpenThread = { nav.navigate("thread/$it") },
            )
        }
        composable("new") {
            NewMessageScreen(
                app = app,
                onOpenThread = {
                    nav.navigate("thread/$it") { popUpTo("inbox") }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("accounts") {
            AccountsScreen(
                app = app,
                onLinkSignal = { nav.navigate("signal-link") },
                onSetupImessage = { nav.navigate("imessage-setup") },
                onLoginInstagram = { nav.navigate("instagram-login") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("signal-link") {
            SignalLinkScreen(app = app, onBack = { nav.popBackStack() })
        }
        composable("imessage-setup") {
            IMessageSetupScreen(app = app, onBack = { nav.popBackStack() })
        }
        composable("instagram-login") {
            com.sidephone.aviary.ui.InstagramLoginScreen(app = app, onBack = { nav.popBackStack() })
        }
    }
}
