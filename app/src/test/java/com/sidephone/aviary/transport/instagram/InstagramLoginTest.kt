package com.sidephone.aviary.transport.instagram

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    @Test fun passwordKeyOrderIsWebThenLauncherThenQe() {
        assertEquals(
            listOf("web", "launcher/sync/", "qe/sync/"),
            InstagramLogin.PASSWORD_KEY_SOURCES,
        )
        val seen = mutableListOf<String>()
        val web = InstagramLogin.fetchPasswordKey { source ->
            seen += source
            if (source == "web") InstagramLogin.EncryptionKey(42, HEX_KEY, InstagramLogin.KeyKind.WEB) else null
        }
        assertEquals(listOf("web"), seen)
        assertEquals(InstagramLogin.KeyKind.WEB, web?.kind)
        assertEquals(42, web?.id)

        val stopped = mutableListOf<String>()
        val app = InstagramLogin.fetchPasswordKey { source ->
            stopped += source
            if (source == "launcher/sync/") {
                InstagramLogin.EncryptionKey(3, "app-key", InstagramLogin.KeyKind.APP)
            } else {
                null
            }
        }
        assertEquals(listOf("web", "launcher/sync/"), stopped)
        assertEquals(InstagramLogin.KeyKind.APP, app?.kind)
        assertEquals("app-key", app?.key)
    }

    @Test fun webLoginHeadersUseABrowserUserAgent() {
        val browser = InstagramDevice.WEB_USER_AGENT
        assertFalse(browser.startsWith("Instagram "))
        assertTrue(browser.contains("Chrome/"))
        assertTrue(browser.contains("Mobile"))

        val page = InstagramLogin.webPageHeaders(browser)
        assertEquals(browser, page["User-Agent"])
        assertEquals("text/html,application/xhtml+xml", page["Accept"])
        assertFalse(page.containsKey("X-IG-App-ID"))
        assertFalse(page.containsKey("X-IG-Capabilities"))
        assertFalse(page.containsKey("X-Bloks-Version-Id"))

        val login = InstagramLogin.webLoginHeaders(browser, "csrf-cookie", "0", "roll-1")
        assertEquals(browser, login["User-Agent"])
        assertEquals(InstagramDevice.WEB_APP_ID, login["X-IG-App-ID"])
        assertEquals("936619743392459", login["X-IG-App-ID"])
        assertFalse(login["X-IG-App-ID"] == InstagramDevice.APP_ID)
        assertEquals("csrf-cookie", login["X-CSRFToken"])
        assertEquals("csrftoken=csrf-cookie", login["Cookie"])
        assertEquals(InstagramLogin.WEB_LOGIN_URL, login["Referer"])
        assertEquals("https://www.instagram.com", login["Origin"])
        assertEquals("roll-1", login["X-Instagram-AJAX"])
        assertFalse(login.containsKey("X-IG-Capabilities"))
        assertFalse(login.containsKey("X-Bloks-Version-Id"))
        assertFalse(login.containsKey("X-IG-Android-ID"))
        assertFalse(login["User-Agent"]!!.contains(InstagramDevice.APP_VERSION))
    }

    @Test fun webLoginPageSuppliesTheVersion10Key() {
        val html = """
            <html><script>
            {"InstagramPasswordEncryption":{"key_id":"42","public_key":"$HEX_KEY","version":"10"},
             "SprinkleConfig":{"param_name":"jazoest","should_randomize":false,"version":2},
             "csrf_token":"csrf-from-html","rollout_hash":"roll-1"}
            </script></html>
        """.trimIndent()
        val page = InstagramLogin.parseWebLoginPage(html, listOf("csrftoken=csrf-cookie; Path=/; Secure"))
        assertEquals(InstagramLogin.KeyKind.WEB, page.key?.kind)
        assertEquals(42, page.key?.id)
        assertEquals(HEX_KEY, page.key?.key)
        assertEquals("csrf-cookie", page.csrf)
        assertEquals("jazoest", page.sprinkleName)
        assertEquals(2, page.sprinkleVersion)
        assertEquals("roll-1", page.rolloutHash)
        assertEquals("2" + "csrf-cookie".sumOf { it.code }, InstagramLogin.sprinkleToken("csrf-cookie", 2, false))
        assertNull(InstagramLogin.parseWebLoginPage("""{"key_id":"9","public_key":"$HEX_KEY","version":"9"}""").key)
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

        val both = InstagramLogin.classify(200, """
            {"two_factor_required":true,"two_factor_info":{
              "username":"ada","two_factor_identifier":"id-both",
              "sms_two_factor_on":true,"totp_two_factor_on":true}}
        """.trimIndent()) as InstagramLogin.Classified.TwoFactor
        assertEquals("either", both.method)
        assertNull(InstagramLogin.verificationMethod(both.method))
        assertEquals("1", InstagramLogin.verificationMethod("SMS"))
        assertEquals("3", InstagramLogin.verificationMethod("authenticator"))
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

        val wrong = InstagramLogin.classify(200, """{"user":true,"authenticated":false,"status":"ok"}""")
            as InstagramLogin.Classified.Failure
        assertEquals(InstagramLogin.MSG_BAD_PASSWORD, wrong.message)
        val unknown = InstagramLogin.classify(200, """{"user":false,"status":"ok"}""")
            as InstagramLogin.Classified.Failure
        assertEquals(InstagramLogin.MSG_UNKNOWN_USER, unknown.message)
    }

    @Test fun twoFactorCheckpointKeepsTheCheckpointMessage() {
        val message = InstagramLogin.twoFactorSubmitMessage(
            success = false,
            challengeMessage = InstagramLogin.MSG_CHECKPOINT,
            errorMessage = "2FA failed",
            stillTwoFactor = true,
            method = "SMS",
            username = "ada",
        )
        assertEquals(InstagramLogin.MSG_CHECKPOINT, message)
        assertNull(InstagramLogin.twoFactorSubmitMessage(
            success = true,
            challengeMessage = null,
            errorMessage = null,
            stillTwoFactor = false,
            method = "SMS",
            username = "ada",
        ))
    }

    @Test fun secretboxAndHsalsaMatchRecordedVectors() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(24) { (0xA0 + it).toByte() }
        val sealed = InstagramLogin.secretboxSeal("hello instagram".toByteArray(), nonce, key)
        assertEquals(
            "f58a3ff798ae4a6d5bf0ba583d1c891fd32b311ac181f227e2fa291d6e6360",
            sealed.joinToString("") { "%02x".format(it) },
        )
        assertEquals("hello instagram", InstagramLogin.secretboxOpen(sealed, nonce, key)!!.toString(Charsets.UTF_8))
        val input = ByteArray(16) { it.toByte() }
        val hsKey = ByteArray(32) { (255 - it).toByte() }
        val out = InstagramLogin.hSalsa20(input, hsKey, "expand 32-byte k".toByteArray())
        assertEquals(
            "c47de7654f8db660c96b5f21f882e0ad1ff0c4d8ab2def4e4668f90c2d3e34b4",
            out.joinToString("") { "%02x".format(it) },
        )
    }

    @Test fun browserEnvelopeDecryptsWithAZeroIv() {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val pair = gen.generateKeyPair()
        val priv = (pair.private as X25519PrivateKeyParameters).encoded
        val pub = (pair.public as X25519PublicKeyParameters).encoded
        val password = "secret"
        val enc = InstagramLogin.encryptBrowserPassword(password, 7, pub.joinToString("") { "%02x".format(it) })
        val parts = enc.split(':')
        assertEquals("#PWD_INSTAGRAM_BROWSER", parts[0])
        assertEquals("10", parts[1])
        val timestamp = parts[2]
        val payload = java.util.Base64.getDecoder().decode(parts[3])
        assertEquals(1, payload[0].toInt() and 0xff)
        assertEquals(7, payload[1].toInt() and 0xff)
        val sealedLen = (payload[2].toInt() and 0xff) or ((payload[3].toInt() and 0xff) shl 8)
        assertEquals(80, sealedLen)
        val sealed = payload.copyOfRange(4, 4 + sealedLen)
        val body = payload.copyOfRange(4 + sealedLen, payload.size)
        assertEquals(payload.size, 4 + sealedLen + body.size)
        val aesKey = openSealedKey(sealed, priv, pub)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, ByteArray(12)))
        cipher.updateAAD(timestamp.toByteArray(Charsets.UTF_8))
        val tag = body.copyOfRange(0, 16)
        val ciphertext = body.copyOfRange(16, body.size)
        assertEquals(password, cipher.doFinal(ciphertext + tag).toString(Charsets.UTF_8))
    }

    @Test fun androidUserAgentMatchesTheCentralConstants() {
        assertEquals("567067343352427", InstagramDevice.APP_ID)
        assertEquals(
            "Instagram 454.0.0.54.77 Android (34/14; 480dpi; 1344x2992; Google/google; " +
                "Pixel 8 Pro; husky; husky; en_US; 1105698528)",
            InstagramDevice.userAgent(),
        )
    }

    private fun openSealedKey(sealed: ByteArray, recipientPrivate: ByteArray, recipientPublic: ByteArray): ByteArray {
        val ephemeral = sealed.copyOfRange(0, 32)
        val box = sealed.copyOfRange(32, sealed.size)
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(recipientPrivate, 0))
        val shared = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(ephemeral, 0), shared, 0)
        val key = InstagramLogin.hSalsa20(ByteArray(16), shared, "expand 32-byte k".toByteArray(Charsets.US_ASCII))
        val blake = Blake2bDigest(192)
        blake.update(ephemeral, 0, ephemeral.size)
        blake.update(recipientPublic, 0, recipientPublic.size)
        val nonce = ByteArray(24)
        blake.doFinal(nonce, 0)
        return InstagramLogin.secretboxOpen(box, nonce, key)!!
    }

    private companion object {
        const val HEX_KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    }
}
