package com.sidephone.aviary.transport

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Watchdog: MediaTek/OEM battery managers aggressively kill background apps, which would stop
 * the [ReceiveService] and silently break notifications until the app is reopened. WorkManager
 * survives process death and app kills, so a periodic job (15 min — the platform minimum) simply
 * re-starts the receive service. [ReceiveService.start] is idempotent, so a no-op when it's alive.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ReceiveService.start(applicationContext)
        // Opportunistic housekeeping while we're awake: keep the media cache under budget.
        runCatching { com.sidephone.aviary.data.MediaStore(applicationContext).enforceBudget() }
        return Result.success()
    }

    companion object {
        private const val NAME = "receive-watchdog"

        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, work,
            )
        }
    }
}
