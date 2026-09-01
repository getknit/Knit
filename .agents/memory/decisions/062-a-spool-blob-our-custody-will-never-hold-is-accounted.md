---
id: "062"
slug: a-spool-blob-our-custody-will-never-hold-is-accounted
title: "A spool blob our custody will never hold is accounted, not re-pulled"
date: 2026-08-30
topics: [spool, custody, accounting]
---

# ADR 062 — A spool blob our custody will never hold is accounted, not re-pulled

A lab Pixel 9 that nobody had touched for hours logged **748 `RATCHET_DUPLICATE` drops** and climbing —
576 fifteen minutes earlier. `MeshMetrics` is a process-local `AtomicLong`, so that was one uninterrupted
run, not an artefact of reinstalling the app in the lab. Nor was it ADR 053's sealed tick: `receiptsResent`
was **0**. It was the Internet plane, with `spoolPulled = spoolBridged = 814` against `dropped = 830`
(`748 DUPLICATE + 81 EPOCH_GONE + 1 AEAD_FAIL`) — very nearly every pull was a re-delivery of a DM this
device decrypted days earlier.

**The evidence, because the shape is what names the cause.** The drops arrived in bursts — 423, 420, 199,
197 — not a drip, and the last two bursts were *the same 197 frame ids*, 100 % overlap, 16 minutes apart.
None of the 453 distinct ids were in either device's custody store, and the two stores were byte-identical
and converged (`liveFingerprint` equal, 345 live rows, 0 expired), which rules the mesh custody plane out
entirely. `…debug.SPOOL` had it: `local = 105, spool = 400, converged = false` on both DM scopes, pegged at
`maxFrames`.

**The mechanism.** `SPOOL_PROTOCOL.md` §12.2 sets the scope TTL at 48 h against the mesh's 24 h custody TTL
*deliberately* — "longer retention stores frames the inner ratchet may no longer decrypt". So for the back
half of a spool's retention there is a band of blobs the client pulled, delivered, and then aged out of
custody. `local` is derived from custody, so those ids can never re-enter it: the digests can never agree,
`heal` LISTs on every tick, and every pull re-bridges a frame whose chain index the ratchet consumed long
ago. §9.3's invalid set does not catch them — they are *valid*, passing hash, AEAD, signature and the
frame-set rule; they die at the custody store's dead-on-arrival guard, which is downstream of the bridge and
reports nothing back. The heal round already skipped them within one connection (`accepted`), which is why
this was invisible on the bench; `session()` cleared that set on every disconnect, so Doze, a Wi-Fi flip or a
spool restart re-pulled the whole band. Four reconnects in 2.6 h of logs, ~200–400 re-deliveries each.

**The fix is an inward mirror of C-9.2-1.** A new §9.6 **accounted set** per (spool, scope): a blob that
bridged and that custody did not keep is folded into our local digest *as if held*, counted in `localCount`,
and never pulled again. The scope converges, the per-tick LIST stops, and the re-delivery stops with it.
Three properties are load-bearing:

- **Ask the store, do not re-derive the rule.** `!store.has(env.id)` after `deliver` returns is exact by
  construction and covers quota eviction and any future refusal for free. A second copy of the custody TTL
  classification inside the spool client would be a second convergence-critical constant to keep in step —
  precisely the class of bug `rules/mesh.md` warns about.
- **Never accounted *and* held.** The digest is an XOR fold, so an id in both sets would cancel its own
  contribution and diverge us permanently. `heal` filters the accounted band by `!in local` before folding.
- **Prune to the listing.** An accounted id the spool has since expired leaves our fold carrying something
  the spool no longer counts — the same divergence mirrored — so a LIST that no longer names it drops it.

The set survives a reconnect and dies with the process. That is a deliberate stopping point, written into
the spec as C-9.6-4 (a durable set is permitted, not required): the plane persists nothing by design, and a
process restart costs one band, once, bounded by `maxFrames`. What it must not cost is one band per
reconnect. `accepted` still clears on reconnect for the reason it always did — that is what lets a custody
wipe re-converge by the ordinary route, and it now only re-pulls what is still live.

**Not done, deliberately.** Pre-filtering the pull set by age is impossible and would be wrong anyway: a
blob id is an opaque hash, so age is unknowable before the pull, and a 30 h-old frame we have genuinely
never seen still *delivers* (only custody refuses it). The band must be pulled once; it is the second pull
that is the bug. Shortening the scope TTL to match custody was rejected for the reason §12.2 gives — the
48 h is the rotation drain window, and halving it would strand a peer that was offline for a day.

`spoolAccounted` is the corroborating counter (climbing in step with `spoolPulled` round after round is the
regression), and the debug bridge reports `accounted` per scope beside `local`/`spool`. Three JVM tests in
`ScopeSyncTest` pin the reconnect case, convergence on the band, and the prune; `FakeSpool` grew
`dropSockets()` and `expire()` for them.
