package com.sidephone.aviary.transport.signal

import org.junit.Assert.*
import org.junit.Test

class SignalChallengeTest {
    @Test fun pauseSurvivesRestartAndOnlyMatchingAcceptanceUnblocks() {
        var disk: String? = null
        val gate = SignalChallenge(disk) { disk = it }
        gate.requireVerification("challenge-a")
        assertTrue(runCatching { gate.checkSendingAllowed() }.exceptionOrNull() is SignalVerificationRequired)
        val restored = SignalChallenge(disk) { disk = it }
        restored.accepted("stale-challenge")
        assertEquals("challenge-a", restored.state.value)
        restored.accepted("challenge-a")
        restored.checkSendingAllowed()
        assertNull(disk)
    }

    @Test fun parallelChallengesDoNotReplaceActiveVerification() {
        val gate = SignalChallenge(null) {}
        gate.requireVerification("first")
        gate.requireVerification("second")
        assertEquals("first", gate.state.value)
    }

    @Test fun failedPersistenceDoesNotPublishOrClearState() {
        val gate = SignalChallenge("pending") { error("disk failure") }
        assertTrue(runCatching { gate.accepted("pending") }.isFailure)
        assertEquals("pending", gate.state.value)
    }

    @Test fun onlyCaptchaCallbacksAreAccepted() {
        assertEquals("signal-hcaptcha.token", SignalChallenge.captchaToken("signalcaptcha://signal-hcaptcha.token"))
        assertNull(SignalChallenge.captchaToken("https://example.com/token"))
        assertNull(SignalChallenge.captchaToken("signalcaptcha://"))
        assertNull(SignalChallenge.captchaToken("signalcaptcha://invalid token"))
    }
}
