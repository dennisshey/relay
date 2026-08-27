package com.sidephone.aviary.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * On-disk store for decrypted message attachments. Files are content-addressed
 * (SHA-256 of the bytes) so identical media is stored once. Lives in the app's
 * private storage; decrypted media never leaves the device.
 */
class MediaStore(context: Context) {

    private val dir = File(context.filesDir, "media").apply { mkdirs() }

    /** Save decrypted bytes and return the absolute file path. */
    fun save(bytes: ByteArray, contentType: String?): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(20).joinToString("") { "%02x".format(it) }
        val file = File(dir, "$hash.${extensionFor(contentType)}")
        if (!file.exists()) file.writeBytes(bytes)
        return file.absolutePath
    }

    /**
     * Cap the on-disk media cache: if the store exceeds [maxBytes], delete the oldest files
     * (by last-modified) until it's back under budget. Content is re-downloadable per transport,
     * so eviction only affects very old attachments. Runs off the main thread by the caller.
     */
    fun enforceBudget(maxBytes: Long = DEFAULT_BUDGET_BYTES) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun extensionFor(contentType: String?): String = when {
        contentType == null -> "bin"
        contentType.startsWith("image/") -> contentType.substringAfter('/').substringBefore('+')
        contentType.startsWith("video/") -> contentType.substringAfter('/')
        contentType.startsWith("audio/") -> contentType.substringAfter('/')
        else -> "bin"
    }

    companion object {
        /** Default media-cache budget (~250 MB) before oldest attachments are evicted. */
        const val DEFAULT_BUDGET_BYTES = 250L * 1024 * 1024
    }
}
