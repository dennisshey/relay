package com.sidephone.aviary.data

import java.io.InputStream
import java.io.ByteArrayOutputStream

internal object AttachmentStreams {
    /** Read a bounded prefix; works on Android versions without InputStream.readNBytes. */
    fun prefix(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(minOf(limit, 64 * 1024))
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        var remaining = limit
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }
}
