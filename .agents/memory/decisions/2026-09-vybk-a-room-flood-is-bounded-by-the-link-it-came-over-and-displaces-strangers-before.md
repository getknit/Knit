---
id: "2026-09.vybk"
slug: a-room-flood-is-bounded-by-the-link-it-came-over-and-displaces-strangers-before
title: "A room flood is bounded by the link it came over and displaces strangers before contacts"
date: 2026-09-13
topics: [mesh, retention, sybil]
---

# ADR 2026-09.vybk — A room flood is bounded by the link it came over and displaces strangers before contacts

Status: Accepted (2026-09-13)

**What was observed.** The Nearby room is the one thread anyone in radio range can write into, and every
bound on it was keyed on something the writer chooses. `MessageRepository.sweepRetention` capped the room at
2,000 rows newest-first, and custody (`ForwardRepository`) quotas 200 frames per sender and 200 broadcast
rows in total — but a sender is a node id, a node id is the hash of a key pair the sender minted, and so a
flood from one device wears a fresh identity per frame and slips every per-sender rule. The caps closed
storage *exhaustion* (the table cannot grow) and were being read as a flood defence; they were not one. A
2,000-post flood is ~600 KB, one NAN link moves that in under a second, and newest-first then evicted every
honest post in the room, a contact's and the user's own included (`RoomFloodLabTest`'s first scenario shows
the exact signature on the old rule: `Bob's posts survive the flood … but was:<null>`). And storage was the
cheap part: every inbound room post ran an Ed25519 verify, a custody insert, the ALBERT moderator
(`InboundPipeline`, the room branch of `classifyText`) and a relay to every other neighbour, so the flood was
a battery drain and a mesh-wide amplifier before it was a storage problem.

**What changed.** Two bounds that do not key on who the sender claims to be. (1) The room sweep reads who
wrote what: `sweepRetention` takes a `knownSenders` set — `MeshManager.sweepLocalStorage` passes `accepted +
verified + authored + me`, the same three DM signals `Conversations.isAccepted` applies to a group's
senders (a DM's conversation id *is* its peer's node id, so the set doubles as a sender set) — and a
stranger keeps at most `roomMaxPerStranger` (200, custody's per-identity quota applied to what is kept);
when the room is still over cap, strangers' oldest posts go first (`deleteOldestFromStrangersIn`), and the
old newest-first `deleteOldestInConversation` is the last resort for a room over cap on known senders alone.
(2) `mesh/IngressBudget`, a per-**link** token bucket over cleartext room posts in `MeshRouter.handleInbound`
— burst 240 (a link-up custody re-serve hands over at most the 200-row broadcast quota at once), then 30 a
minute. A link is one radio peer — one NDP, one L2CAP socket — and a device holds one at a time, so one
attacking device gets one link's budget however many identities it claims: the mesh's per-IP limit. It runs
ahead of verify, custody, moderation and relay, so a refused frame costs one map probe and the flood stops at
the first honest hop; `DropReason.INGRESS_REFUSED` counts them for Diagnostics. The alternative a reader
reaches for first is a per-sender rate limit — it is the same Sybil hole as the quotas. The other is to
meter in `InboundPipeline.onDeliver`, but by then the router has marked the frame seen and will relay it
after the handler returns, so nothing would have been contained.

**What it costs.** The meter is the one bound on custodied frames that is *not* identical on every node
(`rules/mesh.md`, the content-digest rule), and it is safe only because of where it sits: between the
dedup *check* and the dedup *add*. A refused frame is never marked seen, so the custody re-offer serves it
again through the same meter — a delay, never a veto — and the carried sets re-converge at the metered pace
once the flood stops; while it runs, the digests between a flooded node and its flooder churn, which is
the correct price. A duplicate is never metered (a re-serve is evidence of propagation, not a cost), and an
addressed or sealed frame is never metered (a DM receipt and a group seed converge through custody and must
not be throttled at the link). What it does not cover: a Sybil flood still displaces *other strangers'* room
posts, at ≤30/min per attacking link and never a contact's; honest traffic relayed through a flooded
neighbour shares that neighbour's bucket during the flood; and the two numbers are guesses at honest peaks —
a dense-crowd trial is what would say whether 30/min starves a busy room's relay share. The trap: a *room*
tick toward an author who is not a live neighbour deliberately never escalates into custody (ADR
2026-09.aa27), so a room scenario that ends in the full lab oracle needs a triangle, not a line. What keeps
this true: `RoomFloodLabTest` (the contact's posts survive; a line Mallory–Alice–Bob with Alice's budget at
five delivers five, refuses fifteen, and Alice and Bob still converge on messages, ticks and custody),
`MeshRouterTest`'s refused-then-admitted and duplicates-not-metered cases, `IngressBudgetTest`, and
`MessageRetentionTest`'s per-stranger and strangers-first cases against the real DAO SQL.
