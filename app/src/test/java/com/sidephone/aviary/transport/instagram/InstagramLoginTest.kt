package com.sidephone.aviary.transport.instagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramLoginTest {

    @Test fun encryptionHeadersBeatAMissingWebPage() {
        val key = InstagramLogin.encryptionKeyFromHeaders("86", "QUJD")
        assertEquals(InstagramLogin.KeyKind.APP, key?.kind)
        assertEquals(86, key?.id)
        assertEquals("QUJD", key?.key)
        assertNull(InstagramLogin.encryptionKeyFromHeaders("nope", "QUJD"))
        assertNull(InstagramLogin.encryptionKeyFromHeaders("86", "  "))
        assertNull(InstagramLogin.encryptionKeyFromHeaders(null, null))
    }

    @Test fun missingHeadersFallBackToTheWebLoginKey() {
        val html = """
            <html><script>
            {"InstagramPasswordEncryption":{"key_id":"42","public_key":"$HEX_KEY","version":"10"},
             "SprinkleConfig":{"param_name":"jazoest","should_randomize":false,"version":2},
             "csrf_token":"csrf-from-html","rollout_hash":"roll-1"}
            </script></html>
        """.trimIndent()
        assertNull(InstagramLogin.resolvePasswordKey(null, null))
        val key = InstagramLogin.resolvePasswordKey(
            idHeader = null,
            keyHeader = null,
            webHtml = html,
            setCookies = listOf("csrftoken=csrf-cookie; Path=/; Secure"),
        )
        assertEquals(InstagramLogin.KeyKind.WEB, key?.kind)
        assertEquals(42, key?.id)
        assertEquals(HEX_KEY, key?.key)
        val page = InstagramLogin.parseWebLoginPage(html, listOf("csrftoken=csrf-cookie; Path=/"))
        assertEquals("csrf-cookie", page.csrf)
        assertEquals("jazoest", page.sprinkleName)
        assertEquals(2, page.sprinkleVersion)
        assertEquals("roll-1", page.rolloutHash)
        assertEquals("2" + "csrf-cookie".sumOf { it.code }, InstagramLogin.sprinkleToken("csrf-cookie", 2, false))
    }

    @Test fun anAppHeaderKeyIsNotReplacedByTheWebPage() {
        val html = """{"key_id":"9","public_key":"$HEX_KEY","version":"10"}"""
        val key = InstagramLogin.resolvePasswordKey("3", "app-key", html)
        assertEquals(InstagramLogin.KeyKind.APP, key?.kind)
        assertEquals("app-key", key?.key)
    }

    @Test fun webKeyOtherThanVersion10IsIgnored() {
        val html = """{"key_id":"9","public_key":"$HEX_KEY","version":"9"}"""
        assertNull(InstagramLogin.resolvePasswordKey(null, null, html))
    }

    @Test fun twoFactorSmsAndTotpAreDistinct() {
        val sms = InstagramLogin.classify(200, """
            {"two_factor_required":true,"two_factor_info":{
              "username":"ada","two_factor_identifier":"id-sms",
              "sms_two_factor_on":true,"totp_two_factor_on":false}}
        """.trimIndent()) as InstagramLogin.Classified.TwoFactor
        assertEquals("id-sms", sms.identifier)
        assertEquals("SMS", sms.method)
        assertTrue(InstagramLogin.twoFactorPrompt(sms.method, sms.username).contains("SMS"))

        val totp = InstagramLogin.classify(400, """
            {"two_factor_required":true,"two_factor_info":{
              "username":"ada","two_factor_identifier":"id-totp","totp_two_factor_on":true}}
        """.trimIndent()) as InstagramLogin.Classified.TwoFactor
        assertEquals("authenticator", totp.method)
        assertTrue(InstagramLogin.twoFactorPrompt(totp.method, "ada").contains("authenticator"))
    }

    @Test fun checkpointChallengeAndBadPasswordAndRateLimit() {
        val checkpoint = InstagramLogin.classify(400, """
            {"message":"checkpoint_challenge_required","error_type":"checkpoint_challenge_required",
             "status":"fail","challenge":{"api_path":"/challenge/abc/"}}
        """.trimIndent()) as InstagramLogin.Classified.Challenge
        assertEquals("/challenge/abc/", checkpoint.apiPath)
        assertEquals(InstagramLogin.MSG_CHECKPOINT, checkpoint.message)

        val challenge = InstagramLogin.classify(400, """
            {"message":"challenge_required","challenge":{"api_path":"/challenge/1/"}}
        """.trimIndent()) as InstagramLogin.Classified.Challenge
        assertEquals(InstagramLogin.MSG_CHALLENGE, challenge.message)

        val bad = InstagramLogin.classify(400, """
            {"message":"The password you entered is incorrect. Please try again.",
             "error_type":"bad_password","status":"fail"}
        """.trimIndent()) as InstagramLogin.Classified.Failure
        assertTrue(bad.message.contains("incorrect"))

        val coded = InstagramLogin.classify(400, """{"error_type":"bad_password","message":"bad_password"}""")
            as InstagramLogin.Classified.Failure
        assertEquals(InstagramLogin.MSG_BAD_PASSWORD, coded.message)

        val limited = InstagramLogin.classify(429, "") as InstagramLogin.Classified.Failure
        assertEquals(InstagramLogin.MSG_RATE_LIMIT, limited.message)

        val feedback = InstagramLogin.classify(400, """
            {"message":"feedback_required","spam":true,"feedback_message":"Please wait a few minutes before you try again."}
        """.trimIndent()) as InstagramLogin.Classified.Failure
        assertTrue(feedback.message.contains("few minutes"))
    }

    @Test fun loggedInUserAndWebAuthenticatedBothCountAsSuccess() {
        val mobile = InstagramLogin.classify(200, """
            {"status":"ok","logged_in_user":{"pk":42,"username":"ada"}}
        """.trimIndent()) as InstagramLogin.Classified.Success
        assertEquals("42", mobile.userId)
        assertEquals("ada", mobile.username)
        val web = InstagramLogin.classify(200, """{"authenticated":true,"userId":"7","status":"ok"}""")
            as InstagramLogin.Classified.Success
        assertEquals("7", web.userId)
    }

    @Test fun secretboxAndHsalsaMatchRecordedVectors() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(24) { (0xA0 + it).toByte() }
        val sealed = InstagramLogin.secretboxSeal("hello instagram".toByteArray(), nonce, key)
        assertEquals(
            "f58a3ff798ae4a6d5bf0ba583d1c891fd32b311ac181f227e2fa291d6e6360",
            sealed.joinToString("") { "%02x".format(it) },
        )
        val input = ByteArray(16) { it.toByte() }
        val hsKey = ByteArray(32) { (255 - it).toByte() }
        val out = InstagramLogin.hSalsa20(input, hsKey, "expand 32-byte k".toByteArray())
        assertEquals(
            "c47de7654f8db660c96b5f21f882e0ad1ff0c4d8ab2def4e4668f90c2d3e34b4",
            out.joinToString("") { "%02x".format(it) },
        )
    }

    @Test fun browserEnvelopeUsesTheVersion10Prefix() {
        val enc = InstagramLogin.encryptBrowserPassword("secret", 7, HEX_KEY)
        assertTrue(enc.startsWith("#PWD_INSTAGRAM_BROWSER:10:"))
        val payload = java.util.Base64.getDecoder().decode(enc.substringAfterLast(':'))
        assertEquals(1, payload[0].toInt())
        assertEquals(7, payload[1].toInt())
    }

    @Test fun androidUserAgentMatchesTheCentralConstants() {
        assertEquals("567067343352427", InstagramDevice.APP_ID)
        assertEquals(
            "Instagram 454.0.0.54.77 Android (34/14; 480dpi; 1344x2992; Google/google; " +
                "Pixel 8 Pro; husky; husky; en_US; 1105698528)",
            InstagramDevice.userAgent(),
        )
    }

    private companion object {
        const val HEX_KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    }
}
