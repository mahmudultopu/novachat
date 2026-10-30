package app.novachat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ApiException(val status: Int, val code: String, override val message: String) : Exception(message)

val NovaJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** The NovaChat REST API. Every call runs on the IO dispatcher. */
class ApiClient(private val http: OkHttpClient) {

    suspend fun requestCode(baseUrl: String, phone: String): RequestCodeResponse =
        call(baseUrl, "POST", "/v1/auth/request-code", null, buildJsonObject { put("phone", phone) })

    suspend fun verify(baseUrl: String, phone: String, code: String, deviceName: String): VerifyResponse =
        call(baseUrl, "POST", "/v1/auth/verify", null, buildJsonObject {
            put("phone", phone)
            put("code", code)
            put("device_name", deviceName)
        })

    suspend fun updateName(session: Session, name: String): User =
        call(session.serverUrl, "PATCH", "/v1/me", session.token, buildJsonObject { put("name", name) })

    suspend fun updateProfile(session: Session, name: String, about: String): User =
        call(session.serverUrl, "PATCH", "/v1/me", session.token, buildJsonObject {
            put("name", name)
            put("about", about)
        })

    suspend fun setAvatar(session: Session, jpeg: ByteArray): User = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(session.serverUrl.trimEnd('/') + "/v1/me/avatar")
            .header("Authorization", "Bearer ${session.token}")
            .put(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        execute(request) { NovaJson.decodeFromString<User>(it.body!!.string()) }
    }

    suspend fun removeAvatar(session: Session): User =
        call(session.serverUrl, "DELETE", "/v1/me/avatar", session.token, null)

    suspend fun avatar(session: Session, id: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(session.serverUrl.trimEnd('/') + "/v1/avatars/$id")
            .header("Authorization", "Bearer ${session.token}")
            .build()
        execute(request) { it.body!!.bytes() }
    }

    suspend fun logout(session: Session) {
        call<Unit>(session.serverUrl, "POST", "/v1/auth/logout", session.token, null)
    }

    suspend fun userByPhone(session: Session, phone: String): User =
        call(session.serverUrl, "GET", "/v1/users/by-phone", session.token, null, mapOf("phone" to phone))

    suspend fun user(session: Session, id: String): User =
        call(session.serverUrl, "GET", "/v1/users/$id", session.token, null)

    suspend fun createGroup(session: Session, name: String, members: List<String>): GroupDto =
        call(session.serverUrl, "POST", "/v1/groups", session.token, buildJsonObject {
            put("name", name)
            put("members", JsonArray(members.map { JsonPrimitive(it) }))
        })

    suspend fun groups(session: Session): List<GroupDto> =
        call(session.serverUrl, "GET", "/v1/groups", session.token, null)

    suspend fun group(session: Session, id: String): GroupDto =
        call(session.serverUrl, "GET", "/v1/groups/$id", session.token, null)

    suspend fun updateGroup(session: Session, id: String, changes: JsonObject): GroupDto =
        call(session.serverUrl, "PATCH", "/v1/groups/$id", session.token, changes)

    suspend fun setGroupAvatar(session: Session, id: String, jpeg: ByteArray): GroupDto = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(session.serverUrl.trimEnd('/') + "/v1/groups/$id/avatar")
            .header("Authorization", "Bearer ${session.token}")
            .put(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        execute(request) { NovaJson.decodeFromString<GroupDto>(it.body!!.string()) }
    }

    suspend fun removeGroupAvatar(session: Session, id: String): GroupDto =
        call(session.serverUrl, "DELETE", "/v1/groups/$id/avatar", session.token, null)

    suspend fun addGroupMembers(session: Session, id: String, userIds: List<String>): GroupDto =
        call(session.serverUrl, "POST", "/v1/groups/$id/members", session.token, buildJsonObject {
            put("user_ids", JsonArray(userIds.map { JsonPrimitive(it) }))
        })

    suspend fun removeGroupMember(session: Session, id: String, userId: String): GroupDto =
        call(session.serverUrl, "DELETE", "/v1/groups/$id/members/$userId", session.token, null)

    /** Leaving answers with no content: you can no longer see the group. */
    suspend fun leaveGroup(session: Session, id: String) {
        call<Unit>(session.serverUrl, "DELETE", "/v1/groups/$id/members/${session.user.id}", session.token, null)
    }

    suspend fun setGroupRole(session: Session, id: String, userId: String, role: String): GroupDto =
        call(session.serverUrl, "PUT", "/v1/groups/$id/members/$userId", session.token, buildJsonObject { put("role", role) })

    suspend fun resetInvite(session: Session, id: String): GroupDto =
        call(session.serverUrl, "POST", "/v1/groups/$id/invite", session.token, null)

    suspend fun revokeInvite(session: Session, id: String): GroupDto =
        call(session.serverUrl, "DELETE", "/v1/groups/$id/invite", session.token, null)

    suspend fun previewInvite(session: Session, code: String): InvitePreview =
        call(session.serverUrl, "GET", "/v1/invites/$code", session.token, null)

