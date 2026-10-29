package app.novachat.data

import app.novachat.data.local.ChatDao
import app.novachat.data.local.MessageEntity
import app.novachat.data.local.MetaEntity
import app.novachat.data.local.UserEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Keeps the signed-in user's chats in the on-phone database and in step with
 * the server: catches up through /v1/sync after every (re)connect, applies
 * live frames, and resends messages that were typed while offline. The UI
 * reads straight from the database, so chats open instantly, even offline.
 */
class ChatRepository(
    private val api: ApiClient,
    private val socket: ChatSocket,
    private val dao: ChatDao,
    private val scope: CoroutineScope,
) {
    private var session: Session? = null
    private var jobs = emptyList<Job>()
    /** Serialises database writes that read-modify-write, such as sync. */
    private val writeLock = Mutex()

    /** Who is typing to us right now, with when their indicator expires. */
    private val typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var openPeer: String? = null

    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorized: SharedFlow<Unit> = _unauthorized

    val connection: StateFlow<ConnectionState> = socket.state

    private val myId = MutableStateFlow<String?>(null)

    val conversations: Flow<List<Conversation>> = combine(
        dao.lastMessages(),
        myId.flatMapUnread(),
        dao.users(),
    ) { last, unread, people ->
        val names = people.associateBy { it.id }
        last.map { m ->
            Conversation(
                peer = names[m.peerId]?.toUser() ?: User(id = m.peerId, phone = "…"),
                lastMessage = m.toChatMessage(),
                unread = unread[m.peerId] ?: 0,
            )
        }.sortedByDescending { it.lastMessage.id ?: Long.MAX_VALUE }
    }

    fun messagesWith(peerId: String): Flow<List<ChatMessage>> =
        dao.messagesWith(peerId).map { list -> list.map { it.toChatMessage() } }

    fun user(peerId: String): Flow<User?> = dao.user(peerId).map { it?.toUser() }

    fun isTyping(peerId: String): Flow<Boolean> = typing.map { peerId in it }

    @Synchronized
    fun start(newSession: Session) {
        if (session?.token == newSession.token && session?.serverUrl == newSession.serverUrl) {
            session = newSession
            return
        }
        stop(clearData = false)
        session = newSession
        myId.value = newSession.user.id
        jobs = listOf(
            scope.launch {
                // Another account used this phone before: start from a clean slate.
                writeLock.withLock {
                    if (dao.meta(OWNER) != newSession.user.id) {
                        dao.clear()
                        dao.putMeta(MetaEntity(OWNER, newSession.user.id))
                    }
                }
                // Subscribe to frames before connecting so "ready" is never missed.
                launch(start = CoroutineStart.UNDISPATCHED) { socket.frames.collect(::onFrame) }
                connectIfCurrent(newSession)
            },
            scope.launch {
                socket.state.collect { if (it == ConnectionState.Unauthorized) _unauthorized.tryEmit(Unit) }
            },
        )
    }

    @Synchronized
    private fun connectIfCurrent(s: Session) {
        if (session?.token == s.token) socket.connect(s.serverUrl, s.token)
    }

    /** Disconnects. [clearData] also wipes the chats stored on this phone (log out). */
    @Synchronized
    fun stop(clearData: Boolean) {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        socket.disconnect()
        session = null
        myId.value = null
        typing.value = emptyMap()
        if (clearData) scope.launch { writeLock.withLock { dao.clear() } }
    }

    /** Finds a NovaChat user by phone number and remembers them. */
    suspend fun findByPhone(phone: String): User {
        val s = session ?: error("not signed in")
        return api.userByPhone(s, phone).also { dao.upsertUser(it.toEntity()) }
    }

    fun send(peerId: String, text: String) {
        val s = session ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        val msg = MessageEntity(
            senderId = s.user.id,
            clientId = UUID.randomUUID().toString(),
            id = null,
            recipientId = peerId,
            peerId = peerId,
            body = body,
            createdAtMillis = System.currentTimeMillis(),
            deliveredAt = null,
            readAt = null,
            failed = false,
        )
        scope.launch {
            dao.upsertMessages(listOf(msg))
            transmit(msg)
        }
    }

    fun retry(clientId: String) {
        val me = session?.user?.id ?: return
        scope.launch {
            val msg = dao.message(me, clientId) ?: return@launch
            dao.setFailed(me, clientId, false)
            transmit(msg)
        }
    }

    fun sendTyping(peerId: String) {
        socket.send(buildJsonObject { put("type", "typing"); put("to", peerId) }.toString())
    }

    /** Call while a chat is on screen; incoming messages there are marked read. */
    fun setOpenChat(peerId: String?) {
        openPeer = peerId
        if (peerId != null) scope.launch { markRead(peerId) }
    }

    private suspend fun markRead(peerId: String) {
        val me = session?.user?.id ?: return
        val unread = dao.unreadIds(peerId, me)
        if (unread.isNotEmpty() && sendReceipt("read", unread)) {
            dao.markRead(unread, OffsetDateTime.now().toString())
        }
    }

    private fun transmit(msg: MessageEntity) {
        socket.send(
            buildJsonObject {
                put("type", "send")
                put("client_id", msg.clientId)
                put("to", msg.recipientId)
                put("body", msg.body)
            }.toString(),
        )
    }

    private fun sendReceipt(status: String, ids: List<Long>): Boolean = ids.isEmpty() || socket.send(
        buildJsonObject {
            put("type", "receipt")
            put("status", status)
            put("message_ids", JsonArray(ids.map { JsonPrimitive(it) }))
        }.toString(),
    )

    private suspend fun onFrame(frame: ServerFrame) {
        when (frame.type) {
            "ready" -> catchUp()
            "message" -> frame.message?.let { applyMessages(listOf(it)) }
            "receipt" -> applyReceipt(frame)
            "typing" -> frame.from?.let { from ->
                typing.update { it + (from to System.currentTimeMillis() + TYPING_MS) }
                scope.launch {
                    delay(TYPING_MS)
                    typing.update { all -> all.filterValues { it > System.currentTimeMillis() } }
                }
            }
            "error" -> {
                val me = session?.user?.id
                val cid = frame.clientId
                if (me != null && cid != null) dao.setFailed(me, cid, true)
            }
        }
    }

    /** Pulls everything since the newest stored message, then resends pending ones. */
    private suspend fun catchUp() {
        val s = session ?: return
        try {
            while (true) {
                val page = api.sync(s, dao.lastSeq() ?: 0L, SYNC_PAGE)
                applyMessages(page)
                if (page.size < SYNC_PAGE) break
            }
        } catch (e: ApiException) {
            if (e.status == 401) _unauthorized.tryEmit(Unit)
            return
        }
        dao.pending(s.user.id).forEach(::transmit)
    }

    private suspend fun applyMessages(dtos: List<MessageDto>) {
        val s = session ?: return
        val me = s.user.id
        if (dtos.isEmpty()) return
        writeLock.withLock {
            dao.upsertMessages(dtos.map { it.toEntity(me) })
        }

        // Tell senders their messages reached this phone, and mark the open chat read.
        sendReceipt("delivered", dtos.filter { it.recipientId == me && it.senderId != me && it.deliveredAt == null }.map { it.id })
        openPeer?.let { markRead(it) }

        val unknown = dtos.flatMap { listOf(it.senderId, it.recipientId) }.toSet() - dao.userIds().toSet() - me
        for (id in unknown) {
            runCatching { api.user(s, id) }.getOrNull()?.let { dao.upsertUser(it.toEntity()) }
        }
    }

    private suspend fun applyReceipt(frame: ServerFrame) {
        if (frame.messageIds.isEmpty()) return
        val at = frame.at ?: OffsetDateTime.now().toString()
        if (frame.status == "read") dao.markRead(frame.messageIds, at) else dao.markDelivered(frame.messageIds, at)
    }

    private fun MessageDto.toEntity(me: String) = MessageEntity(
        senderId = senderId,
        clientId = clientId,
        id = id,
        recipientId = recipientId,
        peerId = if (senderId == me) recipientId else senderId,
        body = body,
        createdAtMillis = runCatching { OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() }
            .getOrDefault(System.currentTimeMillis()),
        deliveredAt = deliveredAt,
        readAt = readAt,
        failed = false,
    )

    private fun User.toEntity() = UserEntity(id = id, phone = phone, name = name, about = about)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<String?>.flatMapUnread(): Flow<Map<String, Int>> = flatMapLatest { me ->
        if (me == null) {
            flowOf(emptyMap())
        } else { dao.unreadCounts(me).map { list -> list.associate { it.peerId to it.unread } }
        }
    }

    private companion object {
        const val SYNC_PAGE = 500
        const val TYPING_MS = 4_000L
        const val OWNER = "owner_user_id"
    }
}
