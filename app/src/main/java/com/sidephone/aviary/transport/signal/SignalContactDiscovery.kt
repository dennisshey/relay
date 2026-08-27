package com.sidephone.aviary.transport.signal

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.signal.libsignal.net.CdsiLookupRequest
import org.signal.libsignal.net.Network
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import java.util.Optional
import java.util.function.Consumer

/**
 * Maps a phone number (E.164) to its Signal ACI via CDSI (contact discovery). libsignal's Network
 * client performs the SGX-attested lookup internally; we just fetch short-lived CDSI credentials
 * from the chat server first. Used to start a conversation with someone by phone number.
 */
class SignalContactDiscovery(
    private val account: SignalAccount,
    private val http: OkHttpClient,
    private val baseUrl: String = "https://chat.signal.org",
) {
    /** The recipient's ACI (bare UUID string), or null if they're not on Signal / lookup failed. */
    fun aciFor(e164: String): String? {
        val creds = fetchCreds() ?: return null
        return runCatching {
            val network = Network(Network.Environment.PRODUCTION, USER_AGENT)
            val request = CdsiLookupRequest(
                emptySet(),                 // previousE164s
                setOf(e164),                // newE164s
                emptyMap<ServiceId, ProfileKey>(),
                Optional.empty(),           // token
            )
            val response = network.cdsiLookup(creds.first, creds.second, request, Consumer<ByteArray> { }).get()
            response.entries()[e164]?.aci?.rawUUID?.toString()
        }.onFailure { Log.w(TAG, "cdsi lookup failed", it) }.getOrNull()
    }

    /** Resolve a Signal @username to its ACI via the username-hash lookup (no CDSI needed). */
    fun aciForUsername(username: String): String? {
        val handle = username.trim().removePrefix("@")
        return runCatching {
            val hash = org.signal.libsignal.usernames.Username(handle).hash
            val b64 = android.util.Base64.encodeToString(
                hash, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            )
            val req = Request.Builder()
                .url("$baseUrl/v1/accounts/username_hash/$b64")
                .header("Authorization", "Basic ${account.authToken()}")
                .get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "username lookup HTTP ${resp.code}"); return null }
                JSONObject(resp.body!!.string()).optString("uuid").ifBlank { null }
            }
        }.onFailure { Log.w(TAG, "username lookup failed", it) }.getOrNull()
    }

    private fun fetchCreds(): Pair<String, String>? = runCatching {
        val req = Request.Builder()
            .url("$baseUrl/v2/directory/auth")
            .header("Authorization", "Basic ${account.authToken()}")
            .get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w(TAG, "cdsi auth HTTP ${resp.code}"); return null }
            val j = JSONObject(resp.body!!.string())
            j.getString("username") to j.getString("password")
        }
    }.getOrNull()

    companion object {
        private const val TAG = "SignalCDS"
        private const val USER_AGENT = "Signal-Android/7.10.0"
    }
}
