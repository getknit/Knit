package app.getknit.knit.ui

import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.PRESENCE_LINGER_MS
import app.getknit.knit.mesh.spool.SpoolStatus

/**
 * How we can currently get a frame to a node — each backed by evidence, which is the whole point of the
 * three tiers. Ordered strongest first; a node is classified by the best evidence it has.
 *
 * One rule for every surface that draws a presence dot with a label beside it — Diagnostics' three sections
 * and the Profile status line — so a peer Diagnostics lists under "Reachable via relay" can never read as
 * "Offline" on their profile. Both derive from [reachOf] over the same three inputs.
 */
enum class Reach {
    /** A short-range radio (BLE/NAN) has sighted this peer's **own** radio. The only honest "connected". */
    Direct,

    /**
     * Something carried this peer's own recent traffic to us within the linger: a LoRa board put its frames
     * on air (which may have been a gateway relaying for a peer with no board of its own), or it pushed
     * into a scope we share on a connected spool. A path, not proximity, and not a route — neither plane
     * knows how far away it is.
     */
    Relay,

    /** Known — we hold a profile — but nothing currently reaches it. */
    Known,
}

/**
 * The DM peers the Internet plane is currently a path to. A scope existing proves nothing about its peer:
 * it is derived from the pairwise ratchet root, so it stays subscribed and converged while its peer sits
 * switched off in a drawer, and reading that as reach put two long-dead emulators under "reachable via
 * relay" the day Diagnostics shipped (ADR 2026-09.2ajk). Only `ScopeStatus.peerSeenAt` — that peer's own
 * recent traffic, within [PRESENCE_LINGER_MS] of [now] — is evidence, and only on a connected spool. The
 * label is the DM peer's node id, so a group scope's `g-…` matches no peer; a retiring scope is a drained
 * rotation and carries nothing new either way.
 */
fun spoolPresentPeers(
    spools: List<SpoolStatus>,
    now: Long,
): Set<String> =
    spools
        .filter { it.connected }
        .flatMap { it.scopes }
        .filter { !it.retiring && it.peerSeenAt != null && now - it.peerSeenAt <= PRESENCE_LINGER_MS }
        .mapTo(mutableSetOf()) { it.label }

/**
 * Classifies one node by the best evidence it has: [nearby] is [MeshController.neighbors] (the short-range
 * planes, the only ones that sight the peer's own radio), [reachable] is [MeshController.reachable] (every
 * plane, long-range included) and [spoolPresent] is [spoolPresentPeers]. The mesh is a pure flood network
 * with no routing table, so no tier claims a *route* — only that something reached us from that node, or
 * could carry a frame back.
 */
fun reachOf(
    nodeId: String,
    nearby: Set<String>,
    reachable: Set<String>,
    spoolPresent: Set<String>,
): Reach =
    when (nodeId) {
        in nearby -> Reach.Direct
        in reachable, in spoolPresent -> Reach.Relay
        else -> Reach.Known
    }
