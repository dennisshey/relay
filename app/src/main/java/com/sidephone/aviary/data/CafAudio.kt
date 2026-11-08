package com.sidephone.aviary.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * iMessage voice messages arrive as Apple CAF ("Core Audio Format") files, which Android's
 * MediaPlayer can't open. The audio inside is a codec Android does play — Opus on current iOS,
 * AAC or AMR on older builds, occasionally raw PCM — so we unwrap the CAF container and rewrap
 * the same packets in one Android understands: Ogg (Opus), ADTS (AAC), .amr (AMR) or WAV (PCM).
 * No re-encoding, so it's fast and lossless.
 */
object CafAudio {

    /** True if [file] starts with the CAF magic "caff". */
    fun isCaf(file: File): Boolean = runCatching {
        file.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && String(b, Charsets.ISO_8859_1) == "caff"
        }
    }.getOrDefault(false)

    /**
     * A path MediaPlayer can play for [path]: the file itself if it isn't CAF, otherwise a
     * converted copy cached in [cacheDir] (converted once, reused after). Null if the CAF holds
     * a codec we can't rewrap.
     */
    fun playablePath(path: String, cacheDir: File): String? {
        val src = File(path)
        if (!isCaf(src)) return path
        val dir = File(cacheDir, "voice").apply { mkdirs() }
        val stem = "${src.name.hashCode().toUInt()}_${src.length()}"
        dir.listFiles()?.firstOrNull { it.nameWithoutExtension == stem && it.length() > 0 }
            ?.let { return it.absolutePath }
        val (bytes, ext) = runCatching { convert(src.readBytes()) }.getOrNull() ?: return null
        val out = File(dir, "$stem.$ext")
        val tmp = File(dir, "$stem.$ext.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) { tmp.delete(); return null }
        return out.absolutePath
    }

    private class Desc(
        val sampleRate: Double, val formatId: String, val formatFlags: Int,
        val bytesPerPacket: Int, val framesPerPacket: Int, val channels: Int, val bitsPerChannel: Int,
    )

    /** Convert CAF bytes to (container bytes, file extension). Throws if unsupported. */
    fun convert(caf: ByteArray): Pair<ByteArray, String> {
        val buf = ByteBuffer.wrap(caf).order(ByteOrder.BIG_ENDIAN)
        require(caf.size >= 8 && String(caf, 0, 4, Charsets.ISO_8859_1) == "caff") { "not CAF" }
        buf.position(8)
        var desc: Desc? = null
        var cookie: ByteArray? = null
        var packetTable: ByteArray? = null
        var priming = 0
        var audio: ByteArray? = null
        while (buf.remaining() >= 12) {
            val type = ByteArray(4).also { buf.get(it) }.toString(Charsets.ISO_8859_1)
            var size = buf.long
            if (size < 0 || size > buf.remaining()) size = buf.remaining().toLong() // data chunk "to EOF"
            val start = buf.position()
            when (type) {
                "desc" -> desc = Desc(
                    buf.double, ByteArray(4).also { buf.get(it) }.toString(Charsets.ISO_8859_1),
                    buf.int, buf.int, buf.int, buf.int, buf.int,
                )
                "kuki" -> cookie = caf.copyOfRange(start, start + size.toInt())
                "pakt" -> {
                    buf.long; buf.long // number of packets, valid frames
                    priming = buf.int
                    buf.int // remainder frames
                    packetTable = caf.copyOfRange(buf.position(), start + size.toInt())
                }
                // The data chunk opens with a 4-byte edit count before the audio.
                "data" -> audio = caf.copyOfRange(start + 4, start + size.toInt())
            }
            buf.position(start + size.toInt())
        }
        val d = requireNotNull(desc) { "no desc chunk" }
        val data = requireNotNull(audio) { "no data chunk" }
        val packets by lazy { splitPackets(d, data, packetTable) }
        return when (d.formatId) {
            "opus" -> oggOpus(d, packets, priming) to "ogg"
            "aac " -> adts(d, cookie, packets) to "aac"
            "samr" -> (("#!AMR\n").toByteArray(Charsets.ISO_8859_1) + data) to "amr"
            "lpcm", "ulaw", "alaw" -> wav(d, data) to "wav"
            else -> error("unsupported CAF codec '${d.formatId}'")
        }
    }

    /** Cut the data chunk into packets: fixed-size when the desc says so, else from the pakt table. */
    private fun splitPackets(d: Desc, data: ByteArray, table: ByteArray?): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (d.bytesPerPacket > 0) {
            var i = 0
            while (i + d.bytesPerPacket <= data.size) { out += data.copyOfRange(i, i + d.bytesPerPacket); i += d.bytesPerPacket }
            return out
        }
        val t = requireNotNull(table) { "variable packets with no packet table" }
        var ti = 0
        fun varint(): Long {
            var v = 0L
            while (ti < t.size) {
                val b = t[ti++].toInt() and 0xFF
                v = (v shl 7) or (b and 0x7F).toLong()
                if ((b and 0x80) == 0) return v
            }
            return -1
        }
        var off = 0
        while (ti < t.size) {
            val size = varint().toInt()
            if (d.framesPerPacket == 0) varint() // per-packet frame count, unused
            if (size <= 0 || off + size > data.size) break
            out += data.copyOfRange(off, off + size)
            off += size
        }
        return out
    }

    // ---- Opus → Ogg -------------------------------------------------------------------------

    private fun oggOpus(d: Desc, packets: List<ByteArray>, priming: Int): ByteArray {
        val channels = d.channels.coerceIn(1, 2)
        val preSkip = (priming.toLong() * 48000 / d.sampleRate.coerceAtLeast(1.0)).toInt().coerceIn(0, 65535)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.ISO_8859_1)); put(1); put(channels.toByte())
            putShort(preSkip.toShort()); putInt(d.sampleRate.toInt()); putShort(0); put(0)
        }.array()
        val vendor = "Aviary".toByteArray()
        val tags = ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray(Charsets.ISO_8859_1)); putInt(vendor.size); put(vendor); putInt(0)
        }.array()

        val out = ByteArrayOutputStream()
        val serial = 0x41564952 // "AVIR"
        var seq = 0
        out.write(oggPage(head, 0x02, 0, serial, seq++))
        out.write(oggPage(tags, 0x00, 0, serial, seq++))
        var granule = 0L
        packets.forEachIndexed { i, p ->
            granule += opusSamples(p)
            out.write(oggPage(p, if (i == packets.lastIndex) 0x04 else 0x00, granule, serial, seq++))
        }
        return out.toByteArray()
    }

    /** Samples (at 48 kHz) in one Opus packet, from its TOC byte (RFC 6716 §3.1). */
    private fun opusSamples(p: ByteArray): Int {
        if (p.isEmpty()) return 0
        val toc = p[0].toInt() and 0xFF
        val config = toc shr 3
        val frame = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> intArrayOf(480, 960)[config and 1]
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val count = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (p.size > 1) p[1].toInt() and 0x3F else 1
        }
        return frame * count
    }

    private fun oggPage(packet: ByteArray, flags: Int, granule: Long, serial: Int, seq: Int): ByteArray {
        val lacing = ByteArrayOutputStream().apply {
            var n = packet.size
            while (n >= 255) { write(255); n -= 255 }
            write(n)
        }.toByteArray()
        val page = ByteBuffer.allocate(27 + lacing.size + packet.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OggS".toByteArray(Charsets.ISO_8859_1)); put(0); put(flags.toByte())
            putLong(granule); putInt(serial); putInt(seq); putInt(0)
            put(lacing.size.toByte()); put(lacing); put(packet)
        }.array()
        val crc = oggCrc(page)
        page[22] = crc.toByte(); page[23] = (crc ushr 8).toByte()
        page[24] = (crc ushr 16).toByte(); page[25] = (crc ushr 24).toByte()
        return page
    }

    private val crcTable = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if ((r and 0x80000000.toInt()) != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    private fun oggCrc(b: ByteArray): Int {
        var crc = 0
        for (x in b) crc = (crc shl 8) xor crcTable[((crc ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
        return crc
    }

    // ---- AAC → ADTS -------------------------------------------------------------------------

    private val aacRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    private fun adts(d: Desc, cookie: ByteArray?, packets: List<ByteArray>): ByteArray {
        // Prefer the AudioSpecificConfig from the magic cookie (an esds; tag 0x05 holds it).
        var profile = 1 // AAC LC (object type 2) - 1
        var rateIdx = aacRates.indexOf(d.sampleRate.toInt()).takeIf { it >= 0 } ?: 4
        var chan = d.channels.coerceIn(1, 7)
        ascFromCookie(cookie)?.takeIf { it.size >= 2 }?.let { asc ->
            val aot = (asc[0].toInt() and 0xFF) shr 3
            if (aot in 1..4) profile = aot - 1
            rateIdx = ((asc[0].toInt() and 7) shl 1) or ((asc[1].toInt() and 0xFF) shr 7)
            chan = ((asc[1].toInt() and 0xFF) shr 3) and 0xF
        }
        val out = ByteArrayOutputStream()
        for (p in packets) {
            val len = p.size + 7
            out.write(0xFF); out.write(0xF1)
            out.write((profile shl 6) or (rateIdx shl 2) or (chan shr 2))
            out.write(((chan and 3) shl 6) or (len shr 11))
            out.write((len shr 3) and 0xFF)
            out.write(((len and 7) shl 5) or 0x1F)
            out.write(0xFC)
            out.write(p)
        }
        return out.toByteArray()
    }

    private fun ascFromCookie(cookie: ByteArray?): ByteArray? {
        val c = cookie ?: return null
        var i = 0
        while (i < c.size - 2) {
            if (c[i].toInt() == 0x05) {
                var j = i + 1
                var len = 0
                repeat(4) {
                    if (j >= c.size) return null
                    val b = c[j++].toInt() and 0xFF
                    len = (len shl 7) or (b and 0x7F)
                    if ((b and 0x80) == 0) return if (len in 2..64 && j + len <= c.size) c.copyOfRange(j, j + len) else null
                }
            }
            i++
        }
        return null
    }

    // ---- PCM / G.711 → WAV ------------------------------------------------------------------

    private fun wav(d: Desc, data: ByteArray): ByteArray {
        val (format, bits) = when (d.formatId) {
            "ulaw" -> 7 to 8
            "alaw" -> 6 to 8
            else -> (if ((d.formatFlags and 1) != 0) 3 else 1) to d.bitsPerChannel
        }
        val pcm = if (d.formatId == "lpcm" && (d.formatFlags and 2) == 0 && bits > 8) {
            // Big-endian samples: WAV is little-endian, so swap each sample's bytes.
            val w = bits / 8
            data.copyOf().also { o -> var i = 0; while (i + w <= o.size) { o.reverse(i, i + w); i += w } }
        } else data
        val ch = d.channels.coerceAtLeast(1)
        val rate = d.sampleRate.toInt()
        val align = ch * bits / 8
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(format.toShort()); putShort(ch.toShort())
            putInt(rate); putInt(rate * align); putShort(align.toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
}
