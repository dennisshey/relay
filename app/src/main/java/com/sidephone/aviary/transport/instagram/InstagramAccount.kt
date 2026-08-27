package com.sidephone.aviary.transport.instagram

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persisted Instagram session — the tokens returned by a successful login, stored encrypted so
 * we resume without re-entering the password. `authorization` is Instagram's Bearer token
 * (`IG-Set-Authorization` header, a base64 blob carrying sessionid), which authenticates every
 * subsequent request; the www-claim + mid are anti-abuse headers IG expects echoed back.
 */
class InstagramAccount(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "instagram_account", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun str(key: String) = object {
        operator fun getValue(t: Any?, p: Any?): String? = prefs.getString(key, null)
        operator fun setValue(t: Any?, p: Any?, v: String?) =
            prefs.edit().apply { if (v == null) remove(key) else putString(key, v) }.apply()
    }

    var userId: String? by str("user_id")
    var username: String? by str("username")
    var authorization: String? by str("authorization") // IG-Set-Authorization header value
    var wwwClaim: String? by str("www_claim")          // x-ig-www-claim
    var mid: String? by str("mid")                     // x-mid / cookie
    var sessionId: String? by str("session_id")        // sessionid cookie
    var csrfToken: String? by str("csrf_token")
    var rur: String? by str("rur")

    val isLoggedIn: Boolean get() = authorization != null && userId != null

    fun clear() = prefs.edit().clear().apply()
}
