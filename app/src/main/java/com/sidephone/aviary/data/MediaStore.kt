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

    /** Stage an attachment with constant memory, cleaning up failed copies. */
    fun importStream(input: java.io.InputStream, contentType: String?): File {
        val staging = File.createTempFile("incoming-", ".tmp", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            staging.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_ATTACHMENT_BYTES) { "Attachment exceeds Relay's 1 GB limit" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            val hash = digest.digest().take(20).joinToString("") { "%02x".format(it) }
            val target = File(dir, "$hash.${extensionFor(contentType)}")
            if (!target.exists()) check(staging.renameTo(target)) { "Couldn't save attachment" }
            target.setLastModified(System.currentTimeMillis())
            return target
        } finally { staging.delete() }
    }

    fun importFile(file: File, contentType: String?): File {
        require(file.isFile && file.length() <= MAX_ATTACHMENT_BYTES) { "Attachment is missing or exceeds Relay's 1 GB limit" }
        if (file.canonicalFile.parentFile == dir.canonicalFile && !file.name.endsWith(".tmp")) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        return file.inputStream().use { importStream(it, contentType) }
    }

    /**
     * Cap the on-disk media cache: if the store exceeds [maxBytes], delete the oldest files
     * (by last-modified) until it's back under budget. Content is re-downloadable per transport,
     * so eviction only affects very old attachments. Runs off the main thread by the caller.
     */
    fun enforceBudget(maxBytes: Long = DEFAULT_BUDGET_BYTES, protectedPaths: Set<String> = emptySet()) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (f.absolutePath in protectedPaths ||
                f.lastModified() > System.currentTimeMillis() - 60 * 60 * 1000L) continue
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
        const val MAX_ATTACHMENT_BYTES = 1024L * 1024 * 1024

        /** Default media-cache budget (~250 MB) before oldest attachments are evicted. */
        const val DEFAULT_BUDGET_BYTES = 250L * 1024 * 1024
    }
}
