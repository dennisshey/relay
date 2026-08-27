package com.sidephone.aviary.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.sidephone.aviary.RelayApp
import kotlinx.coroutines.launch

/** Handles the notification's inline Reply and Mark-As-Read actions. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RelayApp
        val convoId = intent.getLongExtra(EXTRA_CONVERSATION, -1L)
        if (convoId < 0) return
        when (intent.action) {
            ACTION_MARK_READ -> {
                Notifier.cancel(context, convoId)
                app.appScope.launch { app.repository.markRead(convoId) }
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) {
                    Notifier.cancel(context, convoId); return
                }
                val pending = goAsync()
                app.appScope.launch {
                    try {
                        val convo = app.repository.getConversation(convoId)
                        if (convo != null) {
                            app.router.resolve(convo).sendText(convo, text)
                            app.repository.markRead(convoId)
                        }
                    } finally {
                        // Clear the notification (removes the RemoteInput spinner too).
                        Notifier.cancel(context, convoId)
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "com.sidephone.aviary.action.MARK_READ"
        const val ACTION_REPLY = "com.sidephone.aviary.action.REPLY"
        const val EXTRA_CONVERSATION = "conversationId"
        const val KEY_REPLY = "key_reply_text"
    }
}
