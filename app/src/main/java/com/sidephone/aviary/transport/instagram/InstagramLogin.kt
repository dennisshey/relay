package com.sidephone.aviary.transport.instagram

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Login response and password-key parsing for the private Instagram client.
 * Kept free of Android types so the header, fallback, and challenge paths can be tested
 * with synthetic responses.
 *
 * The mobile key comes from `ig-set-password-encryption-*` on `launcher/sync/` (and, if that
 * omits them, `qe/sync/`). The web fallback reads `InstagramPasswordEncryption` out of the
 * login HTML and seals the password as `#PWD_INSTAGRAM_BROWSER:10`.
 */
object InstagramLogin {

    const val MSG_NO_KEY = "Instagram did not return a password encryption key."
    const val MSG_UNREACHABLE = "Couldn't reach Instagram."
    const val MSG_BAD_PASSWORD = "Wrong username or password."
    const val MSG_UNKNOWN_USER = "No Instagram account uses that username."
    const val MSG_CHOOSE_2FA = "Choose SMS or an authenticator code."
    const val MSG_RATE_LIMIT = "Instagram is rate-limiting this login. Wait a few minutes and try again."
    const val MSG_CHECKPOINT =
        "Instagram needs a checkpoint verification before this login can continue. Approve it in the Instagram app, then try again."
    const val MSG_CHALLENGE =
        "Instagram needs to verify this login. Approve it in the Instagram app, then try again."

    enum class KeyKind { APP, WEB }

    data class EncryptionKey(val id: Int, val key: String, val kind: KeyKind)

    data class WebPage(
        val key: EncryptionKey?,
        val csrf: String?,
        val sprinkleName: String?,
        val sprinkleVersion: Int,
        val sprinkleRandomize: Boolean,
        val rolloutHash: String?,
    )

    sealed class Classified {
        data class Success(val userId: String, val username: String) : Classified()
        data class TwoFactor(val identifier: String, val username: String, val method: String) : Classified()
        data class Challenge(val apiPath: String, val message: String) : Classified()
        data class Failure(val message: String) : Classified()
    }

    /** Mobile password key from the response headers. Blank or non-numeric values are not a key. */
    fun encryptionKeyFromHeaders(idHeader: String?, keyHeader: String?): EncryptionKey? {
        val id = idHeader?.trim()?.toIntOrNull() ?: return null
        val key = keyHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return EncryptionKey(id, key, KeyKind.APP)
    }

    /**
     * Order [InstagramApi] uses when asking Instagram for a password key.
     * `launcher/sync/` is what current clients read; `qe/sync/` is the old call;
     * `web` is the login-page fallback.
     */
    val PASSWORD_KEY_SOURCES = listOf("launcher/sync/", "qe/sync/", "web")

    /** First source that returns a key wins. A null fetch means "try the next source." */
    fun fetchPasswordKey(fetch: (String) -> EncryptionKey?): EncryptionKey? {
        for (source in PASSWORD_KEY_SOURCES) {
            fetch(source)?.let { return it }
        }
        return null
    }

    fun parseWebLoginPage(html: String, setCookies: List<String> = emptyList()): WebPage {
        val enc = webEncryption(html)
        val sprinkle = sprinkle(html)
        return WebPage(
            key = enc,
            csrf = csrfFromCookies(setCookies) ?: csrfFromHtml(html),
            sprinkleName = sprinkle?.first,
            sprinkleVersion = sprinkle?.second ?: 0,
            sprinkleRandomize = sprinkle?.third ?: false,
            rolloutHash = jsonString(html, "rollout_hash"),
        )
    }

    fun sprinkleToken(csrf: String, version: Int, randomize: Boolean): String {
        val sum = csrf.sumOf { it.code }
        return if (randomize || version <= 0) sum.toString() else "$version$sum"
    }

