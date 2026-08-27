package com.sidephone.aviary.data

/**
 * Detects a real media MIME type from a file's leading bytes (magic numbers), and derives a
 * sensible extension/filename. Android's content-resolver sometimes hands back a null or generic
 * `application/octet-stream` for picked media; sending that to iMessage makes the recipient see a
 * nameless, unopenable "attachment" instead of an inline image — so we sniff and fix it up.
 */
object MediaSniff {

    fun mime(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> "image/jpeg"
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> "image/png"
            b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 -> "image/gif"
            b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 &&
                b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> "image/webp"
            // ISO base media (…ftyp…): distinguish HEIC vs QuickTime vs MP4 by the brand.
            b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70 -> {
                val brand = String(bytes, 8, 4, Charsets.ISO_8859_1).lowercase()
                when {
                    brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("hev") -> "image/heic"
                    brand.startsWith("qt") -> "video/quicktime"
                    else -> "video/mp4"
                }
            }
            else -> null
        }
    }

    /** A concrete MIME: use [provided] unless it's null/blank/generic, else sniff [bytes]. */
    fun resolveMime(provided: String?, bytes: ByteArray): String {
        if (!provided.isNullOrBlank() && provided != "application/octet-stream" &&
            provided != "content" && provided != "*/*"
        ) return provided
        return mime(bytes) ?: provided?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    }

    fun ext(mime: String): String = when {
        mime == "image/jpeg" -> "jpg"
        mime == "image/png" -> "png"
        mime == "image/gif" -> "gif"
        mime == "image/webp" -> "webp"
        mime == "image/heic" || mime == "image/heif" -> "heic"
        mime == "video/mp4" -> "mp4"
        mime == "video/quicktime" -> "mov"
        mime.startsWith("audio/") -> "m4a"
        mime == "application/pdf" -> "pdf"
        else -> "dat"
    }

    /** A filename with the right extension; keeps [base] if it already has one. */
    fun fileName(mime: String, base: String?): String {
        val name = base?.takeIf { it.isNotBlank() } ?: "attachment"
        return if (name.contains('.')) name else "$name.${ext(mime)}"
    }
}
