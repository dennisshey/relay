package com.sidephone.aviary.transport.sms

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import com.sidephone.aviary.RelayApp
import kotlinx.coroutines.launch

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.toList() ?: return
        val app = context.applicationContext as RelayApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.smsTransport.onSmsReceived(messages)
            } finally {
                pending.finish()
            }
        }
    }
}

class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        // The M-Notification.ind PDU (sender + content-location) is the "data" extra.
        val pdu = intent.getByteArrayExtra("data")
        val app = context.applicationContext as RelayApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.smsTransport.onWapPush(pdu)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Fired by the platform when a queued MMS download finishes (or fails). */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RelayApp
        val resultOk = resultCode == android.app.Activity.RESULT_OK
        val filePath = intent.getStringExtra(EXTRA_FILE) ?: return
        val from = intent.getStringExtra(EXTRA_FROM)
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.smsTransport.onMmsDownloaded(filePath, from, resultOk)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.sidephone.aviary.MMS_DOWNLOADED"
        const val EXTRA_FILE = "file"
        const val EXTRA_FROM = "from"
    }
}

/** Fired by the platform when an outbound MMS send finishes (or fails). */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RelayApp
        val ok = resultCode == android.app.Activity.RESULT_OK
        val rowId = intent.getLongExtra(EXTRA_ROW, -1L)
        if (rowId < 0) return
        val filePath = intent.getStringExtra(EXTRA_FILE)
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.smsTransport.onMmsSent(rowId, filePath, ok)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.sidephone.aviary.MMS_SENT"
        const val EXTRA_ROW = "row"
        const val EXTRA_FILE = "file"
    }
}

/**
 * Required by the default-SMS-app role: lets the dialer's "reply with message"
 * quick-responses send through us.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recipient = intent?.data?.schemeSpecificPart
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (recipient != null && !body.isNullOrBlank()) {
            val app = application as RelayApp
            app.appScope.launch {
                app.smsTransport.startConversation(recipient).onSuccess { convoId ->
                    app.repository.getConversation(convoId)?.let { convo ->
                        app.smsTransport.sendText(convo, body)
                    }
                }
                stopSelf(startId)
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
