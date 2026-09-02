---
id: "2026-09.2ajk"
slug: lora-reach-is-relay-reach
title: "LoRa reach is relay reach, and a custody re-serve is not presence"
date: 2026-09-01
topics: [lora, mesh, ui]
---

# ADR 2026-09.2ajk — LoRa reach is relay reach, and a custody re-serve is not presence

Status: Accepted (2026-09-01)

**What was observed.** On a four-node mesh with two Meshtastic boards, the Diagnostics screen got every
row wrong at once. Pixel 9 (passive gateway) listed *four* nodes as **directly connected**: Alex (Pixel
7, BLE+NAN+LoRa, the active gateway) tagged `BLE·NAN` with no LoRa; a Pixel 8 with **no board** tagged
`BLE·NAN·LoRa`; a Moto G with no board and no BLE path to this phone tagged `LoRa`; and a fourth phone
that had been **switched off for days** tagged `LoRa`. The plane's own dump says it plainly —
`heard: 2` against `boardsHeard: 1`: one radio in earshot, two people called reachable. The one peer
that had a board was the only one never tagged with it, because ADR 054's `coveredByLink` skip means
its frames go over the BLE link instead of the air.

Two separate errors, stacked.

**`reachable` on this plane is keyed on the frame author, and that is not a proximity claim.**
`LoraMeshTransport` deliberately keys `lastHeardAt` on `RelayEnvelope.senderId` — the right key for "who
can I reach through this mesh", and `shortRange = false` already tells siblings to ignore its sightings.
But `MeshController.neighbors` was `transport.reachable`, the union across *all* children, and that
union is what the whole app calls *nearby*: the foreground notification's count, the chat-list status
row, the Contacts online dot, Profile Details, the group member picker — and Diagnostics' "directly
connected" split. So a gateway relaying somebody's frames made that somebody a nearby neighbour
everywhere.

`neighbors` is now the **short-range** set (`CompositeMeshTransport.shortRangeReachable`, which already
existed for the attachment-deferral rule), which is what its own KDoc always claimed it was; the full
union moved to a new `MeshController.reachable` that only Diagnostics reads. No call site changed —
every consumer of `neighbors` simply became correct. The obvious alternative, tagging rows from
`peerTransports` and leaving the sets alone, fixes the screen and leaves the notification lying;
`shortRangeKinds` is exposed off `MeshTransport.shortRange` rather than restated so the UI's idea of a
proximity plane cannot drift from the transports' own.

**A custody re-serve was being read as presence.** `shouldFastFanout` admits `profile`, `onDeliver`
re-fans anything first-seen, and `LoraFramePolicy.isFresh` exempts `profile` from the age rule — so a
days-old profile that the **Internet plane** had just pulled off a spool went straight onto LoRa air,
and every listener marked its author reachable for the full 45-minute linger. The ADR 044 bridge
backfill and the ADR 039 re-offer do the same thing on purpose for chat. That is the switched-off phone.

`mesh/FramePresence.kt`'s `isPresenceEvidence` now gates **only** the `noteReachable` call:
`PRESENCE_FRESH_MS` (15 min) for everything except `profile`, which gets `PRESENCE_PROFILE_MS` (13 h —
the 12 h `MeshManager.PROFILE_REPUBLISH_MS` cadence plus slack). It cannot be `LoraFramePolicy.isFresh`
itself: that predicate short-circuits **true** for every non-chat type, which is correct for "may this
ride" and exactly wrong for "does this prove anyone is there". Nor can `profile` simply be dropped as
evidence — a peer whose board comes up beacons its profile *first*, and that first hearing is what fires
the ADR 039 re-offer of DMs waiting for it. The rule sits in `mesh/` rather than `mesh/lora/` because the
Internet plane turned out to need exactly the same one.

