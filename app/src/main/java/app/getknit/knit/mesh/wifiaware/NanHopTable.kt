package app.getknit.knit.mesh.wifiaware

import java.util.concurrent.ConcurrentHashMap

/**
 * Which neighbor a coordination-plane message came from, by the opaque per-session handle it arrived on.
 * Wi-Fi Aware names a message's sender only by a `PeerHandle` scoped to one discovery session; the node
 * behind a handle is learned from the cue or advert that rode it, and a later cue on the same handle
 * re-learns it (a handle is per-peer per-session, so the last writer is its current owner).
 *
 * The hop is deliberately the ONLY answer this table gives. A fast frame's `RelayEnvelope.senderId` is its
 * **author** — a relayed frame carries the originator's signed envelope byte-for-byte — and every node
 * re-fans each first-seen custody frame it holds (`InboundPipeline.onDeliver`), so crediting the author
 * called a peer miles away "directly connected over Wi-Fi Aware" for `REACHABLE_LINGER_MS` whenever a
 * neighbor re-served one of its stored profiles; a BLE-only phone with no Aware radio landed in the NAN
 * reachable set the same way (ADR 061). An unknown handle yields null, and no sighting at all is the right
 * answer then: the real hop is cueing us every heartbeat regardless.
 *
 * Pure and keyed by an opaque [K] so the rule is JVM-tested; the transport keys it by (session, handle).
 */
internal class NanHopTable<K : Any> {
    private val owner = ConcurrentHashMap<K, String>()

    /** A cue or advert on [key] named [nodeId]: that handle now means that neighbor. */
    fun learn(
        key: K,
        nodeId: String,
    ) {
        owner[key] = nodeId
    }

    /** The neighbor behind [key], or null when no cue or advert has named it yet. Never a frame's author. */
    fun hopFor(key: K): String? = owner[key]

    /** Forgets every handle [nodeId] owns — the reaper's counterpart to [learn] for a peer gone silent. */
    fun forget(nodeId: String) {
        owner.values.removeIf { it == nodeId }
    }

    /** Drops everything: the sessions the keys were scoped to are gone. */
    fun clear() {
        owner.clear()
    }
}
