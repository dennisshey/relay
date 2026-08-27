package com.sidephone.aviary.transport.sms

import java.io.ByteArrayOutputStream

/**
 * Builds an outbound MMS PDU (M-Send.req) — headers plus a multipart.mixed body of an
 * optional text part and image parts. Hand-rolled WSP/MMS encoding to match [MmsPdu].
 * The platform ([android.telephony.SmsManager.sendMultimediaMessage]) handles the MMSC
 * network transaction; we only supply this composed PDU.
 */
object MmsCompose {

    data class Image(val mime: String, val data: ByteArray, val name: String)

    fun sendReq(
        transactionId: String,
        recipients: List<String>,
        text: String?,
        images: List<Image>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // --- headers ---
        out.write(0x8C); out.write(0x80)                 // Message-Type = m-send-req (128)
        out.write(0x98); writeTextString(out, transactionId) // Transaction-Id
        out.write(0x8D); out.write(0x92)                 // MMS-Version = 1.2
        out.write(0x89); out.write(0x01); out.write(0x81) // From = insert-address-token
        for (r in recipients) {                          // To (one per recipient)
            out.write(0x97); writeTextString(out, "$r/TYPE=PLMN")
        }
        out.write(0x84); writeTextString(out, "application/vnd.wap.multipart.mixed") // Content-Type

        // --- multipart body ---
        val parts = buildList {
            if (!text.isNullOrEmpty()) add(textPart(text))
            images.forEach { add(imagePart(it)) }
        }
        writeUintvar(out, parts.size)
        for (p in parts) {
            writeUintvar(out, p.first.size) // headers length
            writeUintvar(out, p.second.size) // data length
            out.write(p.first)
            out.write(p.second)
        }
        return out.toByteArray()
    }

    /** text/plain; charset=utf-8. Returns (headerBytes, dataBytes). */
    private fun textPart(text: String): Pair<ByteArray, ByteArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        val ct = ByteArrayOutputStream().apply {
            write(0x83)             // well-known media type: text/plain
            write(0x81); write(0xEA) // Charset param (0x81) = UTF-8 (106) as short-integer
        }.toByteArray()
        val headers = ByteArrayOutputStream().apply {
            writeValueLength(this, ct.size); write(ct)
        }.toByteArray()
        return headers to data
    }

    private fun imagePart(image: Image): Pair<ByteArray, ByteArray> {
        val wellKnown = when (image.mime.lowercase()) {
            "image/jpeg", "image/jpg" -> 0x9E
            "image/png" -> 0xA0
            "image/gif" -> 0x9D
            else -> null
        }
        val name = image.name.ifBlank { "image" }
        val headers = ByteArrayOutputStream().apply {
            val ct = ByteArrayOutputStream().apply {
                if (wellKnown != null) write(wellKnown) else writeTextString(this, image.mime)
                write(0x85); writeTextString(this, name) // Name param (0x85)
            }.toByteArray()
            writeValueLength(this, ct.size); write(ct)
        }.toByteArray()
        return headers to image.data
    }

    private fun writeTextString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0x80) != 0) out.write(0x7F) // quote high-bit
        out.write(bytes); out.write(0x00)
    }

    private fun writeValueLength(out: ByteArrayOutputStream, len: Int) {
        if (len < 31) out.write(len) else { out.write(0x1F); writeUintvar(out, len) }
    }

    private fun writeUintvar(out: ByteArrayOutputStream, value: Int) {
        if (value == 0) { out.write(0); return }
        var v = value
        val bytes = ArrayList<Int>()
        while (v > 0) { bytes.add(v and 0x7F); v = v ushr 7 }
        for (i in bytes.indices.reversed()) {
            val b = bytes[i]
            out.write(if (i == 0) b else (b or 0x80))
        }
    }
}