    fun classify(httpCode: Int, body: String): Classified {
        if (httpCode == 429) return Classified.Failure(MSG_RATE_LIMIT)
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return Classified.Failure(
                if (body.trimStart().startsWith("<"))
                    "Instagram returned a web page instead of a login result (HTTP $httpCode)."
                else "Bad response (HTTP $httpCode).",
            )
        if (json.optBoolean("two_factor_required") || json.optString("error_type") == "two_factor_required") {
            val info = json.optJSONObject("two_factor_info") ?: JSONObject()
            val id = info.optString("two_factor_identifier").ifBlank { json.optString("two_factor_identifier") }
            val user = info.optString("username").ifBlank { json.optString("username") }
            return Classified.TwoFactor(id, user, twoFactorMethod(info))
        }
        val user = json.optJSONObject("logged_in_user")
        if (json.optString("status") == "ok" && user != null) {
            val id = user.opt("pk")?.toString().orEmpty()
            if (id.isNotBlank()) return Classified.Success(id, user.optString("username"))
        }
        if (json.optBoolean("authenticated")) {
            val id = json.opt("userId")?.toString().orEmpty().ifBlank { json.optString("user_id") }
            if (id.isNotBlank()) return Classified.Success(id, json.optString("username"))
        }
        val message = json.optString("message")
        val errorType = json.optString("error_type")
        val detail = "$message $errorType".lowercase()
        val checkpoint = json.has("checkpoint_url") ||
            errorType == "checkpoint_challenge_required" ||
            errorType == "checkpoint_required" ||
            errorType == "challenge_required" ||
            message == "checkpoint_required" ||
            message == "checkpoint_challenge_required" ||
            message == "challenge_required"
        if (checkpoint) {
            val path = json.optJSONObject("challenge")?.optString("api_path").orEmpty()
                .ifBlank { json.optJSONObject("checkpoint")?.optString("api_path").orEmpty() }
            val text = when {
                sentence(message) && message != "challenge_required" && message != "checkpoint_required" &&
                    message != "checkpoint_challenge_required" -> message
                errorType == "checkpoint_challenge_required" || message.contains("checkpoint") -> MSG_CHECKPOINT
                else -> MSG_CHALLENGE
            }
            return Classified.Challenge(path, text)
        }
        val rate = httpCode == 400 && message == "feedback_required" ||
            errorType == "rate_limit_error" || errorType == "feedback_required" ||
            message == "feedback_required" || json.optBoolean("spam") ||
            detail.contains("please wait") || detail.contains("try again later")
        if (rate) {
            val feedback = json.optString("feedback_message")
            val text = when {
                sentence(feedback) -> feedback
                sentence(message) && message != "feedback_required" -> message
                else -> MSG_RATE_LIMIT
            }
            return Classified.Failure(text)
        }
        val bad = errorType == "bad_password" || errorType == "invalid_user" ||
            detail.contains("bad_password") || detail.contains("the password you entered") ||
            detail.contains("doesn't appear to belong") || detail.contains("does not appear to belong")
        if (bad) {
            return Classified.Failure(if (sentence(message)) message else MSG_BAD_PASSWORD)
        }
        if (json.has("user") && !json.optBoolean("user")) {
            return Classified.Failure(if (sentence(message)) message else MSG_UNKNOWN_USER)
        }
        if (json.has("authenticated") && !json.optBoolean("authenticated")) {
            return Classified.Failure(if (sentence(message)) message else MSG_BAD_PASSWORD)
        }
        val fallback = message.ifBlank { errorType }.ifBlank { "Login failed (HTTP $httpCode)." }
        return Classified.Failure(fallback)
    }

    fun twoFactorPrompt(method: String, username: String): String = when (method) {
        "SMS" -> "Instagram sent an SMS code for $username. Enter it to finish logging in."
        "authenticator" -> "Enter the code from the authenticator app for $username."
        "either" -> "Choose SMS or an authenticator app for $username, then enter that code."
        else -> "Enter the SMS or authenticator code Instagram is asking for on $username."
    }

    /** Instagram's `verification_method`: 1 is SMS, 3 is TOTP. "either" is not a method. */
    fun verificationMethod(method: String): String? = when (method) {
        "SMS" -> "1"
        "authenticator" -> "3"
        else -> null
    }

    /** Message shown after a 2FA submit. A checkpoint keeps its own text. */
    fun twoFactorSubmitMessage(
        success: Boolean,
        challengeMessage: String?,
        errorMessage: String?,
        stillTwoFactor: Boolean,
        method: String,
        username: String,
    ): String? = when {
        success -> null
        challengeMessage != null -> challengeMessage
        errorMessage != null -> errorMessage
        stillTwoFactor -> twoFactorPrompt(method, username)
        else -> null
    }

