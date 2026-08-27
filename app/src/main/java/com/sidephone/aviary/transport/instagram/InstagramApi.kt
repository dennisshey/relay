package com.sidephone.aviary.transport.instagram

import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Thin client over Instagram's private mobile API (i.instagram.com/api/v1). Presents the fake
 * device from [InstagramDevice] and the saved session from [InstagramAccount] on every call, and
 * captures the rotating auth headers Instagram hands back. Covers login (with the enc_password
 * flow + 2FA/checkpoint detection), the DM inbox, thread history, and sending text.
 */
class InstagramApi(
    private val device: InstagramDevice,
    private val account: InstagramAccount,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val base = "https://i.instagram.com/api/v1"

    // ---- results -----------------------------------------------------------

    sealed class LoginResult {
        data object Success : LoginResult()
        data class TwoFactor(val identifier: String, val username: String) : LoginResult()
        data class Challenge(val apiPath: String) : LoginResult()
        data class Error(val message: String) : LoginResult()
    }

    // ---- headers -----------------------------------------------------------

    private fun Request.Builder.commonHeaders(): Request.Builder = apply {
        header("User-Agent", device.userAgent)
        header("X-IG-App-ID", InstagramDevice.APP_ID)
        header("X-IG-Capabilities", InstagramDevice.CAPABILITIES)
        header("X-IG-Connection-Type", "WIFI")
        header("X-IG-Device-ID", device.deviceId)
        header("X-IG-Family-Device-ID", device.familyDeviceId)
        header("X-IG-Android-ID", device.androidId)
        header("X-IG-Device-Locale", "en_US")
        header("X-IG-Mapped-Locale", "en_US")
        header("Accept-Language", "en-US")
        header("X-IG-WWW-Claim", account.wwwClaim ?: "0")
        header("X-FB-HTTP-Engine", "Liger")
        account.mid?.let { header("X-MID", it) }
        account.authorization?.let { header("Authorization", it) }
        account.csrfToken?.let { header("Cookie", "csrftoken=$it; sessionid=${account.sessionId ?: ""}") }
    }

    /** Pull the rotating auth headers/cookies Instagram sets on a response into the account. */
    private fun capture(resp: Response) {
        resp.header("IG-Set-Authorization")?.takeIf { it.isNotBlank() }?.let { account.authorization = it }
        resp.header("X-IG-Set-WWW-Claim")?.takeIf { it.isNotBlank() }?.let { account.wwwClaim = it }
        resp.header("ig-set-x-mid")?.let { account.mid = it }
        for (c in resp.headers("Set-Cookie")) {
            val (k, v) = c.substringBefore(";").split("=", limit = 2).let { it[0].trim() to it.getOrElse(1) { "" } }
            when (k) {
                "sessionid" -> account.sessionId = v
                "csrftoken" -> account.csrfToken = v
                "mid" -> account.mid = v
                "rur" -> account.rur = v
                "ds_user_id" -> account.userId = v
            }
        }
    }

    private fun signedBody(json: JSONObject) =
        FormBody.Builder().add("signed_body", "SIGNATURE.$json").build()

    // ---- login -------------------------------------------------------------

    /** Pre-login sync: fetches the password-encryption public key + seeds mid/csrf cookies. */
    private data class PubKey(val id: Int, val key: String)
    private fun preLoginSync(): PubKey? {
        val body = JSONObject().apply {
            put("id", device.deviceId)
            put("server_config_retrieval", "1")
        }
        val req = Request.Builder().url("$base/qe/sync/").post(signedBody(body)).commonHeaders()
            .header("X-DEVICE-ID", device.deviceId).build()
        http.newCall(req).execute().use { resp ->
            capture(resp)
            val id = resp.header("ig-set-password-encryption-key-id")?.toIntOrNull()
            val key = resp.header("ig-set-password-encryption-pub-key")
            resp.body?.close()
            if (id != null && key != null) return PubKey(id, key)
        }
        return null
    }

    fun login(username: String, password: String): LoginResult {
        val pub = preLoginSync() ?: return LoginResult.Error("Couldn't reach Instagram (no key)")
        val enc = InstagramCrypto.encryptPassword(password, pub.id, pub.key)
        val payload = JSONObject().apply {
            put("jazoest", InstagramCrypto.jazoest(device.phoneId))
            put("country_codes", JSONArray().put(JSONObject().apply {
                put("country_code", "1"); put("source", JSONArray().put("default"))
            }))
            put("phone_id", device.phoneId)
            put("enc_password", enc)
            put("username", username)
            put("adid", "")
            put("guid", device.deviceId)
            put("device_id", device.androidId)
            put("google_tokens", "[]")
            put("login_attempt_count", "0")
        }
        val req = Request.Builder().url("$base/accounts/login/").post(signedBody(payload))
            .commonHeaders().build()
        http.newCall(req).execute().use { resp ->
            capture(resp)
            val txt = resp.body?.string().orEmpty()
            val j = runCatching { JSONObject(txt) }.getOrNull()
                ?: return LoginResult.Error("Bad response (${resp.code})")
            if (j.optString("status") == "ok" && j.has("logged_in_user")) {
                account.userId = j.getJSONObject("logged_in_user").get("pk").toString()
                account.username = j.getJSONObject("logged_in_user").optString("username", username)
                Log.i(TAG, "logged in as ${account.username} (${account.userId})")
                return LoginResult.Success
            }
            if (j.optBoolean("two_factor_required")) {
                val info = j.optJSONObject("two_factor_info")
                return LoginResult.TwoFactor(
                    info?.optString("two_factor_identifier").orEmpty(),
                    info?.optString("username").ifBlankOr(username),
                )
            }
            if (j.optString("message") == "challenge_required") {
                val path = j.optJSONObject("challenge")?.optString("api_path").orEmpty()
                return LoginResult.Challenge(path)
            }
            return LoginResult.Error(j.optString("message").ifBlankOr(j.optString("error_type", "Login failed")))
        }
    }

    fun submitTwoFactor(code: String, identifier: String, username: String): LoginResult {
        val payload = JSONObject().apply {
            put("verification_code", code)
            put("two_factor_identifier", identifier)
            put("username", username)
            put("device_id", device.androidId)
            put("guid", device.deviceId)
            put("verification_method", "1")
        }
        val req = Request.Builder().url("$base/accounts/two_factor_login/").post(signedBody(payload))
            .commonHeaders().build()
        http.newCall(req).execute().use { resp ->
            capture(resp)
            val j = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                ?: return LoginResult.Error("Bad 2FA response")
            if (j.optString("status") == "ok" && j.has("logged_in_user")) {
                account.userId = j.getJSONObject("logged_in_user").get("pk").toString()
                account.username = j.getJSONObject("logged_in_user").optString("username", username)
                return LoginResult.Success
            }
            return LoginResult.Error(j.optString("message").ifBlankOr("Invalid code"))
        }
    }

    // ---- starting conversations -------------------------------------------

    data class IgUser(val pk: String, val username: String, val fullName: String, val profilePic: String?)

    /** Search Instagram users by name or username for the compose picker. */
    fun searchUsers(query: String): List<IgUser> {
        val q = java.net.URLEncoder.encode(query.trim().removePrefix("@"), "UTF-8")
        val req = Request.Builder()
            .url("$base/users/search/?q=$q&count=20&timezone_offset=0")
            .get().commonHeaders().build()
        return http.newCall(req).execute().use { resp ->
            capture(resp)
            val users = runCatching { JSONObject(resp.body?.string().orEmpty()).optJSONArray("users") }.getOrNull()
                ?: return emptyList()
            (0 until users.length()).mapNotNull { i ->
                val u = users.optJSONObject(i) ?: return@mapNotNull null
                val pk = u.opt("pk")?.toString()?.ifBlank { null } ?: return@mapNotNull null
                IgUser(
                    pk = pk,
                    username = u.optString("username"),
                    fullName = u.optString("full_name"),
                    profilePic = u.optString("profile_pic_url").ifBlank { null },
                )
            }
        }
    }

    /** Resolve a username to its numeric user id (pk), or null if not found. */
    fun resolveUserId(username: String): String? {
        val handle = username.trim().removePrefix("@")
        val req = Request.Builder()
            .url("$base/users/$handle/usernameinfo/")
            .get().commonHeaders().build()
        http.newCall(req).execute().use { resp ->
            capture(resp)
            val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
            if (json?.optString("status") != "ok") { Log.w(TAG, "resolveUserId failed code=${resp.code}"); return null }
            return json.optJSONObject("user")?.opt("pk")?.toString()?.ifBlank { null }
        }
    }

    /** Result of first-message-to-a-new-recipient: the created message id and the thread it made. */
    data class NewThreadSend(val itemId: String?, val threadId: String?)

    /** Send the first message to a recipient by user id (creates the thread). */
    fun sendTextToUser(userId: String, text: String): NewThreadSend {
        val clientContext = UUID.randomUUID().toString()
        val form = FormBody.Builder()
            .add("action", "send_item")
            .add("recipient_users", "[[$userId]]")
            .add("client_context", clientContext)
            .add("mutation_token", clientContext)
            .add("device_id", device.androidId)
            .add("text", text)
        val req = Request.Builder().url("$base/direct_v2/threads/broadcast/text/").post(form.build())
            .commonHeaders().build()
        http.newCall(req).execute().use { resp ->
            capture(resp)
            val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
            if (json?.optString("status") != "ok") {
                Log.w(TAG, "sendTextToUser failed code=${resp.code} body=${json?.toString()?.take(300)}")
                return NewThreadSend(null, null)
            }
            val payload = json.optJSONObject("payload")
            val itemId = payload?.optString("item_id")?.ifBlank { null }
            val threadId = payload?.optString("thread_id")?.ifBlank { null }
            Log.i(TAG, "sendTextToUser ok user=$userId item_id=$itemId thread=$threadId")
            return NewThreadSend(itemId, threadId)
        }
    }

    /** Get (or create) the DM thread with a single recipient [userId]; returns its thread_id. */
    fun threadIdForUser(userId: String): String? {
        val req = Request.Builder()
            .url("$base/direct_v2/threads/get_by_participants/?recipient_users=[$userId]")
            .get().commonHeaders().build()
        http.newCall(req).execute().use { resp ->
            capture(resp)
            val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
            return json?.optJSONObject("thread")?.optString("thread_id")?.ifBlank { null }
        }
    }

    // ---- direct messages ---------------------------------------------------

    /** One inbox thread with its most recent items, as raw JSON for the receiver to map. */
    fun fetchInbox(): JSONObject? {
        val req = Request.Builder()
            .url("$base/direct_v2/inbox/?visual_message_return_type=unseen&thread_message_limit=10&persistentBadging=true&limit=20")
            .get().commonHeaders().build()
        http.newCall(req).execute().use { resp ->
            capture(resp)
            return runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
        }
    }

    /** Find a message's client_context by scanning the current inbox — needed to thread a reply. */
    fun clientContextFor(itemId: String): String? {
        val threads = fetchInbox()?.optJSONObject("inbox")?.optJSONArray("threads") ?: return null
        for (i in 0 until threads.length()) {
            val items = threads.optJSONObject(i)?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val it = items.optJSONObject(j) ?: continue
                if (it.optString("item_id") == itemId)
                    return it.optString("client_context").ifBlank { null }
            }
        }
        return null
    }

    /**
     * Send a text message to an existing thread. When [repliedToItemId] is set, it's sent as a
     * swipe-to-reply quoting that item. Returns the server's item_id on ack, or null on failure —
     * the caller stamps that id onto its optimistic row so the inbox poll doesn't duplicate it.
     */
    fun sendText(
        threadId: String,
        text: String,
        repliedToItemId: String? = null,
        repliedToClientContext: String? = null,
    ): String? {
        val clientContext = UUID.randomUUID().toString()
        val form = FormBody.Builder()
            .add("action", "send_item")
            .add("thread_ids", "[$threadId]")
            .add("client_context", clientContext)
            .add("mutation_token", clientContext)
            .add("device_id", device.androidId)
            .add("text", text)
        // Thread as a swipe-to-reply: Instagram needs BOTH the quoted item's id and its
        // client_context, otherwise the message lands as a plain (unthreaded) message.
        if (!repliedToItemId.isNullOrBlank()) {
            form.add("replied_to_item_id", repliedToItemId)
            if (!repliedToClientContext.isNullOrBlank())
                form.add("replied_to_client_context", repliedToClientContext)
        }
        val req = Request.Builder().url("$base/direct_v2/threads/broadcast/text/").post(form.build())
            .commonHeaders().build()
        http.newCall(req).execute().use { resp ->
            capture(resp)
            val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
            val ok = json?.optString("status") == "ok"
            if (!ok) {
                Log.w(TAG, "sendText failed code=${resp.code} body=${resp.let { json?.toString()?.take(300) }}")
                return null
            }
            // payload.item_id identifies the message so the poll can dedup it.
            val itemId = json.optJSONObject("payload")?.optString("item_id")?.ifBlank { null }
            Log.i(TAG, "sendText ok thread=$threadId item_id=$itemId reply=${!repliedToItemId.isNullOrBlank()}")
            return itemId
        }
    }

    /**
     * Send a JPEG photo to a DM thread: upload the bytes via rupload_igphoto, then configure the
     * upload into the thread. Returns the server item_id on success, or null on failure. Optional
     * reply params thread it as a swipe-to-reply (like [sendText]).
     */
    fun sendPhoto(
        threadId: String,
        jpeg: ByteArray,
        repliedToItemId: String? = null,
        repliedToClientContext: String? = null,
    ): String? {
        val uploadId = System.currentTimeMillis().toString()
        val name = "${uploadId}_0_${kotlin.random.Random.nextLong(1_000_000_000L, 9_999_999_999L)}"
        val ruploadParams = JSONObject().apply {
            put("retry_context", "{\"num_step_auto_retry\":0,\"num_reupload\":0,\"num_step_manual_retry\":0}")
            put("media_type", "1")
            put("xsharing_user_ids", "[]")
            put("upload_id", uploadId)
            put("image_compression", "{\"lib_name\":\"moz\",\"lib_version\":\"3.1.m\",\"quality\":\"80\"}")
        }.toString()
        val uploadReq = Request.Builder()
            .url("https://i.instagram.com/rupload_igphoto/$name")
            .post(jpeg.toRequestBody("application/octet-stream".toMediaType()))
            .commonHeaders()
            .header("X-Instagram-Rupload-Params", ruploadParams)
            .header("X-Entity-Type", "image/jpeg")
            .header("Offset", "0")
            .header("X-Entity-Name", name)
            .header("X-Entity-Length", jpeg.size.toString())
            .header("X_FB_PHOTO_WATERFALL_ID", UUID.randomUUID().toString())
            .build()
        http.newCall(uploadReq).execute().use { resp ->
            capture(resp)
            val ok = runCatching {
                JSONObject(resp.body?.string().orEmpty()).optString("status") == "ok"
            }.getOrDefault(false)
            if (!ok) { Log.w(TAG, "photo rupload failed code=${resp.code}"); return null }
        }
        // Configure the uploaded photo into the thread.
        val clientContext = UUID.randomUUID().toString()
        val form = FormBody.Builder()
            .add("action", "send_item")
            .add("thread_ids", "[$threadId]")
            .add("client_context", clientContext)
            .add("mutation_token", clientContext)
            .add("device_id", device.androidId)
            .add("upload_id", uploadId)
            .add("allow_full_aspect_ratio", "true")
        if (!repliedToItemId.isNullOrBlank()) {
            form.add("replied_to_item_id", repliedToItemId)
            if (!repliedToClientContext.isNullOrBlank())
                form.add("replied_to_client_context", repliedToClientContext)
        }
        val req = Request.Builder()
            .url("$base/direct_v2/threads/broadcast/configure_photo/")
            .post(form.build()).commonHeaders().build()
        http.newCall(req).execute().use { resp ->
            capture(resp)
            val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
            if (json?.optString("status") != "ok") {
                Log.w(TAG, "configure_photo failed code=${resp.code}"); return null
            }
            return json.optJSONObject("payload")?.optString("item_id")?.ifBlank { null }
        }
    }

    /**
     * React to a DM message ([itemId]) with [emoji]. ❤️ uses Instagram's classic "like" reaction;
     * any other emoji is sent as an emoji reaction. [add]=false removes the reaction. Returns true
     * on ack.
     */
    fun reactToMessage(threadId: String, itemId: String, emoji: String, add: Boolean): Boolean {
        val clientContext = UUID.randomUUID().toString()
        val isHeart = emoji == "❤️" || emoji == "❤" || emoji == "♥"
        val form = FormBody.Builder()
            .add("action", "send_item")
            .add("thread_ids", "[$threadId]")
            .add("client_context", clientContext)
            .add("mutation_token", clientContext)
            .add("device_id", device.androidId)
            .add("item_type", "reaction")
            .add("reaction_type", if (isHeart) "like" else "emoji")
            .add("reaction_status", if (add) "created" else "deleted")
            .add("node_type", "item")
            .add("item_id", itemId)
        if (!isHeart) form.add("emoji", emoji)
        val req = Request.Builder()
            .url("$base/direct_v2/threads/broadcast/reaction/")
            .post(form.build()).commonHeaders().build()
        http.newCall(req).execute().use { resp ->
            capture(resp)
            val body = resp.body?.string().orEmpty()
            val ok = runCatching { JSONObject(body).optString("status") == "ok" }.getOrDefault(false)
            if (!ok) Log.w(TAG, "reaction failed code=${resp.code} body=${body.take(200)}")
            return ok
        }
    }

    /** Fetch a public CDN asset (profile picture, media thumbnail). No auth headers needed. */
    fun download(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", device.userAgent).get().build())
            .execute().use { if (it.isSuccessful) it.body?.bytes() else null }
    }.getOrNull()

    private fun String?.ifBlankOr(fallback: String) = this?.takeIf { it.isNotBlank() } ?: fallback

    companion object { private const val TAG = "InstagramApi" }
}
