---
id: "2026-09.2v2t"
slug: a-phone-s-mesh-contribution-is-counted-at-the-hand-off
title: "A phone's mesh contribution is counted at the hand-off, once per message, and stays on the phone"
date: 2026-09-13
topics: [ui, custody, privacy]
---

# ADR 2026-09.2v2t — A phone's mesh contribution is counted at the hand-off, once per message, and stays on the phone

Status: Accepted (2026-09-13; route `yourMesh` + `ui/yourmesh/`, `mesh/ContributionLedger`,
`data/settings/ContributionJournal`, `data/peer/MetPeer*` at DB v14, `ForwardDao.observeCarriedForOthers`, the
`MeshRouter.onRelayed` and `ForwardSync.onServed` hooks)

**What was observed.** The only thing Knit ever told a user about keeping it running was a foreground
notification with a neighbour count, and the only place the mesh's work was visible was Diagnostics, a page of
counter names. Nothing said, in words a non-technical user would read, that their phone had carried a stranger's
message across a campsite last night. And nothing *could* have: `MeshMetrics` is a bag of process-lifetime
`AtomicLong`s that reset on every launch, `forward_store` had no query for "held for someone else", the `peers`
table cannot say "met" (it is written for multi-hop profiles too, and its cap evicts oldest-profile-first), and
custody records no delivery at all — a carried frame is deleted by a cleartext receipt or ages out, and nothing
distinguishes "handed on" from "expired unread". So the Your mesh screen (tap the status line under the chat
list's title, or the overflow item) needed four numbers built from nothing, and every one of them is shown to
the user as something their phone *did*, which is a claim to be careful with.

**What changed, and what the alternatives were.** A *credit* happens at a **hand-off** and nowhere else: the
moment this phone sent someone else's chat frame to at least one peer. That is two places — the router's flood
fan-out (`MeshRouter.onRelayed`, reported only when the target set after split horizon is non-empty) and a
custody re-serve to a peer whose digest showed it lacked the frame (`ForwardSync.onServed`) — and both report a
fact (frame, targets) to `ContributionLedger`, which decides whether it counts. The rules: chat-type frames only
(a profile, a cleartext reaction, a group update is housekeeping, not "a message"; a sealed receipt or reaction
rides as chat and a carrier cannot tell it apart, ADR 018, so it counts and the strings say so); other people's
frames only, never one we authored and never a DM addressed to us (custody holds both, and neither is help for
anyone); to someone other than the author (re-serving an author its own frame is a wipe reconverging, not a
delivery); and **once per frame**, under a 24 h `SeenSet` memo — the custody re-offer runs every 60 s for as long
as a frame lives, and a peer that can never store a frame (it blocks the sender) is offered it every round, so
without the memo one such frame credits ~1,440 hand-offs a day. "Passed along" is the first hand-off to anyone;
"handed straight to" is the first hand-off to the DM's addressee, a subset by construction. It is a send over a
live link, not a confirmed delivery, so the copy never says "delivered". `fastSend` and LoRa fan-outs are
deliberately uncounted: they return `Unit` and may have sent nothing. The alternative a reader reaches for first
is **crediting at custody entry** (`ForwardSync.onSeen`) — rejected because storing is not helping; the frame may
expire unread. The second is **counting `framesRelayed`** — rejected because it fires with an empty neighbour
set, counts every frame type, and resets on launch. "Nearby" is `MeshController.neighbors` exactly as the header
reads it (ADR 2026-09.2ajk), and "met" is that set's lifetime union in `met_peers`, its own collector off the
same `nearbyPeers` (never `transport.neighbors`, which would miss a BLE-sighted, never-linked phone). "Carrying
now" is a live count over `forward_store` (`origin = 0`, `type = 'chat'`, not ours, not to us, unexpired). The
two lifetime counters are two additive DataStore longs behind a `ContributionJournal` slice (the review-count
precedent, not ADR 2026-09.7svb's blob: two monotonic longs in one `edit {}` are already atomic, and an additive
write needs no read-before-write, so a flush can never clobber or race). They are flushed on the 60 s metrics
tick and on `stop()`, never per frame, because every DataStore write re-emits every settings collector in the
app; the ledger's `totals` adds the unflushed delta so the screen moves at once. `met_peers` is Room, not
DataStore, because it is a list of node ids and the database is the encrypted store. Both stores are excluded
from backup already (`res/xml/backup_rules.xml`), and nothing here is ever framed, so the README's "no analytics,
no telemetry" and ADR 028's "automatic egress is the one thing this app is built not to do" hold unchanged.

**What it costs and the traps.** "Carrying now" is what the phone holds, not what is in transit: a sealed
receipt never purges custody (ADR 018), so a DM between two other phones sits in every carrier for its full
24 h whether or not it arrived, and the count on a lab phone reads ~50 while nothing is undelivered (verified
on the P9, 2026-09-13: 51 = 34 DM-form frames between the other lab phones, 15 frames they addressed to
themselves, 2 group frames; every frame of the user's own chats is excluded). The card's hint says exactly
that — copies from the last day, kept for a phone that missed one — rather than "waiting for someone out of
range". DB v14, an additive table with a `lastMetAt` index (the eviction key: the cap of
10,000 sheds least-recently-met, so a burst of unauthenticated ids can never push out a regular contact). It was
minted as 14, never folded into 13: v11–13 sit on the lab phones, and editing 13's identity hash is an open-time
crash. The memo is in-memory, so a re-serve straight after a restart may credit a frame twice; accepted over
persisting frame ids. The `nearbyPeers` collector conflates, so a peer that leaves and returns inside one
conflated window is not re-touched — `lastMetAt` is an eviction key, not a "last seen" surface. `MeshRouter`'s
`onRelayed` parameter sits *before* `onDeliver`, because the tests bind `onDeliver` as a trailing lambda. What
keeps this true: `ContributionLedgerTest` (every rule above), the `onRelayed`/`onServed` cases in
`MeshRouterTest`/`ForwardSyncTest`, `ForwardDaoTest`'s carried-for-others case, `MetPeerRepositoryTest`,
`KnitDatabaseMigrationTest`'s 13→14, `MeshManagerTest`'s met-newcomer case, and `mesh/lab`'s
`ContributionLabTest` — Bob is credited for the DM he carried to Carol, Alice and Carol are credited nothing, and
the numbers survive a restart. Deferred: streaks or per-week views (needs daily buckets), a "carried N today"
notification nudge, crediting `fastSend`/LoRa once they report sends, "met this week" off `lastMetAt`, and
aligning the header's "Connected to N mesh nodes" with the screen's "people nearby".
