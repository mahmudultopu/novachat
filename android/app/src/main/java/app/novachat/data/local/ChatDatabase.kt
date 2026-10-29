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
import app.novachat.data.ChatMessage
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
    )
}

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val phone: String,
    val name: String,
    val about: String,
) {
    fun toUser() = User(id = id, phone = phone, name = name, about = about)
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

data class UnreadCount(val peerId: String, val unread: Int)

@Dao
abstract class ChatDao {
    // Acknowledged messages in server order, then ones still waiting to go out.
    @Query(
        """SELECT * FROM messages WHERE peerId = :peerId
           ORDER BY id IS NULL, id, createdAtMillis""",
    )
    abstract fun messagesWith(peerId: String): Flow<List<MessageEntity>>

    /** The newest message of every chat. */
    @Query(
        """SELECT * FROM messages AS m WHERE m.rowid = (
               SELECT x.rowid FROM messages AS x WHERE x.peerId = m.peerId
               ORDER BY x.id IS NULL DESC, x.id DESC, x.createdAtMillis DESC LIMIT 1)""",
    )
    abstract fun lastMessages(): Flow<List<MessageEntity>>

    @Query(
        """SELECT peerId, COUNT(*) AS unread FROM messages
           WHERE senderId != :me AND readAt IS NULL GROUP BY peerId""",
    )
    abstract fun unreadCounts(me: String): Flow<List<UnreadCount>>

    @Query("SELECT * FROM messages WHERE senderId = :senderId AND clientId = :clientId")
    abstract suspend fun message(senderId: String, clientId: String): MessageEntity?

    @Query(
        """SELECT id FROM messages
           WHERE peerId = :peerId AND senderId != :me AND readAt IS NULL AND id IS NOT NULL""",
    )
    abstract suspend fun unreadIds(peerId: String, me: String): List<Long>

    @Query("SELECT * FROM messages WHERE senderId = :me AND id IS NULL AND failed = 0 ORDER BY createdAtMillis")
    abstract suspend fun pending(me: String): List<MessageEntity>

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

    @Query("SELECT localPath FROM messages WHERE localPath IS NOT NULL")
    abstract suspend fun localPaths(): List<String>

    @Query("UPDATE messages SET failed = :failed WHERE senderId = :senderId AND clientId = :clientId")
    abstract suspend fun setFailed(senderId: String, clientId: String, failed: Boolean)

    @Query("UPDATE messages SET deliveredAt = COALESCE(deliveredAt, :at) WHERE id IN (:ids)")
    abstract suspend fun markDelivered(ids: List<Long>, at: String)

    @Query("UPDATE messages SET deliveredAt = COALESCE(deliveredAt, :at), readAt = COALESCE(readAt, :at) WHERE id IN (:ids)")
    abstract suspend fun markRead(ids: List<Long>, at: String)

    @Query("SELECT * FROM users")
    abstract fun users(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :id")
    abstract fun user(id: String): Flow<UserEntity?>

    @Query("SELECT id FROM users")
    abstract suspend fun userIds(): List<String>

    @Upsert
    abstract suspend fun upsertUser(user: UserEntity)

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
    }
}

/**
 * Chats kept on the phone so they open instantly and work offline.
 *
 * Since end-to-end encryption, decrypted messages and keys exist only here,
 * so every schema change needs a migration that keeps the data.
 */
@Database(
    entities = [MessageEntity::class, UserEntity::class, MetaEntity::class, SessionEntity::class, PreKeyEntity::class],
    version = 3,
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

        fun open(context: Context): ChatDatabase =
            Room.databaseBuilder(context, ChatDatabase::class.java, "novachat.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
