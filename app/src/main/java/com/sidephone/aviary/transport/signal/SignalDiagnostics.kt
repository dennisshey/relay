package com.sidephone.aviary.transport.signal

import android.util.Log

/** Metadata only: never log exception messages, response bodies, keys, or recipient addresses. */
internal object SignalDiagnostics {
    fun failure(stage: String, timestamp: Long, error: Throwable) {
        val http = Regex("HTTP (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)
        val frames = error.stackTrace.take(8).joinToString(" <- ") {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        }
        Log.w("SignalDelivery", "ts=$timestamp stage=$stage error=${error.javaClass.simpleName} http=${http ?: "none"} at=$frames")
    }
}
