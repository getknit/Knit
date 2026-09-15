---
id: "2026-09.7bu7"
slug: a-profile-is-custodied-wherever-it-is-delivered
title: "A profile is custodied wherever it is delivered, flooded or served"
date: 2026-09-15
topics: [mesh, custody, profile, convergence]
---

# ADR 2026-09.7bu7 — A profile is custodied wherever it is delivered, flooded or served

Status: Accepted (2026-09-15)

**What was observed.** The mesh-in-a-box lab ran the `keyreq` walk across three nodes on 2026-09-14
(`KeyExchangeLabTest.aFrameFromAStrangerIsParkedAndReplayedWhenTheKeyIsFetched`). Carol receives a frame
from Alice, whose key she does not hold, parks it and asks Bob. Bob serves Alice's profile; Carol pins it
and replays the frame — and then the three custody digests do not agree. Carol has delivered the profile
and stored nothing, while Alice and Bob both carry it. The next digest exchange does not repair it either:
Bob's re-serve is the same frame id, and Carol's router marked that id seen on delivery, so it is deduped
for the rest of the `SeenSet` window. Custody converged only when the window lapsed (10 min) and a later
re-offer landed — the 60 s loop or a link-up — so the digests disagreed, and the cue plane wanted a
reconcile, for up to eleven minutes after every key request. `TimeLabTest` pinned it closing at the lapse.

It looks like a `KeyExchange` bug and is not one. Nothing is lost, nothing is late, and the recovery itself
works exactly as designed. What it costs is a reconcile link that comes up with nothing to transfer, on
every key recovery, on both phones.

**The mechanism.** `KeyExchange.serve` replays the cached, verbatim-signed profile in a fresh
`WireEnvelope(relay = false, …)`: the response walks hop-by-hop like a `BlobExchange` reply, deliberately
not another flood. `InboundPipeline.onDeliver` custodied only `env.isStorable() && wire.relay`. Those two
are each right on their own and wrong together.

**What changed.** The custody gate now reads
`env.isStorable() && (wire.relay || env.type == FrameType.PROFILE)`. The rule behind it is not "point-to-
point frames are sometimes custodied" but: a point-to-point frame is *addressed*, so nobody else is meant
to hold it — a broadcast or group delivery tick, a sealed ctl DM. A `profile` names no recipient and no
group. It is ambient state every node is meant to hold, and it self-certifies on the `pubKey` in its own
payload, so a carrier can authenticate it without a prior pin (`verifierBundle`, and so `canCarry`) exactly
as it can a flooded copy. That makes the discriminator the type, not the wrapper.

**The alternative, and why not.** The obvious fix is to drop `relay = false` from `serve`, so the response
matches what `ForwardSync.onDigest` already re-serves from custody. It works, and it costs more than it
looks. The requester would then also *relay* the profile onward and `shouldFastFanout` it — and
`CompositeMeshTransport.fastFanout` reaches every child, the LoRa plane included, where ADR 057's 12 h
`profileSeen` gate is the only brake and a profile is ~4.75 s of a ~1 kbps shared medium. Every key
recovery would buy its convergence with airtime on a plane that refuses `keyreq` in the first place. It
would also contradict `KeyExchange`'s own contract — point-to-point recursion, never a second flood — for
a gap that lives entirely in the receiver's bookkeeping.

**What it costs and does not cover.** Nothing new goes on the air; only the requester's store changes. The
three fan-out gates below it (`shouldFastFanout`, `shouldFastSend`, `shouldLongRangeFanout`) stay
`wire.relay`-only and must: widening any of them is the change this one was chosen to avoid. It opens no
surface a flood did not — a neighbour able to hand us a signed profile point-to-point could already have
flooded the identical frame — and it does not weaken the content-digest rule, since the liveness bound
stays the frame-global `(sentAt, id)` every node applies and a served profile past its expiry is still
refused by the store's dead-on-arrival guard.

The trap for the next person: this is a rule about **one type**, not about `relay = false`. Every other
frame that arrives in that wrapper is addressed, and custodying those would undo decisions that were made
on purpose — a sealed tick has no custody row per acker (ADR 2026-09.aa27), and a `relay = false` ctl DM is
covered by `InboundPipelineTest`'s "being relay = false — nothing custodied" case, which is the negative
control here. `KeyExchangeLabTest` now asserts full custody parity with no clock jump, which is the eleven
minutes being gone.
