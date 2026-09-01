---
id: "032"
slug: the-scope-table-is-gated-on-a-confirmed-session
title: "The scope table is gated on a confirmed session, never on the Message Requests rule"
date: 2026-08-22
topics: [spool, privacy, message-requests]
---

# ADR 032 — The scope table is gated on a confirmed session, never on the Message Requests rule

Status: Accepted (2026-08-22)

`ScopeRegistry.dmScopes` filtered DM peers through `Conversations.isAccepted` (via
`MeshManager.isAcceptedConversation`) from the day the client plane landed (`5f185c4`, spool-m3 phase
1/3). Removed: the scope table now follows `RatchetSessions.exportedRoots`, which already admits only
**confirmed** sessions, and nothing else.

The gate contradicted ADR 009, which states the acceptance rule is "a **local presentation decision
only** — never folded into custody/relay, so it is *not* convergence-critical". The Internet plane is a
custody-plane sibling of `ForwardSync`, so gating scope derivation on it is exactly the fold ADR 009
rules out.

The failure it caused is worse than the doc violation, because acceptance is *asymmetric*: it is largely
"I have authored in this thread". A pair where one side has only ever **received** therefore derives a
scope on the sender's device and none on the receiver's — so the two never share a scope id, and the
plane carries nothing **in either direction**, including the sealed receipts that draw the ✓✓. Both ends
report `connected: true`, `lastError: null`, `invalid: 0` and a scope that merely never converges; the
sender's thread reads `RelayReach.Covered`, which renders no ornament, so nothing anywhere says the pair
is not on a shared scope. Observed 2026-08-22 between two lab Pixels whose sessions were byte-identical
mirrors (same `rootHash`, same `establishedAt`, confirmed both ways) while their *group* scopes matched
and synced normally — groups were never gated. The DMs only landed when the devices came back into radio
range, which is what disguised it as a radio problem.

A confirmed session is the right bound and a stronger one than acceptance: it costs a completed X3DH,
which no stranger reaches unsolicited, and it is already the export gate. The spam argument the old gate
implied does not survive contact with the asymmetry — a rule that silently disables the relay for
ordinary one-way threads is not a spam control.

**Not fixed here:** `reachFor` still answers `Covered` from the local scope table alone, so it reports
one side's ability to push, not that the pair share a scope. Honest per-thread coverage needs either
new copy for the outbound-only case or a peer-participation signal inferred in `ScopeSync.accept`
(a spool-sourced blob whose `senderId` is the scope's peer). The extension register's `CAP_SPOOL` bit
does **not** answer it: it says the peer's build speaks the plane, not that the peer derived this scope.