    /** `#PWD_INSTAGRAM_BROWSER:10:{ts}:{base64}` — NaCl box of an AES-GCM session key. */
    fun encryptBrowserPassword(password: String, keyId: Int, publicKeyHex: String): String {
        val peer = hexToBytes(publicKeyHex)
        require(peer.size == 32) { "web public key must be 32 bytes" }
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val aesKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // PolarisEnvelopeEncryption and mautrix-meta encryptAESGCM (pkg/messagix/crypto/aes.go,
        // e4dcf718, 2026-11-16) seal with a 12-byte zero nonce. The envelope has no IV slot.
        val iv = ByteArray(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(timestamp.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)
        val ciphertext = encrypted.copyOfRange(0, encrypted.size - 16)
        val sealed = sealToPublicKey(aesKey, peer)
        val payload = ByteBuffer.allocate(2 + 2 + sealed.size + tag.size + ciphertext.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(1)
            .put(keyId.toByte())
            .putShort(sealed.size.toShort())
            .put(sealed)
            .put(tag)
            .put(ciphertext)
            .array()
        val encoded = Base64.getEncoder().encodeToString(payload)
        return "#PWD_INSTAGRAM_BROWSER:10:$timestamp:$encoded"
    }

    /** NaCl secretbox (XSalsa20-Poly1305). Exposed for the recorded test vector. */
    fun secretboxSeal(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val stream = ByteArray(32 + message.size)
        engine.processBytes(ByteArray(stream.size), 0, stream.size, stream, 0)
        val polyKey = stream.copyOfRange(0, 32)
        val ct = ByteArray(message.size)
        for (i in message.indices) ct[i] = (stream[32 + i].toInt() xor message[i].toInt()).toByte()
        val mac = Poly1305()
        mac.init(KeyParameter(polyKey))
        mac.update(ct, 0, ct.size)
        val tag = ByteArray(16)
        mac.doFinal(tag, 0)
        return tag + ct
    }

    /** Inverse of [secretboxSeal]. Returns null when the Poly1305 tag does not match. */
    fun secretboxOpen(box: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        if (box.size < 16) return null
        val tag = box.copyOfRange(0, 16)
        val ct = box.copyOfRange(16, box.size)
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val stream = ByteArray(32 + ct.size)
        engine.processBytes(ByteArray(stream.size), 0, stream.size, stream, 0)
        val mac = Poly1305()
        mac.init(KeyParameter(stream.copyOfRange(0, 32)))
        mac.update(ct, 0, ct.size)
        val expected = ByteArray(16)
        mac.doFinal(expected, 0)
        if (!expected.contentEquals(tag)) return null
        val out = ByteArray(ct.size)
        for (i in ct.indices) out[i] = (stream[32 + i].toInt() xor ct[i].toInt()).toByte()
        return out
    }

    /** HSalsa20, used to turn an X25519 shared secret into a secretbox key. */
    fun hSalsa20(input: ByteArray, key: ByteArray, constant: ByteArray): ByteArray {
        var x0 = le32(constant, 0)
        var x1 = le32(key, 0)
        var x2 = le32(key, 4)
        var x3 = le32(key, 8)
        var x4 = le32(key, 12)
        var x5 = le32(constant, 4)
        var x6 = le32(input, 0)
        var x7 = le32(input, 4)
        var x8 = le32(input, 8)
        var x9 = le32(input, 12)
        var x10 = le32(constant, 8)
        var x11 = le32(key, 16)
        var x12 = le32(key, 20)
        var x13 = le32(key, 24)
        var x14 = le32(key, 28)
        var x15 = le32(constant, 12)
        repeat(10) {
            var u = x0 + x12
            x4 = x4 xor Integer.rotateLeft(u, 7)
            u = x4 + x0
            x8 = x8 xor Integer.rotateLeft(u, 9)
            u = x8 + x4
            x12 = x12 xor Integer.rotateLeft(u, 13)
            u = x12 + x8
            x0 = x0 xor Integer.rotateLeft(u, 18)

            u = x5 + x1
            x9 = x9 xor Integer.rotateLeft(u, 7)
            u = x9 + x5
            x13 = x13 xor Integer.rotateLeft(u, 9)
            u = x13 + x9
            x1 = x1 xor Integer.rotateLeft(u, 13)
            u = x1 + x13
            x5 = x5 xor Integer.rotateLeft(u, 18)

            u = x10 + x6
            x14 = x14 xor Integer.rotateLeft(u, 7)
            u = x14 + x10
            x2 = x2 xor Integer.rotateLeft(u, 9)
            u = x2 + x14
            x6 = x6 xor Integer.rotateLeft(u, 13)
            u = x6 + x2
            x10 = x10 xor Integer.rotateLeft(u, 18)

            u = x15 + x11
            x3 = x3 xor Integer.rotateLeft(u, 7)
            u = x3 + x15
            x7 = x7 xor Integer.rotateLeft(u, 9)
            u = x7 + x3
            x11 = x11 xor Integer.rotateLeft(u, 13)
            u = x11 + x7
            x15 = x15 xor Integer.rotateLeft(u, 18)

            u = x0 + x3
            x1 = x1 xor Integer.rotateLeft(u, 7)
            u = x1 + x0
            x2 = x2 xor Integer.rotateLeft(u, 9)
            u = x2 + x1
            x3 = x3 xor Integer.rotateLeft(u, 13)
            u = x3 + x2
            x0 = x0 xor Integer.rotateLeft(u, 18)

            u = x5 + x4
            x6 = x6 xor Integer.rotateLeft(u, 7)
            u = x6 + x5
            x7 = x7 xor Integer.rotateLeft(u, 9)
            u = x7 + x6
            x4 = x4 xor Integer.rotateLeft(u, 13)
            u = x4 + x7
            x5 = x5 xor Integer.rotateLeft(u, 18)

            u = x10 + x9
            x11 = x11 xor Integer.rotateLeft(u, 7)
            u = x11 + x10
            x8 = x8 xor Integer.rotateLeft(u, 9)
            u = x8 + x11
            x9 = x9 xor Integer.rotateLeft(u, 13)
            u = x9 + x8
            x10 = x10 xor Integer.rotateLeft(u, 18)

            u = x15 + x14
            x12 = x12 xor Integer.rotateLeft(u, 7)
            u = x12 + x15
            x13 = x13 xor Integer.rotateLeft(u, 9)
            u = x13 + x12
            x14 = x14 xor Integer.rotateLeft(u, 13)
            u = x14 + x13
            x15 = x15 xor Integer.rotateLeft(u, 18)
        }
        val out = ByteArray(32)
        putLe32(out, 0, x0)
        putLe32(out, 4, x5)
        putLe32(out, 8, x10)
        putLe32(out, 12, x15)
        putLe32(out, 16, x6)
        putLe32(out, 20, x7)
        putLe32(out, 24, x8)
        putLe32(out, 28, x9)
        return out
    }

    private fun sealToPublicKey(aesKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val pair = gen.generateKeyPair()
        val priv = pair.private as X25519PrivateKeyParameters
        val pub = (pair.public as X25519PublicKeyParameters).encoded
        val shared = boxKey(priv.encoded, peerPublic)
        val blake = Blake2bDigest(192)
        blake.update(pub, 0, pub.size)
        blake.update(peerPublic, 0, peerPublic.size)
        val nonce = ByteArray(24)
        blake.doFinal(nonce, 0)
        return pub + secretboxSeal(aesKey, nonce, shared)
    }

    private fun boxKey(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val shared = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), shared, 0)
        return hSalsa20(ByteArray(16), shared, "expand 32-byte k".toByteArray(Charsets.US_ASCII))
    }

