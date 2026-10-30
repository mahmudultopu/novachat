package app.novachat.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.novachat.data.Attachment
import app.novachat.data.CallLog
import app.novachat.data.ChatMessage
import app.novachat.data.Group
import app.novachat.data.GroupMember
import app.novachat.data.MessageRef
import app.novachat.data.Status
import app.novachat.data.NovaJson
import app.novachat.data.User
import kotlinx.coroutines.flow.Flow

/**
 * One message, sent or received. [id] is the server's sequence number and
 * stays null until the server acknowledges a message typed on this phone.
 * [peerId] is the other person in the chat, so a chat is one index lookup.
 */
@Entity(
    tableName = "messages",
    primaryKeys = ["senderId", "clientId"],
    indices = [Index("peerId", "id"), Index("id")],
)
data class MessageEntity(
    val senderId: String,
    val clientId: String,
    val id: Long?,
    val recipientId: String,
    val peerId: String,
    val body: String,
    val createdAtMillis: Long,
    val deliveredAt: String?,
    val readAt: String?,
    val failed: Boolean,
    /** Outgoing only: the encrypted envelope, kept so a resend is identical. */
    val envelope: String? = null,
    /** Outgoing only: the recipient identity key [envelope] was encrypted to. */
    val envelopeKey: String? = null,
    /** Incoming message this phone could not decrypt. */
    @ColumnInfo(defaultValue = "0") val undecryptable: Boolean = false,
    /** [Attachment] as JSON, including its key: only on this phone and inside envelopes. */
    val attachment: String? = null,
    /** Path of the decrypted file in the app's private storage. */
    val localPath: String? = null,
    /** [MessageRef] (JSON) of the message this one replies to. */
    val replyTo: String? = null,
    @ColumnInfo(defaultValue = "0") val forwarded: Boolean = false,
    /** Reactions as a JSON map of user ID to emoji. */
    val reactions: String? = null,
    val editedAtMillis: Long? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
    /**
     * A hidden control message (reaction, edit, delete) as [MessageContent]
     * JSON. It is stored so it goes out after a reconnect and so the sync
     * position stays right, but never shown as a bubble.
     */
    val control: String? = null,
    /** Group chats: the group. [peerId] is the group's ID too. */
    val groupId: String? = null,
    /** Hidden per-member copy of the group message with this client ID. */
    val parentClientId: String? = null,
    @ColumnInfo(defaultValue = "0") val system: Boolean = false,
    /** A finished call: [CallLog] as JSON. */
    val callLog: String? = null,
    /** Disappearing messages: when this message is wiped from the phone. */
    val expiresAtMillis: Long? = null,
) {
    fun toChatMessage() = ChatMessage(
        clientId = clientId,
        id = id,
        senderId = senderId,
        recipientId = recipientId,
        body = body,
        createdAtMillis = createdAtMillis,
        deliveredAt = deliveredAt,
        readAt = readAt,
        failed = failed,
        undecryptable = undecryptable,
        attachment = attachment?.let { runCatching { NovaJson.decodeFromString<Attachment>(it) }.getOrNull() },
        localPath = localPath,
        peerId = peerId,
        replyTo = replyTo?.let { runCatching { NovaJson.decodeFromString<MessageRef>(it) }.getOrNull() },
        forwarded = forwarded,
        reactions = reactions?.let { runCatching { NovaJson.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty(),
        editedAtMillis = editedAtMillis,
        deleted = deleted,
        starred = starred,
        groupId = groupId,
        system = system,
        callLog = callLog?.let { runCatching { NovaJson.decodeFromString<CallLog>(it) }.getOrNull() },
    )
}

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val phone: String,
    val name: String,
    val about: String,
    val avatarId: String? = null,
    val lastSeenAt: String? = null,
) {
    fun toUser() = User(id = id, phone = phone, name = name, about = about, avatarId = avatarId, lastSeenAt = lastSeenAt)
}

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val avatarId: String?,
    val onlyAdminsSend: Boolean,
    val onlyAdminsEdit: Boolean,
    val inviteCode: String?,
    /** [GroupMember] list as JSON. */
    val members: String,
    val left: Boolean,
) {
    fun toGroup() = Group(
        id = id,
        name = name,
        description = description,
        avatarId = avatarId,
        onlyAdminsSend = onlyAdminsSend,
        onlyAdminsEdit = onlyAdminsEdit,
        inviteCode = inviteCode,
        members = runCatching { NovaJson.decodeFromString<List<GroupMember>>(members) }.getOrDefault(emptyList()),
        left = left,
    )
}

