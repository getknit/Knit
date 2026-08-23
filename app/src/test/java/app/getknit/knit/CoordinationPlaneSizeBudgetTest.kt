package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.PrekeyInfo
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RatchetInit
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.wifiaware.WifiAwareTransport
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkePrivateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable size budget for the Wi-Fi Aware coordination-plane fast path ([WifiAwareTransport]'s
 * `fastFanout`/`fastSend`, one `sendMessage` of ≤ [WifiAwareTransport.COORD_MSG_MAX] bytes including
 * the 1-byte tag). Every representative frame is built with **real** crypto (Tink Ed25519 signatures,
 * a real [RatchetEngine] session for the v2 sealed forms) and real-length ids (22-char [FrameId],
 * 26-char [NodeId]), so the pinned ≤/> expectations are the measured truth about what rides the fast
 * plane and what silently no-ops — not hand-derived CBOR arithmetic. Prints a size table so codec/dict
 * work (`mesh/link/FastFrameCodec`) can be tuned against the same fixtures.
 */
class CoordinationPlaneSizeBudgetTest {
    /** A device identity: its cipher (private keys), its public bundle, and the nodeId it derives to. */
    private class Party(
        val crypto: MessageCrypto,
        val bundle: PublicKeyBundle,
        /** The raw X25519 identity scalar (the extraction IdentityKeyStore.dhIdentityPrivate performs). */
        val dhPriv: ByteArray,
    ) {
        val nodeId: String = NodeId.fromPublicKeyBundle(bundle.encoded)

        /** Wraps + signs [env] with this party's key (mirrors MeshManager.sign). */
        fun sign(
            env: RelayEnvelope,
            relay: Boolean = true,
        ): WireEnvelope {
            val signed = WireCodec.encodeEnvelope(env)
            return WireEnvelope(relay = relay, sig = crypto.signRaw(signed), signed = signed)
        }
    }

