package app.novachat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val phone: String,
    val name: String = "",
    val about: String = "",
    /** Profile photo at /v1/avatars/{id}, or null for initials. */
    @SerialName("avatar_id") val avatarId: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
) {
    val displayName: String get() = name.ifBlank { phone }
}

/** Whether someone is connected right now, or when they last were. */
data class Presence(val online: Boolean, val lastSeenAt: String?)

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
    /** Set on group events the server writes ("Ana added Ben"); [body] is a [GroupEvent]. */
    @SerialName("group_id") val groupId: String? = null,
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
    val online: Boolean? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
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
    val peerId: String = "",
    val replyTo: MessageRef? = null,
    val forwarded: Boolean = false,
    /** Who reacted with which emoji, by user ID. */
    val reactions: Map<String, String> = emptyMap(),
    val editedAtMillis: Long? = null,
    /** Deleted for everyone: only a placeholder is left. */
    val deleted: Boolean = false,
    val starred: Boolean = false,
    val groupId: String? = null,
    /** A group event such as "Ana added Ben"; [body] is a [GroupEvent] as JSON. */
    val system: Boolean = false,
) {
    val ref: MessageRef get() = MessageRef(senderId, clientId)

    val status: DeliveryStatus
        get() = when {
            failed -> DeliveryStatus.Failed
            id == null -> DeliveryStatus.Pending
            readAt != null -> DeliveryStatus.Read
            deliveredAt != null -> DeliveryStatus.Delivered
            else -> DeliveryStatus.Sent
        }
}

/** A chat in the list. For a group, [peer] carries the group's name and photo. */
data class Conversation(
    val peer: User,
    val lastMessage: ChatMessage,
    val unread: Int,
    val group: Group? = null,
)

@Serializable
data class GroupMemberDto(val user: User, val role: String)

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("avatar_id") val avatarId: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("only_admins_send") val onlyAdminsSend: Boolean = false,
    @SerialName("only_admins_edit") val onlyAdminsEdit: Boolean = false,
    @SerialName("invite_code") val inviteCode: String? = null,
    val members: List<GroupMemberDto> = emptyList(),
)

@Serializable
data class GroupMember(@SerialName("user_id") val userId: String, val role: String) {
    val isAdmin: Boolean get() = role == "admin"
}

/** A group as stored on the phone. [left] once you leave or are removed. */
data class Group(
    val id: String,
    val name: String,
    val description: String = "",
    val avatarId: String? = null,
    val onlyAdminsSend: Boolean = false,
    val onlyAdminsEdit: Boolean = false,
    val inviteCode: String? = null,
    val members: List<GroupMember> = emptyList(),
    val left: Boolean = false,
) {
    fun member(userId: String?): GroupMember? = members.firstOrNull { it.userId == userId }
    fun isAdmin(userId: String?): Boolean = member(userId)?.isAdmin == true
    fun canSend(userId: String?): Boolean = !left && member(userId) != null && (!onlyAdminsSend || isAdmin(userId))
    fun canEditInfo(userId: String?): Boolean = !left && member(userId) != null && (!onlyAdminsEdit || isAdmin(userId))

    /** The group shown where a chat list expects a person. */
    fun asUser() = User(id = id, phone = "", name = name, about = description, avatarId = avatarId)
}

/** The body of a group event message. */
@Serializable
data class GroupEvent(
    val event: String,
    val actor: String,
    val targets: List<String> = emptyList(),
    val name: String? = null,
)

@Serializable
data class InvitePreview(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("avatar_id") val avatarId: String? = null,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("is_member") val isMember: Boolean = false,
)

data class Session(
    val token: String,
    val user: User,
    val serverUrl: String,
)

/**
 * What a message says. Encrypted end to end as JSON inside the envelope.
 *
 * A message with [reaction], [edit] or [delete] is a hidden control message:
 * it changes an earlier message instead of showing up as a bubble. Because
 * they travel encrypted like everything else, the server cannot tell who
 * reacted with what, or what a message was edited to.
 */
@Serializable
data class MessageContent(
    val text: String? = null,
    val attachment: Attachment? = null,
    @SerialName("reply_to") val replyTo: MessageRef? = null,
    val forwarded: Boolean = false,
    val reaction: Reaction? = null,
    val edit: Edit? = null,
    val delete: MessageRef? = null,
    /** Group messages: which group, and the message's ID shared by every member's copy. */
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("group_msg_id") val groupMessageId: String? = null,
) {
    val isControl: Boolean get() = reaction != null || edit != null || delete != null
}

/** Points at one message: messages are unique per sender and client ID. */
@Serializable
data class MessageRef(
    @SerialName("sender_id") val senderId: String,
    @SerialName("client_id") val clientId: String,
)

/** An emoji reaction to [target]; an empty [emoji] takes the reaction back. */
@Serializable
data class Reaction(val target: MessageRef, val emoji: String)

/** New text for one of the sender's own messages. */
@Serializable
data class Edit(val target: MessageRef, val text: String)

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
