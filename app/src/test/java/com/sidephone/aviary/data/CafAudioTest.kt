package com.sidephone.aviary.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real Apple CAF files made with macOS `afconvert` (opus, aac, 16-bit PCM). */
class CafAudioTest {

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("caf/$name")).readBytes()

    private fun keep(name: String, bytes: ByteArray) {
        runCatching { File("build/caf-out", name).apply { parentFile?.mkdirs() }.writeBytes(bytes) } // for manual ffprobe checks
    }

    @Test fun opusBecomesOggOpus() {
        val (ogg, ext) = CafAudio.convert(fixture("voice_opus.caf"))
        keep("voice.ogg", ogg)
        assertEquals("ogg", ext)
        // Walk every page: magic, sequence, and a nonzero final granule.
        var off = 0; var pages = 0; var granule = 0L
        while (off < ogg.size) {
            assertEquals("OggS", String(ogg, off, 4, Charsets.ISO_8859_1))
            val bb = ByteBuffer.wrap(ogg, off, 27).order(ByteOrder.LITTLE_ENDIAN)
            granule = bb.getLong(off + 6)
            assertEquals(pages, bb.getInt(off + 18))
            val segs = ogg[off + 26].toInt() and 0xFF
            var body = 0
            for (i in 0 until segs) body += ogg[off + 27 + i].toInt() and 0xFF
            if (pages == 0) assertEquals("OpusHead", String(ogg, off + 27 + segs, 8, Charsets.ISO_8859_1))
            off += 27 + segs + body; pages++
        }
        assertEquals(ogg.size, off)
        assertTrue(pages > 10)
        assertTrue(granule > 48000) // > 1 s of audio
    }

    @Test fun aacBecomesAdts() {
        val (aac, ext) = CafAudio.convert(fixture("voice_aac.caf"))
        keep("voice.aac", aac)
        assertEquals("aac", ext)
        var off = 0; var frames = 0
        while (off < aac.size) {
            assertEquals(0xFF, aac[off].toInt() and 0xFF)
            assertEquals(0xF0, aac[off + 1].toInt() and 0xF6)
            val len = ((aac[off + 3].toInt() and 3) shl 11) or ((aac[off + 4].toInt() and 0xFF) shl 3) or
                ((aac[off + 5].toInt() and 0xFF) shr 5)
            off += len; frames++
        }
        assertEquals(aac.size, off)
        assertTrue(frames > 10)
    }

    @Test fun pcmBecomesWav() {
        val (wav, ext) = CafAudio.convert(fixture("voice_pcm.caf"))
        keep("voice.wav", wav)
        assertEquals("wav", ext)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.ISO_8859_1))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.ISO_8859_1))
    }

    @Test fun nonCafIsRejected() {
        assertTrue(runCatching { CafAudio.convert(ByteArray(64)) }.isFailure)
    }
}