/** A status update; rows are deleted 24 hours after posting. */
@Entity(tableName = "statuses", primaryKeys = ["authorId", "id"])
data class StatusEntity(
    val authorId: String,
    val id: String,
    val text: String,
    val background: Int?,
    /** [Attachment] JSON, with its key. */
    val attachment: String?,
    val localPath: String?,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val viewed: Boolean,
    /** Map of viewer ID to when they looked, as JSON (own posts). */
    val viewers: String?,
    val sent: Boolean,
) {
    fun toStatus() = Status(
        id = id,
        authorId = authorId,
        text = text,
        background = background,
        attachment = attachment?.let { runCatching { NovaJson.decodeFromString<Attachment>(it) }.getOrNull() },
        localPath = localPath,
        createdAtMillis = createdAtMillis,
        expiresAtMillis = expiresAtMillis,
        viewed = viewed,
        viewers = viewers?.let { runCatching { NovaJson.decodeFromString<Map<String, Long>>(it) }.getOrNull() }.orEmpty(),
        sent = sent,
    )
}

/** Small settings that belong to the signed-in account, such as its user ID. */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** End-to-end sessions with one person, sealed with the Keystore key. */
@Entity(tableName = "crypto_sessions")
data class SessionEntity(
    @PrimaryKey val peerId: String,
    val state: String,
)

/** A one-time prekey's private half, sealed with the Keystore key. */
@Entity(tableName = "prekeys")
data class PreKeyEntity(
    @PrimaryKey val id: Int,
    val privateKey: String,
)

/** How the user arranged one chat: pinned, archived or muted. Kept on this phone only. */
@Entity(tableName = "chat_prefs")
data class ChatPrefsEntity(
    @PrimaryKey val peerId: String,
    val pinnedAtMillis: Long? = null,
    val archived: Boolean = false,
    val mutedUntilMillis: Long? = null,
    @ColumnInfo(defaultValue = "0") val disappearSec: Int = 0,
)

/** Someone in the phone's address book; [userId] is set when they use NovaChat. */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val phone: String,
    val name: String,
    val userId: String? = null,
)

data class UnreadCount(val peerId: String, val unread: Int)

@Dao
abstract class ChatDao {
    // Acknowledged messages in server order, then ones still waiting to go out.
    @Query(
        """SELECT * FROM messages WHERE peerId = :peerId AND control IS NULL
           ORDER BY id IS NULL, id, createdAtMillis""",
    )
    abstract fun messagesWith(peerId: String): Flow<List<MessageEntity>>

    /** The newest message of every chat. */
    @Query(
        """SELECT * FROM messages AS m WHERE m.rowid = (
               SELECT x.rowid FROM messages AS x WHERE x.peerId = m.peerId AND x.control IS NULL
               ORDER BY x.id IS NULL DESC, x.id DESC, x.createdAtMillis DESC LIMIT 1)""",
    )
    abstract fun lastMessages(): Flow<List<MessageEntity>>

    @Query(
        """SELECT peerId, COUNT(*) AS unread FROM messages
           WHERE senderId != :me AND readAt IS NULL AND control IS NULL AND system = 0 GROUP BY peerId""",
    )
    abstract fun unreadCounts(me: String): Flow<List<UnreadCount>>

    @Query("SELECT * FROM messages WHERE senderId = :senderId AND clientId = :clientId")
    abstract suspend fun message(senderId: String, clientId: String): MessageEntity?

    @Query(
        """SELECT id FROM messages
           WHERE peerId = :peerId AND senderId != :me AND readAt IS NULL AND id IS NOT NULL""",
    )
    abstract suspend fun unreadIds(peerId: String, me: String): List<Long>

    /** Messages (and hidden copies) waiting to go out, except group messages not yet copied per member. */
    @Query(
        """SELECT * FROM messages WHERE senderId = :me AND id IS NULL AND failed = 0
           AND NOT (groupId IS NOT NULL AND control IS NULL) ORDER BY createdAtMillis""",
    )
    abstract suspend fun pending(me: String): List<MessageEntity>

