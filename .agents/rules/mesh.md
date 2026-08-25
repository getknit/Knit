# Mesh invariants

Never-break rules for anything under `mesh/`, `protocol/`, or `data/` custody. The operational detail
behind each lives in `context/mesh-transport.md`, `context/wire-format.md`, and
`context/store-and-forward.md`.

## Keep each radio behind `MeshTransport`

- Nothing outside `mesh/wifiaware/` may import `android.net.wifi.aware.*` (or
  `ConnectivityManager`/`NetworkRequest` for the NAN data path).
- Nothing outside `mesh/bluetooth/` may import `android.bluetooth.*` (the Meshtastic GATT client lives at
  `mesh/bluetooth/meshtastic/MeshtasticGatt` under that boundary; its pure session/codec sit in `mesh/lora/`,
  which imports no Android at all — ADR 038).
- Nothing outside `mesh/spool/OkHttpSpoolDialer.kt` may import `okhttp3.*`. The Internet plane's socket
  sits behind the `SpoolLink`/`SpoolSocket` seam for the same reason the radios sit behind
  `MeshTransport`: everything protocol-shaped above it (`SpoolConnection`, `ScopeSync`) stays pure and
  runs against an in-process fake spool in unit tests.
- Everything above the transport talks only to the `MeshTransport` interface; `CompositeMeshTransport`
  runs every radio at once behind that seam (Bluetooth preferred, Wi-Fi Aware second, LoRa last), so
  orchestration (`MeshManager`/`MeshRouter`) is unchanged and another sibling transport drops in the same
  way — the LoRa plane (ADR 038) is a fast-plane-only child, `neighbors` always empty, that carries only the
  broadcast subset. `MeshTransport.shortRange` (LoRa = false) tells the composite a sighting doesn't imply
  proximity, so it's excluded from the foreign-reachable union and from `shortRangeReachable`. The socket
  record codec (`mesh/link/LinkFraming`) is transport-neutral and shared by the NAN NDP socket and the BLE
  L2CAP socket.
- After changing the `MeshTransport` interface, run `:app:testDebugUnitTest` — a test double
  (`RecordingTransport` in `MeshRouterTest`) implements that interface and won't be caught by
  `assembleDebug`. Same trap on `ForwardStore` (`FakeForwardStore`, `FakeCustody`) and `RatchetStore`.

## The Internet plane is a custody-plane sibling, not a third transport

`ScopeSync` (`mesh/spool/`, `docs/SPOOL_PROTOCOL.md`) sits beside `ForwardSync` under `MeshManager` —
deliberately **not** behind `MeshTransport`, whose seam is peer-addressed and radio-shaped while a scope
has no neighbors (ADR 019). It reaches the app through exactly two existing doors and adds no delivery
semantics of its own: `InboundPipeline.canCarry` authenticates a pulled frame, and
`MeshRouter.handleInbound` delivers it (dedup, custody, roster vetting, and the onward mesh relay come
free). Two invariants that are easy to break:

- **Only frames matching the scope frame-set rule may be sealed into a scope, in *both* directions**
  (`ScopeFrames.eligibleFor`, spec §4.4) — a scope is not a general-purpose upload channel. The group
  half has two traps: a `groupleave` carries its group id in the **payload** (never in
  `RelayEnvelope.group`), and the sender is vetted against the **founding** roster (members ∪ departed),
  because a leaver is already departed when its own leave frame is evaluated. A cleartext `profile` rides
  **both** forms (ADR 022) and is the exception to the addressing pattern: it names no recipient and no
  group, so the DM half matches it on sender alone and the group half rests wholly on the founding roster.
  Do not "tighten" that back to a recipient match — it is the only carrier of the prekey, and without it a
  peer off the radios can never bootstrap or repair a DM session, nor receive group sender-key seeds.
- **A blob that fails validation is quarantined per (spool, scope), never merely dropped** (spec §9.3).
  Spools are untrusted storage: a garbage blob folds into *their* digest and never ours, so without the
  invalid set the two digests diverge forever and the client re-pulls it on every heal round.
- **Attachments are a second object class, deliberately outside the scope digest**
  (`ScopeAttachments`, spec §4.5/§6.5/§9.5). Presence is discovered by asking (`ahave`), never by
  anti-entropy, because the quota is in *bytes* and a byte budget cannot be identical on every node —
  folding it in would make two spools with different budgets diverge forever. Two more traps: the
  attachment id is **keyed** (`HKDF(nonceKey, …)`), since the plain hash rides the mesh in cleartext
  and would otherwise let a spool confirm a frame belongs to a scope; and a client must **never** send
  an attachment record to a spool that omitted the three HELLO limits — an unknown record is skipped
  without an answer, stranding that `q` until the request timeout.
- **The attachment push-half deferral is a delay, never a veto** (`AttachmentDeferPolicy`, spec §9.5,
  ADR 021). Bytes wait while the radios are still carrying them, and only attachments — gating *frames*
  would make the scope digest a function of local mesh state and it would never converge again. Two
  rules keep a deferral from becoming a stranded image: it must **re-open by itself** when the evidence
  lapses (which is why the expiring `MeshTransport.reachable` sighting is half the rule and the
  never-expiring delivery tick cannot be the whole of it — a frame can be acked while its bytes were
  never pulled), and it must **end before the frame leaves custody**, since an attachment stops being
  nameable once `ScopeAttachments.references` no longer sees its frame. Group scopes never defer: the
  sealed group tick flips on the *first* member's receipt, so it can never mean "everyone holds it".
