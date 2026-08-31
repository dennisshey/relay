package com.sidephone.aviary.transport.sms

/**
 * Minimal MMS PDU decoder (OMA MMS Encapsulation over WSP) — just enough to read an
 * inbound notification (M-Notification.ind: sender + content-location URL) and the
 * retrieved message (M-Retrieve.conf: sender + text + image parts). Hand-rolled in the
 * spirit of [MiniProto]; no external MMS library. Part MIME is taken from the part
 * header, falling back to sniffing the data bytes, so image extraction is robust even
 * when a carrier's content-type encoding is unusual.
 */
object MmsPdu {

    data class Notification(
        val from: String?,
        val contentLocation: String?,
        val transactionId: String?,
        val subject: String?,
    )

    data class Part(val contentType: String, val data: ByteArray)
    data class Retrieved(
        val from: String?,
        /** To + Cc recipients (everyone the message was addressed to, which for an inbound group
         *  MMS includes you and the other members — used to reconstruct the group thread). */
        val to: List<String>,
        val subject: String?,
        val dateSeconds: Long?,
        val parts: List<Part>,
    ) {
        /** Concatenated text parts (SMIL layout excluded). */
        val text: String get() = parts.filter { it.contentType.startsWith("text/") }
            .joinToString("\n") { String(it.data, Charsets.UTF_8) }.trim()
        val images: List<Part> get() = parts.filter { it.contentType.startsWith("image/") }
    }

    // MMS header field codes (already OR'd with 0x80 as they appear on the wire).
    private const val FROM = 0x89
    private const val TO = 0x97
    private const val CC = 0x82
    private const val CONTENT_LOCATION = 0x83
    private const val TRANSACTION_ID = 0x98
    private const val MESSAGE_TYPE = 0x8C
    private const val SUBJECT = 0x96
    private const val CONTENT_TYPE = 0x84
    private const val DATE = 0x85
    private const val MESSAGE_SIZE = 0x8E
    private const val MMS_VERSION = 0x8D
    private const val MESSAGE_ID = 0x8B

    fun parseNotification(pdu: ByteArray): Notification {
        val r = Reader(pdu)
        var from: String? = null
        var loc: String? = null
        var tid: String? = null
        var subject: String? = null
        try {
            while (r.hasMore()) {
                when (val field = r.readByte() and 0xFF) {
                    MESSAGE_TYPE, MMS_VERSION -> r.readByte()
                    MESSAGE_SIZE, DATE -> r.readLongInteger()
                    TRANSACTION_ID -> tid = r.readTextString()
                    CONTENT_LOCATION, MESSAGE_ID -> loc = r.readTextString().also { if (field == MESSAGE_ID) loc = null }
                    FROM -> from = r.readFromValue()
                    SUBJECT -> subject = r.readEncodedString()
                    else -> if (field and 0x80 == 0) { r.readTextString(); r.readTextString() } else r.skipGeneric()
                }
            }
        } catch (_: Exception) { /* best-effort */ }
        return Notification(from?.clean(), loc, tid, subject)
    }

    fun parseRetrieveConf(pdu: ByteArray): Retrieved {
        val r = Reader(pdu)
        var from: String? = null
        val to = mutableListOf<String>()
        var subject: String? = null
        var date: Long? = null
        val parts = mutableListOf<Part>()
        try {
            loop@ while (r.hasMore()) {
                when (val field = r.readByte() and 0xFF) {
                    MESSAGE_TYPE, MMS_VERSION -> r.readByte()
                    DATE -> date = r.readLongInteger()
                    MESSAGE_SIZE -> r.readLongInteger()
                    TRANSACTION_ID, MESSAGE_ID, CONTENT_LOCATION -> r.readTextString()
                    FROM -> from = r.readFromValue()
                    TO, CC -> r.readEncodedString().clean().takeIf { it.isNotBlank() }?.let { to += it }
                    SUBJECT -> subject = r.readEncodedString()
                    CONTENT_TYPE -> { r.readContentType(); parts += r.readMultipart(); break@loop }
                    else -> if (field and 0x80 == 0) { r.readTextString(); r.readTextString() } else r.skipGeneric()
                }
            }
        } catch (_: Exception) { /* best-effort: keep whatever parsed */ }
        return Retrieved(from?.clean(), to, subject, date, parts)
    }

    private fun String.clean(): String =
        substringBefore("/TYPE=").substringBefore("/type=").trim()

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun hasMore() = pos < b.size
        fun peek() = b[pos].toInt() and 0xFF
        fun readByte(): Int = b[pos++].toInt()

        fun readUintvar(): Int {
            var v = 0
            while (true) {
                val x = b[pos++].toInt() and 0xFF
                v = (v shl 7) or (x and 0x7F)
                if (x and 0x80 == 0) return v
            }
        }