    private fun party(): Party {
        TinkInit.ensure()
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get(SIG_TEMPLATE))
        val dhPriv =
            (hybrid.primary.key as HpkePrivateKey).privateKeyBytes.toByteArray(InsecureSecretKeyAccess.get())
        return Party(MessageCrypto(hybrid, sig), PublicKeyBundle.fromPrivate(hybrid, sig), dhPriv)
    }

    /**
     * Author-side v2 epoch ratchet against [to] (which published signed prekey [toSpk]): real engine
     * state driving real sealed frames, distilled from InboundPipelineTest's V2Author. The first [dm]
     * carries the X3DH [RatchetInit]; later ones are the steady-state form — both budgets matter.
     */
    private class V2Sealer(
        val party: Party,
        private val to: Party,
        private val toSpk: RatchetCrypto.KeyPair,
    ) {
        private val engine = RatchetEngine()
        private var session: RatchetEngine.SessionState =
            engine
                .initiate(
                    peerId = to.nodeId,
                    ownIkPriv = party.dhPriv,
                    peerIkPub = to.bundle.dhPublicKey(),
                    peerSpk = RatchetEngine.PeerPrekey(id = SPK_ID, pub = toSpk.pub),
                    now = SESSION_AT,
                ).session

        /** Marks the session confirmed (as the peer's first reply would), so later frames drop the init. */
        fun confirm() {
            session = session.copy(confirmed = true)
        }

        fun dm(
            id: String,
            body: String,
            ctl: Int? = null,
            ack: String? = null,
            rp: ReactionPayload? = null,
            acks: List<String>? = null,
        ): RelayEnvelope {
            val aad = MessageCrypto.header(id, party.nodeId, SENT_AT, to.nodeId)
            val plain = MessageContent(body = body, ctl = ctl, ack = ack, rp = rp, acks = acks).encode()
            val sealed = checkNotNull(engine.seal(session, plain, aad, toSpk.pub, now = SESSION_AT))
            session = sealed.session
            val h = sealed.header
            return RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = party.nodeId,
                sentAt = SENT_AT,
                recipientId = to.nodeId,
                payload =
                    WireCodec.encodePayload(
                        ChatContent(
                            enc =
                                EncEnvelope(
                                    v = EncEnvelope.VERSION_RATCHET,
                                    nonce = sealed.nonce,
                                    ct = sealed.ct,
                                    keys = emptyList(),
                                    r =
                                        RatchetHeader(
                                            se = h.se,
                                            ek = h.ek,
                                            pe = h.pe,
                                            n = h.n,
                                            init = h.init?.let { RatchetInit(eph = it.eph, pkid = it.pkid, at = it.at) },
                                            flags = h.flags,
                                        ),
                                ),
                        ),
                    ),
            )
        }
    }

    /** The on-air message size for [wire]: the encoded envelope plus the transport's 1-byte tag. */
    private fun legacySize(wire: WireEnvelope): Int = WireCodec.encodeWire(wire).size + 1

    /** Legacy + compact budgets for [wire]: prints the row, sanity-checks the crypto, returns the sizes. */
    private fun report(
        label: String,
        wire: WireEnvelope,
        author: Party,
    ): Sizes {
        val legacy = legacySize(wire)
        val compact = checkNotNull(FastFrameCodec.encodeCompact(wire)) { "$label must be compact-encodable" }
        val parts =
            when {
                compact.size <= WifiAwareTransport.COORD_MSG_MAX -> {
                    1
                }

                else -> {
                    checkNotNull(
                        FastFrameCodec.fragment(compact, WifiAwareTransport.COORD_MSG_MAX, fragId = 1),
                    ) { "$label must fit ${FastFrameCodec.MAX_PARTS} parts" }.size
                }
            }
        val deflated = if ((compact[1].toInt() and 0x02) != 0) "deflated" else "stored"
        println(
            "size-budget: $label legacy=${legacy}B compact=${compact.size}B ($deflated) parts=$parts " +
                "(cap ${WifiAwareTransport.COORD_MSG_MAX})",
        )
        assertEquals("raw Ed25519 signature", 64, wire.sig.size)
        assertTrue("$label verifies", MessageCrypto.verify(author.bundle, wire.sig, wire.signed))
        val decoded = checkNotNull(FastFrameCodec.decodeCompact(compact)) { "$label compact round-trip" }
        assertTrue("$label signature survives the compact round-trip", MessageCrypto.verify(author.bundle, decoded.sig, decoded.signed))
        assertTrue("compact never expands past legacy", compact.size < legacy)
        return Sizes(legacy, compact.size, parts)
    }

    private class Sizes(
        val legacy: Int,
        val compact: Int,
        val parts: Int,
    )

    // --- fixtures ---

    private fun cleartextReceipt(alice: Party): WireEnvelope =
        alice.sign(
            RelayEnvelope(
                type = FrameType.RECEIPT,
                id = FrameId.new(),
                senderId = alice.nodeId,
                sentAt = SENT_AT,
                payload = WireCodec.encodePayload(ReceiptContent(ackId = FrameId.new())),
            ),
        )

    private fun cleartextReaction(alice: Party): WireEnvelope =
        alice.sign(
            RelayEnvelope(
                type = FrameType.REACTION,
                id = FrameId.new(),
                senderId = alice.nodeId,
                sentAt = SENT_AT,
                payload = WireCodec.encodePayload(ReactionContent(messageId = FrameId.new(), emoji = "👍")),
            ),
        )

    private fun fullProfile(
        alice: Party,
        spk: RatchetCrypto.KeyPair,
    ): WireEnvelope {
        val spkSig = alice.crypto.signRaw(RatchetCrypto.spkSigningBytes(SPK_ID, spk.pub))
        return alice.sign(
            RelayEnvelope(
                type = FrameType.PROFILE,
                id = FrameId.new(),
                senderId = alice.nodeId,
                sentAt = SENT_AT,
                payload =
                    WireCodec.encodePayload(
                        ProfileContent(
                            name = "Alice Example",
                            status = "Out exploring the mesh",
                            pubKey = alice.bundle.encoded,
                            deviceTag = "tag-0123456789abcdef",
                            protoVersion = Protocol.VERSION,
                            capabilities = Protocol.LOCAL_CAPABILITIES,
                            prekey = PrekeyInfo(id = SPK_ID, pub = spk.pub, sig = spkSig),
                            version = SENT_AT,
                        ),
                    ),
            ),
        )
    }

    private fun typingDm(
        alice: Party,
        to: Party,
    ): WireEnvelope =
        alice.sign(
            RelayEnvelope(
                type = FrameType.TYPING,
                id = FrameId.new(),
                senderId = alice.nodeId,
                sentAt = SENT_AT,
                recipientId = to.nodeId,
                payload = WireCodec.encodePayload(TypingContent()),
            ),
            relay = false,
        )

    private fun typingGroup(alice: Party): WireEnvelope =
        alice.sign(
            RelayEnvelope(
                type = FrameType.TYPING,
                id = FrameId.new(),
                senderId = alice.nodeId,
                sentAt = SENT_AT,
                payload = WireCodec.encodePayload(TypingContent(groupId = "g-" + "a".repeat(26))),
            ),
            relay = false,
        )

    // --- budgets: cleartext frames ---

    @Test
    fun cleartextReceiptFitsOneMessage() {
        val alice = party()
        val sizes = report("cleartext-receipt", cleartextReceipt(alice), alice)
        assertTrue(sizes.legacy <= WifiAwareTransport.COORD_MSG_MAX)
        assertEquals("one message, with headroom regained", 1, sizes.parts)
    }

    @Test
    fun cleartextReactionFitsOneMessage() {
        val alice = party()
        val sizes = report("cleartext-reaction", cleartextReaction(alice), alice)
        assertTrue(sizes.legacy <= WifiAwareTransport.COORD_MSG_MAX)
        assertEquals(1, sizes.parts)
    }

    @Test
    fun typingCuesFitOneMessage() {
        val alice = party()
        val bob = party()
        val dm = report("typing-dm", typingDm(alice, bob), alice)
        val group = report("typing-group", typingGroup(alice), alice)
        assertTrue(dm.legacy <= WifiAwareTransport.COORD_MSG_MAX && group.legacy <= WifiAwareTransport.COORD_MSG_MAX)
        assertEquals(1, dm.parts)
        assertEquals(1, group.parts)
    }

    @Test
    fun fullProfileBudget() {
        val alice = party()
        val sizes = report("profile-full", fullProfile(alice, RatchetCrypto.generateKeyPair()), alice)
        assertTrue(
            "a full profile (pubKey + prekey) outgrows one message — fastFanout no-ops it today",
            sizes.legacy > WifiAwareTransport.COORD_MSG_MAX,
        )
        assertTrue("compact + fragmentation carries it in <= 3", sizes.parts <= FastFrameCodec.MAX_PARTS)
    }

    // --- budgets: v2 sealed frames (the frames Tier 0 exists for) ---

    @Test
    fun sealedCtlReceiptBudget() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        val first = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, ack = FrameId.new()), relay = false)
        sealer.confirm()
        val steady = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, ack = FrameId.new()), relay = false)
        val initSizes = report("sealed-receipt-init", first, alice)
        val steadySizes = report("sealed-receipt-steady", steady, alice)
        assertTrue("sealed receipts outgrow one legacy message (why Tier 0 exists)", steadySizes.legacy > WifiAwareTransport.COORD_MSG_MAX)
        assertTrue("the init form is the larger of the two", initSizes.legacy > steadySizes.legacy)
        assertTrue("compact + fragmentation carries both in <= 2", initSizes.parts <= 2 && steadySizes.parts <= 2)
    }

    @Test
    fun batchedSealedReceiptNeverRidesTheFastPlane() {
        // The executable reason AckSync structurally keeps pending batches off fastSend: a batched tick
        // (the custody escalation) outgrows even the compact fragment budget well before its 64-ack cap,
        // so the coordination plane could never carry it — escalated batches ride custody (NDP) only.
        // (Not routed through report(): that helper checkNotNull's fragmentation, and failing to fit IS
        // this test's point.)
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        sealer.confirm()
        val batch =
            alice.sign(
                sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, acks = List(16) { FrameId.new() }),
                relay = false,
            )
        val legacy = legacySize(batch)
        assertTrue("a 16-ack batch outgrows one legacy message", legacy > WifiAwareTransport.COORD_MSG_MAX)
        val compact = checkNotNull(FastFrameCodec.encodeCompact(batch)) { "batched tick must still compact-encode" }
        val frags = FastFrameCodec.fragment(compact, WifiAwareTransport.COORD_MSG_MAX, fragId = 1)
        assertTrue(
            "a 16-ack batch cannot ride the fast plane the way a single tick does (single ticks fit <= 2 fragments)",
            frags == null || frags.size > 2,
        )
    }

    @Test
    fun sealedCtlReactionBudget() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        sealer.confirm()
        val steady =
            alice.sign(
                sealer.dm(
                    FrameId.new(),
                    body = "",
                    ctl = MessageContent.CTL_REACTION,
                    rp = ReactionPayload(messageId = FrameId.new(), emoji = "👍"),
                ),
            )
        val sizes = report("sealed-reaction-steady", steady, alice)
        assertTrue("sealed reactions outgrow one legacy message", sizes.legacy > WifiAwareTransport.COORD_MSG_MAX)
        assertTrue(sizes.parts <= 2)
    }

    @Test
    fun shortSealedDmChatBudget() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        sealer.confirm()
        val steady = alice.sign(sealer.dm(FrameId.new(), body = "See you at the north gate in ten minutes"))
        val sizes = report("sealed-dm-40char-steady", steady, alice)
        assertTrue("even a short sealed DM outgrows one legacy message", sizes.legacy > WifiAwareTransport.COORD_MSG_MAX)
        assertTrue(sizes.parts <= 2)
    }

    @Test
    fun fragBudgetArithmetic() {
        // 3 parts x (cap - 4 B frag header) is the ceiling for any compact frame on this plane.
        assertEquals(
            753,
            FastFrameCodec.MAX_PARTS * (WifiAwareTransport.COORD_MSG_MAX - FastFrameCodec.FRAG_HEADER_BYTES),
        )
    }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"
        const val SIG_TEMPLATE = "ED25519_RAW"
        const val SPK_ID = 1

        /** Fixed realistic clocks so every run measures identical frames. */
        const val SENT_AT = 1_755_700_000_000L
        const val SESSION_AT = 1_755_700_000_000L
    }
}
