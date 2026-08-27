package com.sidephone.aviary.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records a short voice note to an AAC/MP4 file and hands back the bytes. Kept deliberately
 * simple: one recording at a time, output in a container both iMessage and Signal accept as an
 * audio attachment ("audio/mp4").
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var file: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return false
        val f = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()
        return runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            true
        }.getOrElse { runCatching { r.release() }; f.delete(); false }
    }

    /** Stop and return the recorded bytes (temp file deleted), or null on failure. */
    fun stop(): ByteArray? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        val stopped = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!stopped) { f?.delete(); return null }
        return f?.let { runCatching { it.readBytes() }.getOrNull().also { _ -> f.delete() } }
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
        file?.delete()
        file = null
    }
}
