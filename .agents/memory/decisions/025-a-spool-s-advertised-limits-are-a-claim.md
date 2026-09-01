---
id: "025"
slug: a-spool-s-advertised-limits-are-a-claim
title: "A spool's advertised limits are a claim, not a bound — the client's own request is the bound"
date: 2026-08-19
topics: [spool, limits]
---

# ADR 025 — A spool's advertised limits are a claim, not a bound — the client's own request is the bound

`SpoolLimits` arrives in the spool's `hello` and nothing else vouches for it. Every inbound check written
against `limits.maxBlob` / `maxPull` / `maxAChunk` / `powBits` is therefore a check the attacker
parameterises: advertise `Int.MAX_VALUE` and the check is gone. Before this, the whole receive path had no
size or count check at all — `SpoolConnection.onBlob`/`onAchunk` appended every scope-matching record into
an unbounded list, so a spool that accepted a `pull` and simply withheld the terminal `ok` grew our heap
for the full 30 s request timeout (GitLab #21).

Decision, in three layers, each with a source of truth the spool cannot move:

1. **The request is the bound.** `Pending` carries what the request named — the `pull`'s id set, the
   `aget`'s index window — and spends one slot per named id on arrival. That single mechanism is the
   unsolicited-record check, the duplicate filter and the length cap at once, and it makes the worst case
   `|ids we chose| × |bound we declared|` instead of unbounded × unbounded.
2. **Sizes come from what we declared at SUB**, so `SpoolConnection` keeps `ScopeBounds` per scope rather
   than a bare id set. What we will not push, we will not accept. Structural constants
   (`ScopeCrypto.SEALED_CHUNK_BYTES`) win where the spec pins a size outright.
3. **Advertised limits are clamped at the HELLO boundary**, once, so every present and future reader of
   `conn.limits` inherits the narrowing instead of having to remember it. `powBits` too: it is the
   cheapest attack in the file — one integer buys full-budget mining per scope on every heal round.

**The trap when hardening this again.** A record we reject is not automatically a blob to quarantine.
§9.3's invalid set exists to stop a re-pull loop and is a *bounded* 512-entry per-scope set that evicts
oldest-first, so letting an untrusted party write into it on demand is the same bug in a different shape.
The split: an id we **requested** and got an unusable answer for is quarantined (it is in the spool's
digest and never in ours — merely dropping it is the permanent divergence `rules/mesh.md` forbids); an id
we **never requested** is dropped silently, because it was never pulled and §9.3 does not reach it.

Still open, deliberately: `accept` claims an `accepted` slot before validation and never releases it, and
an unsolicited `event` that fails validation still quarantines. Narrowing that means amending
`rules/mesh.md`'s absolute "never merely dropped" wording, which is a separate call.
