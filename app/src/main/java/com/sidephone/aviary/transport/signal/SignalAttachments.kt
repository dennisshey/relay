package com.sidephone.aviary.transport.signal

import android.net.Uri
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads and decrypts Signal message attachments. An attachment on the CDN is
 * `[16-byte IV][AES-256-CBC ciphertext][32-byte HMAC-SHA256]`, encrypted with the
 * 64-byte key from the AttachmentPointer (aesKey||macKey). Everything is verified
 * and decrypted on-device.
 */
class SignalAttachments(private val client: OkHttpClient) {

    data class Pointer(
        val cdnId: Long?,
        val cdnKey: String?,
        val cdnNumber: Int,
        val contentType: String?,
        val key: ByteArray?,
        val size: Int,
        val digest: ByteArray?,
        val fileName: String?,
    )

    /** Parse a DataMessage AttachmentPointer proto. */
    fun parse(bytes: ByteArray): Pointer {
        val f = MiniProto.parse(bytes)
        return Pointer(
            cdnId = MiniProto.varintField(f, 1),
            cdnKey = MiniProto.stringField(f, 15),
            cdnNumber = MiniProto.varintField(f, 14)?.toInt() ?: 0,
            contentType = MiniProto.stringField(f, 2),
            key = MiniProto.bytesField(f, 3),
            size = MiniProto.varintField(f, 4)?.toInt() ?: 0,
            digest = MiniProto.bytesField(f, 6),
            fileName = MiniProto.stringField(f, 7),
        )
    }

    /** Download + verify + decrypt an attachment; returns the plaintext media bytes. */
    fun download(pointer: Pointer): ByteArray? = runCatching {
        val key = pointer.key ?: return null
        if (key.size < 64) return null
        val id = pointer.cdnKey?.let { Uri.encode(it) }
            ?: pointer.cdnId?.toString() ?: return null
        val url = "${cdnHost(pointer.cdnNumber)}/attachments/$id"

        val encrypted = client.newCall(Request.Builder().url(url).get().build()).execute()
            .use { resp -> if (!resp.isSuccessful) return null else resp.body?.bytes() ?: return null }

        if (encrypted.size < 16 + 32) return null
        pointer.digest?.let { digest ->
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(encrypted), digest)) {
                Log.w(TAG, "attachment digest mismatch"); return null
            }
        }
        val aesKey = key.copyOfRange(0, 32)
        val macKey = key.copyOfRange(32, 64)
        val theirMac = encrypted.copyOfRange(encrypted.size - 32, encrypted.size)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
            .doFinal(encrypted.copyOfRange(0, encrypted.size - 32))
        if (!MessageDigest.isEqual(mac, theirMac)) { Log.w(TAG, "attachment MAC mismatch"); return null }

        val iv = encrypted.copyOfRange(0, 16)
        val ciphertext = encrypted.copyOfRange(16, encrypted.size - 32)
        val plain = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        }.doFinal(ciphertext)
        // Plaintext is bucket-padded; the pointer's size is the real length.
        if (pointer.size in 1..plain.size) plain.copyOf(pointer.size) else plain
    }.onFailure { Log.w(TAG, "attachment download failed: ${it.message}") }.getOrNull()

    private fun cdnHost(cdnNumber: Int): String = when (cdnNumber) {
        2 -> "https://cdn2.signal.org"
        3 -> "https://cdn3.signal.org"
        else -> "https://cdn.signal.org"
    }

    /**
     * Encrypt + upload media, returning a serialized AttachmentPointer proto (or
     * null on failure). Encryption is AES-256-CBC + HMAC-SHA256 with a random key.
     */
    fun upload(
        authToken: String,
        media: ByteArray,
        contentType: String?,
        fileName: String?,
        chatBaseUrl: String = "https://chat.signal.org",
    ): ByteArray? = runCatching {
        val aesKey = randomBytes(32)
        val macKey = randomBytes(32)
        val iv = randomBytes(16)
        val ct = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        }.doFinal(media)
        val body = iv + ct
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
            .doFinal(body)
        val file = body + mac
        val digest = MessageDigest.getInstance("SHA-256").digest(file)

        // 1. Obtain an upload form.
        val formReq = Request.Builder()
            .url("$chatBaseUrl/v4/attachments/form/upload")
            .header("Authorization", "Basic $authToken").get().build()
        val form = client.newCall(formReq).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w(TAG, "upload form HTTP ${resp.code}"); return null }
            JSONObject(resp.body?.string() ?: return null)
        }
        val cdn = form.getInt("cdn")
        val cdnKey = form.getString("key")
        val signedUploadLocation = form.getString("signedUploadLocation")
        val headers = form.getJSONObject("headers")

        // 2. Upload the encrypted bytes.
        if (!uploadBytes(cdn, signedUploadLocation, headers, file)) return null

        // 3. Build the AttachmentPointer.
        MiniProto.Writer()
            .string(15, cdnKey)                 // cdnKey
            .string(2, contentType ?: "application/octet-stream")
            .bytes(3, aesKey + macKey)          // key
            .varint(4, media.size.toLong())     // size
            .bytes(6, digest)                   // digest
            .varint(14, cdn.toLong())           // cdnNumber
            .also { if (!fileName.isNullOrBlank()) it.string(7, fileName) }
            .toByteArray()
    }.onFailure { Log.w(TAG, "attachment upload failed: ${it.message}") }.getOrNull()

    private fun uploadBytes(
        cdn: Int, signedUploadLocation: String, headers: JSONObject, file: ByteArray,
    ): Boolean {
        val octet = "application/offset+octet-stream".toMediaType()
        return if (cdn == 2) {
            // POST to get the resumable URL, then PUT the bytes.
            val post = Request.Builder().url(signedUploadLocation)
                .post(ByteArray(0).toRequestBody())
                .applyHeaders(headers).header("Content-Type", "application/octet-stream").build()
            val location = client.newCall(post).execute().use { it.header("location") } ?: return false
            val put = Request.Builder().url(location)
                .put(file.toRequestBody("application/octet-stream".toMediaType()))
                .header("Content-Range", "bytes 0-${file.size - 1}/${file.size}").build()
            client.newCall(put).execute().use { it.isSuccessful }
        } else {
            // CDN3: TUS creation-with-upload (single POST).
            val post = Request.Builder().url(signedUploadLocation)
                .post(file.toRequestBody(octet))
                .applyHeaders(headers)
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Length", file.size.toString())
                .header("Content-Type", "application/offset+octet-stream")
                .build()
            client.newCall(post).execute().use { it.isSuccessful }
        }
    }

    private fun Request.Builder.applyHeaders(headers: JSONObject): Request.Builder = apply {
        headers.keys().forEach { k ->
            if (!k.equals("host", ignoreCase = true)) header(k, headers.getString(k))
        }
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    companion object {
        private const val TAG = "SignalAttachments"
    }
}
