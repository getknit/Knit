package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The member-side frame rules of `docs/SPOOL_PROTOCOL.md` §4.3/§4.4/§9.2. The frame-set rule is the
 * security boundary of the plane — it is what stops a scope from becoming a general-purpose upload
 * channel — so it is tested as a full accept/reject matrix, in both directions.
 */
class ScopeFramesTest {
    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val mallory = "mmmmmmmmmmmmmmmmmmmmmmmmmm"
    private val root = ByteArray(32) { it.toByte() }

    private fun scope(
        peer: String = bob,
        secret: ByteArray = root,
    ) = Scope(
        id = ScopeCrypto.dmScopeId(secret, alice, peer),
        keys = ScopeCrypto.dmSealKeys(secret, alice, peer),
        peerId = peer,
        bounds = ScopeRegistry.DEFAULT_BOUNDS,
    )

    @Test
    fun `accepts a v2-sealed DM between the scope's pair, either direction`() {
        val outbound = dmFrame("m1", from = alice, to = bob).envelope
        val inbound = dmFrame("m2", from = bob, to = alice).envelope
        assertTrue(ScopeFrames.eligibleForDm(outbound, alice, bob))
        assertTrue(ScopeFrames.eligibleForDm(inbound, alice, bob))
    }

    @Test
    fun `refuses a DM involving a third party`() {
        val fromStranger = dmFrame("m3", from = mallory, to = alice).envelope
        val toStranger = dmFrame("m4", from = alice, to = mallory).envelope
        assertFalse(ScopeFrames.eligibleForDm(fromStranger, alice, bob))
        assertFalse(ScopeFrames.eligibleForDm(toStranger, alice, bob))
    }

    @Test
    fun `refuses non-chat types, group forms, the broadcast room, and v1 or unsealed payloads`() {
        val receipt = envelope(FrameType.RECEIPT, alice, bob, ChatContent(enc = ratchetEnc()))
        val group =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "g1",
                senderId = alice,
                recipientId = bob,
                group = GroupInfo(id = "g-01", members = listOf(alice, bob), createdBy = alice),
                payload = WireCodec.encodePayload(ChatContent(enc = ratchetEnc())),
            )
        val broadcast = RelayEnvelope(FrameType.CHAT, "b1", alice, payload = WireCodec.encodePayload(ChatContent(body = "hi")))
        val cleartext = envelope(FrameType.CHAT, alice, bob, ChatContent(body = "hi"))
        val v1 = envelope(FrameType.CHAT, alice, bob, ChatContent(enc = ratchetEnc().copyAsV1()))
        val groupRatchet = envelope(FrameType.CHAT, alice, bob, ChatContent(enc = ratchetEnc().copyWithoutDmHeader()))

        listOf(receipt, group, broadcast, cleartext, v1, groupRatchet).forEach {
            assertFalse("${it.type}/${it.id} must not ride a DM scope", ScopeFrames.eligibleForDm(it, alice, bob))
        }
    }

    @Test
    fun `sealing is deterministic, so every member seals a frame to the same blob id`() {
        val frame = dmFrame("m1", from = alice, to = bob)
        val mine = ScopeFrames.seal(scope(), frame.sig, frame.signed)
        val theirs = ScopeFrames.seal(scope(), frame.sig, frame.signed)
        assertArrayEquals(mine.blobId, theirs.blobId)
        assertArrayEquals(mine.blob, theirs.blob)
        assertEquals(ScopeCrypto.SEAL_VERSION, mine.blob[0])
    }

    @Test
    fun `opens a well-formed blob back into its verbatim custody unit`() {
        val frame = dmFrame("m1", from = bob, to = alice)
        val sealed = ScopeFrames.seal(scope(), frame.sig, frame.signed)
        val opened = ScopeFrames.open(scope(), alice, sealed.blobId, sealed.blob)
        assertNotNull(opened)
        assertArrayEquals(frame.sig, opened!!.wire.sig)
        assertArrayEquals(frame.signed, opened.wire.signed)
        assertEquals("m1", opened.env.id)
        // §9.4's re-serve shape: a fresh wrapper with a full hop budget, not the sender's counters.
        assertEquals(0, opened.wire.hops)
        assertTrue(opened.wire.ttl > 0)
    }

    @Test
    fun `quarantines a mismatched content address, a tampered blob, and a foreign scope`() {
        val frame = dmFrame("m1", from = bob, to = alice)
        val sealed = ScopeFrames.seal(scope(), frame.sig, frame.signed)

        val wrongId = ScopeCrypto.blobId(byteArrayOf(9, 9, 9))
        assertNull("content address must be re-verified", ScopeFrames.open(scope(), alice, wrongId, sealed.blob))

        val tampered = sealed.blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull("AEAD must reject a flipped byte", ScopeFrames.open(scope(), alice, ScopeCrypto.blobId(tampered), tampered))

        val other = scope(secret = ByteArray(32) { (it + 100).toByte() })
        assertNull("the aad binds the scope id", ScopeFrames.open(other, alice, sealed.blobId, sealed.blob))

        assertNull(
            "a truncated blob is structurally invalid",
            ScopeFrames.open(scope(), alice, ScopeCrypto.blobId(ByteArray(4)), ByteArray(4)),
        )
    }

    @Test
    fun `a blob whose frame fails the frame-set rule is refused even though it opens`() {
        // A blob sealed correctly into the scope but carrying a frame addressed to someone else: the
        // uploader is a scope member, so only the frame-set rule catches this.
        val foreign = dmFrame("m9", from = alice, to = mallory)
        val sealed = ScopeFrames.seal(scope(), foreign.sig, foreign.signed)
        assertNull(ScopeFrames.open(scope(), alice, sealed.blobId, sealed.blob))
    }

    @Test
    fun `dead-on-arrival guard trips exactly at the scope ttl, not the mesh custody ttl`() {
        val env = dmFrame("m1", from = alice, to = bob, sentAt = 1_000L).envelope
        val ttl = ScopeRegistry.DEFAULT_TTL_MS
        assertFalse(ScopeFrames.deadOnArrival(env, ttl, now = 1_000L + ttl - 1))
        assertTrue(ScopeFrames.deadOnArrival(env, ttl, now = 1_000L + ttl))
    }

    private fun envelope(
        type: String,
        from: String,
        to: String,
        content: ChatContent,
    ) = RelayEnvelope(type = type, id = "x", senderId = from, recipientId = to, payload = WireCodec.encodePayload(content))

    private fun ratchetEnc() = dmFrame("seed", alice, bob).let { WireCodec.decodePayload<ChatContent>(it.envelope.payload)!!.enc!! }

    private fun EncEnvelope.copyAsV1() = EncEnvelope(v = 1, nonce = nonce, ct = ct, keys = keys, r = r)

    private fun EncEnvelope.copyWithoutDmHeader() = EncEnvelope(v = v, nonce = nonce, ct = ct, keys = keys, r = null)
}
