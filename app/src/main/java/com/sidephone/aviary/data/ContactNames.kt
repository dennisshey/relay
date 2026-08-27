package com.sidephone.aviary.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps a Signal participant id (ACI) to a resolved display name. Populated as
 * profiles resolve; read by the UI to label group message senders. Stored
 * Keystore-encrypted on-device.
 */
class ContactNames(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "aviary_names", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val cache = ConcurrentHashMap<String, String>().apply {
        prefs.all.forEach { (k, v) -> if (v is String) put(k, v) }
    }

    fun get(id: String): String? = cache[id]

    fun put(id: String, name: String) {
        cache[id] = name
        prefs.edit().putString(id, name).apply()
    }
}
