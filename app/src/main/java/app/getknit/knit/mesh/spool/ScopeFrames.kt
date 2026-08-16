package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * One conversation's Internet presence: the scope id both members derive from a shared secret, the
 * seal keys bound to it, and the retention [bounds] members declare at SUB. [peerId] is the DM
 * counterpart (group scopes are deferred — `docs/SPOOL_PROTOCOL.md` §3.2/§3.3 and the roadmap).
 * [retiring] marks a scope derived from a *previous* session root, kept subscribed for the ratchet's
 * drain window so a session replacement doesn't strand blobs (spec §3.1): we still pull and heal it,
 * but never seal fresh frames into it.
 */
class Scope(
    val id: ByteArray,
    val keys: ScopeCrypto.SealKeys,
    val peerId: String,
    val bounds: ScopeBounds,
    val retiring: Boolean = false,
) {
    /** The spec's display form — lowercase hex — and this scope's identity in maps/logs/diagnostics. */
    val idHex: String = hex(id)

    override fun equals(other: Any?): Boolean = other is Scope && other.idHex == idHex

    override fun hashCode(): Int = idHex.hashCode()
}

/**
 * The member-side frame rules of `docs/SPOOL_PROTOCOL.md` — what may ride a scope (§4.4), how a frame
 * becomes a blob and back (§4.3/§4.4), and the outward dead-on-arrival guard (§9.2). Pure: no Android,
 * no IO, no state, so the whole rule set is unit-testable against fixtures.
 *
 * The Ed25519 sender check the spec's §4.4 pipeline also demands is deliberately **not** re-implemented
 * here — it is `InboundPipeline.canCarry`, which already resolves the pinned key, rejects a blocked
 * sender, and verifies the signature byte-exact. [ScopeSync] runs it as the step between [open] and the
 * mesh bridge, the way [app.getknit.knit.mesh.ForwardSync] injects its `authenticate` hook.
 */
object ScopeFrames {
    /** A sealed custody unit ready to PUSH: the blob and its content address. */
    class Sealed(
        val blobId: ByteArray,
        val blob: ByteArray,
    )

    /** A blob that survived [open]: the re-serve wrapper and its decoded routing envelope. */
    class Opened(
        val wire: WireEnvelope,
        val env: RelayEnvelope,
    )

    /**
     * The DM half of the §4.4 frame-set rule: `type = chat`, the sender/recipient pair is exactly this
     * scope's two members, and the payload is v2-sealed with the DM ratchet header. It governs **both**
     * directions — a frame that fails it is neither sealed into the scope nor accepted out of it.
     *
     * Group forms, the plaintext broadcast room, profiles, and the cleartext receipt/reaction frames are
     * all excluded: a scope-eligible pair is ratchet-capable by construction, so its receipts and
     * reactions already ride as sealed ctl frames inside chat (`docs/ENCRYPTED_RECEIPTS_REACTIONS.md`).
     */
    fun eligibleForDm(
        env: RelayEnvelope,
        selfId: String,
        peerId: String,
    ): Boolean {
        if (env.type != FrameType.CHAT || env.group != null) return false
        val recipient = env.recipientId ?: return false
        val pairMatches =
            (env.senderId == selfId && recipient == peerId) ||
                (env.senderId == peerId && recipient == selfId)
        if (!pairMatches) return false
        val enc = WireCodec.decodePayload<ChatContent>(env.payload)?.enc ?: return false
        return enc.v == EncEnvelope.VERSION_RATCHET && enc.r != null
    }

    /**
     * Seals a custody unit into [scope] (§4.3). Deterministic by construction, so every member seals the
     * same frame to the same blob id — that is what makes spool dedup and cross-uploader digest
     * convergence hold without coordination.
     */
    fun seal(
        scope: Scope,
        sig: ByteArray,
        signed: ByteArray,
    ): Sealed {
        val blob = ScopeCrypto.seal(scope.keys, scope.id, sig, signed)
        return Sealed(blobId = ScopeCrypto.blobId(blob), blob = blob)
    }

    /**
     * Runs §4.4's ordered validation over a pulled or evented blob: the content address, the AEAD (which
     * also enforces the seal version and the scope-bound aad), the envelope decode, and the frame-set
     * rule. Returns null for **any** failure — the caller quarantines the blob id in its invalid set
     * (§9.3) rather than retrying it, because the fault is always the uploader's and never the spool's.
     *
     * The returned [Opened.wire] is the custody re-serve shape: the same `signed`/`sig` bytes under a
     * fresh wrapper with a full hop budget (ttl default, hops 0), exactly what
     * [app.getknit.knit.mesh.ForwardSync.onDigest] stamps when it re-serves to a neighbor.
     */
    @Suppress("ReturnCount") // one guard per §4.4 step reads better than a nested pyramid
    fun open(
        scope: Scope,
        selfId: String,
        blobId: ByteArray,
        blob: ByteArray,
    ): Opened? {
        if (!ScopeCrypto.blobId(blob).contentEquals(blobId)) return null
        // ScopeCrypto.open throws IllegalArgumentException on a structurally invalid blob and the JDK
        // AEAD exception on a wrong key/scope/tamper; both mean "quarantine", so collapse them here.
        val unsealed = runCatching { ScopeCrypto.open(scope.keys, scope.id, blob) }.getOrNull() ?: return null
        val env = WireCodec.decodeEnvelope(unsealed.signed) ?: return null
        if (!eligibleForDm(env, selfId, scope.peerId)) return null
        return Opened(WireEnvelope(sig = unsealed.sig, signed = unsealed.signed), env)
    }

    /**
     * §9.2's outward guard: never push a frame whose frame-global expiry has already lapsed. The mesh's
     * custody store applies the same rule inward (`ForwardRepository.store` refuses a dead-on-arrival
     * frame), so an expired frame can neither enter local custody nor bounce between our uploads and the
     * spool's eviction. [ttlMs] is the scope's, not the mesh custody TTL — they are different numbers.
     */
    fun deadOnArrival(
        env: RelayEnvelope,
        ttlMs: Long,
        now: Long,
    ): Boolean = env.sentAt + ttlMs <= now
}

/** Lowercase hex — the spec's display form for scope ids, blob ids, and digests (§2). */
fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
