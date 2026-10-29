package app.novachat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val phone: String,
    val name: String = "",
    val about: String = "",
) {
    val displayName: String get() = name.ifBlank { phone }
}

/** A message as the server stores it. */
@Serializable
data class MessageDto(
    val id: Long,
    @SerialName("client_id") val clientId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("recipient_id") val recipientId: String,
    val body: String = "",
    /** End-to-end encrypted content; the server cannot read it. */
    val envelope: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
)

@Serializable
data class RequestCodeResponse(
    val phone: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("dev_code") val devCode: String? = null,
)

@Serializable
data class VerifyResponse(
    val token: String,
    val user: User,
    @SerialName("is_new") val isNew: Boolean,
)

@Serializable
data class ApiErrorBody(val error: String = "", val message: String = "")

/** Every frame the server sends over the WebSocket. */
@Serializable
data class ServerFrame(
    val type: String,
    @SerialName("user_id") val userId: String? = null,
    val message: MessageDto? = null,
    val status: String? = null,
    @SerialName("message_ids") val messageIds: List<Long> = emptyList(),
    val at: String? = null,
    val from: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    val code: String? = null,
    val error: String? = null,
)

enum class DeliveryStatus { Pending, Sent, Delivered, Read, Failed }

/** A message as the app shows it, including ones not yet acknowledged. */
data class ChatMessage(
    val clientId: String,
    val id: Long?,
    val senderId: String,
    val recipientId: String,
    val body: String,
    val createdAtMillis: Long,
    val deliveredAt: String? = null,
    val readAt: String? = null,
    val failed: Boolean = false,
    val undecryptable: Boolean = false,
    val attachment: Attachment? = null,
    /** The decrypted file on this phone, once sent or downloaded. */
    val localPath: String? = null,
) {
    val status: DeliveryStatus
        get() = when {
            failed -> DeliveryStatus.Failed
            id == null -> DeliveryStatus.Pending
            readAt != null -> DeliveryStatus.Read
            deliveredAt != null -> DeliveryStatus.Delivered
            else -> DeliveryStatus.Sent
        }
}

data class Conversation(
    val peer: User,
    val lastMessage: ChatMessage,
    val unread: Int,
)

data class Session(
    val token: String,
    val user: User,
    val serverUrl: String,
)

/** What a message says. Encrypted end to end as JSON inside the envelope. */
@Serializable
data class MessageContent(
    val text: String? = null,
    val attachment: Attachment? = null,
)

enum class AttachmentKind { image, video, voice, file }

/**
 * A photo, video, voice note or document. The file itself is uploaded
 * encrypted; [key] and [digest] (base64) open and check it, and only ever
 * travel inside the end-to-end encrypted message.
 */
@Serializable
data class Attachment(
    val kind: AttachmentKind,
    val mime: String,
    val size: Long,
    /** Server blob id; null until the upload finishes. */
    val id: String? = null,
    val key: String? = null,
    val digest: String? = null,
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** A tiny blurred JPEG (base64) shown until the real file is downloaded. */
    val thumb: String? = null,
)

@Serializable
data class SignedPreKeyDto(
    val id: Int,
    @SerialName("public_key") val publicKey: String,
    val signature: String,
)

@Serializable
data class OneTimePreKeyDto(
    val id: Int,
    @SerialName("public_key") val publicKey: String,
)

@Serializable
data class PublishKeysRequest(
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signing_key") val signingKey: String,
    @SerialName("signed_prekey") val signedPreKey: SignedPreKeyDto,
    @SerialName("one_time_prekeys") val oneTimePreKeys: List<OneTimePreKeyDto> = emptyList(),
)

@Serializable
data class PublishKeysResponse(@SerialName("one_time_prekeys") val oneTimePreKeys: Int)

@Serializable
data class KeyBundleDto(
    @SerialName("user_id") val userId: String,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signing_key") val signingKey: String,
    @SerialName("signed_prekey") val signedPreKey: SignedPreKeyDto,
    @SerialName("one_time_prekey") val oneTimePreKey: OneTimePreKeyDto? = null,
)

@Serializable
data class UploadResponse(val id: String, val size: Long)
