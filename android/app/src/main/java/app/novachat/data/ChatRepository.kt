package app.novachat.data

import androidx.room.withTransaction
import app.novachat.crypto.AttachmentCipher
import app.novachat.crypto.CryptoException
import app.novachat.crypto.Envelope
import app.novachat.crypto.PreKeyBundle
import app.novachat.crypto.SessionCipher
import app.novachat.crypto.b64
import app.novachat.crypto.unb64
import app.novachat.data.local.ChatDatabase
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the signed-in user's chats in the on-phone database and in step with
 * the server: catches up through /v1/sync after every (re)connect, applies
 * live frames, and resends messages that were typed while offline. The UI
 * reads straight from the database, so chats open instantly, even offline.
 *
 * Every message is end-to-end encrypted here before it leaves the phone and
 * decrypted here when it arrives; the server only relays envelopes.
 */
class ChatRepository(
    private val api: ApiClient,
    private val socket: ChatSocket,
    private val db: ChatDatabase,
    private val keys: E2eKeys,
    val media: MediaFiles,
    private val scope: CoroutineScope,
) {
    private val dao = db.chats()
    private val cipher = SessionCipher(keys)
    private var session: Session? = null
    private var jobs = emptyList<Job>()
    /** Serialises database writes that read-modify-write, such as sync. */
    private val writeLock = Mutex()
    /** Ratchet sessions must advance one message at a time. */
    private val cryptoLock = Mutex()

    /** Messages being encrypted/uploaded right now, so a resend never doubles up. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    /** Client IDs of attachments being downloaded, for progress spinners. */
    val downloading: StateFlow<Set<String>> = _downloading

    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())

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

    fun presence(peerId: String): Flow<Presence?> = _presence.map { it[peerId] }

    /** Loads a profile photo, downloading it once and then reading it from the cache. */
    suspend fun avatar(id: String, sizePx: Int): android.graphics.Bitmap? {
        val file = media.avatarFile(id)
        if (!file.exists()) {
            val s = session ?: return null
            val bytes = try {
                api.avatar(s, id)
            } catch (e: ApiException) {
                return null
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tmp = java.io.File(file.path + "." + java.util.UUID.randomUUID() + ".part")
                tmp.writeBytes(bytes)
                tmp.renameTo(file)
            }
        }
        return media.loadPreview(file.path, video = false, maxPx = sizePx)
    }

    /** Fetches someone's latest name, about and photo. */
    fun refreshProfile(peerId: String) {
        val s = session ?: return
        scope.launch { runCatching { api.user(s, peerId) }.getOrNull()?.let { dao.upsertUser(it.toEntity()) } }
    }

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
                        keys.reset()
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

    /** Disconnects. [clearData] also wipes the chats and keys stored on this phone (log out). */
    @Synchronized
    fun stop(clearData: Boolean) {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        socket.disconnect()
        session = null
        myId.value = null
        typing.value = emptyMap()
        _presence.value = emptyMap()
        if (clearData) {
            scope.launch {
                writeLock.withLock {
                    dao.clear()
                    keys.reset()
                    media.clear()
                }
            }
        }
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
            deliver(msg)
        }
    }

    /** Sends a photo, video, voice note or document, with an optional caption. */
    fun sendAttachment(peerId: String, prepared: PreparedMedia, caption: String = "") {
        val s = session ?: return
        val msg = MessageEntity(
            senderId = s.user.id,
            clientId = UUID.randomUUID().toString(),
            id = null,
            recipientId = peerId,
            peerId = peerId,
            body = caption.trim(),
            createdAtMillis = System.currentTimeMillis(),
            deliveredAt = null,
            readAt = null,
            failed = false,
            attachment = NovaJson.encodeToString(prepared.attachment),
            localPath = prepared.file.path,
        )
        scope.launch {
            dao.upsertMessages(listOf(msg))
            deliver(msg)
        }
    }

    /** Fetches and decrypts a received attachment into app storage. */
    fun download(message: ChatMessage) {
        val s = session ?: return
        val a = message.attachment ?: return
        val id = a.id ?: return
        if (message.localPath != null || a.key == null || a.digest == null) return
        if (message.clientId in _downloading.value) return
        _downloading.update { it + message.clientId }
        scope.launch {
            try {
                val plain = AttachmentCipher.decrypt(api.downloadAttachment(s, id), a.key.unb64(), a.digest.unb64())
                val file = media.newFile(media.extensionFor(a))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.writeBytes(plain) }
                dao.setLocalPath(message.senderId, message.clientId, file.path)
            } catch (e: ApiException) {
                // Offline or expired: the bubble offers to try again.
            } catch (e: CryptoException) {
                // Tampered or corrupt download; never show it.
            } finally {
                _downloading.update { it - message.clientId }
            }
        }
    }

    fun retry(clientId: String) {
        val me = session?.user?.id ?: return
        scope.launch {
            val msg = dao.message(me, clientId) ?: return@launch
            dao.setFailed(me, clientId, false)
            deliver(msg)
        }
    }

    fun sendTyping(peerId: String) {
        socket.send(buildJsonObject { put("type", "typing"); put("to", peerId) }.toString())
    }

    /** Call while a chat is on screen; incoming messages there are marked read. */
    fun setOpenChat(peerId: String?) {
        openPeer = peerId
        if (peerId != null) {
            scope.launch { markRead(peerId) }
            queryPresence(peerId)
            refreshProfile(peerId)
        }
    }

    private fun queryPresence(peerId: String) {
        socket.send(buildJsonObject { put("type", "presence_query"); put("to", peerId) }.toString())
    }

    /**
     * The 60-digit number both people see for their chat. If it matches on
     * both phones, nobody is intercepting the encryption. Null until the
     * first message has set up encryption with this person.
     */
    suspend fun safetyNumber(peerId: String): String? {
        val me = session?.user ?: return null
        val theirs = cipher.remoteIdentity(peerId) ?: return null
        return SessionCipher.safetyNumber(me.id, keys.identity().dh.publicKey, peerId, theirs)
    }

    private suspend fun markRead(peerId: String) {
        val me = session?.user?.id ?: return
        val unread = dao.unreadIds(peerId, me)
        if (unread.isNotEmpty() && sendReceipt("read", unread)) {
            dao.markRead(unread, OffsetDateTime.now().toString())
        }
    }

    /**
     * Encrypts a message (once: the envelope is saved so resends are
     * identical) and hands it to the socket. Without a connection it stays
     * pending and goes out after the next reconnect.
     */
    private suspend fun deliver(msg: MessageEntity) {
        if (!inFlight.add(msg.clientId)) return
        try {
            deliverOnce(msg)
        } finally {
            inFlight.remove(msg.clientId)
        }
    }

    private suspend fun deliverOnce(msg: MessageEntity) {
        val s = session ?: return
        // A file goes up (encrypted) before the message that points to it.
        val attachment = if (msg.attachment != null) uploadIfNeeded(s, msg) ?: return else null
        var envelope = msg.envelope
        var identityKey = msg.envelopeKey
        if (envelope == null || identityKey == null) {
            if (!cipher.hasSession(msg.peerId)) {
                val bundle = try {
                    api.keyBundle(s, msg.peerId)
                } catch (e: ApiException) {
                    // 404: they have not opened an encrypting version of NovaChat yet.
                    if (e.status != 0) dao.setFailed(msg.senderId, msg.clientId, true)
                    return
                }
                try {
                    cryptoLock.withLock {
                        if (!cipher.hasSession(msg.peerId)) cipher.startSession(msg.peerId, bundle.toPreKeyBundle())
                    }
                } catch (e: CryptoException) {
                    // A bad signature on their prekey: never send to keys we cannot trust.
                    dao.setFailed(msg.senderId, msg.clientId, true)
                    return
                }
            }
            try {
                cryptoLock.withLock {
                    val content = NovaJson.encodeToString(
                        MessageContent(text = msg.body.ifEmpty { null }, attachment = attachment),
                    ).toByteArray()
                    envelope = NovaJson.encodeToString(cipher.encrypt(msg.peerId, content))
                    identityKey = cipher.remoteIdentity(msg.peerId)?.b64()
                    dao.setEnvelope(msg.senderId, msg.clientId, envelope, identityKey)
                }
            } catch (e: CryptoException) {
                dao.setFailed(msg.senderId, msg.clientId, true)
                return
            }
        }
        socket.send(
            buildJsonObject {
                put("type", "send")
                put("client_id", msg.clientId)
                put("to", msg.recipientId)
                put("envelope", envelope)
                put("identity_key", identityKey)
            }.toString(),
        )
    }

    /**
     * Encrypts and uploads a message's file once, saving the blob id and key.
     * Returns the finished attachment, or null when the message has none or
     * the upload must wait for a connection.
     */
    private suspend fun uploadIfNeeded(s: Session, msg: MessageEntity): Attachment? {
        val json = msg.attachment ?: return null
        val a = NovaJson.decodeFromString<Attachment>(json)
        if (a.id != null) return a
        val path = msg.localPath
        if (path == null || !java.io.File(path).exists()) {
            dao.setFailed(msg.senderId, msg.clientId, true)
            return null
        }
        val enc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AttachmentCipher.encrypt(java.io.File(path).readBytes())
        }
        val id = try {
            api.uploadAttachment(s, enc.ciphertext)
        } catch (e: ApiException) {
            if (e.status != 0) dao.setFailed(msg.senderId, msg.clientId, true) // e.g. too large
            return null
        }
        val done = a.copy(id = id, key = enc.key.b64(), digest = enc.digest.b64())
        dao.setAttachment(msg.senderId, msg.clientId, NovaJson.encodeToString(done))
        return done
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
            "ready" -> {
                openPeer?.let(::queryPresence)
                catchUp()
            }
            "presence" -> {
                val who = frame.userId
                if (who != null && frame.online != null) {
                    _presence.update { it + (who to Presence(frame.online, frame.lastSeenAt)) }
                    // Clear a stale "typing…" when they leave.
                    if (!frame.online) typing.update { it - who }
                }
            }
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
                if (me != null && cid != null) onSendError(me, cid, frame.code)
            }
        }
    }

    private suspend fun onSendError(me: String, clientId: String, code: String?) {
        val msg = dao.message(me, clientId) ?: return
        if (code == "stale_identity" && msg.envelope != null) {
            // They reinstalled or moved to a new phone: start a fresh session
            // with their new keys and encrypt the message again.
            cryptoLock.withLock { keys.forgetSessions(msg.peerId) }
            dao.setEnvelope(me, clientId, null, null)
            scope.launch { deliver(msg.copy(envelope = null, envelopeKey = null)) }
        } else {
            dao.setFailed(me, clientId, true)
        }
    }

    /** Publishes keys, pulls everything since the newest stored message, then resends pending ones. */
    private suspend fun catchUp() {
        val s = session ?: return
        try {
            keys.publish(api, s)
        } catch (e: ApiException) {
            if (e.status == 401) {
                _unauthorized.tryEmit(Unit)
                return
            }
            // Keys go up on the next reconnect; receiving still works meanwhile.
        }
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
        dao.pending(s.user.id).forEach { deliver(it) }
    }

    private suspend fun applyMessages(dtos: List<MessageDto>) {
        val s = session ?: return
        val me = s.user.id
        if (dtos.isEmpty()) return
        writeLock.withLock {
            for (dto in dtos) store(dto, me)
        }

        // Tell senders their messages reached this phone, and mark the open chat read.
        sendReceipt("delivered", dtos.filter { it.recipientId == me && it.senderId != me && it.deliveredAt == null }.map { it.id })
        openPeer?.let { markRead(it) }

        val unknown = dtos.flatMap { listOf(it.senderId, it.recipientId) }.toSet() - dao.userIds().toSet() - me
        for (id in unknown) {
            runCatching { api.user(s, id) }.getOrNull()?.let { dao.upsertUser(it.toEntity()) }
        }
    }

    /**
     * Saves one message from the server. A message we already have only gets
     * its server fields updated. A new one is decrypted, and the ratchet step
     * and the message are saved in one transaction, so a crash cannot lose a
     * message whose key was already used up.
     */
    private suspend fun store(dto: MessageDto, me: String) {
        if (dao.message(dto.senderId, dto.clientId) != null) {
            dao.applyServerCopy(dto.senderId, dto.clientId, dto.id, dto.deliveredAt, dto.readAt)
            return
        }
        val peer = if (dto.senderId == me) dto.recipientId else dto.senderId
        val base = dto.toEntity(peer)
        val envelope = dto.envelope
        if (envelope == null) {
            dao.insertMessage(base) // sent before encryption existed
            return
        }
        if (dto.senderId == me) {
            // Our own message from an earlier install: its keys are gone.
            dao.insertMessage(base.copy(body = "", undecryptable = true))
            return
        }
        cryptoLock.withLock {
            db.withTransaction {
                val content = try {
                    val decrypted = cipher.decrypt(peer, NovaJson.decodeFromString<Envelope>(envelope))
                    NovaJson.decodeFromString<MessageContent>(String(decrypted.plaintext))
                } catch (e: CryptoException) {
                    null
                } catch (e: kotlinx.serialization.SerializationException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
                dao.insertMessage(
                    if (content == null) {
                        base.copy(body = "", undecryptable = true)
                    } else {
                        base.copy(
                            body = content.text.orEmpty(),
                            attachment = content.attachment?.let { NovaJson.encodeToString(it) },
                        )
                    },
                )
            }
        }
    }

    private suspend fun applyReceipt(frame: ServerFrame) {
        if (frame.messageIds.isEmpty()) return
        val at = frame.at ?: OffsetDateTime.now().toString()
        if (frame.status == "read") dao.markRead(frame.messageIds, at) else dao.markDelivered(frame.messageIds, at)
    }

    private fun MessageDto.toEntity(peer: String) = MessageEntity(
        senderId = senderId,
        clientId = clientId,
        id = id,
        recipientId = recipientId,
        peerId = peer,
        body = body,
        createdAtMillis = runCatching { OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() }
            .getOrDefault(System.currentTimeMillis()),
        deliveredAt = deliveredAt,
        readAt = readAt,
        failed = false,
    )

    private fun KeyBundleDto.toPreKeyBundle() = PreKeyBundle(
        identityKey = identityKey.unb64(),
        signingKey = signingKey.unb64(),
        signedPreKeyId = signedPreKey.id,
        signedPreKey = signedPreKey.publicKey.unb64(),
        signedPreKeySignature = signedPreKey.signature.unb64(),
        oneTimePreKeyId = oneTimePreKey?.id,
        oneTimePreKey = oneTimePreKey?.publicKey?.unb64(),
    )

    private fun User.toEntity() = UserEntity(id = id, phone = phone, name = name, about = about, avatarId = avatarId, lastSeenAt = lastSeenAt)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<String?>.flatMapUnread(): Flow<Map<String, Int>> = flatMapLatest { me ->
        if (me == null) {
            flowOf(emptyMap())
        } else {
            dao.unreadCounts(me).map { list -> list.associate { it.peerId to it.unread } }
        }
    }

    private companion object {
        const val SYNC_PAGE = 500
        const val TYPING_MS = 4_000L
        const val OWNER = "owner_user_id"
    }
}
