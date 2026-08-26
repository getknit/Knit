package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * The custody window a LoRa gateway gossips about and serves from (ADR 044). The long-range plane has no
 * `neighbors`, so `ForwardSync`'s digest exchange never runs over it; this is the bounded stand-in, and it
 * lives behind a seam for the same reason [FarPeerFrameSource] does — `mesh/lora/` is pure, and the store is
 * Room.
 *
 * Implemented by [MeshManager] over `ForwardStore.liveFrames`, which is already exactly the right set: it is
 * TTL-bounded and quota-trimmed by the ADR 006 convergence rules, so nothing here needs its own age gate and
 * an expired frame can never be served. Nothing is stored, nothing is re-encoded, and no custody rule
 * changes — a served frame is the verbatim `sig`/`signed` re-wrapped like any custody re-serve.
 *
 * Late-bound (the transport is constructed before [MeshManager]); resolved at call time.
 */
interface BridgeFrameSource {
    /**
     * Prefixes of the newest [limit] live frames this node holds that could ride the hop, newest first, for
     * the body of a [app.getknit.knit.mesh.lora.LoraCtl] OFFER. Newest-first matters: the encoder truncates
     * to what one packet holds, so the oldest are the ones that fall off.
     */
    suspend fun offerPrefixes(limit: Int): IntArray

    /**
     * The frames we hold that [theirPrefixes] does not name — what a far gateway is missing — priority
     * ordered (the key bootstrap first, then sealed DMs, then room traffic newest-first) and capped at
     * [limit]. [dms] carries the user's "private messages over LoRa" switch; with it off, DM-form frames are
     * never offered to the air, exactly as on the live fan-out path.
     */
    suspend fun framesMissing(
        theirPrefixes: IntArray,
        limit: Int,
        dms: Boolean,
    ): List<WireEnvelope>
}
