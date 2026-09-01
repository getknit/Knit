---
id: "019"
slug: the-internet-plane-is-a-scoped-custody-spool-protocol
title: "The internet plane is a scoped-custody spool protocol — M1 ships the public spec plus pure-crypto anchors, nothing else"
date: 2026-08-15
topics: [spool, crypto, protocol]
---

# ADR 019 — The internet plane is a scoped-custody spool protocol — M1 ships the public spec plus pure-crypto anchors, nothing else

Status: Accepted (2026-08-15; docs/SPOOL_PROTOCOL.md, `mesh/crypto/scope/`, `mesh/spool/`)

Names committed: **spool** (the store-and-forward relay daemon — "relay" is taken by
`RelayEnvelope`/`relayed()`/the mesh `relay` flag), **scope** (one conversation's internet
presence), **`ScopeSync`** (the future client plane — a custody-plane sibling of `ForwardSync`
under `MeshManager`, deliberately NOT a third `MeshTransport`: the seam is radio-shaped and a scope
has no neighbors), **`knit-spool`** (the reference daemon repo, **AGPL-3.0**; the app stays
GPL-3.0-or-later, no shared code). The spec is the product: `docs/SPOOL_PROTOCOL.md` is normative
and public from day one, `ScopeCrypto`/`SpoolPow`/`SpoolRecords` are its reference implementation
and the vector tests its executable anchors — the daemon (M2) and client (M3) implement the spec,
not each other.

M1 deliberately stops at pure functions — the FS docs' §8 "API-only, no consumer" posture one
layer up. **No mesh-wire fields land**: the scope-config ctl (`CTL_SCOPE_CONFIG = 7`,
`MessageContent.sc`) and the group root (`GroupKeyPayload.gr = {root, version, minter}`) are named
normatively in the spec but ship additively with their consumers (client plane / group scopes),
under WIRE_COMPAT's released-numbers-append-only, unreleased-still-editable rule. Two design-phase
intents were amended with rationale recorded in the spec: the **outer seal is scope-static**, not
epoch-rotating (per-epoch keys deadlock — the DM epoch identifiers needed to select the key live
inside the sealed blob and a fresh epoch's DH pub can't be enumerated; group epoch seals would be
unopenable exactly by the seed-lagging member whose custody/re-flood/key-request signal the frame
must keep feeding — `sealv = 2` reserves the epoch-keyed variant, and the `exportEpochSeal`
surfaces stay API-only for it), and the **scope config rides as a ctl inside sealed v2 chat**, not
as a new frame type (the ADR 016/018 custody argument: `isCustodial` is a fixed list on deployed
builds, and the config is exactly the frame that must survive store-and-forward). The seal is
**deterministic** (SIV-style nonce keyed off a scope secret) so any member seals a frame to the
identical blobId — spool dedup and cross-uploader digest convergence by construction, and the
keyed nonce denies spools a known-plaintext confirmation oracle. The group **shared root** deferred
by ADR 017/GROUP_FORWARD_SECRECY §8 is confirmed along the reserved mechanism: creator-minted,
deterministic re-mint on departure (creator if remaining, else smallest remaining nodeId),
highest-`(version, minter)` wins, gossiped on the existing `CTL_GROUP_KEY` channel.

**Amended 2026-08-16 (M2 shipped):** the `knit-spool` reference daemon + 22-check conformance
suite exist; implementing them surfaced eight spec ambiguities, resolved the same day as semantic
clarifications in SPOOL_PROTOCOL.md — §6.2 tombstone count bound (`max(2 × maxFrames, 1024)`,
§12 row) and forgotten-scope semantics (LIST/PULL answer empty; PUSH recreates through the §6.4
creation gates — the reachable use of `push.pow`), §6.4 recommended shed shape (whole scope,
tombstones included, plus an empty-digest re-anchor), §7.1 post-negotiation hello = `err
malformed` (4000 is pre-hello only), §7.2 unsolicited-digest SHOULD / pull-over-`maxPull`
truncation / duplicate-push acks without re-fan-out / `version` code reserved-never-emitted. No
wire field, vector, or derivation changed — the §13 anchors are untouched.

**Amended 2026-08-16 (M3 MVP — the client plane runs):** `ScopeSync` exists and syncs **DM scopes
only**, off by default, over OkHttp (`mesh/spool/`). Four shape decisions worth not relitigating:

1. **The MVP's spool list is a device setting, not the signed scope config.** `CTL_SCOPE_CONFIG`
   (ctl 7, `MessageContent.sc`) is still unshipped — carrying it is the one *wire* change this plane
   needs, and it wants its own WIRE_COMPAT precedent entry plus golden vectors rather than riding
   in with the first working socket. Until then bounds are the spec's §12 defaults held as
   constants in `ScopeRegistry`, and every scope syncs against every configured spool.
2. **The local blob-id set is derived, never stored.** Because the seal is deterministic, `blobId`
   is a pure function of (scope, frame), so the held-set is re-sealed on demand from
   `ForwardStore.liveFrames` behind an LRU. That is why this milestone needs no `forward_store`
   column and **no DB migration** — worth preserving, since a persisted blobId would have to be
   invalidated on every scope rotation.
3. **Session secrets stay behind `RatchetSessions`.** The plane consumes `exportedRoots()` —
   `pairwiseRoot` exports only, taken under the ratchet mutex, unconfirmed sessions skipped.
4. **Cleartext `ws://` is debug-only**, enforced twice (the debug manifest's `usesCleartextTraffic`
   and the dialer's own scheme check against `BuildConfig.DEBUG`), because `knit-spool` terminates
   no TLS of its own and the lab daemon is plain `ws://` on the LAN.

Deferred with reasons, not just deferred: the validated-Internet `ConnectivityManager` seam (the
MVP reconnects on backoff, which keeps `rules/mesh.md`'s NAN-only `ConnectivityManager` restriction
intact and avoids adding `ACCESS_NETWORK_STATE`); the spool-list editor, which is why the Settings
switch is `BuildConfig.DEBUG`-gated and spools are configured over `…debug.SPOOL`; Tor; group scopes.

**Amended 2026-08-16 (M4 — group scopes ship, and the v1 mint opens to any member):** the plane now
carries groups. Machinery: the shared root persists in a new `group_roots` table (**DB v2 → v3**,
`KnitMigrations.MIGRATION_2_3` — this reverses M3's "no DB migration" note, which held only because
the *blob-id set* is derived, and still does); `GroupRootPolicy`/`GroupRootStore` hold the pure rules
and the seam; `ScopeRegistry` gained a group seam and `Scope` a `groupId`/`roster`; and the plane's
**first mesh-wire field** lands additively as `GroupKeyPayload.gr` (its own `GroupRootPayload` type,
rule 1's `@ByteString` exception), riding the existing `CTL_GROUP_KEY` ctl DM rather than a new frame
type or ctl value — the ADR 016/018 custody argument a third time.

Four decisions worth not relitigating:

1. **Any member may mint version 1**, amending the spec's creator-only rule. The creator-only gap
   ("a group whose creator never opts in gets no scope") was booked as accepted and is now closed by
   damping rather than restricting: the **preferred minter** (creator if still a member, else the
   smallest remaining node id — the function the departure re-mint already used) mints immediately,
   anyone else after a 6 h grace measured from a **persisted** eligibility stamp. Competing v1
   lineages are not an error; `(version, minter)` collapses them and the loser's blobs age out.
2. **The same rule now covers the departure re-mint**, which fixes a latent stall the draft had: a
   deterministic re-minter that is offline or plane-off froze rotation for everyone. `recordDeparture`
   records the obligation (`remintDueAt`) inside the leave-rekey transaction; the heal pass mints. The
   split is what makes rotation crash-safe.
3. **Adoption is never rate-limited, and gains two mandatory bounds.** Refusing a strictly-greater
   root strands the device on a dead lineage permanently, so the bound lives on the send side (the
   per-(group, member) seed-send floor). The two adoption bounds close real insider DoS instead: the
   `minter` must be in the founding roster (else any member wins every tie forever with a
   lexicographically maximal fake id), and the version must stay inside the ceiling/jump bound (else
   one grief-mint at `2³¹ − 1` freezes the scope). The residual — an insider burning the version space
   before departing — is stated honestly in the spec rather than engineered away.
4. **Roots are adopted and gossiped even with the plane switched off**; only minting checks the
   switch. That is what carries a root across a plane-off member sitting between two plane-on ones, and
   it is why `GroupRootStore` is wired in `appModule`, outside `ScopeSync`'s nullable lifetime.

**Amended 2026-08-16 (the M4 device smoke found a deadlock — lock order is now enforced, not
documented):** the fleet smoke wedged a Pixel 8. Not a crash: an ANR on the debug bridge, with the Room
connection held by a *suspended* coroutine, so no thread dump showed an owner. Cause was a lock-order
inversion that predated M4 — `db.withTransaction { commitOpen(…) }` takes **transaction → mutex** while
`sealDm`/`sealGroup`/`currentSeeds`/`sweep`/`exportedRoots` took **mutex → connection** — which M4 made
reachable by calling `currentSeeds` from `gossipGroupRoot` on every inbound `CTL_GROUP_KEY` instead of
only from two rare floored paths. The class docs had stated the order as a rule for *callers*; that was
the bug's hiding place. It is now enforced inside both facades by the injected `SessionTransactor`
(`locked { }` = transaction outer, mutex inner, reentrant so the decrypt path just joins), pinned by
`SessionTransactorOrderTest`, and written up in `rules/mesh.md`. Lesson worth keeping: **a concurrency
invariant that depends on every caller remembering it is not an invariant.**

The one healing subtlety, added to the spec in the same pass: a root has **no ack**, so the send-side
dedup (`lastRootGossipVersion`) would suppress a re-send forever after a single lost gossip. The fix is
the anti-entropy direction — a distribution carrying a root *older* than ours (or none, while we hold
one) is answered with ours, floored like any seed send and self-terminating once they adopt. Without
it, "we already sent it" and "they have it" are silently conflated.

`knit-spool` needs **no change** — a spool is scope-blind, so a group scope is one more opaque id.
No derivation, no seal, and no §13 vector moved: `ScopeCrypto.groupScopeId`/`groupSealKeys` were
already written and vector-pinned at M1.

**Amended 2026-08-16 (M6 — the plane gets a face, and the switch ships):** `ui/relay/` adds the
Internet relays screen (route `relays`, reached from a Profile summary row) with the master switch, a
relay-list editor, per-relay health, and a one-time consent sheet. `ProfileScreen`'s
`BuildConfig.DEBUG` gate is **gone**: the editor was the stated hard prerequisite for un-gating, since
the app seeds a default spool that a release user must be able to remove. No wire, DB or protocol
change — only `SpoolStatus` gained `maxAttachBytes` and `SettingsStore` a `spool_consented` key.

Five UX decisions worth not relitigating:

1. **A relay refusal is not a failed send, and the UI must never imply it is.** The mesh carries the
   frame regardless, so every string is about *reach* ("nearby only", "not covered"), tinted
   `onSurfaceVariant` rather than `error`, and the ✓/✓✓ delivery tick is left completely untouched.
   Conflating the two would teach users to read a working send as a broken one.
2. **Only permanent causes are marked.** `rate`, `pow`, `tombstoned` and an unreachable spool all heal
   on the next heal round, so they stay invisible; the marker fires only on the two conditions that
   stay true until the user changes something — an attachment larger than every connected relay's
   `maxAttachBytes`, and relays that advertise no attachment support at all (§7.3's three-limits gate,
   read through `SpoolLimits.attachments`). A relay outage likewise yields `RelayReach.Silent`, not
   "not covered", so a transient blip does not paint a notice across every open thread.
3. **Markers sit at the altitude of the fact.** Scope coverage is a property of the *conversation*
   (the Nearby room is excluded structurally by §4.4; a DM without a confirmed ratchet session or a
   group without a root simply has no scope yet), so it renders once under the header — not stamped on
   every bubble. Only the attachment case is per-message.
4. **The frame-size case was found to be unreachable and was deliberately not built.**
   `TextLimits.MESSAGE = 2000` caps a body near 8 KB against a 64 KiB `maxBlob`, so `ScopeSync`'s
   `blob.size <= maxBlob` filter is a defensive guard no chat message trips. "Over the spool limit" in
   practice means attachments.
5. **The scheme rule has exactly one home.** `SpoolUrl.isAcceptable` is shared by the dialer and the
   editor, so a release build cannot store a `ws://` relay that would then silently never dial. Two
   copies would eventually disagree, and the one that drifts is the one that lets cleartext in.

Reach derivation is pure and unit-tested (`data/relay/RelayReach.kt`, `RelayReachTest`); the plumbing
that feeds it is `RelayStatusRepository`, a shared polled read of `MeshController.spoolStatus()`, since
`ScopeSync` exposes a snapshot rather than a stream and the value changes on connect/disconnect, never
per frame. One accident kept it cheap: `ScopeFrames.Scope.label` (`peerId ?: groupId`) is already
identical to `Conversations.idFor`, so scope→thread mapping needed no new key.

Scheme spec: docs/SPOOL_PROTOCOL.md; wire posture: docs/WIRE_COMPAT.md (its `GroupKeyPayload.gr`
precedent entry); context: context/e2e-encryption.md; deferred remainder: memory/roadmap.md.

**Amended 2026-08-16 (M5 — attachments ride the scope, in their own namespace):** the plane now carries
image bytes, closing the un-fetchable-image gap the spec's §11 had registered. New spec sections
§4.5/§6.5/§7.3/§9.5, added as fresh sub-numbers so **no existing cross-reference moved**; new client code
is `ScopeCrypto.attachmentId`/`sealChunk`/`openChunk`, `mesh/spool/ScopeAttachments`, five records in
`SpoolRecords`, and the attachment pass in `ScopeSync`. This is the first amendment that asks anything of
spool implementations, and `knit-spool` gained the matching half (both stores, the server, four
conformance checks, `SPOOL_MAX_ATTACH_BYTES`).

**M5 lands with no mesh-wire change at all** — no field, no ctl value, no capability bit, and no DB
migration. Everything a fetcher needs already rides in cleartext on the frame: `ChatContent.attachmentHash`
(the *ciphertext* hash) and `attachmentMime`, put there by the DB v19 precedent precisely so a blind
carrier could custody images. Size is not needed on the mesh because the spool reports the chunk count.
`GoldenVectorTest` is therefore untouched; only `ScopeVectorTest` and `SpoolRecordsTest` gained rows,
regenerated together and mirrored into spec §13 (and re-pinned independently by `knit-spool`'s
`SpecVectorTest`, which is a genuine cross-implementation check — two codebases, byte-identical records).

Five decisions worth not relitigating:

1. **A separate namespace, deliberately outside the frame digest.** Attachments are discovered by asking
   (`ahave`), never by anti-entropy. Folding them in would make a *byte* quota convergence-relevant, and
   two spools with different budgets would then never converge — the ADR 006 lesson, and the same reason
   `ForwardEntity.attachmentHash` stays out of `StoreDigest` and `CARRIER_BLOB_BUDGET_BYTES` is a purely
   local knob. It also means `maxAttachBytes` is the operator's alone and is **not** in the SUB-declared
   `ScopeBounds`: members must agree on what the digest folds, and on nothing else.
2. **`aid` is keyed, not the attachment hash itself.** `aHash` travels the mesh in the clear, so an
   unkeyed id would hand a spool that has any source of candidate hashes a confirmation oracle linking a
   frame to a scope. `HKDF(nonceKey, "…/aid" ‖ scopeId ‖ aHash)` closes it — §4.3's known-plaintext
   argument applied to the one object that actually travels in cleartext.
3. **Fixed 48 KiB chunks are structural, not tunable.** A constant chunk size is what makes a chunk's
   position derivable from the attachment alone, so there is no manifest object for two members to
   disagree about, and the sealed chunk (49221 B) still fits the 64 KiB `maxBlob`. The header
   (`aHash ‖ index ‖ total`) is sealed *inside*, so a member cannot replay a chunk elsewhere; the
   decisive check is still that the reassembled bytes hash to the address the frame named.
4. **Capability negotiation is a gate, not a hint.** Three HELLO limits, present together or absent
   together. The reason is mechanical: an unknown record is *skipped*, and a skipped request is never
   answered, so an optimistic `ahave` to a v1 spool strands that `q` until the 30 s timeout — once per
   attachment, per scope, per round. `FakeSpool(attachments = false)` models exactly that and the test
   asserts not one attachment record goes out.
5. **Partial downloads stay in memory.** M3's "derived, never stored" property is worth more than saving
   a re-fetch, and a partial-chunk table would be the first thing to break it. The cost is honest — a
   process death mid-transfer refetches that attachment — and the spool-side bitmap already makes the
   *upload* half resume for free (a test pins that only the missing chunk is re-sent). Persisting the
   download half is registered in §11.

One bound stated rather than engineered away: the want set derives from **custody**, whose 24 h TTL is
shorter than a scope's 48 h, so a frame that has aged out locally stops driving a fetch even while the
spool still holds the bytes. That matches the mesh carrier's own behaviour and keeps the seam small.

Scheme spec: docs/SPOOL_PROTOCOL.md §4.5/§6.5/§7.3/§9.5; wire posture: docs/WIRE_COMPAT.md (its
no-mesh-change precedent entry); deferred remainder: memory/roadmap.md.
