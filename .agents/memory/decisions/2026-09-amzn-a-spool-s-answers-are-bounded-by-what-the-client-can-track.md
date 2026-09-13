---
id: "2026-09.amzn"
slug: a-spool-s-answers-are-bounded-by-what-the-client-can-track
title: "A spool's answers are bounded by what the client can track"
date: 2026-09-13
topics: [spool, hardening]
---

# ADR 2026-09.amzn — A spool's answers are bounded by what the client can track

Status: Accepted (2026-09-13) — GitLab #27, the follow-ups ADR 025 left open. Spec §9.1 C-9.1-3…5, §9.3
C-9.3-3/4, §9.5 C-9.5-11.

**What was observed.** ADR 025 bounded what a spool can make us *buffer* — one slot per id the request
named — and stopped there. Five paths still let a spool set the terms. `accept` claimed a slot in the
512-entry `accepted` guard *before* validating, and on failure claimed a second in `invalid`, so garbage
`event`s evicted genuine entries from both (an event flood of 512 undid the swept-frame guard, and the
scope's whole history was re-pulled and re-delivered every round). `invalid` and `invalidAttachments` were
never cleared for the life of the worker — one corrupt chunk denied an attachment at that spool for good,
from an honest spool as much as a hostile one. A `list` was bounded by the 128 KiB record cap and nothing
else: ~4000 ids, each hexed, held and pulled in serial round trips inside one `heal`, and — the larger
harm — more than the 512 the invalid set can track, so the quarantine churned every round. `ahas` and the
`list` reply were correlated on `q` alone while `blob`/`achunk` had been rewritten to check scope and id.
And three advertised numbers were never acted on: `maxScopes` (every scope SUBbed regardless, the refused
ones re-SUBbed every tick), `sub()`'s Boolean (a `maxRecord` too small for a SUB read as "connected"
forever, subscribing to nothing), and the 30 s request timeout that a silent spool could spend serially
per request per scope.

**What changed.** Three principles, one per shape of leak. *A claim is not an entry*: `accept` still
claims before validation (the pump and the worker race, and claiming after would re-open double delivery)
but the claim now evicts nothing, is released on every non-delivery outcome and trimmed only after a
delivery — so garbage costs the guard nothing — and quarantine happens on the pull path only
(`Source.PULL`), because C-9.3-1 is written for a *pulled* blob and an id nobody pulled cannot start the
re-pull loop the set exists to stop; `rules/mesh.md`'s "never merely dropped" now says "a *pulled* blob".
*An entry has a horizon*: `invalid` is pruned to the listing (the C-9.6-3 precedent — unlisted, it cannot
be re-pulled), and `invalidAttachments` carries a timestamp and expires at the scope TTL, since presence
is asked for, never listed, and by then the poisoned copy has left an honest spool (S-6.5-4). *A listing
is refused whole, never truncated*, past `accountBound` ids — the worker's own tracking bound, the same
number that bounds the accounted and (now) the invalid set, so "a listing never exceeds what the sets can
hold" is true by construction — or past `max(2 × maxFrames, 1024)` tombstones. Then the smaller ones:
`ahas`/`list` replies are checked against the question's scope (and aid) and a mismatch is *dropped*, not
failed, so it lands on the silence counter like the non-answer it is; the SUB batch is capped at
`maxScopes` with the commons first; a `quota`/`pow` refusal parks the scope for `retryMs` coerced into
[5 min, 1 h] — `coerceIn`, never `max`, or one absurd hint parks a scope forever; a SUB the record layer
refused to send aborts the session as `too_large`; three consecutive unanswered requests abort it as
`unresponsive`. Both aborts set `SpoolConnection.fault`, which `session()` folds into its return so the
backoff *grows* — a session we had to end is not a reached one — and carries into `lastError` past the
spool's echoed `close 1000`.

The alternatives. A second bounded set for unsolicited ids keeps the absolute wording but adds code and a
metric that counts two things; the spec already said "pulled". Pruning `invalidAttachments` to the frames
that still reference it is a *shorter* horizon (custody is 24 h) that buys nothing, since an unreferenced
entry is never consulted. Failing a mismatched reply fast would let a hostile spool answer every question
wrongly at no cost and never strike out. Chunking a SUB under a tiny `maxRecord` — a spool that cannot
carry a 6 KB SUB cannot carry a 64 KiB blob. And `onMessage`'s double decode was measured, not fixed:
kotlinx-cbor 1.11.0's `skipElement` skips a byte string with `input.skip(length)`, so `peekType` is a
header walk, not a second pass over `data`. `hex()` did move to a nibble table (`HexFormat` is API 34).

**What it costs, and the trap.** A dead socket now ends the session at once (`pump.invokeOnCompletion`
wakes the loop) rather than at the next tick, which is what made the fault visible in tests and is a
small unrelated improvement. A refused listing costs one `list` per tick and a `lastError` of
`overlong_list` for as long as it lasts. `lastError` is no longer cleared on every fresh handshake: a
carried fault, or a parked scope, keeps it — otherwise a silent spool reads as connected for the minutes
it takes to strike out again. Not covered: a spool that answers the hello and every SUB and then nothing
else never issues a request, so it never strikes out; it sits connected and idle, harmlessly. The trap is
the number: `accountBound` is the listing threshold *and* both set bounds on purpose. Raise `maxFrames`
and `BLOB_SET_MAX` moves with it, or a full scope's listing is refused. And any future client-side close
must go through `SpoolConnection.abort`, or `session()` returns "reached" and the failure loops at one
second. Kept true by `ScopeSyncTest` (the garbage-event trio, the invalid-set horizon pair, the listing
trio, the park/quota/maxRecord/silent quartet) and `SpoolConnectionTest` (mismatched `ahas`/`list`, the
strike counter and its reset, the commons pin clamp) over `FakeSpool`'s new hostile knobs: `announce`,
`listingPadding`/`tombstonePadding`, `maxScopes`/`scopeQuota`/`quotaRetryMs`, `maxRecord`, `mute()`.
