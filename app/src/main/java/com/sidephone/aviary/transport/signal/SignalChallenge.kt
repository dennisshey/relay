package com.sidephone.aviary.transport.signal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class SignalVerificationRequired : Exception("Signal requires verification. Tap Verify Signal to continue.")

/** Persist before publishing so process death cannot restart challenged sends. */
internal class SignalChallenge(initial: String?, private val persist: (String?) -> Unit) {
    private val pending = MutableStateFlow(initial)
    val state: StateFlow<String?> = pending

    @Synchronized fun requireVerification(token: String) {
        if (pending.value == null) {
            persist(token)
            pending.value = token
        }
    }

    fun checkSendingAllowed() {
        if (pending.value != null) throw SignalVerificationRequired()
    }

    @Synchronized fun accepted(token: String) {
        if (pending.value == token) {
            persist(null)
            pending.value = null
        }
    }

    companion object {
        fun captchaToken(url: String): String? = url.takeIf { it.startsWith("signalcaptcha://") }
            ?.removePrefix("signalcaptcha://")?.takeIf { it.isNotBlank() && it.length <= 32768 && it.none(Char::isWhitespace) }
    }
}
