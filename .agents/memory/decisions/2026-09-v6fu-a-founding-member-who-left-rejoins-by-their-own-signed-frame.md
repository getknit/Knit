---
id: "2026-09.v6fu"
slug: a-founding-member-who-left-rejoins-by-their-own-signed-frame
title: "A founding member who left rejoins by their own signed frame"
date: 2026-09-11
topics: [groups, roster, crypto]
---

# ADR 2026-09.v6fu — A founding member who left rejoins by their own signed frame

Status: Accepted (2026-09-11; `InboundPipeline.rejoinBy`/`vetRoster`, `GroupRepository.recordRejoin`,
`PendingGroupKeys`, `MessageEntity.KIND_MEMBER_REJOINED`, `InboundPipelineTest` rejoin cases,
`GroupRepositoryTest`)

Leave a group, then "create a group" with the same people and send: on every other phone the message never
arrives and the sender never gets a tick. The lab repro (Pixel 9 → Pixel 7, 2026-09-11) read on the Pixel 7
as a `leave:` notice for Walter, then `GROUP_RATCHET_DUPLICATE` for the new frame. Not a lost seed — the
Pixel 7 had refused it on purpose.

Two rules met. A group's id is the hash of its member set (`Conversations.groupIdFor`), deliberately, so
the same people always resolve to the same thread and nobody gets duplicates; and the roster is pinned —
the founding set only ever comes from a roster that derives to the id, and membership only shrinks, by the
member's own signed `groupleave` (`vetRoster`, the roster-integrity phase of the group ratchet). Put
together: re-creating a group you left resolves to the *same* group, in which every other member holds you
as departed, and there was no wire-level way back. `ContactsViewModel.createGroup`'s KDoc said "re-creating
rejoins it"; that was true only on the creator's own phone. Their new seed hit `adoptGroupSeeds`, failed
`senderId in members`, and — the DM ratchet having already consumed the ctl frame — was gone; their chat
frame then tried their old chain and read as a duplicate.

## The mirror of the leave

A departed founding member is re-added by a signed frame of their own that lists them as a member
(`rejoinBy`, decided inside `reconcileGroup`'s transaction, threaded into `vetRoster`). It is the exact
mirror of the rule that removed them: only your signature can take you out, only your signature can put you
back. Nobody can re-add anyone else — Bob's frame still listing Alice (he never saw her leave) does nothing,
and that is pinned by test — and the founding set, the id's preimage, is untouched, so the derivation
invariant that makes id forgery impossible is intact. No new frame type: the first chat frame, or a
`groupupdate`, carries the roster already. An old build relays those verbatim and keeps the member departed,
the same degradation it has for everything additive.

Two guards make the flip safe against custody, which re-serves old frames for 24 h:

- **A pre-leave frame must not rejoin.** In it the sender was, of course, a member. So the rejoin requires
  the frame's `sentAt` to beat the recorded leave — the `leave:<group>:<member>` notice's `sentAt`, written
  atomically with the tombstone by `recordDeparture`. A missing notice reads as "left at 0": lenient, never a
  lock-out.
- **A re-served old leave must not evict them again.** `recordDeparture` now refuses a leave older than the
  `rejoin:` notice. Leave and rejoin are last-writer-wins on the member's own clock, and the two deterministic
  notice rows *are* the clocks — a schema column would have been a v13 migration for two longs.

## Rekey, floored

A rejoin rekeys exactly as a departure does (`recordRejoin`: our send chains die in the same transaction,
so the next send mints an epoch distributed to the roster as it now is). Without it the returning member's
`flushPendingGroupKeysFor` would hand them the *current* epoch's seed, which decrypts that epoch from its
start — everything sealed while they were out that custody still holds. The doc's leave-rekey line is
"you read nothing sealed after you left", and a rejoin should not quietly reopen it.

The rekey is floored per (group, member) at one an hour (`REJOIN_REKEY_FLOOR_MS`); membership itself is
never floored. This is the amplifier §6.1 warns about, in a new coat: leave/rejoin is two frames for the
attacker and a mint plus a seed fan-out for every other member, unbounded without the floor. Floored, a
flip inside the hour restores membership and skips the re-mint — the only thing the member can then read
early is the gap they themselves just created.

## The seed still gets there first

The rejoiner's seed floods before the frame that rejoins them — the seed-before-roster race of the day
before (`PendingGroupKeys`), in a second shape. The hold now also parks a `CTL_GROUP_KEY` whose sender we
hold as departed (`holding group key … sender departed`), and the replay `reconcileGroup` runs after every
accept releases it once the tombstone lifts. Same pre-commit park, same skipped-key replay.

## What it does not do

It does not add anyone. A group is still its founding roster; a rejoin is a founding member coming back,
and the founding set is what the id hashes. It does not resurrect a group *we* left — `groupFrameRefused`
still drops every frame for a `left` row. And it does not change the wire: no field, no type, no version.

The trap for the next reader: `rejoinRekeyDue` treats "never" as open, not as time 0 — the rig's clock is
42 ms, and the `?: 0L` idiom the seed floor uses would have read every first rejoin as inside the window.
