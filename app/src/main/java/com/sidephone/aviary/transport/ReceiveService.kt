package com.sidephone.aviary.transport

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sidephone.aviary.RelayApp
import com.sidephone.aviary.R
import kotlinx.coroutines.launch

/**
 * Keeps the app process alive in the background so every transport (iMessage poll, Signal
 * socket, SMS) keeps receiving and posting notifications while the UI is closed. Modern
 * Android kills a backgrounded process within minutes and only exempts a foreground service,
 * which is why message notifications were unstable without one. The required ongoing
 * notification lives on a low-importance channel so it stays out of the way.
 */
class ReceiveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, RelayApp.CHANNEL_BACKGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Messenger")
            .setContentText("Receiving messages")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Make sure every transport's receive loop is running (all are idempotent). Covers the
        // case where the OS restarted just this service (START_STICKY) after killing the process.
        val app = application as RelayApp
        app.appScope.launch { runCatching { app.transports.startAll() } }
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, ReceiveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
