package com.sidephone.aviary.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * On-disk store for decrypted avatar images, keyed by a conversation's external
 * id (a contact ACI or a "group:<masterKey>" id). Files live in the app's private
 * storage; the decrypted images never leave the device.
 */
class AvatarStore(context: Context) {

    private val dir = File(context.filesDir, "avatars").apply { mkdirs() }

    // Cache the resolved path per key ("" = known-absent) so scrolling the inbox doesn't hash +
    // stat the filesystem on the UI thread for every row. Invalidated on save().
    private val pathCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = digest.take(16).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.jpg")
    }

    fun has(key: String): Boolean = path(key) != null

    fun save(key: String, jpeg: ByteArray) {
        file(key).writeBytes(jpeg)
        pathCache.remove(key) // was possibly cached as absent; force a re-resolve
    }

    /** Absolute path if an avatar is stored for this key, else null. Cached to avoid per-row I/O. */
    fun path(key: String): String? {
        pathCache[key]?.let { return it.ifEmpty { null } }
        val resolved = file(key).takeIf { it.exists() }?.absolutePath ?: ""
        pathCache[key] = resolved
        return resolved.ifEmpty { null }
    }
}
