package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.PRESENCE_LINGER_MS

/**
 * The DM peers the Internet plane is currently a path to. A scope existing proves nothing about its peer:
 * it is derived from the pairwise ratchet root, so it stays subscribed and converged while its peer sits
 * switched off in a drawer, and reading that as reach put two long-dead emulators under "reachable via
 * relay" the day Diagnostics shipped (ADR 2026-09.2ajk). Only [ScopeStatus.peerSeenAt] — that peer's own
 * recent traffic, within [lingerMs] of [now] — is evidence, and only on a connected spool. The label is the
 * DM peer's node id, so a group scope's `g-…` matches no peer; a retiring scope is a drained rotation and
 * carries nothing new either way.
 *
 * One function, two windows (ADR 2026-09.y5f3): the presence dot reads it at [PRESENCE_LINGER_MS], the
 * mesh — `AckSync`'s route choice and the LoRa plane's Internet cover — at
 * [app.getknit.knit.mesh.SPOOL_COVER_MS], so the two can never disagree on what counts as evidence, only
 * on how long it is trusted.
 */
fun spoolPresentPeers(
    spools: List<SpoolStatus>,
    now: Long,
    lingerMs: Long = PRESENCE_LINGER_MS,
): Set<String> =
    spools
        .filter { it.connected }
        .flatMap { it.scopes }
        .filter { !it.retiring && it.peerSeenAt != null && now - it.peerSeenAt <= lingerMs }
        .mapTo(mutableSetOf()) { it.label }