**The Internet plane made the same claim, and the first fix repeated it.** "Reachable via relay"
originally counted any live scope on a connected spool. That is wrong for the identical reason: a scope
is derived from the **pairwise ratchet root**, so it is subscribed, connected and *converged* whether or
not its peer has opened the app in a month — it proves a path to the spool, not to the peer. Shipping
that put two emulators nobody had powered on in days straight back under the relay heading on the first
install. `ScopeStatus.peerSeenAt` is the repair: `ScopeSync` stamps it when it bridges a blob whose author
is the scope's own `peerId` *and* which passes the same `isPresenceEvidence` — because a spool holds
blobs for 48 h and a client pulls whatever it lacks whenever it next connects, so a scope yields old
frames as a matter of course and one backlog pull would otherwise resurrect its author. The stamp is our
clock at acceptance, not the frame's `sentAt`, so one linger (`PRESENCE_LINGER_MS`, 45 min, LoRa's number
for LoRa's reason) reads it correctly. It survives a reconnect: losing the socket is our event, not
theirs. `…debug.SPOOL` reports it as `peerSeenAgoMs` (`-1` for never), the only field in that dump that
describes the peer rather than the spool.

**Reachability in the UI now needs evidence.** "Reachable via relay" used to be
`peers − reachable`: the entire peer table, i.e. everyone whose profile ever arrived. It is now a
current path — heard over a long-range plane, or a spool scope whose peer has pushed to it within the
linger (`ScopeStatus.label` is the DM peer's node id; a `g-…` group label matches no peer and drops out;
a `retiring` scope is a drained rotation and carries nothing new). The remainder moved to a third *Known,
not reachable* section, newest profile first and capped at five, because listing the whole peer table is
what made the old section meaningless. A row now shows only the planes its section can honestly claim —
short-range radios on a direct row, LoRa/Relay on a relay row — since `BLE·NAN·LoRa` read as "this peer
has a board" when it only ever meant "somebody's board carried its frames".

**What it costs.** Presence over a store-and-forward long-range plane stays fuzzy: a phone switched off
for six hours still looks reachable until its profile stamp ages past 13 h, and the 45-minute linger
already claims more than it knows. Tightening further would need the plane to learn which **radio**
transmitted a frame — `boardsHeardAt` knows the node numbers but nothing binds one to a Knit node id
(`LoraCtl`'s OFFER carries `hash64(nodeId)`, but only gateways offer). A dedicated presence packet would
buy that and was rejected: it spends airtime on a duty-cycled band, which is the thing ADR 044 and ADR
054 exist to stop. The LoRa transport row also still counts authors rather than boards; it now says
"N heard" instead of "N nearby · 0 linked", and the real board count remains on the LoRa settings screen.

**The trap the next person will hit.** Presence is never a delivery gate. On both planes the check wraps
*only* the presence bookkeeping — `noteReachable` on LoRa, `notePeerPresence` on the spool — and decode,
dedup, delivery, custody and the onward relay all still run for a frame that fails it. Move the check
any earlier and the plane becomes a propagation black hole for exactly the backfill it exists to serve.
The other trap is the one this ADR fell into twice: **a store-and-forward plane's convergence state is
never a statement about a peer.** `converged`, `connected`, a subscribed scope and a 45-minute linger are
all equally true of somebody who has been switched off for a week.

Regressions: `FramePresenceTest.presenceIsNotFreshness` (the two predicates share a number and must not
share a rule), `liveTrafficProvesItsAuthorIsThereAndAReServeDoesNot`,
`aProfileGetsTheRepublishWindowNotTheFreshOne`;
`LoraMeshTransportTest.aReofferedDmCrossesButDoesNotPutItsAuthorOnTheAir` and
`aProfileCountsUntilItsAuthorStopsRepublishingIt`;
`ScopeSyncTest.a scope reports its peer seen only once that peer has pushed something recent` and
`a backlog pull does not make its author present`;
`CompositeMeshTransportTest.shortRangeKindsNamesTheProximityPlanes`; and the three
`DiagnosticsViewModelTest` reach-classification cases.
