package app.novachat.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Holds the signed-in user's chats in memory and keeps them in step with the
 * server: catches up through /v1/sync after every (re)connect, applies live
 * frames, and resends messages that were typed while offline.
 *
 * Next step: persist to an encrypted Room database so chats open instantly
 * and survive restarts (see the guide's realtime messaging section).
 */
class ChatRepository(
    private val api: ApiClient,
    private val socket: ChatSocket,
    private val scope: CoroutineScope,
) {
    private var session: Session? = null
    private var jobs = emptyList<Job>()
    private var lastSeq = 0L

    /** Messages keyed by the other person's user ID, oldest first. */
    private val messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val users = MutableStateFlow<Map<String, User>>(emptyMap())
    /** Who is typing to us right now, with when their indicator expires. */
    private val typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var openPeer: String? = null

    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorized: SharedFlow<Unit> = _unauthorized

    val connection: StateFlow<ConnectionState> = socket.state

    val conversations: Flow<List<Conversation>> = combine(messages, users) { byPeer, people ->
        val me = session?.user?.id
        byPeer.mapNotNull { (peerId, list) ->
            val last = list.lastOrNull() ?: return@mapNotNull null
            Conversation(
                peer = people[peerId] ?: User(id = peerId, phone = "…"),
                lastMessage = last,
                unread = list.count { it.senderId != me && it.readAt == null },
            )
        }.sortedByDescending { it.lastMessage.createdAtMillis }
    }

    fun messagesWith(peerId: String): Flow<List<ChatMessage>> = messages.map { it[peerId].orEmpty() }

    fun user(peerId: String): Flow<User?> = users.map { it[peerId] }

    fun isTyping(peerId: String): Flow<Boolean> = typing.map { peerId in it }

    @Synchronized
    fun start(newSession: Session) {
        if (session?.token == newSession.token && session?.serverUrl == newSession.serverUrl) {
            session = newSession
            return
        }
        stop()
        session = newSession
        jobs = listOf(
            scope.launch { socket.frames.collect(::onFrame) },
            scope.launch {
                socket.state.collect { if (it == ConnectionState.Unauthorized) _unauthorized.tryEmit(Unit) }
            },
        )
        socket.connect(newSession.serverUrl, newSession.token)
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        socket.disconnect()
        session = null
        lastSeq = 0
        messages.value = emptyMap()
        users.value = emptyMap()
        typing.value = emptyMap()
    }

    /** Finds a NovaChat user by phone number and remembers them. */
    suspend fun findByPhone(phone: String): User {
        val s = session ?: error("not signed in")
        return api.userByPhone(s, phone).also { u -> users.update { it + (u.id to u) } }
    }

    fun send(peerId: String, text: String) {
        val s = session ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        val msg = ChatMessage(
            clientId = UUID.randomUUID().toString(),
            id = null,
            senderId = s.user.id,
            recipientId = peerId,
            body = body,
            createdAtMillis = System.currentTimeMillis(),
        )
        messages.update { it + (peerId to (it[peerId].orEmpty() + msg)) }
        transmit(msg)
    }

    fun retry(peerId: String, clientId: String) {
        val msg = messages.value[peerId]?.firstOrNull { it.clientId == clientId } ?: return
        replace(peerId, msg.copy(failed = false))
        transmit(msg)
    }

    fun sendTyping(peerId: String) {
        socket.send(buildJsonObject { put("type", "typing"); put("to", peerId) }.toString())
    }

    /** Call while a chat is on screen; incoming messages there are marked read. */
    fun setOpenChat(peerId: String?) {
        openPeer = peerId
        if (peerId != null) markRead(peerId)
    }

    private fun markRead(peerId: String) {
        val me = session?.user?.id ?: return
        val unread = messages.value[peerId].orEmpty().filter { it.senderId != me && it.readAt == null && it.id != null }
        if (unread.isEmpty()) return
        if (sendReceipt("read", unread.mapNotNull { it.id })) {
            val now = OffsetDateTime.now().toString()
            val ids = unread.map { it.clientId }.toSet()
            messages.update { all ->
                all + (peerId to all[peerId].orEmpty().map { if (it.clientId in ids) it.copy(readAt = now) else it })
            }
        }
    }

    private fun transmit(msg: ChatMessage) {
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
            "error" -> frame.clientId?.let { cid ->
                messages.value.forEach { (peer, list) ->
                    list.firstOrNull { it.clientId == cid }?.let { replace(peer, it.copy(failed = true)) }
                }
            }
        }
    }

    /** Pulls everything since the last seen message, then resends pending ones. */
    private suspend fun catchUp() {
        val s = session ?: return
        try {
            while (true) {
                val page = api.sync(s, lastSeq, SYNC_PAGE)
                applyMessages(page)
                if (page.size < SYNC_PAGE) break
            }
        } catch (e: ApiException) {
            if (e.status == 401) _unauthorized.tryEmit(Unit)
            return
        }
        messages.value.values.flatten().filter { it.status == DeliveryStatus.Pending }.forEach(::transmit)
    }

    private suspend fun applyMessages(dtos: List<MessageDto>) {
        val s = session ?: return
        val me = s.user.id
        if (dtos.isEmpty()) return
        messages.update { current ->
            val next = current.toMutableMap()
            for (dto in dtos) {
                val peer = if (dto.senderId == me) dto.recipientId else dto.senderId
                val incoming = dto.toChatMessage()
                val list = next[peer].orEmpty()
                val i = list.indexOfFirst { it.clientId == dto.clientId && it.senderId == dto.senderId }
                next[peer] = if (i >= 0) {
                    list.toMutableList().also { it[i] = incoming }
                } else {
                    list + incoming
                }.sortedWith(compareBy<ChatMessage> { it.id ?: Long.MAX_VALUE }.thenBy { it.createdAtMillis })
            }
            next
        }
        lastSeq = maxOf(lastSeq, dtos.maxOf { it.id })

        // Tell senders their messages reached this phone, and mark the open chat read.
        sendReceipt("delivered", dtos.filter { it.recipientId == me && it.senderId != me && it.deliveredAt == null }.map { it.id })
        openPeer?.let(::markRead)

        val unknown = dtos.flatMap { listOf(it.senderId, it.recipientId) }.toSet() - users.value.keys - me
        for (id in unknown) {
            runCatching { api.user(s, id) }.getOrNull()?.let { u -> users.update { it + (u.id to u) } }
        }
    }

    private fun applyReceipt(frame: ServerFrame) {
        val ids = frame.messageIds.toSet()
        val at = frame.at ?: OffsetDateTime.now().toString()
        val read = frame.status == "read"
        messages.update { all ->
            all.mapValues { (_, list) ->
                list.map { m ->
                    if (m.id in ids) m.copy(deliveredAt = m.deliveredAt ?: at, readAt = if (read) at else m.readAt) else m
                }
            }
        }
    }

    private fun replace(peerId: String, msg: ChatMessage) {
        messages.update { all ->
            all + (peerId to all[peerId].orEmpty().map { if (it.clientId == msg.clientId) msg else it })
        }
    }

    private fun MessageDto.toChatMessage() = ChatMessage(
        clientId = clientId,
        id = id,
        senderId = senderId,
        recipientId = recipientId,
        body = body,
        createdAtMillis = runCatching { OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() }
            .getOrDefault(System.currentTimeMillis()),
        deliveredAt = deliveredAt,
        readAt = readAt,
    )

    private companion object {
        const val SYNC_PAGE = 500
        const val TYPING_MS = 4_000L
    }
}