    suspend fun joinInvite(session: Session, code: String): GroupDto =
        call(session.serverUrl, "POST", "/v1/invites/$code/join", session.token, null)

    suspend fun privacy(session: Session): PrivacySettings =
        call(session.serverUrl, "GET", "/v1/me/privacy", session.token, null)

    suspend fun updatePrivacy(session: Session, changes: JsonObject): PrivacySettings =
        call(session.serverUrl, "PATCH", "/v1/me/privacy", session.token, changes)

    suspend fun blocked(session: Session): List<User> =
        call(session.serverUrl, "GET", "/v1/blocks", session.token, null)

    suspend fun block(session: Session, userId: String) {
        call<Unit>(session.serverUrl, "PUT", "/v1/blocks/$userId", session.token, null)
    }

    suspend fun unblock(session: Session, userId: String) {
        call<Unit>(session.serverUrl, "DELETE", "/v1/blocks/$userId", session.token, null)
    }

    /** Which of these phone numbers belong to NovaChat users. The server doesn't keep them. */
    suspend fun matchContacts(session: Session, phones: List<String>): List<User> =
        call<ContactMatches>(session.serverUrl, "POST", "/v1/contacts/match", session.token, buildJsonObject {
            put("phones", JsonArray(phones.map { JsonPrimitive(it) }))
        }).users

    suspend fun report(session: Session, userId: String, reason: String, block: Boolean) {
        call<Unit>(session.serverUrl, "POST", "/v1/reports", session.token, buildJsonObject {
            put("user_id", userId)
            put("reason", reason)
            put("block", block)
        })
    }

    suspend fun sync(session: Session, after: Long, limit: Int): List<MessageDto> =
        call(session.serverUrl, "GET", "/v1/sync", session.token, null,
            mapOf("after" to after.toString(), "limit" to limit.toString()))

    suspend fun publishKeys(session: Session, keys: PublishKeysRequest): PublishKeysResponse =
        call(session.serverUrl, "PUT", "/v1/keys", session.token, NovaJson.encodeToJsonElement(keys).jsonObject)

    suspend fun keyBundle(session: Session, userId: String): KeyBundleDto =
        call(session.serverUrl, "GET", "/v1/keys/$userId", session.token, null)

    /** STUN and TURN servers for setting up a call. */
    suspend fun iceServers(session: Session): IceServersResponse =
        call(session.serverUrl, "GET", "/v1/calls/ice", session.token, null)

    /** Uploads an already encrypted file and returns its blob id. */
    suspend fun uploadAttachment(session: Session, ciphertext: ByteArray): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(session.serverUrl.trimEnd('/') + "/v1/attachments")
            .header("Authorization", "Bearer ${session.token}")
            .post(ciphertext.toRequestBody(OCTET_TYPE))
            .build()
        execute(request) { NovaJson.decodeFromString<UploadResponse>(it.body!!.string()).id }
    }

    suspend fun downloadAttachment(session: Session, id: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(session.serverUrl.trimEnd('/') + "/v1/attachments/$id")
            .header("Authorization", "Bearer ${session.token}")
            .build()
        execute(request) { it.body!!.bytes() }
    }

    private fun <T> execute(request: Request, read: (okhttp3.Response) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException(0, "network", "Can't reach the server")
        }
        return response.use {
            if (!it.isSuccessful) {
                val err = runCatching { NovaJson.decodeFromString<ApiErrorBody>(it.body?.string().orEmpty()) }.getOrNull()
                throw ApiException(it.code, err?.error.orEmpty(), err?.message?.ifBlank { null } ?: "Server error ${it.code}")
            }
            try {
                read(it)
            } catch (e: IOException) {
                throw ApiException(0, "network", "Connection lost during transfer")
            }
        }
    }

    private suspend inline fun <reified T> call(
        baseUrl: String,
        verb: String,
        path: String,
        token: String?,
        body: JsonObject?,
        query: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        val url = try {
            (baseUrl.trimEnd('/') + path).toHttpUrl().newBuilder().apply {
                query.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
        } catch (e: IllegalArgumentException) {
            throw ApiException(0, "bad_server_url", "Server address \"$baseUrl\" is not a valid URL")
        }
        val request = Request.Builder().url(url).apply {
            token?.let { header("Authorization", "Bearer $it") }
            val payload = body ?: if (verb == "GET") null else JsonObject(emptyMap())
            method(verb, payload?.toString()?.toRequestBody(JSON_TYPE))
        }.build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException(0, "network", "Can't reach the server at $baseUrl")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val err = runCatching { NovaJson.decodeFromString<ApiErrorBody>(text) }.getOrNull()
                throw ApiException(it.code, err?.error.orEmpty(), err?.message?.ifBlank { null } ?: "Server error ${it.code}")
            }
            if (T::class == Unit::class) Unit as T else NovaJson.decodeFromString<T>(text)
        }
    }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
        val OCTET_TYPE = "application/octet-stream".toMediaType()
    }
}
