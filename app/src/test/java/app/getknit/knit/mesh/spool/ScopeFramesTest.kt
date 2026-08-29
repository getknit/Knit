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
    private val carol = "cccccccccccccccccccccccccc"
    private val mallory = "mmmmmmmmmmmmmmmmmmmmmmmmmm"
    private val root = ByteArray(32) { it.toByte() }

    private val groupId = "g-00112233445566778899aabb"
    private val founding = setOf(alice, bob, carol)

    private fun scope(
        peer: String = bob,
        secret: ByteArray = root,
    ) = Scope(
        id = ScopeCrypto.dmScopeId(secret, alice, peer),
        keys = ScopeCrypto.dmSealKeys(secret, alice, peer),
        bounds = ScopeRegistry.DEFAULT_BOUNDS,
        peerId = peer,
    )

    private fun groupScope(
        gid: String = groupId,
        roster: Set<String> = founding,
        secret: ByteArray = root,
        version: Int = 1,
    ) = Scope(
        id = ScopeCrypto.groupScopeId(secret, gid, version),
        keys = ScopeCrypto.groupSealKeys(secret, gid, version),
        bounds = ScopeRegistry.DEFAULT_BOUNDS,
        groupId = gid,
        roster = roster,
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

    // --- the group form (spec §4.4's second half) ---

    @Test
    fun `accepts the three group types a group scope carries`() {
        val chat = groupChatFrame("gc1", from = bob, groupId = groupId, members = founding.toList()).envelope
        val update = groupUpdateFrame("gu1", from = bob, groupId = groupId, members = founding.toList()).envelope
        val leave = groupLeaveFrame("gl1", from = bob, groupId = groupId).envelope

        listOf(chat, update, leave).forEach {
            assertTrue("${it.type} must ride its group's scope", ScopeFrames.eligibleFor(it, alice, groupScope()))
        }
    }

    @Test
    fun `a groupleave is matched on its PAYLOAD group id, since the envelope carries none`() {
        val leave = groupLeaveFrame("gl1", from = bob, groupId = groupId).envelope
        // The trap this pins: reading only RelayEnvelope.group would drop every departure, which is the
        // frame that drives the remaining members' leave-rekey and the scope rotation itself.
        assertNull("MeshManager.sendGroupLeave sets no envelope group", leave.group)
        assertTrue(ScopeFrames.eligibleFor(leave, alice, groupScope()))
        assertFalse(ScopeFrames.eligibleFor(leave, alice, groupScope(gid = "g-ffffffffffffffffffffffff")))
    }

    @Test
    fun `a departed member's own leave still rides, because the roster is the FOUNDING one`() {
        // carol has departed, so the effective roster no longer holds her — but her signed leave (and her
        // pre-departure frames) must still reach the others. Safe: the re-mint rotates the scope id.
        val leave = groupLeaveFrame("gl2", from = carol, groupId = groupId).envelope
        assertTrue(ScopeFrames.eligibleFor(leave, alice, groupScope(roster = founding)))
        assertFalse(
            "a sender outside the founding roster is refused",
            ScopeFrames.eligibleFor(leave, alice, groupScope(roster = setOf(alice, bob))),
        )
    }

    @Test
    fun `refuses another group, a non-member sender, a v1 group chat, and a DM`() {
        val otherGroup = groupChatFrame("x1", bob, "g-ffffffffffffffffffffffff", founding.toList()).envelope
        val stranger = groupChatFrame("x2", mallory, groupId, founding.toList()).envelope
        val dm = dmFrame("x3", from = alice, to = bob).envelope
        val v1Group =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "x4",
                senderId = bob,
                group = GroupInfo(id = groupId, members = founding.toList(), createdBy = alice),
                payload = WireCodec.encodePayload(ChatContent(enc = ratchetEnc().copyAsV1())),
            )
        // The DM ratchet header in a group frame is the other half of the form split (§4.4): a scope-bound
        // group frame must carry `g`, never `r`.
        val dmHeaderInGroup =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "x5",
                senderId = bob,
                group = GroupInfo(id = groupId, members = founding.toList(), createdBy = alice),
                payload = WireCodec.encodePayload(ChatContent(enc = ratchetEnc())),
            )

        listOf(otherGroup, stranger, dm, v1Group, dmHeaderInGroup).forEach {
            assertFalse("${it.id} must not ride a group scope", ScopeFrames.eligibleFor(it, alice, groupScope()))
        }
    }

    @Test
    fun `a group frame does not ride a DM scope, and a DM does not ride a group scope`() {
        val groupChat = groupChatFrame("gc2", bob, groupId, founding.toList()).envelope
        assertFalse(ScopeFrames.eligibleFor(groupChat, alice, scope()))
        assertFalse(ScopeFrames.eligibleFor(dmFrame("m1", alice, bob).envelope, alice, groupScope()))
    }

    @Test
    fun `both members' profiles ride a DM scope, matched on sender since a profile addresses nobody`() {
        val mine = profileFrame("p1", from = alice).envelope
        val theirs = profileFrame("p2", from = bob).envelope
        assertNull("fixture must address nobody, or it is not exercising the sender-only match", mine.recipientId)
        assertTrue(ScopeFrames.eligibleForDm(mine, alice, bob))
        assertTrue(ScopeFrames.eligibleForDm(theirs, alice, bob))
    }

    @Test
    fun `a third party's profile rides neither form`() {
        val stranger = profileFrame("p3", from = mallory).envelope
        assertFalse(ScopeFrames.eligibleForDm(stranger, alice, bob))
        assertFalse(ScopeFrames.eligibleForGroup(stranger, groupId, founding))
    }

    @Test
    fun `a founding-roster member's profile rides a group scope, and the roster is the whole rule`() {
        val member = profileFrame("p4", from = carol).envelope
        assertTrue(ScopeFrames.eligibleForGroup(member, groupId, founding))
        // It names no group, so nothing else can gate it — a roster that excludes the sender is the only
        // thing standing between a co-member's prekey and this scope.
        assertFalse(ScopeFrames.eligibleForGroup(member, groupId, setOf(alice, bob)))
    }

    @Test
    fun `a profile blob round-trips through the seal and the frame-set rule`() {
        val frame = profileFrame("p5", from = bob)
        val sealed = ScopeFrames.seal(scope(), frame.sig, frame.signed)
        val opened = ScopeFrames.open(scope(), alice, sealed.blobId, sealed.blob)
        assertNotNull(opened)
        assertEquals("p5", opened!!.env.id)
        assertArrayEquals(frame.signed, opened.wire.signed)
    }

    @Test
    fun `a scope with neither form carries nothing`() {
        val formless = Scope(id = ByteArray(32), keys = ScopeCrypto.dmSealKeys(root, alice, bob), bounds = ScopeRegistry.DEFAULT_BOUNDS)
        assertFalse(ScopeFrames.eligibleFor(dmFrame("m1", alice, bob).envelope, alice, formless))
    }

    @Test
    fun `a group blob round-trips through the seal and the frame-set rule`() {
        val frame = groupChatFrame("gc3", from = bob, groupId = groupId, members = founding.toList())
        val sealed = ScopeFrames.seal(groupScope(), frame.sig, frame.signed)
        val opened = ScopeFrames.open(groupScope(), alice, sealed.blobId, sealed.blob)
        assertNotNull(opened)
        assertArrayEquals(frame.signed, opened!!.wire.signed)
        assertEquals("gc3", opened.env.id)
        // A rotated scope (fresh root, bumped version) cannot open the old lineage's blob — the aad binds
        // the scope id, and §3.3 says old blobs are never migrated.
        assertNull(ScopeFrames.open(groupScope(secret = ByteArray(32) { 99 }, version = 2), alice, sealed.blobId, sealed.blob))
    }

    private fun envelope(
        type: String,
        from: String,
        to: String,
        content: ChatContent,
    ) = RelayEnvelope(type = type, id = "x", senderId = from, recipientId = to, payload = WireCodec.encodePayload(content))

    private fun ratchetEnc() = dmFrame("seed", alice, bob).let { WireCodec.decodePayload<ChatContent>(it.envelope.payload)!!.enc!! }

    private fun EncEnvelope.copyAsV1() = EncEnvelope(v = 1, nonce = nonce, ct = ct, keys = keys, r = r)

    /** The v3 DM form (ADR 059): the same header, an empty (derived) nonce, one version higher. */
    private fun EncEnvelope.copyAsV3() = EncEnvelope(v = EncEnvelope.VERSION_DM_V3, nonce = ByteArray(0), ct = ct, keys = keys, r = r)

    @Test
    fun `a v3 DM rides the scope and a v3 group form does not`() {
        val v3 = envelope(FrameType.CHAT, alice, bob, ChatContent(enc = ratchetEnc().copyAsV3()))
        assertTrue("v3 is the DM form's compact sibling and must stay spool-eligible", ScopeFrames.eligibleForDm(v3, alice, bob))
        val v3GroupShaped = envelope(FrameType.CHAT, alice, bob, ChatContent(enc = ratchetEnc().copyAsV3().copyWithoutDmHeader()))
        assertFalse(ScopeFrames.eligibleForDm(v3GroupShaped, alice, bob))
    }

    private fun EncEnvelope.copyWithoutDmHeader() = EncEnvelope(v = v, nonce = nonce, ct = ct, keys = keys, r = null)
}