    /** Group messages typed here that have not been copied for each member yet. */
    @Query(
        """SELECT * FROM messages AS m WHERE senderId = :me AND id IS NULL AND failed = 0
           AND groupId IS NOT NULL AND control IS NULL AND system = 0
           AND NOT EXISTS (SELECT 1 FROM messages AS f WHERE f.senderId = :me AND f.parentClientId = m.clientId)
           ORDER BY createdAtMillis""",
    )
    abstract suspend fun pendingGroupRoots(me: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE senderId = :me AND parentClientId = :parent")
    abstract suspend fun copyCount(me: String, parent: String): Int

    /** The chat message (not a hidden or event row) someone else sent with this server ID. */
    @Query("SELECT * FROM messages WHERE id = :id AND senderId != :me AND control IS NULL AND system = 0 LIMIT 1")
    abstract suspend fun incomingById(id: Long, me: String): MessageEntity?

    @Query("SELECT EXISTS (SELECT 1 FROM messages WHERE id = :id)")
    abstract suspend fun hasId(id: Long): Boolean

    @Query("UPDATE messages SET id = :id WHERE senderId = :me AND clientId = :clientId AND id IS NULL")
    abstract suspend fun setIdIfMissing(me: String, clientId: String, id: Long)

    @Query("SELECT DISTINCT parentClientId FROM messages WHERE id IN (:ids) AND parentClientId IS NOT NULL")
    abstract suspend fun parentsOf(ids: List<Long>): List<String>

    /** A group message is delivered (read) once every member's copy is. */
    @Query(
        """UPDATE messages SET
             deliveredAt = CASE WHEN NOT EXISTS (SELECT 1 FROM messages AS f WHERE f.senderId = :me
               AND f.parentClientId = :parent AND f.deliveredAt IS NULL) THEN COALESCE(deliveredAt, :at) ELSE deliveredAt END,
             readAt = CASE WHEN NOT EXISTS (SELECT 1 FROM messages AS f WHERE f.senderId = :me
               AND f.parentClientId = :parent AND f.readAt IS NULL) THEN COALESCE(readAt, :at) ELSE readAt END
           WHERE senderId = :me AND clientId = :parent""",
    )
    abstract suspend fun rollUpReceipts(me: String, parent: String, at: String)

    @Query("SELECT MAX(id) FROM messages")
    abstract suspend fun lastSeq(): Long?

    @Upsert
    abstract suspend fun upsertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMessage(message: MessageEntity)

    /** Applies the server's copy of a message we already have, keeping our text. */
    @Query(
        """UPDATE messages SET id = :id, deliveredAt = COALESCE(deliveredAt, :deliveredAt),
           readAt = COALESCE(readAt, :readAt), failed = 0
           WHERE senderId = :senderId AND clientId = :clientId""",
    )
    abstract suspend fun applyServerCopy(senderId: String, clientId: String, id: Long, deliveredAt: String?, readAt: String?)

    @Query("UPDATE messages SET envelope = :envelope, envelopeKey = :envelopeKey WHERE senderId = :senderId AND clientId = :clientId")
    abstract suspend fun setEnvelope(senderId: String, clientId: String, envelope: String?, envelopeKey: String?)

    @Query("UPDATE messages SET attachment = :attachment WHERE senderId = :senderId AND clientId = :clientId")
    abstract suspend fun setAttachment(senderId: String, clientId: String, attachment: String)

    @Query("UPDATE messages SET localPath = :path WHERE senderId = :senderId AND clientId = :clientId")
    abstract suspend fun setLocalPath(senderId: String, clientId: String, path: String)

    /** Call offers to [peerId] that never reached the server. */
    @Query(
        """DELETE FROM messages WHERE senderId = :me AND peerId = :peerId AND id IS NULL
           AND control LIKE '%"call":%' AND control LIKE '%"type":"offer"%'""",
    )
    abstract suspend fun deleteUnsentCallOffers(me: String, peerId: String)

    /** Recent calls, newest first, for the Calls tab. */
    @Query("SELECT * FROM messages WHERE callLog IS NOT NULL AND deleted = 0 ORDER BY createdAtMillis DESC LIMIT 200")
    abstract fun callLogs(): Flow<List<MessageEntity>>

    /** Visible messages whose text matches [pattern], a LIKE pattern where `!` escapes; newest first. */
    @Query(
        """SELECT * FROM messages WHERE control IS NULL AND system = 0 AND deleted = 0 AND undecryptable = 0
           AND body LIKE :pattern ESCAPE '!' ORDER BY createdAtMillis DESC LIMIT 100""",
    )
    abstract suspend fun searchMessages(pattern: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE starred = 1 ORDER BY createdAtMillis DESC")
    abstract fun starred(): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET starred = :starred WHERE senderId = :senderId AND clientId = :clientId")
    abstract suspend fun setStarred(senderId: String, clientId: String, starred: Boolean)

    @Query("UPDATE messages SET reactions = :reactions WHERE senderId = :senderId AND clientId = :clientId")
    abstract suspend fun setReactions(senderId: String, clientId: String, reactions: String?)

    @Query("UPDATE messages SET body = :body, editedAtMillis = :at WHERE senderId = :senderId AND clientId = :clientId AND deleted = 0")
    abstract suspend fun applyEdit(senderId: String, clientId: String, body: String, at: Long)

    /** Deleted for everyone: keeps a placeholder bubble, drops the content. */
    @Query(
        """UPDATE messages SET deleted = 1, body = '', attachment = NULL, localPath = NULL,
           reactions = NULL, starred = 0, replyTo = NULL, editedAtMillis = NULL
           WHERE senderId = :senderId AND clientId = :clientId""",
    )
    abstract suspend fun markDeleted(senderId: String, clientId: String)

    @Query("DELETE FROM messages WHERE senderId = :senderId AND clientId = :clientId")
    abstract suspend fun deleteMessage(senderId: String, clientId: String)

    @Query("SELECT localPath FROM messages WHERE localPath IS NOT NULL")
    abstract suspend fun localPaths(): List<String>

    @Query("UPDATE messages SET failed = :failed WHERE senderId = :senderId AND clientId = :clientId")
    abstract suspend fun setFailed(senderId: String, clientId: String, failed: Boolean)

    /** Receipts for messages we sent. A group message's own row is rolled up from its copies instead. */
    @Query(
        """UPDATE messages SET deliveredAt = COALESCE(deliveredAt, :at)
           WHERE id IN (:ids) AND senderId = :me AND NOT (groupId IS NOT NULL AND control IS NULL)""",
    )
    abstract suspend fun markDelivered(me: String, ids: List<Long>, at: String)

    @Query(
        """UPDATE messages SET deliveredAt = COALESCE(deliveredAt, :at), readAt = COALESCE(readAt, :at)
           WHERE id IN (:ids) AND senderId = :me AND NOT (groupId IS NOT NULL AND control IS NULL)""",
    )
    abstract suspend fun markRead(me: String, ids: List<Long>, at: String)

    /** We read these incoming messages on this phone. */
    @Query(
        """UPDATE messages SET deliveredAt = COALESCE(deliveredAt, :at), readAt = COALESCE(readAt, :at)
           WHERE id IN (:ids) AND senderId != :me""",
    )
    abstract suspend fun markReadHere(me: String, ids: List<Long>, at: String)

    @Query("SELECT * FROM users")
    abstract fun users(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :id")
    abstract fun user(id: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = :id")
    abstract suspend fun userNow(id: String): UserEntity?

    @Query("SELECT id FROM users")
    abstract suspend fun userIds(): List<String>

    @Upsert
    abstract suspend fun upsertUser(user: UserEntity)

    @Query("SELECT * FROM groups")
    abstract fun groups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE id = :id")
    abstract fun group(id: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE id = :id")
    abstract suspend fun groupNow(id: String): GroupEntity?

    @Query("SELECT id FROM groups WHERE `left` = 0")
    abstract suspend fun activeGroupIds(): List<String>

    @Upsert
    abstract suspend fun upsertGroup(group: GroupEntity)

    @Query("UPDATE groups SET `left` = 1, inviteCode = NULL WHERE id = :id")
    abstract suspend fun markLeft(id: String)

    @Query("UPDATE groups SET members = :members WHERE id = :id")
    abstract suspend fun setMembers(id: String, members: String)

    @Query("DELETE FROM groups")
    abstract suspend fun deleteGroups()

    @Query("SELECT * FROM statuses ORDER BY createdAtMillis")
    abstract fun statuses(): Flow<List<StatusEntity>>

    @Query("SELECT * FROM statuses WHERE authorId = :authorId AND id = :id")
    abstract suspend fun status(authorId: String, id: String): StatusEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertStatus(status: StatusEntity)

    @Query("UPDATE statuses SET viewed = 1 WHERE authorId = :authorId AND id = :id")
    abstract suspend fun markStatusViewed(authorId: String, id: String)

    @Query("UPDATE statuses SET viewers = :viewers WHERE authorId = :authorId AND id = :id")
    abstract suspend fun setStatusViewers(authorId: String, id: String, viewers: String)

    @Query("UPDATE statuses SET attachment = :attachment WHERE authorId = :authorId AND id = :id")
    abstract suspend fun setStatusAttachment(authorId: String, id: String, attachment: String)

    @Query("UPDATE statuses SET localPath = :path WHERE authorId = :authorId AND id = :id")
    abstract suspend fun setStatusLocalPath(authorId: String, id: String, path: String)

    @Query("UPDATE statuses SET sent = 1 WHERE authorId = :authorId AND id = :id")
    abstract suspend fun markStatusSent(authorId: String, id: String)

    @Query("SELECT * FROM statuses WHERE authorId = :me AND sent = 0 AND expiresAtMillis > :now")
    abstract suspend fun unsentStatuses(me: String, now: Long): List<StatusEntity>

    @Query("DELETE FROM statuses WHERE authorId = :authorId AND id = :id")
    abstract suspend fun deleteStatus(authorId: String, id: String)

    @Query("SELECT * FROM statuses WHERE expiresAtMillis <= :now")
    abstract suspend fun expiredStatuses(now: Long): List<StatusEntity>

    @Query("DELETE FROM statuses WHERE expiresAtMillis <= :now")
    abstract suspend fun deleteExpiredStatuses(now: Long)

    @Query("DELETE FROM statuses")
    abstract suspend fun deleteStatuses()

    /** Everyone you have a 1:1 chat with: who sees your status updates. */
    @Query(
        """SELECT DISTINCT peerId FROM messages
           WHERE groupId IS NULL AND control IS NULL AND peerId != :me""",
    )
    abstract suspend fun directPeers(me: String): List<String>

    @Query("SELECT value FROM meta WHERE `key` = :key")
    abstract suspend fun meta(key: String): String?

    @Upsert
    abstract suspend fun putMeta(meta: MetaEntity)

    @Query("SELECT state FROM crypto_sessions WHERE peerId = :peerId")
    abstract suspend fun session(peerId: String): String?

    @Upsert
    abstract suspend fun putSession(session: SessionEntity)

    @Query("DELETE FROM crypto_sessions WHERE peerId = :peerId")
    abstract suspend fun deleteSession(peerId: String)

    @Query("SELECT privateKey FROM prekeys WHERE id = :id")
    abstract suspend fun preKey(id: Int): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPreKeys(keys: List<PreKeyEntity>)

    @Query("DELETE FROM prekeys WHERE id = :id")
    abstract suspend fun deletePreKey(id: Int)

    @Query("DELETE FROM meta WHERE `key` = :key")
    abstract suspend fun deleteMetaKey(key: String)

    @Query("DELETE FROM crypto_sessions")
    abstract suspend fun deleteSessions()

    @Query("DELETE FROM prekeys")
    abstract suspend fun deletePreKeys()

    @Query("SELECT * FROM chat_prefs")
    abstract fun chatPrefs(): Flow<List<ChatPrefsEntity>>

    @Query("SELECT * FROM chat_prefs WHERE peerId = :peerId")
    abstract suspend fun chatPrefsNow(peerId: String): ChatPrefsEntity?

    @Query("SELECT * FROM chat_prefs WHERE peerId = :peerId")
    abstract fun chatPrefsFor(peerId: String): Flow<ChatPrefsEntity?>

    @Query("SELECT * FROM messages WHERE expiresAtMillis IS NOT NULL AND expiresAtMillis <= :now")
    abstract suspend fun expiredMessages(now: Long): List<MessageEntity>

    /**
     * Wipes a disappearing message but keeps a hidden row, so catching up
     * with the server never fetches it again.
     */
    @Query(
        """UPDATE messages SET body = '', attachment = NULL, localPath = NULL, replyTo = NULL, reactions = NULL,
           callLog = NULL, starred = 0, control = '{}', expiresAtMillis = NULL
           WHERE senderId = :senderId AND clientId = :clientId""",
    )
    abstract suspend fun wipeExpired(senderId: String, clientId: String)

    @Query("SELECT COUNT(*) FROM chat_prefs WHERE pinnedAtMillis IS NOT NULL")
    abstract suspend fun pinnedCount(): Int

    @Upsert
    abstract suspend fun upsertChatPrefs(prefs: ChatPrefsEntity)

    @Query("DELETE FROM chat_prefs")
    abstract suspend fun deleteChatPrefs()

    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE")
    abstract fun contacts(): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertContacts(contacts: List<ContactEntity>)

    @Query("DELETE FROM contacts")
    abstract suspend fun deleteContacts()

    /** Swaps in a fresh copy of the address book. */
    @Transaction
    open suspend fun replaceContacts(contacts: List<ContactEntity>) {
        deleteContacts()
        insertContacts(contacts)
    }

    @Query("DELETE FROM messages")
    abstract suspend fun deleteMessages()

    @Query("DELETE FROM users")
    abstract suspend fun deleteUsers()

    @Query("DELETE FROM meta")
    abstract suspend fun deleteMeta()

    /** Wipes everything, e.g. on log out or when another account signs in. */
    @Transaction
    open suspend fun clear() {
        deleteMessages()
        deleteUsers()
        deleteMeta()
        deleteSessions()
        deletePreKeys()
        deleteGroups()
        deleteStatuses()
        deleteChatPrefs()
        deleteContacts()
    }
}

/**
 * Chats kept on the phone so they open instantly and work offline.
 *
 * Since end-to-end encryption, decrypted messages and keys exist only here,
 * so every schema change needs a migration that keeps the data.
 */
@Database(
    entities = [MessageEntity::class, UserEntity::class, MetaEntity::class, SessionEntity::class, PreKeyEntity::class, GroupEntity::class, StatusEntity::class, ChatPrefsEntity::class, ContactEntity::class],
    version = 11,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao

    companion object {
        /** 1 -> 2: end-to-end encryption (envelopes, sessions, prekeys). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `envelope` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `envelopeKey` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `undecryptable` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `crypto_sessions` (`peerId` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`peerId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `prekeys` (`id` INTEGER NOT NULL, `privateKey` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        /** 2 -> 3: photos, videos, voice notes and documents. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `attachment` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `localPath` TEXT")
            }
        }

        /** 3 -> 4: profile photos and last seen. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `users` ADD COLUMN `avatarId` TEXT")
                db.execSQL("ALTER TABLE `users` ADD COLUMN `lastSeenAt` TEXT")
            }
        }

        /** 4 -> 5: replies, reactions, edits, deletes, forwards and stars. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `replyTo` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `forwarded` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `reactions` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `editedAtMillis` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `starred` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `control` TEXT")
            }
        }

        /** 5 -> 6: groups. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `groupId` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `parentClientId` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `system` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `avatarId` TEXT, `onlyAdminsSend` INTEGER NOT NULL, " +
                        "`onlyAdminsEdit` INTEGER NOT NULL, `inviteCode` TEXT, `members` TEXT NOT NULL, " +
                        "`left` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** 6 -> 7: status updates. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `statuses` (`authorId` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `background` INTEGER, `attachment` TEXT, `localPath` TEXT, " +
                        "`createdAtMillis` INTEGER NOT NULL, `expiresAtMillis` INTEGER NOT NULL, " +
                        "`viewed` INTEGER NOT NULL, `viewers` TEXT, `sent` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`authorId`, `id`))",
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN callLog TEXT")
            }
        }

        /** 8 -> 9: pinned, archived and muted chats. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_prefs` (`peerId` TEXT NOT NULL, `pinnedAtMillis` INTEGER, " +
                        "`archived` INTEGER NOT NULL, `mutedUntilMillis` INTEGER, PRIMARY KEY(`peerId`))",
                )
            }
        }

        /** 9 -> 10: disappearing messages. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `expiresAtMillis` INTEGER")
                db.execSQL("ALTER TABLE `chat_prefs` ADD COLUMN `disappearSec` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `contacts` (`phone` TEXT NOT NULL, `name` TEXT NOT NULL, `userId` TEXT, PRIMARY KEY(`phone`))")
            }
        }

        fun open(context: Context): ChatDatabase =
            Room.databaseBuilder(context, ChatDatabase::class.java, "novachat.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .build()
    }
}
