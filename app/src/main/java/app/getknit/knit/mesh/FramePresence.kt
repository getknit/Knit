package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope

/**
 * When a frame arriving over a **store-and-forward** plane is evidence that its *author* is currently
 * there, as opposed to evidence that somebody's storage still holds something it wrote long ago.
 *
 * Both planes that need this key presence on the frame's `senderId`, and on both of them a frame is
 * routinely handed over by somebody other than its author:
 *  - **LoRa** (`mesh/lora/`) — the ADR 044 bridge backfill re-serves history by design, the ADR 039
 *    re-offer replays old DMs, and `InboundPipeline.onDeliver` re-fans anything first-seen.
 *  - **The Internet plane** (`mesh/spool/`) — a spool holds blobs for 48 h and a client pulls whatever
 *    it lacks whenever it next connects, so a scope yields old frames as a matter of course.
 *
 * Without an age rule a phone that has been switched off for days reads as a live neighbour: its last
 * frames are still in somebody's custody, still get re-fanned, and still name it as their author.
 * `onDeliver` even re-fans over LoRa a frame the Internet plane pulled off a spool moments earlier, which
 * is how a node nobody had seen in days arrived on the air of a mesh it was never part of (ADR
 * 2026-09.2ajk).
 *
 * **Two windows, because the two stamps mean different things:**
 *  - a `profile`'s `sentAt` is a **publish** stamp its author refreshes on a cadence
 *    (`MeshManager.PROFILE_REPUBLISH_MS`, 12 h) while the profile *version* stays put, so a live node's
 *    beacon is always inside [PRESENCE_PROFILE_MS] and a node that stopped republishing simply ages out.
 *    It has to stay evidence at all: a peer whose board comes up beacons its profile *first*, and that
 *    first hearing is what fires the ADR 039 re-offer of the DMs waiting for it.
 *  - everything else is live traffic when it is live at all, so it gets [PRESENCE_FRESH_MS] — the same
 *    window LoRa's fan-out applies before transmitting, which is what makes a backfilled or re-offered
 *    frame fail here while the live copy of the same frame passes.
 *
 * Deliberately **not** `LoraFramePolicy.isFresh`, which shares one of those numbers: that predicate
 * short-circuits **true** for every non-chat type, which is right for "may this ride" and exactly wrong
 * for "does this prove anyone is there". Answering different questions is why they stay separate
 * (`LoraFramePolicyTest.presenceIsNotFreshness`).
 *
 * Presence only — never a delivery gate. A frame that fails this is still decoded, delivered, custodied
 * and relayed exactly as before; a caller that lets it decide anything else turns its plane into a
 * propagation black hole for the very backfill the bridge exists to serve.
 */
internal fun isPresenceEvidence(
    env: RelayEnvelope,
    now: Long,
): Boolean = now - env.sentAt <= if (env.type == FrameType.PROFILE) PRESENCE_PROFILE_MS else PRESENCE_FRESH_MS

/** How recent live traffic must be to say its author is here: LoRa's fan-out freshness window. */
internal const val PRESENCE_FRESH_MS = 15 * 60_000L

/**
 * How old a `profile`'s publish stamp may be and still say its author is here: the 12 h republish cadence
 * plus an hour of slack, so a beacon from a node that is merely idle never falls off while a node that
 * has stopped republishing does.
 */
internal const val PRESENCE_PROFILE_MS = 13 * 60 * 60_000L

/**
 * How long presence lingers after the evidence for it. There are no periodic cues on either plane, so a
 * peer that is present but quiet must not blink out between messages. `LoraMeshTransport`'s own
 * `REACHABLE_LINGER_MS` is the same number for the same reason and stays separate: that one is a routing
 * linger the plane sweeps against, this one is how long a Diagnostics reader is told a path exists.
 */
internal const val PRESENCE_LINGER_MS = 45 * 60_000L
