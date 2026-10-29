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
import app.novachat.data.local.ChatPrefsEntity
import app.novachat.data.local.MessageEntity
import app.novachat.data.local.GroupEntity
import app.novachat.data.local.MetaEntity
import app.novachat.data.local.StatusEntity
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

    private val _callSignals = MutableSharedFlow<IncomingCallSignal>(extraBufferCapacity = 32)
    /** Call offers, answers and hang-ups from the people we chat with. */
    val callSignals: SharedFlow<IncomingCallSignal> = _callSignals

    /** People we have messages queued for who haven't opened NovaChat since signing up. */
    private val _waitingForKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Who is typing to us right now, with when their indicator expires. */
    private val typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var openPeer: String? = null

    private val _incoming = MutableSharedFlow<IncomingNotice>(extraBufferCapacity = 64)
    /** New messages from other people, for notifications. */
    val incoming: SharedFlow<IncomingNotice> = _incoming

    /** Whether the app is on screen; the server shows us online only then. */
    @Volatile
    private var appActive = true

    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorized: SharedFlow<Unit> = _unauthorized

    val connection: StateFlow<ConnectionState> = socket.state

    private val myId = MutableStateFlow<String?>(null)

    val conversations: Flow<List<Conversation>> = combine(
        dao.lastMessages(),
        myId.flatMapUnread(),
        dao.users(),
        dao.groups(),
        dao.chatPrefs(),
    ) { last, unread, people, groupRows, prefRows ->
        val names = people.associateBy { it.id }
        val groups = groupRows.associate { it.id to it.toGroup() }
        val prefs = prefRows.associateBy { it.peerId }
        last.map { m ->
            val group = groups[m.peerId]
            Conversation(
                peer = group?.asUser() ?: names[m.peerId]?.toUser() ?: User(id = m.peerId, phone = "…"),
                lastMessage = m.toChatMessage(),
                unread = unread[m.peerId] ?: 0,
                group = group,
                prefs = prefs[m.peerId]?.toPrefs() ?: ChatPrefs(),
            )
        }.sortedWith(
            // Pinned chats first, most recently pinned on top; then newest message first.
            compareByDescending<Conversation> { prefs[it.peer.id]?.pinnedAtMillis ?: Long.MIN_VALUE }
                .thenByDescending { it.lastMessage.id ?: Long.MAX_VALUE },
        )
    }

    /** Pins a chat to the top of the list. Returns false if [MAX_PINNED] chats are already pinned. */
    suspend fun setPinned(peerId: String, pinned: Boolean): Boolean = db.withTransaction {
        val p = dao.chatPrefsNow(peerId) ?: ChatPrefsEntity(peerId)
        if (pinned && p.pinnedAtMillis == null && dao.pinnedCount() >= MAX_PINNED) return@withTransaction false
        dao.upsertChatPrefs(p.copy(pinnedAtMillis = if (pinned) p.pinnedAtMillis ?: System.currentTimeMillis() else null))
        true
    }

    /** Moves a chat into or out of Archived. An archived chat is never pinned. */
    suspend fun setArchived(peerId: String, archived: Boolean) = db.withTransaction {
        val p = dao.chatPrefsNow(peerId) ?: ChatPrefsEntity(peerId)
        dao.upsertChatPrefs(p.copy(archived = archived, pinnedAtMillis = if (archived) null else p.pinnedAtMillis))
    }

    /** Silences a chat's notifications until [untilMillis], or unmutes it when null. */
    suspend fun setMuted(peerId: String, untilMillis: Long?) = db.withTransaction {
        val p = dao.chatPrefsNow(peerId) ?: ChatPrefsEntity(peerId)
        dao.upsertChatPrefs(p.copy(mutedUntilMillis = untilMillis))
    }

    private fun ChatPrefsEntity.toPrefs() =
        ChatPrefs(pinned = pinnedAtMillis != null, archived = archived, mutedUntilMillis = mutedUntilMillis)

    /** Everyone this phone knows, by user ID. */
    val people: Flow<Map<String, User>> = dao.users().map { list -> list.associate { it.id to it.toUser() } }

    fun group(id: String): Flow<Group?> = dao.group(id).map { it?.toGroup() }

    /** Status updates from the last 24 hours, oldest first. */
    val statuses: Flow<List<Status>> = dao.statuses().map { list ->
        val now = System.currentTimeMillis()
        list.filter { it.expiresAtMillis > now }.map { it.toStatus() }
    }

    private val _downloadingStatus = MutableStateFlow<Set<String>>(emptySet())
    val downloadingStatus: StateFlow<Set<String>> = _downloadingStatus

    /** Posts a text status on a coloured background. */
    fun postTextStatus(text: String, background: Int) {
        val body = text.trim()
        if (body.isEmpty()) return
        postStatus(body, background, null, null)
    }

    /** Posts a photo or video status, with an optional caption. */
    fun postMediaStatus(prepared: PreparedMedia, caption: String) {
        postStatus(caption.trim(), null, prepared.attachment, prepared.file.path)
    }

    private fun postStatus(text: String, background: Int?, attachment: Attachment?, localPath: String?) {
        val s = session ?: return
        val now = System.currentTimeMillis()
        val status = StatusEntity(
            authorId = s.user.id,
            id = UUID.randomUUID().toString(),
            text = text.take(MAX_STATUS_TEXT),
            background = background,
            attachment = attachment?.let { NovaJson.encodeToString(it) },
            localPath = localPath,
            createdAtMillis = now,
            expiresAtMillis = now + STATUS_TTL_MS,
            viewed = true,
            viewers = null,
            sent = false,
        )
        scope.launch {
            dao.insertStatus(status)
            sendStatus(status)
        }
    }

    /**
     * Sends your status to everyone you chat with one-to-one, encrypted to
     * each of them like a message. A photo or video goes up once.
     */
    private suspend fun sendStatus(status: StatusEntity) {
        if (!inFlight.add("status:" + status.id)) return
        try {
            val s = session ?: return
            var attachment = status.attachment?.let { NovaJson.decodeFromString<Attachment>(it) }
            if (attachment != null && attachment.id == null) {
                val path = status.localPath ?: return
                val enc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AttachmentCipher.encrypt(java.io.File(path).readBytes())
                }
                val id = try {
                    api.uploadAttachment(s, enc.ciphertext)
                } catch (e: ApiException) {
                    return // tried again after the next reconnect
                }
                attachment = attachment.copy(id = id, key = enc.key.b64(), digest = enc.digest.b64())
                dao.setStatusAttachment(status.authorId, status.id, NovaJson.encodeToString(attachment))
            }
            val post = StatusPost(status.id, status.text.ifEmpty { null }, status.background, attachment)
            sendHidden(dao.directPeers(s.user.id), MessageContent(status = post))
            dao.markStatusSent(status.authorId, status.id)
        } finally {
            inFlight.remove("status:" + status.id)
        }
    }

    /** Marks someone's status as seen and tells them, like a read tick. */
    fun viewStatus(status: Status) {
        val me = session?.user?.id ?: return
        if (status.authorId == me || status.viewed) return
        scope.launch {
            dao.markStatusViewed(status.authorId, status.id)
            sendHidden(listOf(status.authorId), MessageContent(statusSeen = status.id))
        }
    }

    /** Deletes your status here and on every phone it went to. */
    fun deleteStatus(status: Status) {
        val s = session ?: return
        if (status.authorId != s.user.id) return
        scope.launch {
            dao.deleteStatus(status.authorId, status.id)
            status.localPath?.let { deleteFile(it) }
            if (status.sent) sendHidden(dao.directPeers(s.user.id), MessageContent(statusDelete = status.id))
        }
    }

    /** Fetches and decrypts a status photo or video. */
    fun downloadStatus(status: Status) {
        val s = session ?: return
        val a = status.attachment ?: return
        val id = a.id ?: return
        val key = status.authorId + status.id
        if (status.localPath != null || a.key == null || a.digest == null || key in _downloadingStatus.value) return
        _downloadingStatus.update { it + key }
        scope.launch {
            try {
                val plain = AttachmentCipher.decrypt(api.downloadAttachment(s, id), a.key.unb64(), a.digest.unb64())
                val file = media.newFile(media.extensionFor(a))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.writeBytes(plain) }
                dao.setStatusLocalPath(status.authorId, status.id, file.path)
            } catch (e: ApiException) {
                // Offline: the viewer offers to try again.
            } catch (e: CryptoException) {
                // Tampered or corrupt: never show it.
            } finally {
                _downloadingStatus.update { it - key }
            }
        }
    }

    /** Queues one hidden, encrypted message per recipient. */
    private suspend fun sendHidden(recipients: List<String>, content: MessageContent) {
        val me = session?.user?.id ?: return
        val json = NovaJson.encodeToString(content)
        val now = System.currentTimeMillis()
        val rows = recipients.filter { it != me }.distinct().map { to ->
            MessageEntity(
                senderId = me,
                clientId = UUID.randomUUID().toString(),
                id = null,
                recipientId = to,
                peerId = to,
                body = "",
                createdAtMillis = now,
                deliveredAt = null,
                readAt = null,
                failed = false,
                control = json,
            )
        }
        dao.upsertMessages(rows)
        rows.forEach { scope.launch { deliver(it) } }
    }

    /** Drops status updates older than 24 hours, with their files. */
    private suspend fun cleanUpStatuses() {
        val now = System.currentTimeMillis()
        dao.expiredStatuses(now).forEach { st -> st.localPath?.let { deleteFile(it) } }
        dao.deleteExpiredStatuses(now)
    }

    /** Recent voice and video calls, newest first. */
    val callLogs: Flow<List<ChatMessage>> = dao.callLogs().map { list -> list.map { it.toChatMessage() } }

    fun messagesWith(peerId: String): Flow<List<ChatMessage>> =
        dao.messagesWith(peerId).map { list -> list.map { it.toChatMessage() } }

    fun user(peerId: String): Flow<User?> = dao.user(peerId).map { it?.toUser() }

    /** Someone this phone knows, right now. */
    suspend fun person(id: String): User? = dao.userNow(id)?.toUser()

    fun isTyping(peerId: String): Flow<Boolean> = typing.map { peerId in it }

    fun presence(peerId: String): Flow<Presence?> = _presence.map { it[peerId] }

    /** True while messages to this person wait for them to open NovaChat. */
    fun waitingForKeys(peerId: String): Flow<Boolean> = _waitingForKeys.map { peerId in it }

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
                // Retry queued messages now and then, for example to someone
                // who hasn't opened NovaChat yet or while the server restarts.
                while (true) {
                    delay(RETRY_MS)
                    if (socket.state.value == ConnectionState.Connected) {
                        dao.pending(newSession.user.id).forEach { deliver(it) }
                    }
                }
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

    fun send(peerId: String, text: String, replyTo: MessageRef? = null) {
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
            replyTo = replyTo?.let { NovaJson.encodeToString(it) },
        )
        scope.launch { queue(msg) }
    }

    /** Sends a photo, video, voice note or document, with an optional caption. */
    fun sendAttachment(peerId: String, prepared: PreparedMedia, caption: String = "", replyTo: MessageRef? = null) {
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
            replyTo = replyTo?.let { NovaJson.encodeToString(it) },
        )
        scope.launch { queue(msg) }
    }

    /**
     * Saves a new outgoing message and sends it. In a group it is saved once
     * for the chat and then copied, encrypted, for each member.
     */
    private suspend fun queue(msg: MessageEntity) {
        if (dao.groupNow(msg.peerId) != null) {
            val root = msg.copy(groupId = msg.peerId)
            dao.upsertMessages(listOf(root))
            deliverGroup(root)
        } else {
            dao.upsertMessages(listOf(msg))
            deliver(msg)
        }
    }

    /** Sends one step of setting up a call, encrypted, in order with the others. */
    suspend fun sendCallSignal(peerId: String, signal: CallSignal) {
        sendControl(peerId, MessageContent(call = signal))
    }

    /** Forgets a call offer that never left this phone, so a call that is over can't ring later. */
    suspend fun dropUnsentCallOffers(peerId: String) {
        val me = session?.user?.id ?: return
        dao.deleteUnsentCallOffers(me, peerId)
    }

    /** Adds "Voice call · 2:31" (or "Missed voice call") to the chat, on both phones. */
    suspend fun sendCallLog(peerId: String, log: CallLog) {
        val s = session ?: return
        queue(
            MessageEntity(
                senderId = s.user.id,
                clientId = UUID.randomUUID().toString(),
                id = null,
                recipientId = peerId,
                peerId = peerId,
                body = "",
                createdAtMillis = System.currentTimeMillis(),
                deliveredAt = null,
                readAt = null,
                failed = false,
                callLog = NovaJson.encodeToString(log),
            ),
        )
    }

    /** The STUN/TURN servers to connect calls through, or a public STUN server if the server can't say. */
    suspend fun iceServers(): List<IceServerDto> {
        val s = session ?: return FALLBACK_ICE
        return try {
            api.iceServers(s).iceServers.ifEmpty { FALLBACK_ICE }
        } catch (e: ApiException) {
            FALLBACK_ICE
        }
    }

    /** Reacts to a message with [emoji], or takes the reaction back with "". */
    fun react(m: ChatMessage, emoji: String) {
        val me = session?.user?.id ?: return
        if (m.deleted || m.undecryptable) return
        scope.launch {
            writeLock.withLock { setReaction(m.ref, me, emoji) }
            sendControl(m.peerId, MessageContent(reaction = Reaction(m.ref, emoji)))
        }
    }

    /** Changes the text of one of your own sent messages, on both phones. */
    fun edit(m: ChatMessage, text: String) {
        val me = session?.user?.id ?: return
        val body = text.trim()
        if (m.senderId != me || body.isEmpty() || body == m.body || !canEdit(m, me)) return
        scope.launch {
            writeLock.withLock { dao.applyEdit(me, m.clientId, body, System.currentTimeMillis()) }
            sendControl(m.peerId, MessageContent(edit = Edit(m.ref, body)))
        }
    }

    /** Replaces one of your messages with "This message was deleted" on both phones. */
    fun deleteForEveryone(m: ChatMessage) {
        val me = session?.user?.id ?: return
        if (!canDeleteForEveryone(m, me)) return
        scope.launch {
            writeLock.withLock { removeContent(m.senderId, m.clientId, m.localPath) }
            sendControl(m.peerId, MessageContent(delete = m.ref))
        }
    }

    /** Removes a message from this phone only. */
    fun deleteForMe(m: ChatMessage) {
        scope.launch {
            writeLock.withLock {
                dao.deleteMessage(m.senderId, m.clientId)
                m.localPath?.let { deleteFile(it) }
            }
        }
    }

    fun setStarred(m: ChatMessage, starred: Boolean) {
        scope.launch { dao.setStarred(m.senderId, m.clientId, starred) }
    }

    /** Starred messages from every chat, newest first. */
    val starred: Flow<List<ChatMessage>> = dao.starred().map { list -> list.map { it.toChatMessage() } }

    /**
     * Sends a copy of [m] to each of [peerIds]. A file that is already
     * uploaded is not uploaded again: the copy points at the same encrypted
     * blob and carries the same key.
     */
    fun forward(m: ChatMessage, peerIds: Collection<String>) {
        val s = session ?: return
        if (m.deleted || m.undecryptable) return
        scope.launch {
            for (peerId in peerIds) {
                val path = m.localPath?.let { src ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val from = java.io.File(src)
                        if (!from.exists()) return@withContext null
                        media.newFile(from.extension.ifEmpty { "bin" }).also { from.copyTo(it) }.path
                    }
                }
                val msg = MessageEntity(
                    senderId = s.user.id,
                    clientId = UUID.randomUUID().toString(),
                    id = null,
                    recipientId = peerId,
                    peerId = peerId,
                    body = m.body,
                    createdAtMillis = System.currentTimeMillis(),
                    deliveredAt = null,
                    readAt = null,
                    failed = false,
                    attachment = m.attachment?.let { NovaJson.encodeToString(it) },
                    localPath = path,
                    forwarded = true,
                )
                scope.launch { queue(msg) }
            }
        }
    }

    fun canEdit(m: ChatMessage, me: String?): Boolean =
        m.senderId == me && m.id != null && !m.deleted && m.attachment == null &&
            System.currentTimeMillis() - m.createdAtMillis < EDIT_WINDOW_MS

    fun canDeleteForEveryone(m: ChatMessage, me: String?): Boolean =
        m.senderId == me && m.id != null && !m.deleted &&
            System.currentTimeMillis() - m.createdAtMillis < DELETE_WINDOW_MS

    /** Queues a hidden control message (reaction, edit, delete) for [peerId]. */
    private suspend fun sendControl(peerId: String, content: MessageContent) {
        val s = session ?: return
        if (dao.groupNow(peerId) != null) {
            fanOut(peerId, content.copy(groupId = peerId), parent = null)
            return
        }
        val msg = MessageEntity(
            senderId = s.user.id,
            clientId = UUID.randomUUID().toString(),
            id = null,
            recipientId = peerId,
            peerId = peerId,
            body = "",
            createdAtMillis = System.currentTimeMillis(),
            deliveredAt = null,
            readAt = null,
            failed = false,
            control = NovaJson.encodeToString(content),
        )
        dao.upsertMessages(listOf(msg))
        deliver(msg)
    }

    private suspend fun setReaction(target: MessageRef, who: String, emoji: String) {
        val row = dao.message(target.senderId, target.clientId) ?: return
        val now = row.reactions?.let { runCatching { NovaJson.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()
        val next = if (emoji.isEmpty()) now - who else now + (who to emoji)
        dao.setReactions(target.senderId, target.clientId, if (next.isEmpty()) null else NovaJson.encodeToString(next))
    }

    private suspend fun removeContent(senderId: String, clientId: String, localPath: String?) {
        dao.markDeleted(senderId, clientId)
        localPath?.let { deleteFile(it) }
    }

    private suspend fun deleteFile(path: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { java.io.File(path).delete() }
    }

    /**
     * Applies a control message that [from] sent in the chat with [peer].
     * People can react to any message in their chat, but only edit or delete
     * their own, and only within the time limits.
     */
    private suspend fun applyControl(from: String, peer: String, c: MessageContent, sentAtMillis: Long) {
        c.call?.let {
            // Calls are one-to-one: ignore call signals inside a group chat.
            if (peer == from) _callSignals.tryEmit(IncomingCallSignal(from, it, sentAtMillis))
            return
        }
        if (applyStatusControl(from, c, sentAtMillis)) return
        val ref = c.reaction?.target ?: c.edit?.target ?: c.delete ?: return
        val target = dao.message(ref.senderId, ref.clientId) ?: return
        if (target.peerId != peer || target.deleted) return
        when {
            c.reaction != null -> setReaction(ref, from, c.reaction.emoji.take(16))
            c.edit != null -> if (target.senderId == from && target.attachment == null &&
                sentAtMillis - target.createdAtMillis < EDIT_WINDOW_MS + CLOCK_SLACK_MS
            ) {
                dao.applyEdit(target.senderId, target.clientId, c.edit.text.take(MAX_TEXT), sentAtMillis)
            }
            c.delete != null -> if (target.senderId == from &&
                sentAtMillis - target.createdAtMillis < DELETE_WINDOW_MS + CLOCK_SLACK_MS
            ) {
                removeContent(target.senderId, target.clientId, target.localPath)
            }
        }
    }

    private suspend fun notice(m: MessageEntity) {
        val mutedUntil = dao.chatPrefsNow(m.peerId)?.mutedUntilMillis
        if (mutedUntil != null && mutedUntil > System.currentTimeMillis()) return
        val sender = dao.userNow(m.senderId)?.toUser()
        val senderName = sender?.displayName ?: "Someone"
        val group = m.groupId?.let { dao.groupNow(it) }
        val a = m.attachment?.let { runCatching { NovaJson.decodeFromString<Attachment>(it) }.getOrNull() }
        val call = m.callLog?.let { runCatching { NovaJson.decodeFromString<CallLog>(it) }.getOrNull() }
        // Only missed calls notify; an answered call needs no reminder.
        if (call != null && !call.missed) return
        val text = when {
            m.undecryptable -> "New message"
            call != null -> if (call.video) "📹 Missed video call" else "📞 Missed voice call"
            a == null -> m.body
            else -> when (a.kind) {
                AttachmentKind.image -> "📷 Photo"
                AttachmentKind.video -> "🎥 Video"
                AttachmentKind.voice -> "🎤 Voice message"
                AttachmentKind.file -> "📄 " + (a.name ?: "Document")
            } + if (m.body.isNotBlank()) " · " + m.body else ""
        }
        _incoming.tryEmit(
            IncomingNotice(
                chatId = m.peerId,
                title = group?.name ?: senderName,
                isGroup = group != null,
                senderId = m.senderId,
                senderName = senderName,
                senderAvatarId = sender?.avatarId,
                text = text,
                atMillis = m.createdAtMillis,
            ),
        )
    }

    /** Applies a status post, view or delete. Returns false for other control messages. */
    private suspend fun applyStatusControl(from: String, c: MessageContent, sentAtMillis: Long): Boolean {
        val me = session?.user?.id ?: return true
        c.status?.let { post ->
            val expires = sentAtMillis + STATUS_TTL_MS
            if (expires > System.currentTimeMillis()) {
                dao.insertStatus(
                    StatusEntity(
                        authorId = from,
                        id = post.id.take(64),
                        text = post.text.orEmpty().take(MAX_STATUS_TEXT),
                        background = post.background,
                        attachment = post.attachment?.takeIf {
                            it.kind == AttachmentKind.image || it.kind == AttachmentKind.video
                        }?.let { NovaJson.encodeToString(it) },
                        localPath = null,
                        createdAtMillis = sentAtMillis,
                        expiresAtMillis = expires,
                        viewed = false,
                        viewers = null,
                        sent = true,
                    ),
                )
            }
            return true
        }
        c.statusSeen?.let { id ->
            val row = dao.status(me, id) ?: return true
            val viewers = row.viewers?.let { runCatching { NovaJson.decodeFromString<Map<String, Long>>(it) }.getOrNull() }.orEmpty()
            if (from !in viewers) dao.setStatusViewers(me, id, NovaJson.encodeToString(viewers + (from to sentAtMillis)))
            return true
        }
        c.statusDelete?.let { id ->
            dao.status(from, id)?.localPath?.let { deleteFile(it) }
            dao.deleteStatus(from, id)
            return true
        }
        return false
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
            if (msg.groupId != null && msg.control == null) deliverGroup(msg) else deliver(msg)
        }
    }

    /** Call when the app comes on screen or goes to the background. */
    fun setActive(active: Boolean) {
        appActive = active
        sendActive()
        // Back on an open chat: what arrived meanwhile is read now.
        if (active) openPeer?.let { markChatRead(it) }
    }

    private fun sendActive() {
        socket.send(buildJsonObject { put("type", "active"); put("active", appActive) }.toString())
    }

    /** True while this chat is open on screen, so it needs no notification. */
    fun isOnScreen(peerId: String): Boolean = appActive && openPeer == peerId

    /** Marks a chat read, e.g. from a notification. */
    fun markChatRead(peerId: String) {
        scope.launch { markRead(peerId) }
    }

    fun sendTyping(peerId: String) {
        socket.send(buildJsonObject { put("type", "typing"); put("to", peerId) }.toString())
    }

    /** Call while a chat is on screen; incoming messages there are marked read. */
    fun setOpenChat(peerId: String?) {
        openPeer = peerId
        if (peerId != null) {
            scope.launch {
                markRead(peerId)
                if (dao.groupNow(peerId) == null) {
                    queryPresence(peerId)
                    refreshProfile(peerId)
                } else {
                    refreshGroup(peerId)
                }
            }
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
            dao.markReadHere(me, unread, OffsetDateTime.now().toString())
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
            if (!cipher.hasSession(msg.recipientId)) {
                val bundle = try {
                    api.keyBundle(s, msg.recipientId)
                } catch (e: ApiException) {
                    when {
                        // They haven't opened NovaChat since signing up: keep the
                        // message queued and try again later.
                        e.status == 404 -> _waitingForKeys.update { it + msg.recipientId }
                        // Offline, or the server is waking up or updating.
                        e.status == 0 || e.status >= 500 -> Unit
                        else -> dao.setFailed(msg.senderId, msg.clientId, true)
                    }
                    return
                }
                _waitingForKeys.update { it - msg.recipientId }
                try {
                    cryptoLock.withLock {
                        if (!cipher.hasSession(msg.recipientId)) cipher.startSession(msg.recipientId, bundle.toPreKeyBundle())
                    }
                } catch (e: CryptoException) {
                    // A bad signature on their prekey: never send to keys we cannot trust.
                    dao.setFailed(msg.senderId, msg.clientId, true)
                    return
                }
            }
            try {
                cryptoLock.withLock {
                    val content = (
                        msg.control ?: NovaJson.encodeToString(
                            MessageContent(
                                text = msg.body.ifEmpty { null },
                                attachment = attachment,
                                replyTo = msg.replyTo?.let { NovaJson.decodeFromString<MessageRef>(it) },
                                forwarded = msg.forwarded,
                                callLog = msg.callLog?.let { NovaJson.decodeFromString<CallLog>(it) },
                            ),
                        )
                    ).toByteArray()
                    envelope = NovaJson.encodeToString(cipher.encrypt(msg.recipientId, content))
                    identityKey = cipher.remoteIdentity(msg.recipientId)?.b64()
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
     * Sends a group message typed on this phone: uploads its file once, then
     * makes one hidden copy per member, each encrypted over that member's own
     * session. The message's ticks are rolled up from the copies.
     */
    private suspend fun deliverGroup(root: MessageEntity) {
        if (!inFlight.add(root.clientId)) return
        try {
            val s = session ?: return
            val groupId = root.groupId ?: return
            val group = dao.groupNow(groupId)?.toGroup() ?: return
            if (!group.canSend(s.user.id)) {
                dao.setFailed(root.senderId, root.clientId, true)
                return
            }
            if (dao.copyCount(s.user.id, root.clientId) > 0) return
            val attachment = if (root.attachment != null) uploadIfNeeded(s, root) ?: return else null
            val content = MessageContent(
                text = root.body.ifEmpty { null },
                attachment = attachment,
                replyTo = root.replyTo?.let { NovaJson.decodeFromString<MessageRef>(it) },
                forwarded = root.forwarded,
                groupId = groupId,
                groupMessageId = root.clientId,
            )
            fanOut(groupId, content, parent = root.clientId)
        } finally {
            inFlight.remove(root.clientId)
        }
    }

    /** Queues one hidden, individually encrypted copy of [content] per group member. */
    private suspend fun fanOut(groupId: String, content: MessageContent, parent: String?) {
        val s = session ?: return
        val me = s.user.id
        val group = dao.groupNow(groupId)?.toGroup() ?: return
        val json = NovaJson.encodeToString(content)
        val now = System.currentTimeMillis()
        val copies = group.members.map { it.userId }.filter { it != me }.map { member ->
            MessageEntity(
                senderId = me,
                clientId = UUID.randomUUID().toString(),
                id = null,
                recipientId = member,
                peerId = groupId,
                body = "",
                createdAtMillis = now,
                deliveredAt = null,
                readAt = null,
                failed = false,
                control = json,
                groupId = groupId,
                parentClientId = parent,
            )
        }
        val saved = db.withTransaction {
            if (parent != null && dao.copyCount(me, parent) > 0) return@withTransaction false
            dao.upsertMessages(copies)
            // Alone in the group: nothing to send, so the message counts as sent.
            if (parent != null && copies.isEmpty()) dao.setIdIfMissing(me, parent, dao.lastSeq() ?: 0L)
            true
        }
        if (saved) copies.forEach { scope.launch { deliver(it) } }
    }

    suspend fun createGroup(name: String, memberIds: List<String>): String {
        val s = session ?: throw ApiException(0, "signed_out", "Not signed in")
        val g = api.createGroup(s, name.trim(), memberIds)
        saveGroup(g)
        return g.id
    }

    /** Fetches a group; if you are no longer in it, the chat stays read-only. */
    fun refreshGroup(id: String) {
        val s = session ?: return
        scope.launch {
            try {
                saveGroup(api.group(s, id))
            } catch (e: ApiException) {
                if (e.status == 404) dao.markLeft(id)
            }
        }
    }

    private suspend fun refreshGroups(s: Session) {
        val all = api.groups(s)
        all.forEach { saveGroup(it) }
        val current = all.map { it.id }.toSet()
        dao.activeGroupIds().filter { it !in current }.forEach { dao.markLeft(it) }
    }

    private suspend fun saveGroup(g: GroupDto) {
        g.members.forEach { dao.upsertUser(it.user.toEntity()) }
        dao.upsertGroup(
            GroupEntity(
                id = g.id,
                name = g.name,
                description = g.description,
                avatarId = g.avatarId,
                onlyAdminsSend = g.onlyAdminsSend,
                onlyAdminsEdit = g.onlyAdminsEdit,
                inviteCode = g.inviteCode,
                members = NovaJson.encodeToString(g.members.map { GroupMember(it.user.id, it.role) }),
                left = false,
            ),
        )
    }

    /** Runs a group change on the server and stores the group as it is afterwards. */
    suspend fun changeGroup(action: suspend ApiClient.(Session) -> GroupDto) {
        val s = session ?: throw ApiException(0, "signed_out", "Not signed in")
        saveGroup(api.action(s))
    }

    suspend fun leaveGroup(id: String) {
        val s = session ?: throw ApiException(0, "signed_out", "Not signed in")
        api.leaveGroup(s, id)
        dao.markLeft(id)
    }

    suspend fun previewInvite(code: String): InvitePreview {
        val s = session ?: throw ApiException(0, "signed_out", "Not signed in")
        return api.previewInvite(s, code)
    }

    suspend fun joinInvite(code: String): String {
        val s = session ?: throw ApiException(0, "signed_out", "Not signed in")
        val g = api.joinInvite(s, code)
        saveGroup(g)
        return g.id
    }

    /**
     * Keeps the stored member list in step with an event as it arrives, so a
     * message from someone just added is accepted even before the group is
     * fetched again.
     */
    private suspend fun applyGroupEvent(groupId: String, e: GroupEvent, me: String) {
        val row = dao.groupNow(groupId) ?: return
        var members = row.toGroup().members
        when (e.event) {
            "added" -> members = members + e.targets.filter { t -> members.none { it.userId == t } }.map { GroupMember(it, "member") }
            "joined" -> if (members.none { it.userId == e.actor }) members = members + GroupMember(e.actor, "member")
            "removed" -> members = members.filter { it.userId !in e.targets }
            "left" -> members = members.filter { it.userId != e.actor }
            "promoted" -> members = members.map { if (it.userId in e.targets) it.copy(role = "admin") else it }
            "demoted" -> members = members.map { if (it.userId in e.targets) it.copy(role = "member") else it }
            else -> return
        }
        dao.setMembers(groupId, NovaJson.encodeToString(members))
        if (members.none { it.userId == me }) dao.markLeft(groupId)
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
            // Too large fails for good; offline or a waking server is retried.
            if (e.status in 400..499) dao.setFailed(msg.senderId, msg.clientId, true)
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
                if (!appActive) sendActive()
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
            cryptoLock.withLock { keys.forgetSessions(msg.recipientId) }
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
            refreshGroups(s)
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
        dao.pendingGroupRoots(s.user.id).forEach { deliverGroup(it) }
        dao.unsentStatuses(s.user.id, System.currentTimeMillis()).forEach { sendStatus(it) }
        cleanUpStatuses()
    }

    private suspend fun applyMessages(dtos: List<MessageDto>) {
        val s = session ?: return
        val me = s.user.id
        if (dtos.isEmpty()) return
        val changedGroups = mutableSetOf<String>()
        val fresh = mutableListOf<Long>()
        // A brand-new install catching up on history does not notify.
        val catchingUpHistory = dao.lastSeq() == null
        writeLock.withLock {
            for (dto in dtos) {
                val isNew = dto.senderId != me && !dao.hasId(dto.id)
                store(dto, me)?.let { changedGroups += it }
                if (isNew) fresh += dto.id
            }
        }
        changedGroups.forEach { refreshGroup(it) }

        // Tell senders their messages reached this phone, and mark the open chat read.
        sendReceipt("delivered", dtos.filter { it.recipientId == me && it.senderId != me && it.deliveredAt == null }.map { it.id })
        if (appActive) openPeer?.let { markRead(it) }

        val unknown = dtos.flatMap { listOf(it.senderId, it.recipientId) }.toSet() - dao.userIds().toSet() - me
        for (id in unknown) {
            runCatching { api.user(s, id) }.getOrNull()?.let { dao.upsertUser(it.toEntity()) }
        }
        if (!catchingUpHistory) fresh.forEach { id -> dao.incomingById(id, me)?.let { notice(it) } }
    }

    /**
     * Saves one message from the server. A message we already have only gets
     * its server fields updated. A new one is decrypted, and the ratchet step
     * and the message are saved in one transaction, so a crash cannot lose a
     * message whose key was already used up.
     */
    private suspend fun store(dto: MessageDto, me: String): String? {
        val existing = dao.message(dto.senderId, dto.clientId)
        if (existing != null) {
            dao.applyServerCopy(dto.senderId, dto.clientId, dto.id, dto.deliveredAt, dto.readAt)
            // The first member's copy getting through marks the group message sent.
            existing.parentClientId?.let { dao.setIdIfMissing(me, it, dto.id) }
            return null
        }
        // Group messages are stored under their shared ID, so look for the server ID too.
        if (dto.senderId != me && dao.hasId(dto.id)) return null
        val peer = if (dto.senderId == me) dto.recipientId else dto.senderId
        val base = dto.toEntity(peer)
        val groupId = dto.groupId
        if (groupId != null && dto.envelope == null) {
            // A group event written by the server: "Ana added Ben".
            if (dto.recipientId != me) return null
            dao.insertMessage(base.copy(peerId = groupId, recipientId = groupId, groupId = groupId, system = true))
            runCatching { NovaJson.decodeFromString<GroupEvent>(dto.body) }.getOrNull()?.let { applyGroupEvent(groupId, it, me) }
            return groupId
        }
        val envelope = dto.envelope
        if (envelope == null) {
            dao.insertMessage(base) // sent before encryption existed
            return null
        }
        if (dto.senderId == me) {
            // Our own message from an earlier install: its keys are gone.
            dao.insertMessage(base.copy(body = "", undecryptable = true))
            return null
        }
        var refresh: String? = null
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
                val gid = content?.groupId
                val group = gid?.let { dao.groupNow(it)?.toGroup() }
                if (gid != null && group == null) refresh = gid
                when {
                    content == null -> dao.insertMessage(base.copy(body = "", undecryptable = true))
                    gid != null -> {
                        val allowed = group == null || (
                            !group.left && group.member(dto.senderId) != null &&
                                (content.isControl || !group.onlyAdminsSend || group.isAdmin(dto.senderId))
                            )
                        val inGroup = base.copy(peerId = gid, recipientId = gid, groupId = gid)
                        when {
                            // Not (or no longer) allowed to post here: keep a hidden row so it is not fetched again.
                            !allowed -> dao.insertMessage(inGroup.copy(body = "", control = "{}"))
                            content.isControl -> {
                                dao.insertMessage(inGroup.copy(body = "", control = NovaJson.encodeToString(content)))
                                applyControl(dto.senderId, gid, content, base.createdAtMillis)
                            }
                            else -> dao.insertMessage(
                                inGroup.copy(
                                    clientId = content.groupMessageId ?: dto.clientId,
                                    body = content.text.orEmpty().take(MAX_TEXT),
                                    attachment = content.attachment?.let { NovaJson.encodeToString(it) },
                                    replyTo = content.replyTo?.let { NovaJson.encodeToString(it) },
                                    forwarded = content.forwarded,
                                ),
                            )
                        }
                    }
                    content.isControl -> {
                        dao.insertMessage(base.copy(body = "", control = NovaJson.encodeToString(content)))
                        applyControl(dto.senderId, peer, content, base.createdAtMillis)
                    }
                    else -> dao.insertMessage(
                        base.copy(
                            body = content.text.orEmpty().take(MAX_TEXT),
                            attachment = content.attachment?.let { NovaJson.encodeToString(it) },
                            replyTo = content.replyTo?.let { NovaJson.encodeToString(it) },
                            forwarded = content.forwarded,
                            callLog = content.callLog?.let { NovaJson.encodeToString(it) },
                            // An answered call is nothing new to read: don't count it as unread.
                            readAt = if (content.callLog?.missed == false) base.readAt ?: dto.createdAt else base.readAt,
                        ),
                    )
                }
            }
        }
        return refresh
    }

    private suspend fun applyReceipt(frame: ServerFrame) {
        val me = session?.user?.id ?: return
        if (frame.messageIds.isEmpty()) return
        val at = frame.at ?: OffsetDateTime.now().toString()
        writeLock.withLock {
            if (frame.status == "read") dao.markRead(me, frame.messageIds, at) else dao.markDelivered(me, frame.messageIds, at)
            dao.parentsOf(frame.messageIds).forEach { dao.rollUpReceipts(me, it, at) }
        }
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
        const val MAX_PINNED = 3
        const val TYPING_MS = 4_000L
        const val RETRY_MS = 30_000L
        val FALLBACK_ICE = listOf(IceServerDto(urls = listOf("stun:stun.l.google.com:19302")))
        const val OWNER = "owner_user_id"
        const val EDIT_WINDOW_MS = 15 * 60_000L
        const val DELETE_WINDOW_MS = 48 * 3_600_000L
        /** Phones' clocks drift; be lenient when checking someone else's limits. */
        const val CLOCK_SLACK_MS = 10 * 60_000L
        const val MAX_TEXT = 65_536
        const val STATUS_TTL_MS = 24 * 3_600_000L
        const val MAX_STATUS_TEXT = 700
    }
}
