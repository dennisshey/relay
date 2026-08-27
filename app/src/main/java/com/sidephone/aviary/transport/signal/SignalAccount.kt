package com.sidephone.aviary.transport.signal

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.signal.libsignal.protocol.IdentityKeyPair
import java.security.SecureRandom

/**
 * Persistent, Keystore-encrypted store for the linked Signal device's credentials.
 * Nothing here ever leaves the phone. Holds what we learn from provisioning plus
 * what the server assigns at link time.
 */
class SignalAccount(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "aviary_signal", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var number: String?
        get() = prefs.getString(NUMBER, null)
        set(v) = prefs.edit().putString(NUMBER, v).apply()

    var aci: String?
        get() = prefs.getString(ACI, null)
        set(v) = prefs.edit().putString(ACI, v).apply()

    var pni: String?
        get() = prefs.getString(PNI, null)
        set(v) = prefs.edit().putString(PNI, v).apply()

    var deviceId: Int
        get() = prefs.getInt(DEVICE_ID, -1)
        set(v) = prefs.edit().putInt(DEVICE_ID, v).apply()

    /** Randomly generated at link time; the server never sees it in the clear again. */
    var password: String?
        get() = prefs.getString(PASSWORD, null)
        set(v) = prefs.edit().putString(PASSWORD, v).apply()

    var aciRegistrationId: Int
        get() = prefs.getInt(ACI_REG_ID, -1)
        set(v) = prefs.edit().putInt(ACI_REG_ID, v).apply()

    var pniRegistrationId: Int
        get() = prefs.getInt(PNI_REG_ID, -1)
        set(v) = prefs.edit().putInt(PNI_REG_ID, v).apply()

    val isRegistered: Boolean get() = aci != null && deviceId > 0 && password != null

    fun storeIdentity(kind: String, keyPair: IdentityKeyPair) {
        prefs.edit()
            .putString("${kind}_identity", Base64.encodeToString(keyPair.serialize(), Base64.NO_WRAP))
            .apply()
    }

    fun identity(kind: String): IdentityKeyPair? =
        prefs.getString("${kind}_identity", null)
            ?.let { IdentityKeyPair(Base64.decode(it, Base64.NO_WRAP)) }

    /** Basic-auth credential used on the authenticated websocket / keys endpoints. */
    fun authToken(): String {
        val user = "${aci}.$deviceId"
        return Base64.encodeToString("$user:$password".encodeToByteArray(), Base64.NO_WRAP)
    }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val KIND_ACI = "aci"
        const val KIND_PNI = "pni"

        private const val NUMBER = "number"
        private const val ACI = "aci"
        private const val PNI = "pni"
        private const val DEVICE_ID = "device_id"
        private const val PASSWORD = "password"
        private const val ACI_REG_ID = "aci_reg_id"
        private const val PNI_REG_ID = "pni_reg_id"

        fun generatePassword(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return Base64.encodeToString(bytes, Base64.NO_WRAP).trimEnd('=')
        }
    }
}
