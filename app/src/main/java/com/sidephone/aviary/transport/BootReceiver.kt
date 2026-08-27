package com.sidephone.aviary.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the receive service (and re-arm the watchdog) after a reboot, so notifications
 *  resume without the user having to open the app first. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            ReceiveService.start(context)
            WatchdogWorker.schedule(context)
        }
    }
}
