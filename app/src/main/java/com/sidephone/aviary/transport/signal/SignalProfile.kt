package com.sidephone.aviary.transport.signal

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves a Signal contact's display name and avatar from their profile. Senders
 * include their profile key in messages (DataMessage.profileKey); with it we fetch
 * the versioned profile, decrypt the name, and download + decrypt the avatar image.
 * Everything is decrypted on-device.
 */
class SignalProfile(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://chat.signal.org",
    private val cdnUrl: String = "https://cdn.signal.org",
) {
    data class Info(val name: String?, val avatar: ByteArray?)

    fun fetch(authToken: String, aci: String, profileKey: ByteArray): Info = runCatching {
        val key = ProfileKey(profileKey)
        val serviceId = ServiceId.Aci.parseFromString(aci)
        val version = key.getProfileKeyVersion(serviceId).serialize()

        val request = Request.Builder()
            .url("$baseUrl/v1/profile/$aci/$version")
            .header("Authorization", "Basic $authToken")
            .get()
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return Info(null, null)
            resp.body?.string() ?: return Info(null, null)
        }
        val json = JSONObject(body)
        val name = json.optString("name", "").ifEmpty { null }
            ?.let { decryptGcm(Base64.decode(it, Base64.NO_WRAP), profileKey) }
            ?.let { plain -> plain.takeWhile { it.toInt() != 0 }.toByteArray().decodeToString() }
            ?.ifBlank { null }
        val avatarPath = json.optString("avatar", "").ifEmpty { null }
        val avatar = avatarPath?.let { downloadAvatar(it, profileKey) }
        Info(name, avatar)
    }.onFailure { Log.w("SignalProfile", "profile fetch failed for $aci: ${it.message}") }
        .getOrDefault(Info(null, null))

    private fun downloadAvatar(path: String, profileKey: ByteArray): ByteArray? {
        val request = Request.Builder().url("$cdnUrl/$path").get().build()
        val encrypted = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null else resp.body?.bytes() ?: return null
        }
        return decryptGcm(encrypted, profileKey)
    }

    /** AES-256-GCM with the profile key: [12-byte nonce][ciphertext][16-byte tag]. */
    private fun decryptGcm(data: ByteArray, profileKey: ByteArray): ByteArray? {
        if (data.size < 12 + 16) return null
        val nonce = data.copyOfRange(0, 12)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(profileKey, "AES"), GCMParameterSpec(128, nonce))
        }.doFinal(data.copyOfRange(12, data.size))
    }
}
