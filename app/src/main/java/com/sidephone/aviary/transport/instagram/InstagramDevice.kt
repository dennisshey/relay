package com.sidephone.aviary.transport.instagram

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import kotlin.random.Random

/**
 * A stable, per-install fake Android device that the Instagram private API is presented with.
 * Instagram ties sessions + challenges to a consistent device fingerprint, so all of these
 * ids are generated once and persisted. Values mirror what a real Instagram-for-Android build
 * sends so requests aren't flagged.
 */
class InstagramDevice(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "instagram_device", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun persistent(key: String, gen: () -> String): String =
        prefs.getString(key, null) ?: gen().also { prefs.edit().putString(key, it).apply() }

    /** "android-<16 hex>" — the primary device id Instagram keys sessions to. */
    val androidId: String get() = persistent("android_id") {
        "android-" + (0 until 16).joinToString("") { Random.nextInt(16).toString(16) }
    }
    val deviceId: String get() = persistent("device_id") { UUID.randomUUID().toString() }
    val phoneId: String get() = persistent("phone_id") { UUID.randomUUID().toString() }
    val familyDeviceId: String get() = persistent("family_id") { UUID.randomUUID().toString() }

    // A concrete, common device profile (Pixel-class) baked into the User-Agent.
    val appVersion = "269.0.0.18.75"
    val appVersionCode = "314665256"
    private val androidVersion = 30
    private val androidRelease = "11"
    private val dpi = "420dpi"
    private val resolution = "1080x2340"
    private val manufacturer = "Google"
    private val model = "Pixel 4"
    private val device = "flame"
    private val cpu = "flame"

    val userAgent: String get() =
        "Instagram $appVersion Android ($androidVersion/$androidRelease; $dpi; $resolution; " +
            "$manufacturer; $model; $device; $cpu; en_US; $appVersionCode)"

    companion object {
        const val APP_ID = "936619743392459"
        const val CAPABILITIES = "3brTvw=="
    }
}
