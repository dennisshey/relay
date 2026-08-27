package com.sidephone.aviary.transport

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for the battery-optimization exemption. Without it, Doze and OEM battery managers
 * throttle/kill the background process, so notifications arrive late or not at all. Being on
 * the exemption list is the single biggest lever for reliable background delivery.
 */
object BatteryOptimization {

    fun isIgnoring(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** System dialog that asks the user to exempt this app from battery optimization. */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
}
