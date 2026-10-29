package app.novachat.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import app.novachat.data.ChatMessage
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
    }
}

/**
 * Chats kept on the phone so they open instantly and work offline.
 *
 * The server still holds every message in this slice, so an upgrade that
 * changes the schema simply rebuilds the database and syncs it again. Real
 * migrations (and SQLCipher encryption) arrive with end-to-end encryption.
 */
@Database(entities = [MessageEntity::class, UserEntity::class, MetaEntity::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao

    companion object {
        fun open(context: Context): ChatDatabase =
            Room.databaseBuilder(context, ChatDatabase::class.java, "novachat.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