    private fun twoFactorMethod(info: JSONObject): String {
        val sms = info.optBoolean("sms_two_factor_on")
        val totp = info.optBoolean("totp_two_factor_on")
        return when {
            sms && totp -> "either"
            totp -> "authenticator"
            else -> "SMS"
        }
    }

    private fun webEncryption(html: String): EncryptionKey? {
        val idRe = Regex(""""key_id"\s*:\s*"?(\d+)"?""")
        val pubRe = Regex(""""public_key"\s*:\s*"([0-9a-fA-F]+)"""")
        val verRe = Regex(""""version"\s*:\s*"?(\d+)"?""")
        for (match in idRe.findAll(html)) {
            val start = (match.range.first - 240).coerceAtLeast(0)
            val end = (match.range.last + 520).coerceAtMost(html.length)
            val window = html.substring(start, end)
            val pub = pubRe.find(window)?.groupValues?.get(1) ?: continue
            val version = verRe.find(window)?.groupValues?.get(1) ?: continue
            if (version != "10" || pub.length != 64) continue
            val id = match.groupValues[1].toIntOrNull() ?: continue
            return EncryptionKey(id, pub, KeyKind.WEB)
        }
        return null
    }

    private fun sprinkle(html: String): Triple<String, Int, Boolean>? {
        val nameRe = Regex(""""param_name"\s*:\s*"([A-Za-z0-9_]+)"""")
        for (match in nameRe.findAll(html)) {
            val start = (match.range.first - 80).coerceAtLeast(0)
            val end = (match.range.last + 240).coerceAtMost(html.length)
            val window = html.substring(start, end)
            val anchor = window.indexOf("should_randomize")
            if (anchor < 0) continue
            val version = Regex(""""version"\s*:\s*"?(\d+)"?""").findAll(window)
                .minByOrNull { kotlin.math.abs(it.range.first - anchor) }
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val randomize = Regex(""""should_randomize"\s*:\s*(true|false)""").find(window)?.groupValues?.get(1) == "true"
            return Triple(match.groupValues[1], version, randomize)
        }
        return null
    }

    private fun csrfFromCookies(setCookies: List<String>): String? {
        val re = Regex("""(?:^|;\s*)csrftoken=([^;]+)""")
        for (cookie in setCookies) {
            re.find(cookie)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun csrfFromHtml(html: String): String? = jsonString(html, "csrf_token")

    private fun jsonString(html: String, field: String): String? =
        Regex(""""${Regex.escape(field)}"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun sentence(value: String) = value.isNotBlank() && value.contains(' ')

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or
            ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or
            ((b[o + 3].toInt() and 0xff) shl 24)

    private fun putLe32(out: ByteArray, o: Int, v: Int) {
        out[o] = v.toByte()
        out[o + 1] = (v ushr 8).toByte()
        out[o + 2] = (v ushr 16).toByte()
        out[o + 3] = (v ushr 24).toByte()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            out[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }
}
