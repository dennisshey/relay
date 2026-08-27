package com.sidephone.aviary.transport.signal

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin HTTP client for the Signal chat service — just the endpoints a linked
 * device needs. Signal doesn't publish its service layer as a library, so (like
 * signal-cli) we speak the REST API directly. All crypto/keys come from libsignal.
 *
 * NOTE: the exact request bodies are version-sensitive and can only be validated
 * against Signal's live servers with a real account. See docs/ROADMAP.md.
 */
class SignalApi(
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://chat.signal.org",
) {
    data class LinkResult(val aci: String, val pni: String, val deviceId: Int)

    /**
     * Registers this device as a linked device using the provisioning code from
     * the primary. Basic auth is (number:password) with a freshly generated
     * password; the server assigns our device id.
     */
    fun linkDevice(
        number: String,
        password: String,
        provisioningCode: String,
        encryptedDeviceName: String,
        aci: IdentityKeys,
        pni: IdentityKeys,
    ): LinkResult {
        val body = JSONObject().apply {
            put("verificationCode", provisioningCode)
            put("accountAttributes", JSONObject().apply {
                put("fetchesMessages", true)
                put("registrationId", aci.registrationId)
                put("pniRegistrationId", pni.registrationId)
                put("name", encryptedDeviceName)
                // Signal-Server rejects (409) a linked device that omits capabilities
                // the account requires. The map is {name: true}. "spqr" is required for
                // all new devices; the rest are declared so we never trigger a
                // capability "downgrade". See DeviceController.isCapabilityDowngrade.
                put("capabilities", JSONObject().apply {
                    put("spqr", true)
                    put("usernameChangeSyncMessage", true)
                    put("storage", true)
                    put("profiles_v2", true)
                    put("optionalPhoneNumber", true)
                })
            })
            put("aciSignedPreKey", signedPreKeyJson(aci.signedPreKeyUpload()))
            put("pniSignedPreKey", signedPreKeyJson(pni.signedPreKeyUpload()))
            put("aciPqLastResortPreKey", signedPreKeyJson(aci.lastResortKyberUpload()))
            put("pniPqLastResortPreKey", signedPreKeyJson(pni.lastResortKyberUpload()))
        }

        val basic = Base64.encodeToString("$number:$password".encodeToByteArray(), Base64.NO_WRAP)
        val request = Request.Builder()
            .url("$baseUrl/v1/devices/link")
            .header("Authorization", "Basic $basic")
            .put(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                android.util.Log.w("SignalApi", "link failed HTTP ${resp.code}: $text")
            }
            check(resp.isSuccessful) { "link failed: HTTP ${resp.code} $text" }
            val json = JSONObject(text)
            return LinkResult(
                aci = json.getString("uuid"),
                pni = json.getString("pni"),
                deviceId = json.getInt("deviceId"),
            )
        }
    }

    /** Uploads the batch of one-time prekeys for one identity after linking. */
    fun uploadOneTimeKeys(authToken: String, identity: String, keys: IdentityKeys) {
        val body = JSONObject().apply {
            put("preKeys", JSONArray().apply {
                keys.oneTimePreKeys.forEach { pk ->
                    put(JSONObject().apply {
                        put("keyId", pk.id)
                        put("publicKey", b64(pk.keyPair.publicKey.serialize()))
                    })
                }
            })
            put("pqPreKeys", JSONArray().apply {
                keys.oneTimeKyberPreKeys.forEach { pk ->
                    put(JSONObject().apply {
                        put("keyId", pk.id)
                        put("publicKey", b64(pk.keyPair.publicKey.serialize()))
                        put("signature", b64(pk.signature))
                    })
                }
            })
        }
        val request = Request.Builder()
            .url("$baseUrl/v2/keys?identity=$identity")
            .header("Authorization", "Basic $authToken")
            .put(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "prekey upload ($identity) failed: HTTP ${resp.code}" }
        }
    }

    /** Authenticated GET for diagnostics; returns "HTTP <code>: <body>". */
    fun debugGet(authToken: String, path: String): String {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Basic $authToken")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                "HTTP ${resp.code}: ${resp.body?.string()?.take(400)}"
            }
        }.getOrElse { "ERROR: ${it.message}" }
    }

    private fun signedPreKeyJson(key: UploadKey) = JSONObject().apply {
        put("keyId", key.keyId)
        put("publicKey", key.publicKeyB64())
        put("signature", key.signatureB64())
    }

    private fun b64(data: ByteArray) = Base64.encodeToString(data, Base64.NO_WRAP)

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
