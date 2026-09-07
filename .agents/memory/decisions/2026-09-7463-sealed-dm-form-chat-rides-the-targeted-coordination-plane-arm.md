---
id: "2026-09.7463"
slug: sealed-dm-form-chat-rides-the-targeted-coordination-plane-arm
title: "Sealed DM-form chat rides the targeted coordination-plane arm"
date: 2026-09-07
topics: [mesh, nan, fanout]
---

# ADR 2026-09.7463 — Sealed DM-form chat rides the targeted coordination-plane arm

While the NAN wedge of ADR 2026-09.9dnk held, P8's **broadcast room messages kept arriving** — a 376-char one
among them — while every DM, every group-key seed and every `CTL_GROUP_KEY_REQ` sat undelivered in its own
custody. P8 had P7 in `cue=[…]` the entire 45 minutes. The frames had a working radio and no permission to
use it, because `FrameFanout.shouldFastFanout` admits `CHAT` only when `recipientId == null && group == null`,
on the stated grounds that DM-form "won't fit the ~255 B channel".

That reasoning was already contradicted twice in the tree. `AckSync` sends **sealed receipts** over
`MeshTransport.fastSend`, and a sealed receipt is wire-indistinguishable from a real DM (ADR 016/018) — same
type, same recipient, same sealed payload; `fastSend`'s own contract says the transport "compacts and
fragments ≤ 3 messages toward capable peers, which is what lets a sealed receipt ride". And LoRa's
`shouldLongRangeFanout` already admits exactly this form, for exactly this reason: on a plane with no data
path, fan-out is the only path a frame can take (ADR 039). The NAN coordination plane is the other
no-data-path plane, and it was the one that did not get DM-form. The real budget is
`coordMsgMax` × `MAX_PARTS` − headers = **753 B**, against a ~300 B DM floor.

`shouldFastSend` now admits that set — the same predicate body as `shouldLongRangeFanout`, kept as a separate
named function because the two answer different questions about different planes and will drift the first
time one plane's budget moves. It is wired at both sites the LoRa arm already uses: `originateSigned`, and
`InboundPipeline`'s inbound re-fan so a bridge node forwards at message-plane speed instead of waiting on its
own NDP. **Targeted, never fanned** — one send to the addressee, so a sealed frame is not sprayed at
neighbours with no business holding it and the channel carries one copy rather than N. The alternative a
reader reaches for first — widening `shouldFastFanout` — is wrong twice over: it would fan a point-to-point
frame at every neighbour, and `rules/mesh.md` forbids widening it for the LoRa plane for the same reason.

The device evidence is one line: `fast-send legacy=-1B compact=288B parts=2 → 24sd5jd4…`, received as
`fast-frame … via=transcoded hop=hzwyqdbj…`. **`legacy=-1B` is the whole point** — the legacy framing cannot
represent the frame at all, so before the compact/transcoded work this really would not have fitted; the
exclusion outlived its own justification. Custody was not bypassed (all three nodes on one `liveFingerprint`,
301 rows), and the feared channel pressure did not appear (P9 `nanMsgSendsFailed` 0, P8 25 against 468 acked).

What this costs is the thing worth writing down: **it hides a dead data path.** This morning's wedge was
findable precisely because DMs stopped; with this change the same wedge presents as "works, slightly worse"
while custody quietly stops converging. So it does not ship alone — `MeshMetrics.nanOwedNoLinkPeakMs` lands
with it, publishing the `syncOwedSince` episode clock that `checkWedge` already computes and that previously
had no consumer outside that one function. Zero on a healthy mesh; minutes means only the best-effort planes
are carrying anything. Do not remove that gauge as an unused metric — it is the replacement for the symptom
this ADR deletes. `FrameFanoutTest` holds the invariants (the two coordination arms disjoint over a generated
envelope matrix; the targeted arm equal to the long-range set; every admitted frame carries a recipient), and
`MeshManagerTest` pins the origination call site to exactly one targeted send and no fan-out.

Not covered: group-form chat still rides neither arm (sender-key sealed and past the budget), and the spool
plane is untouched — a pair scope derives from the pairwise ratchet root, so a third node still cannot bridge
a foreign pair's frame and `ScopeFrames.eligibleFor` still refuses it. That boundary is deliberate. Also
unchanged, because it already worked: multi-hop relay of sealed pairwise frames through an uninvolved node —
`InboundPipeline.canCarry` has always accepted them on the originator's signature alone, verified on the rig
as P9 → P7 over BLE → P7 → P8 over NAN in 3 s. This ADR makes that path *fast*, not possible.
