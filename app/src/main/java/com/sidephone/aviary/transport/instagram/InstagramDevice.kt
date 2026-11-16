package com.sidephone.aviary.transport.instagram

import android.content.Context
import android.webkit.WebSettings
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

    private val appContext = context.applicationContext

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

    val appVersion = APP_VERSION
    val appVersionCode = APP_VERSION_CODE

    val userAgent: String get() = Companion.userAgent()

    /**
     * User-Agent for the instagram.com login. The system WebView default is a normal mobile
     * Chrome string, which is what the website expects. The legacy private `accounts/login/`
     * call identifies as the Android app and is rejected as out of date for every pinned version.
     */
    fun browserUserAgent(): String {
        cachedBrowserUserAgent?.let { return it }
        val resolved = try {
            WebSettings.getDefaultUserAgent(appContext)
        } catch (_: Throwable) {
            null
        }?.takeIf { it.isNotBlank() } ?: WEB_USER_AGENT
        cachedBrowserUserAgent = resolved
        return resolved
    }

    @Volatile private var cachedBrowserUserAgent: String? = null

    companion object {
        // Instagram Android 454, the build mautrix-meta (2026-11-16) and igflare (2026-11-13)
        // present. instagrapi 3.2.15 is one release behind, at 449. Bump these together.
        const val APP_VERSION = "454.0.0.54.77"
        const val APP_VERSION_CODE = "1105698528"
        const val BLOKS_VERSION_ID = "8e8a63073c08f2c7e6701e497d777db5d8d7ad32a5788ce6b1094f20cad0c9f1"
        const val ANDROID_VERSION = 34
        const val ANDROID_RELEASE = "14"
        const val DPI = "480dpi"
        const val RESOLUTION = "1344x2992"
        const val MANUFACTURER = "Google/google"
        const val MODEL = "Pixel 8 Pro"
        const val DEVICE = "husky"
        const val CPU = "husky"

        /** Android app id. Paired with [userAgent] on private API calls after login. */
        const val APP_ID = "567067343352427"
        const val APP_ID_ANDROID = APP_ID
        const val WEB_APP_ID = "936619743392459"
        const val CAPABILITIES = "3brTv10C"

        /**
         * Fallback only, when [browserUserAgent] cannot read the system WebView user agent.
         * A normal mobile Chrome UA — not an Instagram app version. Verified against
         * www.instagram.com/accounts/login/ on 2026-11-16: the page still returns the
         * version-10 password key.
         */
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun userAgent(): String =
            "Instagram $APP_VERSION Android ($ANDROID_VERSION/$ANDROID_RELEASE; $DPI; $RESOLUTION; " +
                "$MANUFACTURER; $MODEL; $DEVICE; $CPU; en_US; $APP_VERSION_CODE)"
    }
}