        /** Text-string: optional 0x7F quote, then bytes to NUL. */
        fun readTextString(): String {
            if (peek() == 0x7F || peek() == 0x22) pos++ // quote
            val start = pos
            while (pos < b.size && b[pos].toInt() != 0) pos++
            val s = String(b, start, pos - start, Charsets.UTF_8)
            if (pos < b.size) pos++ // NUL
            return s
        }

        fun readValueLength(): Int {
            val first = readByte() and 0xFF
            return if (first < 31) first else readUintvar() // 31 = length-quote
        }

        fun readLongInteger(): Long {
            val len = readByte() and 0xFF
            var v = 0L
            repeat(len) { v = (v shl 8) or (b[pos++].toLong() and 0xFF) }
            return v
        }

        fun readIntegerValue(): Long {
            val first = peek()
            return if (first >= 0x80) { pos++; (first and 0x7F).toLong() } else readLongInteger()
        }

        /** From: value-length, then address-present-token (0x80)+addr, or insert-token (0x81). */
        fun readFromValue(): String? {
            val len = readValueLength()
            val end = pos + len
            val token = readByte() and 0xFF
            val addr = if (token == 0x80) readEncodedString() else null
            pos = end
            return addr
        }

        fun readEncodedString(): String {
            if (peek() in 0x00..0x1F) { // value-length + charset + text
                val len = readValueLength()
                val end = pos + len
                readIntegerValue() // charset, ignored
                val s = readTextString()
                pos = end
                return s
            }
            return readTextString()
        }

        /** Consume a content-type value; returns the media type string. */
        fun readContentType(): String {
            val first = peek()
            return when {
                first < 0x20 -> { // value-length form: media + params
                    val len = readValueLength()
                    val end = pos + len
                    val mime = readMediaType()
                    pos = end
                    mime
                }
                first in 0x20..0x7F -> readTextString() // extension-media
                else -> { pos++; wellKnownContentType(first and 0x7F) }
            }
        }

        private fun readMediaType(): String {
            val first = peek()
            return if (first >= 0x80) { pos++; wellKnownContentType(first and 0x7F) }
            else readTextString()
        }

        /** Skip a header value we don't care about (best-effort, general form). */
        fun skipGeneric() {
            val first = peek()
            when {
                first >= 0x80 -> pos++ // short-integer
                first in 0x20..0x7F -> readTextString()
                else -> { val len = readValueLength(); pos += len }
            }
        }

        /** Read the multipart body: n entries of (headersLen, dataLen, headers, data). */
        fun readMultipart(): List<Part> {
            val out = mutableListOf<Part>()
            val nEntries = readUintvar()
            repeat(nEntries) {
                if (pos >= b.size) return@repeat
                val headersLen = readUintvar()
                val dataLen = readUintvar()
                val headerStart = pos
                val mime = runCatching { readContentType() }.getOrDefault("application/octet-stream")
                pos = headerStart + headersLen // skip remaining part headers
                val dataStart = pos
                val end = minOf(dataStart + dataLen, b.size)
                val data = b.copyOfRange(dataStart, end)
                pos = end
                out += Part(refineMime(mime, data), data)
            }
            return out
        }
    }

    /** Trust the sniffed type for known image/text magic; else keep the declared MIME. */
    private fun refineMime(declared: String, data: ByteArray): String =
        sniffMime(data) ?: declared.substringBefore(';').trim().ifBlank { "application/octet-stream" }

    private fun sniffMime(d: ByteArray): String? = when {
        d.size >= 3 && d[0].toInt() and 0xFF == 0xFF && d[1].toInt() and 0xFF == 0xD8 -> "image/jpeg"
        d.size >= 8 && d[0].toInt() and 0xFF == 0x89 && d[1].toInt() == 'P'.code && d[2].toInt() == 'N'.code -> "image/png"
        d.size >= 6 && d[0].toInt() == 'G'.code && d[1].toInt() == 'I'.code && d[2].toInt() == 'F'.code -> "image/gif"
        d.size >= 12 && d[8].toInt() == 'W'.code && d[9].toInt() == 'E'.code && d[10].toInt() == 'B'.code && d[11].toInt() == 'P'.code -> "image/webp"
        else -> null
    }

    private fun wellKnownContentType(code: Int): String = when (code) {
        0x03 -> "text/plain"
        0x1D -> "image/gif"
        0x1E -> "image/jpeg"
        0x1F -> "image/tiff"
        0x20 -> "image/png"
        0x21 -> "image/vnd.wap.wbmp"
        0x23 -> "application/vnd.wap.multipart.mixed"
        0x33 -> "application/vnd.wap.multipart.related"
        else -> "application/octet-stream"
    }
}
