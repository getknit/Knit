---
id: "046"
slug: arrival-time-is-our-own-clock
title: "Arrival time is our own clock, stamped once inbound, and never backfilled"
date: 2026-08-26
topics: [data, room, ui]
---

# ADR 046 — Arrival time is our own clock, stamped once inbound, and never backfilled

Status: Accepted (2026-08-26; DB v7 `messages.arrivedAt`, `KnitMigrations.MIGRATION_6_7`,
`InboundPipeline.deliverChat`, `MessageDetailsViewModel`/`Screen`)

"Message info" could show everything a message row records about its journey — who sent it, the sender's
`sentAt`, the tick, the plane — and could not answer **when**. `messages` had exactly three timing-ish
columns and none of them was a local observation: `sentAt` is the author's wall clock off the wire frame,
`received` is a boolean, `receivedVia` is a plane code. So the gap between "Sam says 19:29" and "it reached
this phone at 19:34" — which on a mesh *is* the store-and-forward latency, the most interesting number the
app could show — was invisible, and it was the only piece of a message's journey nothing recorded.

One nullable `messages.arrivedAt`, stamped in `deliverChat` — the single inbound `MessageEntity` builder all
four flavors (cleartext room, v1 HPKE, v2 ratchet DM, v2 group sender-key) route through. Four properties
that fall out of *where* rather than needing enforcement:

1. **Our clock, not theirs**, for the reason `MessageReceiptEntity.notedAt` gives one table over (ADR 036):
   mesh devices have no time sync, so a peer-clock value can render an arrival earlier than the send it
   belongs to. Stamped with the injected `clock()`, not the raw `System.currentTimeMillis()` the neighbouring
   `sentAt` clamp uses — the plumbing was already there and already overridden in tests.
2. **Stamped once.** The default persist is `messages.saveIfAbsent` (`OnConflictStrategy.IGNORE`), so a
   custody re-serve no-ops. This matters most on the **cleartext room path**, which deliberately skips the
   pre-decrypt exists-gate and re-enters `deliverChat` on every re-serve: first-write-wins rests entirely on
   that IGNORE, exactly as `receivedVia`'s arrival plane already does.
3. **Never on a row we authored.** `MessageRepository.save` is shared by inbound-v2, `MeshManager.sendChat`
   and `GroupRepository.recordDeparture`, so the stamp is set at row-build time in `deliverChat` and nowhere
   in the repository. It is additionally gated on `env.senderId != me`, because one of our own room posts can
   loop back after the SeenSet lapses and (if retention took the row) be written afresh — we did not receive
   that message, we sent it. Null therefore means exactly "we did not observe this arriving".
4. **Never backfilled.** Every row on disk at upgrade keeps null, forever, the same argument MIGRATION_5_6
   makes for un-acked receipts: we never made the observation, and inventing a plausible number is worse than
   saying nothing. The UI renders the absence, not a zero.

**No wire change** — both stamps are local observations. No CBOR field, no `type`, no ctl value, no
capability bit, nothing folded into a digest; a node that never learns the value simply shows nothing.
`GoldenVectorTest`, `WireSerializationTest`, `ScopeVectorTest` and `SpoolRecordsTest` pass **unmodified**,
which is the executable proof.

**Named `arrivedAt`, not the issue's `receivedAt`.** `messages.received` is the *outbound* delivery tick, so
a `receivedAt` beside it would read as "when `received` flipped" — the opposite direction. The concept
already had a name in the codebase: `forward_store.receivedAt` is local arrival for a custody frame.

**Skew is shown, not clamped.** `sentAt` is bounded only by `Protocol.MAX_FUTURE_SKEW_MS` (5 min into the
future), so "sent 19:29, arrived 19:24" is a thing a skewed sender can produce and the screen renders it.
A details screen is where an odd clock should be visible; clamping would display a number we did not store.

**The outbound half needed no column at all.** Work item 29 originally proposed one column reused per
direction, with a first-evidence-wins `CASE` in `markReceived` to keep it idempotent. ADR 036 overtook that:
`message_receipts.notedAt` is already our clock at apply time, already `insertIfAbsent`, and already written
for DMs (`InboundPipeline.ackerFor` attributes a DM ack to its addressed recipient) — the row existed and was
simply never displayed. So a DM's "Delivered …" line is a pure read, matched on the addressed recipient so an
orphan row can't supply it. **ADR 036 rule 3 stands unchanged**: a DM still shows no per-member split, because
its single ✓✓ already names the only recipient there is. Saying *when* is not saying *who*.

Deliberately not done: no arrival time on the bubble or chat list (`compactTimeAgo` stays their job — the
exact time is what the details screen is *for*); `sentAt` remains the retention comparator, so `arrivedAt` is
display-only and no sweep behaviour moves. Tests: `KnitDatabaseMigrationTest` (6→7, both directions null),
`MessageDaoTest` (a re-serve cannot restamp), `InboundPipelineTest` (our clock not the sender's; a re-served
room post keeps its first crossing; our own looped-back post is never stamped), `MeshManagerTest` (outbound
rows stay null), `MessageDetailsViewModelTest` / `MessageDetailsScreenContentTest` (both lines, and their
absence).
