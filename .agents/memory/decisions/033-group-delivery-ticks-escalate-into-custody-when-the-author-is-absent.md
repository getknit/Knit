---
id: "033"
slug: group-delivery-ticks-escalate-into-custody-when-the-author-is-absent
title: "Group delivery ticks escalate into custody when the author is absent, batched as one sealed ctl frame"
date: 2026-08-22
topics: [custody, groups, receipts]
---

# ADR 033 — Group delivery ticks escalate into custody when the author is absent, batched as one sealed ctl frame

Status: Accepted (2026-08-22)

The sealed group tick was the last delivery fact with no delay tolerance beyond process memory: the
*message* converges via custody, but its `CTL_RECEIPT` tick was `relay = false` and sent by AckSync
straight over `transport.send`/`fastSend` — never through `originateSigned`, so never flooded
(`MeshRouter` refuses `relay = false`), never custodied anywhere (the `isStorable() && wire.relay` gate;
not even the acker held a copy), and never visible to the Internet plane (`ScopeSync` seals from
`ForwardStore.liveFrames` only). An author offline past AckSync's in-memory window simply never saw ✓✓.

The fix is an **escalation**, not a replacement, keyed at the *sender* (a local emission choice, the
CAP-gate precedent — never a carry/convergence rule): a **live-linked** author keeps today's cheap
unicast `relay = false` tick (zero custody load for the co-present case); an **absent, sealed-capable**
author's acks batch per author in AckSync and, after a 45 s debounce (a `flushScope` coroutine wake;
`retryPending()` on the heal heartbeat is the backstop), escalate as **one** sealed ctl frame carrying
every pending id — the additive `MessageContent.acks` list (the sixth additive content change,
`docs/WIRE_COMPAT.md`) — originated `relay = true`: flooded, custodied, and spool-eligible with **zero
frame-set-rule change** (a DM-shaped v2 ctl between the pair already satisfies `ScopeFrames.eligibleForDm`
and SPOOL_PROTOCOL §4.4 C-4.4-6; §6.2's "delivery facts do not exist at this layer" is untouched — the
tick is just another opaque scope frame). One chain key per batch, however many messages it acks.
Escalated ids land in a done-but-remembered ledger so the exists-gate's re-ack on every custody re-serve
no-ops instead of re-sealing; an author who links *during* the debounce gets the batch over the live link
(`relay = false`, no custody rows); a failed seal at flush (author unpinned meanwhile) re-materializes the
ids as the legacy per-id cleartext entries. The receiver applies `ack` and every `acks` id under the same
per-id forged-ack guard, `distinct` and bounded at 2× the send cap; `markReceived` stays idempotent and
first-evidence-wins, so re-serves and duplicates are absorbed.

Why custody is legal for this frame: the custody rule stays keyed on frame bytes identical at every node
(`type = chat`, `relay = true` — ADR 006 holds untouched), and ADR 018's vaccine table is unchanged — a
sealed receipt still purges nowhere, so the escalated tick ages out on the frame-global 24 h TTL exactly
like the group message it acks (which was never purge-eligible anyway). The quota math is why batching is
mandatory rather than nice: per-message custodied ticks would cost up to roster × messages frames against
each ticker's 200-per-sender bucket (100 messages × 6 members ≈ 500 frames mesh-wide); one batch per
(member, author) costs ~5, precisely in the offline-author scenario the feature exists for.

Deliberately NOT done: **cleartext ticks never escalate** (a cleartext receipt in custody would re-leak
the delivery event ADR 018 sealed away — the legacy population keeps the unicast retry loop);
**broadcast-room ticks never escalate** (the ambient, shorter-lived class; `owe(escalatable = false)`);
**no group-form tick** (a sender-key-sealed tick would be all-or-nothing on member eligibility and would
broadcast delivery facts to the whole roster — the DM form degrades per-author and every v2 group message
implies an author↔member session by construction, via its seed ctl DM); **no per-member ack matrix** (the
tick's "≥1 member received it" semantic is unchanged; the null arm of the forged-ack guard IS the group
tick); **batches never ride the coordination plane** (a 16-ack batch already outgrows the ≤2-fragment
compact budget — pinned by `CoordinationPlaneSizeBudgetTest.batchedSealedReceiptNeverRidesTheFastPlane` —
so escalation goes through origination, structurally never `fastSend`). Accepted residuals: a process
restart forgets the in-memory ledgers, so a custody re-serve can re-seal a fresh tick while the old frame
still rides custody (the DM-receipt precedent — one duplicate, absorbed idempotently); a ratchet-era lab
build without the `acks` field consumes a batched tick as the pinned chain-advancing no-op (legal only
because the whole sealed-ctl era is on the unreleased v2 train). Diagnostics: `receiptsCustodied` beside
`receiptsSealed`/`receiptsSealedFallback` in Diagnostics and `…debug.STATE`. Tests: `AckSyncTest` (the
escalation suite + the reframed seal-once pin), `InboundPipelineTest` (batched apply under the per-id
guard; end-to-end group delivery → batch → originate), `MessageContentTest`/`GoldenVectorTest` (both
receipt forms pinned; the byte-identical-defaults proof extended). Scheme doc:
`docs/ENCRYPTED_RECEIPTS_REACTIONS.md`; wire precedent: `docs/WIRE_COMPAT.md`; context:
`context/store-and-forward.md`, `context/e2e-encryption.md`.
