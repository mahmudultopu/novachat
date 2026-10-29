package app.novachat.crypto

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory key store standing in for the phone's encrypted database. */
private class MemoryStore : KeyStore {
    val identityKeys = IdentityKeys.generate()
    val spk = DhKeyPair.generate().let { SignedPreKey(1, it, identityKeys.signing.sign(it.publicKey)) }
    val oneTime = (1..5).associateWith { OneTimePreKey(it, DhKeyPair.generate()) }.toMutableMap()
    val sessionsByPeer = mutableMapOf<String, String>()

    override suspend fun identity() = identityKeys
    override suspend fun signedPreKey(id: Int) = spk.takeIf { it.id == id }
    override suspend fun takeOneTimePreKey(id: Int) = oneTime.remove(id)

    // Round-trip through JSON like the real store, so serialization is tested too.
    override suspend fun sessions(peerId: String): List<SessionState> =
        sessionsByPeer[peerId]?.let { SessionCipher.json.decodeFromString<List<SessionState>>(it) }.orEmpty()

    override suspend fun saveSessions(peerId: String, sessions: List<SessionState>) {
        sessionsByPeer[peerId] = SessionCipher.json.encodeToString(sessions)
    }

    fun bundle(withOneTime: Boolean = true): PreKeyBundle {
        val opk = if (withOneTime) oneTime.values.first() else null
        return PreKeyBundle(
            identityKeys.dh.publicKey, identityKeys.signing.publicKey,
            spk.id, spk.keyPair.publicKey, spk.signature,
            opk?.id, opk?.keyPair?.publicKey,
        )
    }
}

class SessionCipherTest {
    private val aliceStore = MemoryStore()
    private val bobStore = MemoryStore()
    private val alice = SessionCipher(aliceStore)
    private val bob = SessionCipher(bobStore)

    private fun wire(e: Envelope) = SessionCipher.json.decodeFromString<Envelope>(SessionCipher.json.encodeToString(e))

    private suspend fun SessionCipher.send(to: String, text: String) = wire(encrypt(to, text.toByteArray()))
    private suspend fun SessionCipher.receive(from: String, e: Envelope) = String(decrypt(from, e).plaintext)

    @Test
    fun conversationWorksBothWays() = runBlocking<Unit> {
        alice.startSession("bob", bobStore.bundle())
        val first = alice.send("bob", "Hello Bob")
        assertNotNull(first.pre, "first message carries the X3DH header")
        val d = bob.decrypt("alice", first)
        assertEquals("Hello Bob", String(d.plaintext))
        assertTrue(d.newSession)
        assertEquals(4, bobStore.oneTime.size, "the one-time prekey is used up")

        assertEquals("Hi Alice", alice.receive("bob", bob.send("alice", "Hi Alice")))
        val afterReply = alice.send("bob", "How are you?")
        assertNull(afterReply.pre, "no X3DH header once Bob has replied")
        assertEquals("How are you?", bob.receive("alice", afterReply))

        repeat(50) { i ->
            val (from, to, fromName, toName) = if (Random.nextBoolean()) {
                listOf(alice, bob, "alice", "bob")
            } else {
                listOf(bob, alice, "bob", "alice")
            }
            from as SessionCipher; to as SessionCipher
            val text = "message $i"
            assertEquals(text, to.receive(fromName as String, from.send(toName as String, text)))
        }
    }

    @Test
    fun outOfOrderAndLostMessages() = runBlocking<Unit> {
        alice.startSession("bob", bobStore.bundle())
        val msgs = (0 until 20).map { alice.send("bob", "m$it") }
        // Bob gets them shuffled, and two never arrive.
        val delivered = msgs.withIndex().filter { it.index != 3 && it.index != 11 }.shuffled()
        for ((i, e) in delivered) assertEquals("m$i", bob.receive("alice", e))
        // After Bob replies, Alice keeps going in a new ratchet step.
        assertEquals("ok", alice.receive("bob", bob.send("alice", "ok")))
        val late = alice.send("bob", "later")
        assertEquals("later", bob.receive("alice", late))
    }

    @Test
    fun aMessageCannotBeDecryptedTwiceOrTampered() = runBlocking<Unit> {
        alice.startSession("bob", bobStore.bundle())
        alice.receive("bob", run { bob.receive("alice", alice.send("bob", "hi")); bob.send("alice", "yo") })
        val e = alice.send("bob", "secret")
        val flipped = e.ct.unb64().also { it[0] = (it[0].toInt() xor 1).toByte() }.b64()
        assertFailsWith<CryptoException> { bob.decrypt("alice", e.copy(ct = flipped)) }
        assertEquals("secret", bob.receive("alice", e))
        assertFailsWith<CryptoException> { bob.decrypt("alice", e) }
    }

    @Test
    fun bothStartAtOnce() = runBlocking<Unit> {
        alice.startSession("bob", bobStore.bundle())
        bob.startSession("alice", aliceStore.bundle())
        val a1 = alice.send("bob", "from alice")
        val b1 = bob.send("alice", "from bob")
        assertEquals("from bob", alice.receive("bob", b1))
        assertEquals("from alice", bob.receive("alice", a1))
        // They now converge on one session and keep talking.
        repeat(5) {
            assertEquals("a$it", bob.receive("alice", alice.send("bob", "a$it")))
            assertEquals("b$it", alice.receive("bob", bob.send("alice", "b$it")))
        }
    }

    @Test
    fun worksWithoutOneTimePreKeys() = runBlocking<Unit> {
        alice.startSession("bob", bobStore.bundle(withOneTime = false))
        assertEquals("hey", bob.receive("alice", alice.send("bob", "hey")))
        assertEquals(5, bobStore.oneTime.size)
    }

    @Test
    fun forgedSignedPreKeyIsRejected() = runBlocking<Unit> {
        val good = bobStore.bundle()
        val forged = PreKeyBundle(
            good.identityKey, good.signingKey, good.signedPreKeyId,
            DhKeyPair.generate().publicKey, good.signedPreKeySignature, null, null,
        )
        assertFailsWith<CryptoException> { alice.startSession("bob", forged) }
    }

    @Test
    fun safetyNumbersMatchOnBothPhones() {
        val a = aliceStore.identityKeys.dh.publicKey
        val b = bobStore.identityKeys.dh.publicKey
        val fromAlice = SessionCipher.safetyNumber("alice-id", a, "bob-id", b)
        val fromBob = SessionCipher.safetyNumber("bob-id", b, "alice-id", a)
        assertEquals(60, fromAlice.length)
        assertEquals(fromAlice, fromBob)
        val other = SessionCipher.safetyNumber("alice-id", a, "bob-id", DhKeyPair.generate().publicKey)
        assertTrue(other != fromAlice)
    }
}
