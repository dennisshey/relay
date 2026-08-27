package com.sidephone.aviary.transport.signal

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.zkgroup.ServerPublicParams
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
import org.signal.libsignal.zkgroup.auth.ClientZkAuthOperations
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.libsignal.zkgroup.groups.UuidCiphertext
import java.security.SecureRandom
import java.util.UUID

/**
 * Resolves a GroupV2 title from its master key. This is the full Signal groups
 * flow: obtain a zkgroup auth credential from the chat service, present it to the
 * storage service to fetch the encrypted group state, and decrypt the title blob
 * locally with the group's secret params. All decryption happens on-device.
 */
class SignalGroups(
    private val client: OkHttpClient,
    private val chatUrl: String = "https://chat.signal.org",
    private val storageUrl: String = "https://storage.signal.org",
    private val cdnUrl: String = "https://cdn.signal.org",
) {
    data class Info(
        val title: String?,
        val avatar: ByteArray?,
        val memberAcis: List<String> = emptyList(),
        val revision: Int = 0,
    )

    fun fetch(authToken: String, aci: String, pni: String, masterKey: ByteArray): Info =
        runCatching {
            val secretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
            val serverParams = ServerPublicParams(Base64.decode(SERVER_PUBLIC_PARAMS, Base64.NO_WRAP))
            val authOps = ClientZkAuthOperations(serverParams)

            val today = System.currentTimeMillis() / 1000L / 86_400L * 86_400L
            val credential = fetchAuthCredential(authToken, today) ?: return Info(null, null)
            val authCredential = authOps.receiveAuthCredentialWithPniAsServiceId(
                ServiceId.Aci(UUID.fromString(aci)),
                ServiceId.Pni(UUID.fromString(pni)),
                today,
                AuthCredentialWithPniResponse(credential),
            )
            val presentation = authOps.createAuthCredentialPresentation(
                SecureRandom(), secretParams, authCredential,
            )
            val groupAuth = "Basic " + Base64.encodeToString(
                ("${hex(secretParams.publicParams.serialize())}:${hex(presentation.serialize())}")
                    .toByteArray(),
                Base64.NO_WRAP,
            )

            val group = fetchGroupState(groupAuth) ?: return Info(null, null)
            // GroupResponse.group = 1; Group.title = 2, avatarUrl = 3.
            val groupProto = MiniProto.parse(MiniProto.bytesField(MiniProto.parse(group), 1)
                ?: return Info(null, null))
            val cipher = ClientZkGroupCipher(secretParams)
            val title = MiniProto.bytesField(groupProto, 2)?.let {
                // GroupAttributeBlob.title = 1
                MiniProto.stringField(MiniProto.parse(cipher.decryptBlob(it)), 1)
            }?.takeIf { it.isNotBlank() }
            val avatar = MiniProto.stringField(groupProto, 3)?.takeIf { it.isNotBlank() }
                ?.let { downloadAvatar(it, cipher) }
            val revision = MiniProto.varintField(groupProto, 6)?.toInt() ?: 0
            // Group.members = 7 (repeated); Member.userId = 1 (encrypted UuidCiphertext).
            val members = groupProto.filter { it.number == 7 }.mapNotNull { field ->
                field.bytes?.let { MiniProto.bytesField(MiniProto.parse(it), 1) }
                    ?.let { runCatching { cipher.decrypt(UuidCiphertext(it)).rawUUID.toString() }.getOrNull() }
            }
            Info(title, avatar, members, revision)
        }.onFailure { Log.w("SignalGroups", "group fetch failed: ${it.message}") }
            .getOrDefault(Info(null, null))

    private fun downloadAvatar(path: String, cipher: ClientZkGroupCipher): ByteArray? {
        val request = Request.Builder().url("$cdnUrl/$path").get().build()
        val encrypted = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null else resp.body?.bytes() ?: return null
        }
        // GroupAttributeBlob.avatar = 2
        return MiniProto.bytesField(MiniProto.parse(cipher.decryptBlob(encrypted)), 2)
    }

    private fun fetchAuthCredential(authToken: String, today: Long): ByteArray? {
        val end = today + 7 * 86_400L
        val request = Request.Builder()
            .url("$chatUrl/v1/certificate/auth/group?redemptionStartSeconds=$today&redemptionEndSeconds=$end")
            .header("Authorization", "Basic $authToken")
            .get()
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string() ?: return null
        }
        val credentials = JSONObject(body).getJSONArray("credentials")
        for (i in 0 until credentials.length()) {
            val c = credentials.getJSONObject(i)
            if (c.getLong("redemptionTime") == today) {
                return Base64.decode(c.getString("credential"), Base64.NO_WRAP)
            }
        }
        return null
    }

    private fun fetchGroupState(groupAuth: String): ByteArray? {
        val request = Request.Builder()
            .url("$storageUrl/v2/groups/")
            .header("Authorization", groupAuth)
            .get()
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        // Signal production zkgroup server public params (from Signal-Android BuildConfig).
        private const val SERVER_PUBLIC_PARAMS =
            "AMhf5ywVwITZMsff/eCyudZx9JDmkkkbV6PInzG4p8x3VqVJSFiMvnvlEKWuRob/1eaIetR31IYeAbm0NdOuHH8Qi+Rexi1wLlpzIo1gstHWBfZzy1+qHRV5A4TqPp15YzBPm0WSggW6PbSn+F4lf57VCnHF7p8SvzAA2ZZJPYJURt8X7bbg+H3i+PEjH9DXItNEqs2sNcug37xZQDLm7X36nOoGPs54XsEGzPdEV+itQNGUFEjY6X9Uv+Acuks7NpyGvCoKxGwgKgE5XyJ+nNKlyHHOLb6N1NuHyBrZrgtY/JYJHRooo5CEqYKBqdFnmbTVGEkCvJKxLnjwKWf+fEPoWeQFj5ObDjcKMZf2Jm2Ae69x+ikU5gBXsRmoF94GXTLfN0/vLt98KDPnxwAQL9j5V1jGOY8jQl6MLxEs56cwXN0dqCnImzVH3TZT1cJ8SW1BRX6qIVxEzjsSGx3yxF3suAilPMqGRp4ffyopjMD1JXiKR2RwLKzizUe5e8XyGOy9fplzhw3jVzTRyUZTRSZKkMLWcQ/gv0E4aONNqs4P+NameAZYOD12qRkxosQQP5uux6B2nRyZ7sAV54DgFyLiRcq1FvwKw2EPQdk4HDoePrO/RNUbyNddnM/mMgj4FW65xCoT1LmjrIjsv/Ggdlx46ueczhMgtBunx1/w8k8V+l8LVZ8gAT6wkU5J+DPQalQguMg12Jzug3q4TbdHiGCmD9EunCwOmsLuLJkz6EcSYXtrlDEnAM+hicw7iergYLLlMXpfTdGxJCWJmP4zqUFeTTmsmhsjGBt7NiEB/9pFFEB3pSbf4iiUukw63Eo8Aqnf4iwob6X1QviCWuc8t0LUlT9vALgh/f2DPVOOmR0RW6bgRvc7DSF20V/omg+YBw=="
    }
}
