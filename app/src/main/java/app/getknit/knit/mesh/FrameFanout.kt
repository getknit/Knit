package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope

// Package-internal frame-propagation knobs shared by MeshManager's outbound origination (originateSigned)
// and InboundPipeline's inbound relay (onDeliver/onCarriedFrame). Lifted to top level so a single
// definition is reachable from both classes without either depending on the other's companion.

/**
 * Whether [env] should *also* ride the [MeshTransport.fastFanout] coordination-plane fast path — a
 * best-effort fan-out to every neighbor at once with **no data path** — on top of the normal flood and
 * store-and-forward custody. It's for small, flood-to-everyone frames: the plaintext broadcast room plus the
 * cleartext metadata frames (reactions, delivery receipts, group roster updates/leaves, profiles) — exactly
 * the non-chat [FrameType.isCustodial] types. In an idle cue-driven mesh no NDP is up, so a plain flood
 * reaches the ≤1 live neighbor (usually zero); the fast path delivers to every neighbor at once and custody
 * backstops any peer that was away. Reused on both origination (`originateSigned`) and relay (`onDeliver`),
 * so a frame hops the mesh at message-plane speed rather than only one hop from the originator. E2E DM/group
 * *chat* frames are excluded (the broadcast-only arm): they carry wrapped keys and won't fit the ~255 B
 * channel, so they ride the NDP flood + custody. The transport still size-gates (no-op if a frame won't
 * fit), and the receiver's SeenSet dedups any copy that also arrives over the flood/custody backstop.
 */
internal fun shouldFastFanout(env: RelayEnvelope): Boolean =
    when (env.type) {
        FrameType.CHAT -> env.recipientId == null && env.group == null

        // broadcast room only (DM/group are E2E)
        else -> FrameType.isCustodial(env.type) // reaction/receipt/group-*/profile; blobreq/keyreq excluded
    }

/**
 * Whether [env] should *also* ride [MeshTransport.longRangeFanout] — the fan-out reserved for a plane with
 * **no data path at all** (the LoRa bridge, ADR 039), for which it is the only path a frame can take. It
 * admits exactly the **DM-form** chat frames [shouldFastFanout] excludes: `chat` with a recipient and no
 * group. That form is deliberately opaque — a real DM, its sealed receipt or reaction, a session reset, a
 * group-key seed and an escalated delivery tick are wire-indistinguishable (ADR 016/018), so all of them
 * ride and none is singled out. Group-form chat (`group != null`) stays out: the long-range plane carries no
 * group conversation, so its frames would only burn airtime. Disjoint from [shouldFastFanout] by construction.
 */
internal fun shouldLongRangeFanout(env: RelayEnvelope): Boolean = env.type == FrameType.CHAT && env.recipientId != null && env.group == null

/**
 * Pull-time soft cap on bytes held *purely* to custody other peers' images (a carried frame references
 * them but no local message does — see [app.getknit.knit.data.blob.BlobDao.carrierOnlyBlobBytes]). Our
 * own/received images are uncapped (kept via their message row); this bounds only the altruistic relay
 * footprint. Because these blobs are NOT folded into the content digest, this is a purely local knob and
 * need not match across nodes, so it can later be made adaptive to free storage without any convergence
 * risk. Read inbound by `InboundPipeline.onCarriedFrame` and outbound by `MeshManager.resumePendingFetches`.
 */
internal const val CARRIER_BLOB_BUDGET_BYTES = 128L * 1024 * 1024
