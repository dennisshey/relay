package com.sidephone.aviary.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the receive service (and re-arm the watchdog) after a reboot or an app update, so
 *  notifications resume without the user having to open the app first. Starting the process runs
 *  [com.sidephone.aviary.RelayApp.onCreate], which sweeps the outbox. That sweep must not resend
 *  failures whose first attempt is outside [com.sidephone.aviary.transport.OutboxPolicy.AUTO_RETRY_WINDOW_MS]. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (isBootOrPackageReplaced(intent.action)) {
            ReceiveService.start(context)
            WatchdogWorker.schedule(context)
        }
    }
}
