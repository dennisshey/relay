package com.sidephone.aviary.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * The message store is encrypted at rest with SQLCipher. The passphrase is random,
 * generated on first launch, and stored only in EncryptedSharedPreferences backed by
 * the Android Keystore — it never leaves the device. (Direct lesson from the 2023
 * Sunbird incident: nothing sensitive may exist outside the handset.)
 */
object DbCrypto {
    private const val PREFS = "aviary_secure"
    private const val KEY = "db_passphrase_hex"

    fun passphrase(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context, PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val existing = prefs.getString(KEY, null)
        if (existing != null) return existing.hexToBytes()
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, fresh.toHex()).apply()
        return fresh
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
