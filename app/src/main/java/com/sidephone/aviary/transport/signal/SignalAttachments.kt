package com.sidephone.aviary.transport.signal

import android.net.Uri
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import org.json.JSONObject
import java.security.SecureRandom

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

    /** Download, authenticate and decrypt without whole-file allocations. Caller owns the result. */
    fun download(pointer: Pointer, directory: File): File? = runCatching {
        val key = pointer.key ?: error("Missing attachment key")
        require(pointer.size >= 0 && pointer.size.toLong() <= com.sidephone.aviary.data.MediaStore.MAX_ATTACHMENT_BYTES)
        val id = pointer.cdnKey?.let { Uri.encode(it) } ?: pointer.cdnId?.toString()
            ?: error("Missing attachment id")
        val encrypted = File.createTempFile("signal-download-", ".tmp", directory)
        val plain = File.createTempFile("signal-plain-", ".tmp", directory)
        try {
            val downloadClient = client.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(30, java.util.concurrent.TimeUnit.MINUTES).build()
            downloadClient.newCall(Request.Builder().url("${cdnHost(pointer.cdnNumber)}/attachments/$id").build())
                .execute().use { response ->
                    check(response.isSuccessful) { "Attachment download HTTP ${response.code}" }
                    val body = response.body ?: error("Empty attachment response")
                    body.byteStream().use { input ->
                        encrypted.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= com.sidephone.aviary.data.MediaStore.MAX_ATTACHMENT_BYTES + 1024 * 1024) {
                                    "Attachment exceeds download limit"
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            AttachmentCipher.decrypt(encrypted, plain, key, pointer.digest, pointer.size.toLong())
            plain
        } catch (e: Exception) { plain.delete(); throw e }
        finally { encrypted.delete() }
    }.onFailure { Log.w(TAG, "attachment download failed: ${it.message}") }.getOrNull()

    private fun cdnHost(cdnNumber: Int): String = when (cdnNumber) {
        2 -> "https://cdn2.signal.org"
        3 -> "https://cdn3.signal.org"
        else -> "https://cdn.signal.org"
    }

    /**
     * Encrypt + upload media, returning a serialized AttachmentPointer proto. Encryption is AES-256-CBC + HMAC-SHA256 with a random key.
     */
    fun upload(
        authToken: String,
        media: File,
        contentType: String?,
        fileName: String?,
        chatBaseUrl: String = "https://chat.signal.org",
    ): ByteArray = runCatching {
        val aesKey = randomBytes(32)
        val macKey = randomBytes(32)
        val iv = randomBytes(16)
        require(media.isFile && media.length() <= Int.MAX_VALUE) { "Invalid attachment file" }
        val file = File.createTempFile("signal-upload-", ".tmp", media.parentFile)
        try {
            val digest = AttachmentCipher.encrypt(media, file, aesKey, macKey, iv)

            // 1. Obtain an upload form.
            val formReq = Request.Builder()
                .url("$chatBaseUrl/v4/attachments/form/upload")
                .header("Authorization", "Basic $authToken").get().build()
            val form = client.newCall(formReq).execute().use { resp ->
                check(resp.isSuccessful) { "Attachment upload form HTTP ${resp.code}" }
                JSONObject(resp.body?.string() ?: error("Empty upload form"))
            }
            val cdn = form.getInt("cdn")
            val cdnKey = form.getString("key")
            val signedUploadLocation = form.getString("signedUploadLocation")
            val headers = form.getJSONObject("headers")

            // 2. Upload the encrypted bytes.
            uploadBytes(cdn, signedUploadLocation, headers, file)

            // 3. Build the AttachmentPointer.
            MiniProto.Writer()
                .string(15, cdnKey)                 // cdnKey
                .string(2, contentType ?: "application/octet-stream")
                .bytes(3, aesKey + macKey)          // key
                .varint(4, media.length())     // size
                .bytes(6, digest)                   // digest
                .varint(14, cdn.toLong())           // cdnNumber
                .also { if (!fileName.isNullOrBlank()) it.string(7, fileName) }
                .toByteArray()
        } finally { file.delete() }
    }.getOrThrow()

    private fun uploadBytes(
        cdn: Int, signedUploadLocation: String, headers: JSONObject, file: File,
    ) {
        val octet = "application/offset+octet-stream".toMediaType()
        if (cdn == 2) {
            // POST to get the resumable URL, then PUT the bytes.
            val post = Request.Builder().url(signedUploadLocation)
                .post(ByteArray(0).toRequestBody())
                .applyHeaders(headers).header("Content-Type", "application/octet-stream").build()
            val location = client.newCall(post).execute().use {
                check(it.isSuccessful) { "Attachment upload session HTTP ${it.code}" }
                it.header("location") ?: error("Missing attachment upload location")
            }
            val put = Request.Builder().url(location)
                .put(file.asRequestBody("application/octet-stream".toMediaType()))
                .header("Content-Range", "bytes 0-${file.length() - 1}/${file.length()}").build()
            client.newCall(put).execute().use { check(it.isSuccessful) { "Attachment upload HTTP ${it.code}" } }
        } else {
            // CDN3: TUS creation-with-upload (single POST).
            val post = Request.Builder().url(signedUploadLocation)
                .post(file.asRequestBody(octet))
                .applyHeaders(headers)
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Length", file.length().toString())
                .header("Content-Type", "application/offset+octet-stream")
                .build()
            client.newCall(post).execute().use { check(it.isSuccessful) { "Attachment upload HTTP ${it.code}" } }
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
