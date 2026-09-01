---
id: "061"
slug: a-coordination-plane-frame-is-a-sighting-of-the-hop-that-delivered-it
title: "A coordination-plane frame is a sighting of the hop that delivered it, never of its author"
date: 2026-08-29
topics: [mesh, wire, presence]
---

# ADR 061 — A coordination-plane frame is a sighting of the hop that delivered it, never of its author

A phone several miles away, with no LoRa board and no Internet path of its own into the lab, appeared in the
Diagnostics "Directly connected" list tagged **Wi-Fi Aware** for exactly 150 s, three times over a re-serve
burst. The same burst put the API-30 Moto — a phone with **no Aware radio** — into a Pixel's NAN reachable set.
Neither was a radio sighting; both were bookkeeping.

**The mechanism.** `WifiAwareTransport.emitFastWire` credited every fast frame to `RelayEnvelope.senderId`:
`noteReachable(Peer(envelope.senderId))`, and `InboundFrame(fromNodeId = envelope.senderId)`. But `senderId`
lives inside the signed bytes, which a relay forwards verbatim (rules/mesh.md), so it is the frame's
**author**, not the neighbor that put it on the air. And `InboundPipeline.onDeliver` re-fans every first-seen
custodial flood frame over the coordination plane — including a custody re-serve of a days-old profile —
so a foreground `heal()` on one phone (digest re-advertise → the neighbors push what they think it lacks →
each first-seen frame re-fanned over NAN, echoed back by the other Pixel) lit up the authors of every
re-served profile as "reachable over NAN": six historical versions of one absent peer's profile in five
seconds. The NAN→BLE early-warning then boosted a scan to chase the phantom (`bt scan → boost
chase=[…]`), and by code reading the same sighting satisfies the bulk-arm `BULK_FRESH_MS` gate, so an image
send could arm a ghost NDP bring-up. The Internet plane was not the vector: it is not a transport child
and can never produce a NAN tag; any Alex-signed frame in anyone's custody reproduces this.

**The decision.** A `NanHopTable` keyed by the (session, `PeerHandle`) a message arrived on — the only thing
Wi-Fi Aware tells us about a sender — mapping to the node the last cue or advert on that handle named
(`onCueReceived`, `onDiscovered`; a later cue re-learns, the reaper `forget`s an absent peer, the two session
resets `clear`). `dispatchFramed` threads the key through the legacy / compact / fragment paths, and
`emitFastWire` takes the resolved `hop`: the sighting is the hop, and `fromNodeId` is the hop — which is also
what `InboundFrame` always promised ("tagged with the neighbor it arrived from"), so the router's split
horizon now excludes the node that sent us the frame instead of its author, and a neighbor no longer gets
its own fan-out echoed straight back. An **unnamed handle is no sighting at all**, not a fallback to the
author: a real hop is cueing us every `CUE_HEARTBEAT_MS` regardless, so nothing is lost, while the author
fallback was the whole bug. `fromNodeId` alone keeps the author fallback in that case — the split horizon
then excludes a node that is at worst not a neighbor, which is harmless — so nothing about delivery changes.
The `fast-frame` logcat line now carries `hop=`, so a phantom is one grep away (`hop=?` is the unattributed
case). Pinned by `NanHopTableTest`; the transport itself stays Android-bound and untested, as before.

**Deliberately unchanged.** `LoraMeshTransport` also stamps `fromNodeId = env.senderId` and counts the
author as heard — correctly: a LoRa "sighting" is defined as "this author's frames reach this board", it is
`shortRange = false`, and the composite already keeps it out of the foreign-reachable union and
`shortRangeReachable`. The cleartext `profile` re-fan itself (ADR 057 bounded it on LoRa only) is a
separate inefficiency this does not touch.