- **A profile has two propagation paths and they order on one number.** The cleartext `profile` frame
  is first contact only (it is self-certifying — the node id IS the hash of the `pubKey` in its own
  payload — so it can never be encrypted); presentation updates to an established contact ride
  `CTL_PROFILE` sealed inside v2 chat, which is what makes them cross the Internet plane. Both writers
  gate on the sender's **profile version** (`ProfilePayload.version`, the same value the cleartext
  frame puts in its envelope `sentAt`, stored as `PeerEntity.updatedAt`) — never on the carrying
  frame's own `sentAt`, or a re-sent ctl outranks a genuinely newer profile. The sealed path never
  touches the pinned key, the prekey, the device tag or the capabilities, and never inserts a peer row.
- **Group-root minting is damped; group-root adoption is not** (`GroupRootPolicy`, spec §3.2). Several
  members minting version 1 at once is normal and self-healing — `(version, minter)` collapses the
  lineages. Refusing to *adopt* a strictly-greater root is the failure mode: the device keeps gossiping
  a root everyone else ignores and never converges again. Bound outbound chatter (the per-(group,
  member) seed-send floor), never adoption.

## The DB transaction is taken BEFORE the ratchet mutex — always

Room over SQLCipher serves this app through a **single** connection, and every `mutex.withLock` block in
`RatchetSessions`/`GroupRatchetSessions` touches the store. So the two acquisitions must always happen in
one order: **transaction OUTER, mutex INNER**. Both facades enforce it themselves via the injected
`SessionTransactor` — take the lock through the private `locked { }` helper, never `mutex.withLock`
directly, and never add a store call under the lock by another route.

Get it backwards and the app deadlocks: the decrypt path (`db.withTransaction { commitOpen(…) }`) holds
the connection and waits for the mutex, while a seal/sweep/export path holds the mutex and waits for the
connection. **Both parties are suspended coroutines, so a thread dump shows nothing** — no thread holds a
transaction, yet every later DB user blocks forever and the process ANRs on whatever reads the database
next. This wedged a lab device on the M4 smoke; `SessionTransactorOrderTest` is the regression, and it
fails loudly (a store call with no enclosing transaction) rather than hanging.

## Keep pure mesh logic Android-free

`MeshRouter`, `SeenSet`, `WireCodec`, `MeshMetrics`, `BlobExchange`, and `Conversations` have no Android
dependencies and are unit-tested with `FakeLoopTransport`/fakes. Keep them that way. `MeshRouter` relay
timing is driven by an injectable `jitter` lambda so tests use a fixed delay + virtual time.

## Forward `signed`/`sig` verbatim on relay — never re-encode them

The wire is layered CBOR of opaque `@ByteString` blobs (`WireEnvelope.signed`/`sig`,
`RelayEnvelope.payload`), **not** kotlinx sealed polymorphism, precisely so a relay rewrites only
`ttl`/`hops` (`WireEnvelope.relayed()`) and passes `signed`+`sig` through byte-for-byte. Decoding `signed`
to a `RelayEnvelope` and re-encoding it could legally reorder CBOR keys and break the originator's Ed25519
signature — the old "an old relay re-encodes and breaks the signature" bomb. Keep `RelayEnvelope.type` a
plain `String` too (an unknown future type must *decode and relay*, not throw).

## Wire changes are a coordinated break — additive only

**Read `docs/WIRE_COMPAT.md` before changing any wire type.** Changing `WireEnvelope`'s shape, the
`WireCodec` config, the signing input, the `SERVICE_NAME`, or removing/renaming a field/type is a
coordinated wire break; adding a nullable/defaulted field or a new `type` is additive. Structure detail:
`context/wire-format.md`.

## Custody must converge — the content-digest rule

**Anything the content digest is folded over must be bounded by a rule that's identical on every node**
(same key, same direction, same origins, same liveness). Evict by the **frame-global `(sentAt, id)`** on
**every** origin (`ORIGIN_SELF` included), fold **live** ids only, and refuse a frame past its
frame-global expiry at store time. This makes the **TTL constants
(`DEFAULT_TTL_MS`/`DEFAULT_BROADCAST_TTL_MS`) and the broadcast-chat classification
convergence-critical — treat changing them like a wire change.** Two nodes that disagree hold different
live sets continuously and churn the NDP cue plane forever. Full failure history + how to verify
(`…debug.STORE`, `liveFingerprint` parity): `context/store-and-forward.md`.

## Inbound handlers must never throw

Decrypt/verify failures must never throw out of the inbound handler — `onDeliver` runs before the router
schedules the relay, so a throw would stop forwarding (`MeshManager.decryptAndDeliver`;
`verifyInbound` swallows failures and returns false so the router still relays). See
`context/e2e-encryption.md`.
