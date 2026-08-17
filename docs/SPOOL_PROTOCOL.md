# The spool protocol — scoped, blinded store-and-forward relays for the Internet plane

Status: **normative spec, v1 draft** · 2026-08-15, updated 2026-08-16 (three times) · ADR 019. This document is
the normative spec and is public from day one; `mesh/crypto/scope/` (`ScopeCrypto`, `SpoolPow`) and
`mesh/spool/` (`SpoolRecords`, `ScopeAttachments`) are the reference implementation of the
derivation/sealing/chunking/digest/PoW/record sections, and `ScopeCryptoTest`/`ScopeVectorTest`/
`SpoolPowTest`/`SpoolRecordsTest`/`ScopeAttachmentsTest` are the executable anchors (§13's vectors are
those tests' pinned constants, verbatim). The reference spool daemon (`knit-spool`, AGPL-3.0) and its
conformance suite exist as of 2026-08-16, as does the Android client plane (`ScopeSync`).
**All of them implement this file, not each other.** *The first 2026-08-16 update resolved eight ambiguities the daemon
implementation surfaced — semantic clarifications only (§6.2, §6.4, §7.1, §7.2, §12); no wire
field, vector, or derivation changed. The second lands with group scopes (M4): §3.2's v1 mint opens
from the creator to **any member** (preferred-minter-plus-grace damping, which also unfreezes a
departure re-mint whose re-minter never comes back), §3.2 adoption gains two mandatory insider-DoS
bounds, and §4.4 pins where a group frame's id actually lives. Again no wire field, no derivation,
no §13 vector changed — spool implementations need no update at all, since a spool never sees a
root. The third lands with attachments (M5): §4.5, §6.5, §7.3 and §9.5 are new, added as fresh
sub-numbers so no existing cross-reference moves. This is the first change that **does** ask
something of spool implementations — an attachment-capable spool advertises three new HELLO limits
and answers five new records, and one that does not simply omits them and is left alone. Still no
mesh wire field, no existing derivation touched, and no existing §13 vector changed; §12 and §13
gain rows rather than losing any.*

Sections are tagged by audience: **[Spool]** is everything a relay implementer needs (no crypto —
a spool never decrypts anything), **[Client]** is member-side, **[Both]** is shared.

## 1. Why, and why this shape [Both]

Knit is an offline mesh messenger; radio proximity is the product. This protocol extends *existing*
conversations across the Internet when no radio path exists — a continuity layer for contacts
already made over the mesh or QR, not a discovery network, not accounts, not a server. The moving
parts:

- A **scope** is one conversation's Internet presence — a DM pair or a group. Members derive its id
  and keys from secrets they already share (§3); nothing about a scope is registered anywhere.
- A **spool** is a small store-and-forward daemon (yarn waits on a spool) holding, per scope, a
  bounded set of **sealed frames** — the mesh's frozen custody unit (`signed` + `sig`, ADR 005)
  AEAD-encrypted under scope keys (§4) — plus a digest over the live set (§6). It streams new
  arrivals to connected subscribers and heals divergence by digest anti-entropy (§9), exactly the
  delay-tolerant custody philosophy the mesh already has, scoped per conversation and blinded.
- Spools never talk to each other. A scope lists several spools in its signed config (§5); every
  member pushes to and pulls from all of them, so **the client union is the federation** — a fresh
  or wiped spool is refilled by any one member, and no spool is load-bearing.
- A frame pulled from a spool re-enters the local mesh through the ordinary re-serve path (§9), so
  one Internet-connected member bridges a whole radio island in both directions with no new
  delivery semantics.

**The structural trade, stated as loudly as the ratchet docs state theirs: a spool is a custody
peer that can never read what it custodies. It learns opaque scope ids, blob sizes and timings, and
subscriber IPs — never node ids, content, rosters, or delivery facts. Moderation of content is
impossible by construction; that is simultaneously the privacy story and the abuse story (§6's
quotas and PoW are the whole toolkit). Spools are cattle: losing one loses nothing a member can't
refill.**

- An **attachment** — the image bytes a frame references rather than carries — rides the same scope
  as a second, separate object class: sealed and chunked (§4.5), stored under a per-scope *byte*
  quota (§6.5), fetched on demand by the member that holds the referencing frame (§9.5). It is
  deliberately outside the frame digest, for the reason §6.5 states.

Non-goals (v1): carrying the plaintext Nearby broadcast room (proximity semantic, spam surface);
contact discovery or any server-side identity; spool-to-spool federation; resistance to a global
passive network observer (Tor optionally covers the IP edge; padding is future study, §11).

## 2. Conventions and encodings [Both]

- **CBOR profile**: definite-length encoding only; unknown map keys are ignored on decode; fields
  equal to their declared default are omitted on encode; map keys are the short field names given in
  §7. Every `ByteArray`-valued field — including byte-array list elements — encodes as a CBOR byte
  string (major type 2), never as an integer array.
- **Ids**: scope ids and blob ids are raw 32-byte strings on the wire; lowercase hex is the display
  form in logs, diagnostics, and this document. Digests are raw 8-byte big-endian strings (§6) —
  a byte string, never a CBOR integer, so implementations never reason about signed-integer
  encodings of high-bit digests.
- **Integers in derivation inputs**: `u32be(n)` / `u64be(n)` are unsigned big-endian, 4 and 8 bytes.
- **KDF**: HKDF-SHA256 (RFC 5869), salt = 32 zero bytes, `L` as stated per derivation. Info strings
  are the ASCII label concatenated with a context: `label ‖ context`. Labels live under
  `knit/scope/v1/...` (key plane) and `knit/spool/v1/...` (transport plane), disjoint from the app's
  `knit/dm/v2/...` and `knit/group/v1/...` namespaces — no derivation here can cross-derive with the
  message ratchets.
- **Context strings** use the house `|` separator: it cannot occur in a 26-character base32 node id
  or a `g-`-prefixed hex group id, so delimited fields cannot alias each other.
- **AEAD**: AES-256-GCM, 12-byte nonce, 128-bit tag.
- **Hash**: SHA-256 everywhere (blob ids, the seal's synthetic nonce input, PoW).
- Prose is normative as written; "must/never/only" sentences are requirements. Where a behavior is
  deliberately implementation-chosen it says so.

## 3. Scopes and keys [Client]

### 3.1 DM scopes

Input: the pair's **pairwiseRoot** — the stable per-session export secret both sides derive from the
DM ratchet (`docs/FORWARD_SECRECY_RATCHET.md` §8: `HKDF(sessionRoot, "knit/dm/v2/export/root")`).
With `idLow`/`idHigh` the two node ids sorted lexicographically:

```
ctx      = UTF8(idLow ‖ "|" ‖ idHigh ‖ "|")
scopeId  = HKDF(ikm = pairwiseRoot, info = "knit/scope/v1/dm/id"  ‖ ctx, L = 32)
sealOkm  = HKDF(ikm = pairwiseRoot, info = "knit/scope/v1/seal"   ‖ ctx, L = 64)
sealKey  = sealOkm[0..31]        nonceKey = sealOkm[32..63]
```

The ids-as-context is defense in depth — the ikm is already pair-secret, so a spool (or anyone)
holding both public node ids cannot compute the scopeId.

**Lifecycle**: the scope follows the *active session*. A session replacement (peer reset after a
wipe, both-initiate race) yields a new pairwiseRoot, hence a new scope; the old scope stays
derivable from the retiring root for the ratchet's 48 h drain window, during which members may keep
it subscribed, after which its blobs age out at spools. A device that loses its own session state
**cannot recover its scopes** — continuity dies with the session, and re-establishment takes an
out-of-band re-meet (mesh or QR). That is coherent with this plane being a continuity layer for
existing contacts, and it is a privacy property: scope ids are unlinkable across session eras.

### 3.2 The shared group root

The group sender-key scheme has no shared secret (`docs/GROUP_FORWARD_SECRECY.md` §8 defers exactly
this object here). The **group root** supplies it:

```
GroupRoot { root: 32 random bytes, version: Int (from 1), minter: nodeId }
```

- **Minting**: **any current member** mints `version = 1` when it holds no root, has the Internet
  plane enabled for the group, and the group is fully ratchet-capable (§3.3). Concurrent mints are
  damped, not forbidden, by the **preferred minter** — the same deterministic function the
  departure re-mint uses below: *the creator if still a member, else the smallest remaining node
  id*. The preferred minter mints immediately; any other eligible member waits `mintGrace` (§12)
  from when it first became eligible, giving a gossiped root time to arrive. The wait is persistent
  state, not a process timer — a device that restarts hourly must not restart the clock.
  Competing v1 mints resolve by the `(version, minter)` order below; the losing lineage's blobs are
  orphaned at spools and age out on `ttlMs`, and members refill the winning scope through the
  ordinary §9.1 push half. *(This replaces the draft's creator-only rule, whose accepted gap — a
  group whose creator never opts in gets no scope — is exactly what the grace closes.)*
- **Distribution is gossip on the existing seed channel**: the root rides as additive fields of the
  group-key control payload (`GroupKeyPayload.gr`, `CTL_GROUP_KEY` — pairwise-sealed ctl DMs) on
  **every** seed send and key-request response from **any member who holds a root**. The minter
  originates versions; everyone gossips the newest they hold. The seed outbox / key-request /
  re-send machinery is the delivery system — root healing costs no new mechanism, and a wiped
  minter passively recovers the current root from the first seed DM it receives. A member that
  receives a distribution carrying a root **older** than its own (or none, while it holds one)
  **should** answer with its own — the root has no acknowledgment, so a stale gossip is the only
  evidence that an earlier one to that member was lost, and without the correction a sender that
  believes it already delivered will never retry. The answer is self-terminating: once the lagging
  member adopts, its next distribution carries the same `(version, minter)` and the branch stops.
  Echoing a root straight back to the member it was just learned from is pointless and should be
  skipped.
- **Adoption**: adopt a carried root iff its `(version, minter)` is **strictly greater** than the
  held one (compare `version`, then `minter` lexicographically) and the carrying DM's sender is in
  the pinned founding roster and not departed. Authenticity is the carrying v2 session plus the
  frame signature; a root is never v1-wrapped (the seed rule, same harvest argument). Adoption is
  idempotent. Two bounds a receiver **must** also enforce, both closing insider denial-of-service
  that the ordering rule alone does not:
  - `minter` must itself be in the pinned founding roster. Otherwise any member wins every tie
    forever by naming a lexicographically-maximal minter id that belongs to nobody.
  - `version` must satisfy `version ≤ held.version + maxRootVersionJump` and
    `version ≤ maxRootVersion` (§12). Otherwise one grief-mint at `2³¹ − 1` puts every future
    legitimate re-mint out of reach, permanently freezing the scope. A legitimate version never
    exceeds the founding roster size (one mint plus at most `size − 1` departures, and rosters
    never grow), so §12's ceiling of 16 is double the model's maximum. State the residual honestly:
    a member willing to burn the version space *before* departing can still freeze rotation, which
    is the same insider tier as grief-rotation below and leaves the mesh path untouched.

  Adoption is otherwise **never rate-limited**, deliberately — refusing a strictly-greater root
  strands the device on a dead lineage with no way back, since it would keep gossiping a root
  everyone else ignores. Outbound chatter is bounded on the *send* side instead (the per-(group,
  member) seed-send floor), which is the safe place to put the bound.
- **Re-mint on departure**: when a member processes a signed `groupleave` it records, in the same
  transaction as the leave-rekey send-chain reset, that a re-mint is due. The mint itself follows
  the same preferred-minter-plus-grace rule as version 1 — the **deterministic re-minter** (creator
  if still a member, else the smallest remaining node id) mints `(fresh 32 bytes, version + 1)`
  immediately, any other remaining member after `mintGrace`. Splitting the record from the mint is
  what makes rotation crash-safe, and the grace is what keeps a re-minter who is offline or has the
  plane switched off from freezing rotation for everyone else. The leave-rekey already fans seed
  ctl DMs to every remaining member; the new root rides them for free.
- **Convergence**: divergent departure views — and, now, several members reaching the end of their
  mint grace at once — can transiently mint competing same-version roots; the `(version, minter)`
  order resolves them deterministically, and the next processed departure mints strictly higher, so
  lineages collapse. A malicious member can grief-rotate, now bounded by the adoption ceiling above
  — insider spam tier, same posture as "a member can spam its own scope" (§6).

### 3.3 Group scopes

```
ctx      = UTF8(groupId ‖ "|") ‖ u32be(rootVersion)
scopeId  = HKDF(ikm = groupRoot, info = "knit/scope/v1/group/id" ‖ ctx, L = 32)
sealOkm  = HKDF(ikm = groupRoot, info = "knit/scope/v1/seal"     ‖ ctx, L = 64)  → sealKey ‖ nonceKey
```

There is **no separate scope-epoch field anywhere: `rootVersion` is the epoch.** A departure
re-mint rotates root and version together, so scopeId and seal keys rotate as one — a removed
member knows the old id and could otherwise keep watching (undecryptable, but observable)
ciphertext flow; to a spool the rotated scope is an unrelated fresh id. Members keep the old scope
subscribed for the 48 h drain (mirroring the DM prev-root window), then let it age out. Old blobs
are **not** migrated: the new scope refills from members' custody via anti-entropy, re-sealed under
the new keys with new blob ids — a different scope, correctly unlinkable.

Groups that are not fully ratchet-capable have no root channel and therefore no scope.

### 3.4 Keys

| Key | Derived from | Rotates when | Held by |
|---|---|---|---|
| DM `scopeId`, `sealKey`, `nonceKey` | pairwiseRoot | session replacement (wipe/reset) | the two members |
| group `scopeId`, `sealKey`, `nonceKey` | groupRoot + rootVersion | departure re-mint | current members (+ departed, until re-mint) |
| groupRoot | minted at random | never in place — replaced by re-mint | same |

## 4. Sealing [Client]

### 4.1 What is sealed

The plaintext is the mesh's frozen custody unit, raw-concatenated — no wrapper, since the signature
is always exactly 64 raw Ed25519 bytes and every custodial frame is signed:

```
pt = sig(64) ‖ signed
```

`signed` is the canonical `RelayEnvelope` CBOR, byte-for-byte what the mesh floods (ADR 005). After
unsealing, the ordinary inbound verification applies unchanged (§4.4).

### 4.2 Key schedule — the outer seal is scope-static

One seal key per scopeId, derived in §3, rotating exactly when the scopeId rotates. Key selection is
therefore a bijection with the scope: a blob pulled from scope S opens under S's one key, no hints,
no trial decryption. The blob's leading byte is a seal-scheme version (`sealv = 0x01`) reserved for
future evolution.

This deliberately amends the design-phase intent that "sealing keys rotate with ratchet epochs".
Recorded so it is not relitigated — per-epoch outer keys fail twice:

1. **DM fresh-epoch bootstrap**: the epoch identifiers needed to select a key (`se`/`ek`/`pe` in the
   ratchet header) are *inside* the sealed blob, and a fresh epoch's key depends on a new DH public
   key also inside it. The receiver of a first-of-epoch blob could neither select nor enumerate the
   key. Deadlock by construction.
2. **Group seed-lag visibility inversion**: a blob sealed under a sender's epoch export would be
   unopenable exactly by the seed-lagging member — the member for whom the frame must stay
   *visible* so it custodies, re-floods, and counts the undecryptable-frame signal that drives seed
   key-requests. Epoch-sealing would starve the group scheme's own recovery loop.

The cost, stated honestly in §10: the outer seal protects **routing metadata** with a
scope-generation horizon, not an epoch horizon. **Content** confidentiality and forward secrecy are
entirely the inner v2 schemes' and are untouched — what is inside `signed` is already
epoch-ratcheted ciphertext. An epoch-keyed outer seal remains reachable as `sealv = 2` (§11); the
ratchet `exportEpochSeal` surfaces are reserved for it.

### 4.3 The deterministic seal

Spools dedupe by blob id and *any* member may independently push the same frame (mesh-carried,
refilling a wiped spool), so sealing must be a pure function of (scope, frame):

```
nonce   = HKDF(ikm = nonceKey, info = "knit/scope/v1/nonce" ‖ SHA-256(pt), L = 12)
aad     = "knit/scope/v1" ‖ scopeId
ct      = AES-256-GCM(key = sealKey, nonce, pt, aad)
blob    = 0x01 ‖ nonce(12) ‖ ct
blobId  = SHA-256(blob)
```

Nonce-reuse analysis (normative rationale):

- The nonce is a **keyed** synthetic IV (SIV-style, from HKDF + SHA-256 — no new primitive).
  Identical frame ⇒ identical `(key, nonce, pt)` ⇒ identical blob ⇒ identical blobId: cross-uploader
  dedup and digest convergence hold by construction, and a re-push is byte-identical.
- Two *distinct* plaintexts collide on `(key, nonce)` only via a SHA-256 collision or the 2⁻⁹⁶
  birthday over a per-scope set bounded by `maxFrames` — negligible; and the only parties able to
  *attempt* to manufacture one are seal-key holders, i.e. scope members, who can already read every
  blob in the scope. GCM's nonce-reuse failure mode grants an attacker nothing they lack.
- The keying is load-bearing against a **confirmation oracle**: an unkeyed `SHA-256(pt)` nonce
  would let a spool that holds candidate frame bytes (say, a cleartext-payload `groupleave`
  observed on the mesh) recompute the nonce and link a mesh identity to a scopeId. `HKDF(nonceKey,
  …)` closes it.
- The aad binds the scope and the scheme label, so a blob replanted into another scope — or fed to
  a future scheme — fails authentication before any content parses.

### 4.4 Unseal validation and the scope frame-set rule

On a pulled or event-delivered blob, a member: verifies `blobId = SHA-256(blob)`; opens the AEAD;
splits `sig`/`signed`; decodes the `RelayEnvelope`; verifies the Ed25519 frame signature against the
**pinned** sender key exactly as mesh inbound does; then enforces the **scope membership of the
frame**:

- **DM scope**: `type = chat`, sender and recipient are exactly the pair, and the payload is
  v2-sealed (`EncEnvelope.v = 2` with the DM ratchet header).
- **Group scope**: `type ∈ {chat (group form), groupupdate, groupleave}` with the scope's group id,
  and the sender in the pinned founding roster. Two details that decide the rule's exact shape:
  - **Where the group id lives is per type.** `chat` and `groupupdate` carry it in the envelope's
    roster field; `groupleave` carries it in its *payload* and leaves the envelope field unset, so
    a rule that only reads the envelope silently excludes departures — the one frame the remaining
    members most need over the Internet.
  - **Founding roster, not the effective one.** A leaver is already departed by the time its own
    `groupleave` is evaluated, and a departed member's pre-departure frames stay legitimately
    re-servable. Admitting them is safe because the departure re-mint rotates the scope id: a
    departed member cannot reach the new scope at all, whatever the frame rule says.

The same rule governs the **push side**: only frames matching it may be sealed into a scope. A
`profile` frame is not scope-carried, and the reason is structural rather than a deferral: it is
authenticated against the `pubKey` *inside its own payload* (a node id is that key bundle's hash), it
addresses no recipient and no group — so no scope is its natural home — and its job is first contact,
which a scope by definition never has. Profile *updates* between established contacts do cross, as an
ordinary sealed chat frame carrying `CTL_PROFILE`, which needs nothing from this rule; the cleartext
frame keeps first contact permanently. Receipts and reactions are likewise already sealed chat-shaped
ctl frames, since a scope-eligible pair is ratchet-capable by construction. A blob whose plaintext fails any check is
discarded and quarantined (§9.3); the fault is the *uploader's*, never the spool's.

A frame that passes re-enters delivery inside a fresh mesh envelope with a full hop budget — the
custody re-serve path — so it re-floods the local mesh; dedup, idempotent delivery, and roster
vetting are the existing inbound gates, unchanged.

### 4.5 Attachments [Client]

A chat frame does not carry its image; it names one. The name is the *ciphertext* hash of the
attachment, and it rides the mesh in **cleartext** on the frame (`ChatContent.attachmentHash`) so a
carrier blind to the sealed content can still custody the bytes. The plane carries the bytes as a
second object class beside frames.

The input is that same attachment ciphertext `A` — already AEAD-encrypted end to end under a fresh
per-send key that lives inside the sealed `MessageContent`, and already content-addressed by
`aHash = SHA-256(A)`. This layer does not re-protect the content, which is already opaque; it blinds
the *routing* of it, exactly as §4.2's outer seal does for frames.

```
aid       = HKDF(ikm = nonceKey, info = "knit/scope/v1/aid" ‖ scopeId ‖ aHash, L = 32)
total     = ceil(|A| / aChunkBytes)                          // aChunkBytes = 49152 (§12)
chunk_i   = A[i·aChunkBytes … min((i+1)·aChunkBytes, |A|))
apt_i     = aHash(32) ‖ u32be(i) ‖ u32be(total) ‖ chunk_i
nonce_i   = HKDF(ikm = nonceKey, info = "knit/scope/v1/anonce" ‖ SHA-256(apt_i), L = 12)
aad       = "knit/scope/v1/attach" ‖ scopeId
ct_i      = AES-256-GCM(key = sealKey, nonce_i, apt_i, aad)
achunk_i  = 0x03 ‖ nonce_i(12) ‖ ct_i
cid_i     = SHA-256(achunk_i)
```

Why each piece is shaped this way:

- **`aid` is keyed, not `aHash` itself.** `aHash` is public on the mesh. An unkeyed attachment id
  would hand a spool that has *any* source of candidate hashes — an operator who also runs a node in
  radio range, a harvested disk — a confirmation oracle linking a mesh frame to a scope id. This is
  §4.3's known-plaintext argument applied to the object that actually travels in the clear, and it is
  the one place where the attachment plane is more exposed than the frame plane if you get it wrong.
- **`sealv = 0x03`.** Distinct from the frame seal's `0x01`, and from the `0x02` reserved for the
  epoch-keyed frame seal (§11), so the two openers can never be fed each other's blobs. The aad
  prefixes differ too, and cannot alias: the scope id that follows is fixed-width, so a frame aad is
  always 45 bytes and a chunk aad 52.
- **Chunking is fixed-size and structural, not tunable.** A constant `aChunkBytes` is what makes a
  chunk's position a function of the attachment alone — there is no manifest object, and therefore
  nothing for two members to disagree about. A sealed chunk is `1 + 12 + 40 + 49152 + 16 = 49221`
  bytes, comfortably inside the 64 KiB `maxBlob`.
- **The header is inside the seal.** `aHash ‖ index ‖ total` binds each chunk to its attachment and
  its position, so a chunk cannot be replayed elsewhere even by a scope member.
- **Deterministic, like §4.3.** Every member seals a given chunk to identical bytes, so cross-uploader
  dedup and idempotent re-push hold with no coordination.

**Chunks are addressed by `(aid, index)`, never by `cid`** — a fetcher cannot know a chunk's content
address before fetching it. `cid` exists so a spool can verify what it is asked to store, the way
`blobId` does for a frame. Integrity for the fetcher is the AEAD, then the sealed header, then the
decisive check: the reassembled bytes must hash to `aHash`, the address the frame named. A member
that fails any of these treats the attachment the way §9.3 treats a bad blob.

## 5. Scope configuration [Client]

A scope's operating parameters ride *inside the conversation itself*, end-to-end sealed like any
message — a spool never sees spool lists or bounds provenance, and the config propagates over mesh
and spools alike with no side channel:

```
CTL_SCOPE_CONFIG = 7      MessageContent.sc: ScopeConfigPayload?
ScopeConfigPayload {
    groupId: String?      // null ⇒ the DM pair of this ctl's endpoints
    spools:  [String]     // WSS URLs (may embed ?k= bearer tokens, §7.1)
    maxFrames: Int        // retention bounds the members assume and declare at SUB (§6.2)
    ttlMs:   Long
    maxBlob: Int
    version: Int          // monotonic per conversation
}
```

- **Carriage is a `MessageContent.ctl` value, not a new wire frame type.** This corrects the
  design-phase sketch by the ADR 016/018 lesson: `isCustodial` is a fixed list on deployed builds,
  so a new frame type floods but is never custodied — and the config is precisely the frame that
  must survive store-and-forward to reach offline members. As a ctl inside a sealed v2 chat frame it
  is custodied by every build, and an old build consumes it as the pinned chain-advancing silent
  no-op. (Mesh-side wire stubs land with the client plane, additively, per `docs/WIRE_COMPAT.md`.)
- **Issuer** is the ctl frame's authenticated sender — never a payload field, so it is not
  separately forgeable. Any member may issue (DM: either party); the creator merely issues first.
- **Conflict rule**: highest `version` wins; tie → highest issuer node id, lexicographically. Config
  delivery cadence is client policy — the LWW rule makes redundant delivery harmless.
- Bounds live in the config, not in app constants, on the ADR 006 lesson: convergence-relevant
  bounds baked into app versions diverge silently across upgrades; pinned in signed per-scope state,
  every member and (via SUB) every spool reads the same numbers from the same place.
- No config acknowledgment and no capability bit exist in v1: the plane is purely additive
  continuity — the mesh path works regardless.

## 6. The spool [Spool]

### 6.1 Data model

Per scope, nothing but:

- `blobId → (blob, arrivedAt)` — the live set;
- a **tombstone set** of evicted/expired blob ids (bounded, expiring on the same `ttlMs` clock);
- the rolling **digest** over the live blob-id set (§6.3);
- subscriber connections.

No accounts, no user rows, no cross-scope index beyond the scope table. A spool that loses its disk
is refilled by any member through §9 — spools are cattle.

### 6.2 Bounds, eviction, tombstones

A sealed frame hides its send time, so a spool orders by what it can see: **evict oldest by
`arrivedAt`** when a scope exceeds `maxFrames`; **expire** at `arrivedAt + ttlMs`. Different spools
may therefore hold different sets — that is fine; spools never sync with each other and clients
union them. Applied bounds per scope are the most recent SUB's declaration **clamped** to the
spool's HELLO-advertised hard caps; DIGEST echoes the applied values plus a `full` flag so a client
can tell eviction pressure from loss.

Three guards close the re-push churn loop (a client re-uploading what a spool just evicted):

1. evicted and expired ids land in the tombstone set; a PUSH matching a tombstone is refused with
   `tombstoned` and does not re-enter the digest;
2. clients apply the dead-on-arrival guard outward (§9.2) — never push a frame whose cleartext
   `sentAt + ttlMs` has already lapsed;
3. LIST responses carry the tombstone ids, so a client learns "seen, evicted" before wasting an
   upload.

On PUSH a spool **must** verify `blobId = SHA-256(data)` (refuse `bad_id`) and enforce `maxBlob` —
a third party must not be able to poison an honest spool's digest. Delivery facts do not exist at
this layer: receipts are just more sealed frames inside a scope; spool copies of everything age out
on the TTL uniformly.

Tombstone sets are **count-bounded** as well as TTL'd (suggested bound in §12, oldest-first drop):
without a count bound, a member cycling unique blobs through eviction grows the set at push rate
for a whole `ttlMs`. Dropping a tombstone early merely re-admits a blob that then ages out through
the ordinary TTL — bounded churn, never divergence.

A spool may also **forget a scope entirely** — watermark shedding (§6.4), an operator wipe, or a
restart of a non-persistent spool — while connections still hold subscriptions for it. The
subscription survives on the connection; `list`/`pull` against a forgotten scope answer empty (an
empty `list` response, all ids `missing`) — never an error. The next `push` **recreates** the
scope: it is exactly §6.4's "first SUB or PUSH for an unknown scope id", so the creation gates
apply (PoW stamp on the `push` record — this is what `push.pow` is for — and any creation rate
limit), the bounds re-apply from the connection's most recent declaration, and the spool sends the
recreated scope's fresh `digest` before the push's `ok` so the uploader re-anchors.

### 6.3 The digest

```
digest(S) = XOR over b ∈ S of FNV1a64(b)      // b = the raw 32-byte blobId
FNV1a64: h = 0xcbf29ce484222325; per byte: h = (h XOR byte) × 0x00000100000001b3 (mod 2⁶⁴)
digest(∅) = 0
```

Order-independent and self-inverse, so add/remove are O(1). Wire form: 8 bytes big-endian, as a
byte string. (This is the shape of the mesh's custody digest with raw-byte input instead of UTF-8
frame-id strings.)

### 6.4 Abuse posture

- Per-scope quotas (§6.2) — a spamming member thrashes only its own conversation.
- **Scope-creation cost**: the first SUB or PUSH for an unknown scope id may demand a PoW stamp
  (§8), difficulty advertised in HELLO; the spool caches accepted `(scopeId, day)` so honest
  clients pay roughly once per scope.
- Per-IP / per-connection rate limits (`rate` + `retryMs`); a global storage watermark with
  oldest-scope shedding is operator policy. When a spool sheds, the recommended shape is a
  **whole-scope drop, tombstones included** — a shed scope is exactly the "wiped spool" §9.1
  refills, and surviving tombstones would refuse the very re-pushes that refill it — followed by an
  empty `digest` (count 0, digest 0) to the scope's still-connected subscribers so they refill
  immediately instead of on the next reconnect.
- **Private spools**: a bearer token in the WSS URL (§7.1) — zero-config access control for
  self-hosters; the URL lives only inside the sealed scope config anyway.

### 6.5 Attachments at the spool [Spool]

A spool that supports attachments (it says so in HELLO — §7.3) keeps, per scope, a second table
alongside §6.1's: per `aid`, the declared `total`, the bytes held, an `arrivedAt`, and the stored
chunks keyed by index. Chunks are opaque, exactly like frame blobs.

- **The quota is bytes, not objects, and it is the spool's alone.** Per-scope `maxAttachBytes`, taken
  straight from the spool's HELLO — deliberately **not** added to the SUB-declared `ScopeBounds`.
  Members must agree on the frame bounds because the frame digest folds over them; attachments are
  outside the digest, so a per-scope declaration would buy no convergence and only add a field two
  members could disagree about. Over budget, evict the **oldest whole `aid`** by `arrivedAt` — never
  individual chunks. Half an attachment is useless to every member and would show up in the bitmap as
  progress that can never complete. An attachment that cannot fit the budget even alone is refused
  `quota`.
- **TTL is stamped at the first chunk and never extended.** Otherwise a member trickling one chunk an
  hour pins an attachment indefinitely.
- **Tombstones**, in the §6.2 shape and with the same TTL and count bound: an evicted or expired `aid`
  is remembered, `aput` against it is refused `tombstoned`, and `ahave` answers `dead`. Without this
  a member re-uploads what the spool just dropped, forever.
- On `aput` a spool **must** verify `cid = SHA-256(data)` (refuse `bad_id`) and enforce `maxAChunk`.
  **First write wins** at a position: an identical `cid` is acked idempotently, a differing one is
  refused `conflict`. Honest members never differ, because §4.5's seal is deterministic; the refusal
  exists so one member cannot poison an attachment for the rest of the scope.
- A whole-scope shed (§6.4) drops that scope's attachments and their tombstones with it.

**Attachments are never folded into the scope digest.** This is the load-bearing decision of the
section, and it is the mesh's own lesson restated: anything a digest folds over must be bounded by a
rule identical on every node, or the two sides never converge and re-attempt forever. A byte quota is
precisely the kind of operator- and device-tunable knob that cannot be identical, which is why the
mesh keeps `ForwardEntity.attachmentHash` out of `StoreDigest` and bounds carrier blobs with a purely
local budget. Attachment presence is therefore discovered by **asking** (`ahave`) rather than by
anti-entropy, and a spool holding fewer attachments than a member is not divergence — it is the
quota working.

## 7. Records [Both]

### 7.1 Binding

- Transport: **WSS** (TLS WebSocket). URL: `wss://host[:port]/spool/v1[?k=<token>]`. A private
  spool compares `k` constant-time and closes `4001` before HELLO on mismatch. The record layer is
  transport-neutral CBOR; an alternative binding (e.g. QUIC) would define its own framing (§11).
- **Exactly one CBOR record per WebSocket binary message** — WS provides framing.
- Each direction sends `hello` first. The spool advertises `v` (highest supported record-layer
  version), `min`, `limits`, `powBits`; the client answers with the chosen `v` in `[min, v]` — and
  nothing else identifying, in either direction. No version overlap: close `4002`. A `hello` *after*
  negotiation is malformed in-band traffic: answered with `err malformed`, connection kept —
  `4000` covers pre-hello traffic only.
- Request/response correlation: the client stamps `q` (monotonically increasing per connection);
  terminal responses (`ok`/`err`) echo it. Server-initiated records (`digest`, `event`) carry no `q`.
- All scope operations require a prior `sub` for that scope on the same connection
  (`not_subscribed` otherwise).
- WS close codes: `4000` malformed pre-hello traffic · `4001` auth · `4002` version · `4003` abuse.

### 7.2 The records

Field names are the CBOR map keys. `bstr32`/`bstr8` = byte strings of that length.

| Record | Direction | Fields | Semantics |
|---|---|---|---|
| `hello` | both, first | `t, v: Int` (+ spool→client: `min: Int, limits, powBits: Int`) | version negotiation; `limits = { maxBlob, maxRecord, maxScopes, maxPull, maxFramesCap: Int, maxTtlMs: Long }` plus, on a spool that supports attachments, `maxAttachBytes, maxAChunk, maxAget: Int` (§7.3); `powBits = 0` disables PoW |
| `sub` | c→s | `t, q: Long, subs: [ { scope: bstr32, bounds: { maxFrames: Int, ttlMs: Long, maxBlob: Int }, pow?: { n: Long, d: Long } } ]` | subscribe + declare bounds; unknown scope with PoW on ⇒ valid stamp or `err pow`; response: one `digest` (or scoped `err`) per scope |
| `digest` | s→c | `t, scope: bstr32, digest: bstr8, count: Int, full: Bool, bounds` | the anti-entropy cue — sent on sub and whenever the spool chooses (e.g. after eviction); the client treats the latest as the anchor |
| `list` | c→s / s→c | `t, q, scope` / `t, q, scope, blobIds: [bstr32], tombstones: [bstr32]` | the id exchange behind a digest mismatch |
| `pull` | c→s | `t, q, scope, blobIds: [bstr32]` (≤ `maxPull`) | answered by `blob`* then `ok { q, missing?: [bstr32] }` |
| `blob` | s→c | `t, scope, blobId: bstr32, data: bstr` | one pulled blob |
| `push` | c→s | `t, q, scope, blobId: bstr32, data: bstr, pow?` | store; spool verifies hash/size/quota/tombstone, folds the digest, fans out `event` |
| `event` | s→c | `t, scope, blobId: bstr32, data: bstr` | live delivery to every *other* subscriber of the scope (uploader excluded); best-effort — a spool may disconnect a slow consumer; correctness rests on §9 |
| `ok` | s→c | `t, q: Long, missing?: [bstr32]` | terminal ack |
| `err` | s→c | `t, code: String, q?: Long, scope?: bstr32, msg?: String, retryMs?: Long` | terminal error; connection-scoped when `q` absent |

Error codes (append-only registry; unknown codes are terminal-generic): `version`, `pow`,
`tombstoned`, `quota`, `too_large`, `bad_id`, `rate`, `not_subscribed`, `malformed`, `internal`,
`conflict`. `version` is **reserved, never emitted in v1** — a hello mismatch is close `4002`, not a
record; the code exists for a future record-level versioning use so it is never recycled. `conflict`
is emitted only by `aput` (§7.3).

Clarifications (v1 semantics the reference daemon and conformance suite pin):

- **Unsolicited `digest`**: a spool **should** send a fresh `digest` to a scope's subscribers
  whenever the live set changes beyond a single acked push — eviction pressure, TTL expiry, a
  watermark shed — so connected clients learn of loss without waiting for a reconnect. Clients
  must not *rely* on it (it is best-effort fan-out like `event`); §9.1 on reconnect remains the
  correctness anchor.
- **`pull` beyond `maxPull`**: the spool truncates the request to `maxPull` — never an error. Ids
  beyond the cap appear in neither `blob`s nor `missing`; a client that overshoots re-pulls the
  remainder.
- **Duplicate `push`** (blobId already live): acked `ok`, **no** `event` fan-out — the push row's
  "fans out `event`" applies to newly stored blobs only. Content addressing makes the duplicate
  byte-identical, so subscribers already have it or will heal via digest.

### 7.3 Attachment records

| Record | Direction | Fields | Semantics |
|---|---|---|---|
| `ahave` | c→s | `t, q, scope: bstr32, aid: bstr32` | what does this spool hold for this attachment; answered by `ahas` |
| `ahas` | s→c | `t, q, scope, aid, total: Int, bits: bstr, dead: Bool` | `total = 0` ⇒ never seen; `dead` ⇒ tombstoned (§6.5); `bits` is the presence bitmap — chunk *i* is bit *i mod 8*, **MSB-first**, of byte *i div 8* |
| `aget` | c→s | `t, q, scope, aid, from: Int, n: Int` (≤ `maxAget`) | answered by `achunk`* then a bare `ok { q }` |
| `achunk` | s→c | `t, scope, aid, idx: Int, total: Int, cid: bstr32, data: bstr` | one sealed chunk; carries no `q`, exactly like `blob` |
| `aput` | c→s | `t, q, scope, aid, idx, total, cid: bstr32, data: bstr, pow?` | store one sealed chunk |

- Indices the spool lacks simply do not arrive from `aget`. The client already knows which those are
  from the bitmap, so nothing enumerates them back and `ok.missing` stays a frame-only field. An
  `aget` overshooting `maxAget` is truncated, never an error — the `pull` rule (§7.2), reapplied.
- `aput` follows §6.5: `cid` verified, `maxAChunk` and the byte quota enforced, tombstones refused,
  first write wins with `conflict` on a differing re-write.
- Attachments are **not** fanned out with `event`. A member learns an attachment exists from the frame
  that names it, not from the spool, so a push-time fan-out would deliver bytes nobody has a reference
  for yet.

**Capability negotiation is a gate, not a hint.** A spool advertises attachment support by including
`maxAttachBytes`, `maxAChunk` and `maxAget` in HELLO's `limits` — all three, or none. A client
**must not** send `ahave`/`aget`/`aput` to a spool that omitted them. The reason is mechanical rather
than stylistic: §2's rule is that unknown records are *skipped*, and a skipped request is never
answered, so an optimistic `ahave` to a v1 spool leaves that `q` outstanding until the client's
request timeout — once per attachment, per scope, per heal round. Support is additive and needs no
record-layer version bump precisely because the flag carries it.

## 8. Proof of work [Both]

Stateless Hashcash (the Nostr NIP-13 family), over data both sides already share — no server
challenge round-trip:

```
input   = "knit/spool/v1/pow" ‖ scopeId(32) ‖ u64be(day) ‖ u64be(n)
valid   ⇔ leadingZeroBits(SHA-256(input)) ≥ powBits           // powBits from HELLO; 0 = off
day     = floor(unixMillis / 86 400 000)                       // UTC day number
```

A spool accepts `day ∈ {today − 1, today, today + 1}` (clock skew + pre-mining bound) and caches
accepted `(scopeId, day)` pairs. The stamp is deliberately spool-independent: spools have no
protocol identity beyond a URL, so there is nothing sound to bind to, and the per-scope-per-day
cache bounds replay value. Recommended default difficulty: 20 bits (~10⁶ hashes, sub-second on a
phone; a mass scope-squatter pays it per scope per day).

## 9. Anti-entropy — the client procedure [Client]

### 9.1 The heal loop

On `digest` mismatch against the local expectation for a scope: request `list`; diff the spool's
live set against local custody (minus tombstoned ids); `pull` what is missing locally, `push` what
the spool lacks. **Bidirectional by design** — this is the "custody peer per scope" doing real
work: a member that carried frames over the mesh while the spool was unreachable refills it; a
fresh spool added to the config heals from any one member; members converge through the union of
whatever every spool holds.

### 9.2 Outward dead-on-arrival guard

Never push a frame whose cleartext-to-members `sentAt + ttlMs` (scope config) has lapsed — the
mesh's custody store applies the same rule inward, so expired frames neither enter local custody
nor bounce between client and spool eviction (§6.2's churn guards close the loop from the spool
side).

### 9.3 The invalid set (load-bearing)

A pulled blob that fails hash check, AEAD, signature, or the frame-set rule (§4.4) goes into a
bounded per-spool **invalid set** keyed by blobId: never re-pulled, never counted as held, never
re-pushed. Without it, one garbage blob at a spool (spools are untrusted storage) folds into the
spool's digest but never the client's — permanent divergence, infinite re-pull. With it, the
divergence is accounted and inert.

### 9.4 The mesh bridge

A blob that passes §4.4 re-enters delivery wrapped in a fresh mesh envelope with a full hop budget
(the custody re-serve shape: same `signed`/`sig`, ttl reset, hops 0), flowing through the ordinary
inbound path — flood-dedup, idempotent persistence, roster vetting, custody capture all unchanged.
Symmetrically, frames the member custodies for a scope (from the mesh or its own sends) are sealed
and pushed. One Internet-connected member thus bridges a whole radio island in both directions with
zero new delivery semantics.

### 9.5 Attachments — the fetch and refill procedure

Both working sets are derived from what the member already holds; nothing about attachments is
persisted by this plane:

- **want(scope)** — live custodied frames that pass §4.4 for the scope, whose cleartext
  `attachmentHash` is set, and whose bytes are absent locally.
- **have(scope)** — the same, bytes present.

Per heal round, per (spool, scope), for a small bounded number of attachments at a time:

1. `ahave` for the `aid` (§4.5). `dead` ⇒ give up on this spool for this attachment; `total = 0` ⇒
   nothing to pull, but the push half below may still apply.
2. `aget` the indices the bitmap marks absent locally, in `maxAget` batches. Open each chunk, check
   its header against what was asked for, buffer it.
3. When complete, verify `SHA-256(reassembled) = aHash` and hand the bytes to the ordinary local blob
   store, so screening, the message row, and the UI all update exactly as they do after a radio pull.
4. Push half: `aput` the indices the bitmap marks missing at the spool, bounded by the byte budget and
   by §9.2's dead-on-arrival guard applied to the newest frame that references the attachment. A
   retiring scope (§3.1/§3.3) is pulled but never refilled, mirroring frames.

Any failure — AEAD, a header that does not match the request, a final hash mismatch — **quarantines
the `aid`** per (spool, scope), the §9.3 invalid-set rule extended. The argument is identical: a spool
is untrusted storage, and without an accounted invalid set a single bad chunk is re-fetched on every
round forever.

Partial downloads live in memory and are **not** persisted. That is a deliberate cost: a process
death mid-transfer re-fetches that attachment. It buys the plane's no-new-persistence property (the
blob-id set is derived, never stored), and the spool-side bitmap already makes the *upload* half
resume for free. Persisting partial downloads is registered in §11.

One honest bound: the want set comes from **custody**, whose TTL (24 h on the mesh) is shorter than a
scope's (48 h). A frame that has aged out of local custody stops driving an attachment fetch even
though the spool may still hold the bytes. This matches the mesh's own carrier behaviour and keeps the
derivation seam small; it is not a convergence problem, only a missed opportunity at the tail.

## 10. Security and privacy claims (honest) [Both]

What a spool (or its disk's taker) observes:

- opaque scope ids and their activity rhythm — sizes, timings, a long-lived pseudonymous channel
  per conversation era (§3's rotations bound the eras);
- subscriber **IPs** and connection patterns — "these k IPs touch the same scope" is an edge
  between IPs, the honest residual leak. **Tor removes it** at battery/latency cost (client SOCKS
  routing; a later milestone ships the toggle). Scope multiplexing over one WSS already blurs
  per-scope timing somewhat; padding/cover traffic is future study (§11).
- for attachments, a **stronger size signal than frames give**: that a scope holds an object of
  roughly `total × 48 KiB`, when it was uploaded, and how many subscribers fetched it. Chunking
  quantises this to 48 KiB, and the keyed `aid` (§4.5) keeps the attachment unlinkable across scopes
  and un-confirmable against a candidate hash — but "this conversation exchanged a ~4 MB image at
  09:14" is visible in a way "this conversation exchanged some frames" is not. That is the price of
  carrying bytes at all, and it is why the byte quota is per scope.

What it cannot do:

- read content, rosters, or delivery facts, or map a scopeId to node ids (the KDFs are keyed by
  member secrets; receipts are indistinguishable sealed frames; the §4.3 keyed nonce denies the
  known-plaintext confirmation oracle);
- forge or tamper: AEAD outside, the mesh's Ed25519 frame signature inside, verified byte-exact
  after unsealing;
- replay usefully: content-addressed ids, idempotent delivery, the dead-on-arrival TTL;
- withhold *undetectably* when the scope is multi-homed: members see spool divergence via digests.

Compromise horizons, stated exactly:

- **Content**: inner v2 ratchet ciphertext with its own epoch-granularity forward secrecy —
  seize-the-disk-then-compromise-a-key-later yields no message bodies beyond what the inner
  schemes' own claims already concede. Untouched by this layer.
- **Routing metadata** (the `RelayEnvelope`: ids, sender/recipient/roster, send times, types): the
  scope-static outer seal (§4.2) gives it a **scope-generation** horizon — a member-device
  compromise plus a harvested spool disk reveals that era's envelope metadata, including frames the
  device itself no longer holds. A member-device compromise reveals the conversation and roster
  anyway; the marginal exposure is the metadata of aged-out frames. `sealv = 2` (§11) is the
  reserved upgrade if that margin ever warrants epoch keys.
- Identity-file-only compromise reaches no scope key (all scope inputs are database-tier session
  secrets, not identity keys).
- Device wipe: scopes are unrecoverable (§3.1) — continuity is a property of live sessions, never
  of any server.

Insider (member) threats: spam bounded by the scope's own quotas; key leakage is E2E's universal
caveat; a departed group member watches old-scope ciphertext flow only until the departure re-mint
rotates the id (§3.3), bounded by the drain window. Scope ids and (in v1) SUB/PUSH are otherwise
unauthenticated toward the spool: anyone who *learns* a scopeId can subscribe to its ciphertext and
burn its quota — accepted in v1 (ids are unguessable KDF outputs; multi-homing and rotation bound
the damage; a TOFU scope-auth extension is registered in §11).

## 11. Extension register [Both]

Deliberately open, additively reachable, in no order: a cleartext `sentAt` hint on PUSH (true-age
eviction at the cost of upload-time metadata — revisit with soak data); UnifiedPush wake-ups
(per-scope random topics to dodge endpoint linkability); a QUIC binding with its own framing;
storage watermark trim; padding/cover traffic; per-conversation opt-out UX; **scope auth** (a TOFU
`HKDF(scopeRoot, …)` credential closing the leaked-scopeId subscribe/flood hole §10 accepts);
time-based group root re-mint (periodic metadata-PFS and spool unlinkability using the existing §3.2
machinery); **`sealv = 2`** — the epoch-keyed outer seal (the ratchet `exportEpochSeal` surfaces are
reserved for it); client→spool `digest` (the record is direction-agnostic already); a `CAP_SPOOL`
capability bit if client UX ever needs a peer-support signal; **persisted partial attachment
downloads** (§9.5 keeps them in memory, so a process death re-fetches). *(Two entries left this list
by shipping: the any-member v1 mint is now §3.2's base rule, and attachments are §4.5/§6.5/§7.3/§9.5.)*

## 12. Constants [Both]

| Constant | Value | Tied to |
|---|---|---|
| record-layer version | 1 | HELLO negotiation |
| scopeId / blobId / roots / keys | 32 B | HKDF / SHA-256 native width |
| seal nonce / tag | 12 B / 128 bit | AES-256-GCM profile (§2) |
| `sealv` | `0x01` | blob leading byte; `2` reserved (§11) |
| digest | FNV-1a-64 XOR fold, empty = 0, 8 B BE byte string | §6.3; the mesh custody digest's shape |
| default scope `ttlMs` | 48 h | 2× mesh custody TTL = the rotation drain window; longer retention stores frames the inner ratchet may no longer decrypt |
| default `maxFrames` | 400 | 2× the mesh's 200-per-sender custody bucket |
| default `maxBlob` | 64 KiB | comfortably above any mesh frame |
| tombstone TTL | = scope `ttlMs` | §6.2 |
| suggested tombstone count bound | max(2 × `maxFrames`, 1024) per scope, oldest-first drop | §6.2 — caps the eviction-cycling growth vector |
| rotation drain (old scope subscribed) | 48 h | DM prev-root TTL mirror (§3.1, §3.3) |
| `mintGrace` (non-preferred minter waits) | 6 h | §3.2 — one waking day's slack before a second lineage appears |
| `maxRootVersion` / `maxRootVersionJump` | 16 / 8 | §3.2 adoption bound; the roster caps at 8, so legitimate versions never approach it |
| PoW default / window | 20 bits / ±1 day UTC | §8 |
| suggested `maxScopes` / `maxPull` | 64 / 64 | HELLO-advertised, spool-tunable |
| `aChunkBytes` | 49152 (48 KiB) | §4.5 — **structural**, not tunable: it is what makes a chunk's position derivable without a manifest |
| attachment `sealv` | `0x03` | §4.5; `0x01` is the frame seal and `0x02` stays reserved for the epoch-keyed one |
| sealed chunk size | 49221 B (`1 + 12 + 40 + 49152 + 16`) | §4.5 — sized to stay inside the 64 KiB `maxBlob` |
| max attachment / `total` | 8 MiB / ≤ 171 chunks | the app's own attachment cap; bounds every allocation sized by a peer-supplied `total` |
| default per-scope `maxAttachBytes` | 16 MiB | §6.5 — 2× one maximal attachment, so a scope holds a little history without becoming storage |
| suggested `maxAget` | 32 | ≈1.5 MiB per batch; HELLO-advertised, spool-tunable |

Defaults marked "default"/"suggested" are operator- or config-tunable; the structural constants
(widths, `sealv`, the digest function) are the protocol.

## 13. Test vectors [Both]

Pinned verbatim by `ScopeVectorTest` and `SpoolRecordsTest` (regenerate only with an intended
scheme change, updating both together). Byte-array fixtures follow `fixture(n, seed)[i] =
(7·i + seed) mod 256`; all values lowercase hex.

Inputs:

```
nodeIdA      = "aaaaabbbbbcccccdddddeeeeef"          nodeIdB = "zzzzzyyyyyxxxxxwwwwwvvvvvu"
groupId      = "g-00112233445566778899aabb"
pairwiseRoot = fixture(32, 1)  = 01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3da
groupRoot    = fixture(32, 2)  = 020910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4db
sig          = fixture(64, 4)  = 040b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dd
                                 e4ebf2f900070e151c232a31383f464d545b626970777e858c939aa1a8afb6bd
signed       = fixture(40, 5)  = 050c131a21282f363d444b525960676e757c838a91989fa6adb4bbc2c9d0d7de
                                 e5ecf3fa01080f16
```

Derivations (§3):

```
dmScopeId       = aeced0ad65f9e416c3a4d6015ff6bd61849df5bcaf89b5a6f19aa9d654e7a8b2
dmSealKey       = fe7c47b82425bb4dbbd224ca192bf81131bdae07299380728b6eb3721d82eac7
dmNonceKey      = 1704dfd72d5f529e8491784d17ed69e10fa7cbc2236667c23c77638dfd645dbe
groupScopeId v1 = c5c544c7c4cb09c72557075ea90adc26b9b8bfa2676d227ef41a581f8c30f53d
groupScopeId v2 = 8ea040bce4597fb6d08dabd50ddc2342fb79775134f7b81de97125847589fef1   // rotation pin
groupSealKey v1 = b7a89432dc831b4035b8bb4709932e696cfe635b26ace09b448b4c600748eb4d
groupNonceKey v1= a35ac015c70ba45bdbb88b23d48d7ea60933fd311605daf7f75f1540c15f28ce
```

Seal (§4; keys/scopeId = the DM values above; deterministic — sealing twice is byte-identical):

```
blob   = 01e6844e8145bbc9581e53f9b0c4019dba5968bb7216685432e7412e1e9a56c8136af0829fdea4c8613b18b7
         df038f08613fa34fd474f93da53da1a05c0350bf9b681290b880083a5593839b08b2496f79d7ddcaefc40943
         d1c0757ce594a12326d551e07a62528d62744ef30b9b24bea8a58856a6545436d099519a1706e1308b3ffe432e
blobId = 8e5c2b6d8be66bb1204b644ebcc62f923bb27b659ecffb9344d35f7eb930d9c2
```

Attachment seal (§4.5; same DM keys/scopeId; `aHash = fixture(32, 6)`, a **one-chunk** attachment —
`total = 1` makes chunk 0 the final chunk, the one that may be short, so the vector stays quotable
instead of carrying 48 KiB):

```
aHash  = fixture(32, 6)  = 060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8df
chunk0 = fixture(48, 7)  = 070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0
                           e7eef5fc030a11181f262d343b424950
aid    = 4bb7dde9341d80ff87ea9f6709699f68f859ff9268fac97aa809e0f8c8d48bb1
achunk = 036f83df42c1392e66eb87ae260f86d05080e007e9d59c6502eca05fd814664927416d29899375df3405d156
         4e7122a16eb5a095169dfa56d078b24fcae72a6c2c1089f75ffdd9c427706abc7dba44a5cb2847c95128a2c1
         d5360cb13980a80f2800bd718b7686b39cce91674728902979241dd815
cid    = e21f04fd3f95cade1a9a7424f6ab9bb45a9e185c836524c9ae7920a8fdfe0c27
```

Digest (§6.3):

```
digest(∅) = 0000000000000000
digest({fixture(32,11), fixture(32,12), fixture(32,13)}) = 834b13d8dc060ce5
```

PoW (§8):

```
scopeId = fixture(32, 9) = 0910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe2
day = 20680, powBits = 8  →  smallest n = 8
SHA-256(input) = 00b776b91276563998bb57f8f3f73a05e0d8afcd3dce8a2583d6d466aadb620e
```

Records (§7; whole-record CBOR, field values as in `SpoolRecordsTest`):

```
helloSpool   = a561746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a00010000696d6178
               5265636f72641a00020000696d617853636f7065731840676d617850756c6c18406c6d61784672616d65
               734361701903e8686d617854746c4d731a240c840067706f774269747314
helloClient  = a261746568656c6c6f617601
sub          = a3617463737562617101647375627381a36573636f7065582001080f161d242b323940474e555c636a71
               787f868d949ba2a9b0b7bec5ccd3da66626f756e6473a3696d61784672616d65731901906574746c4d73
               1a0a4cb800676d6178426c6f621a0001000063706f77a2616e182a61641950c8
digest       = a66174666469676573746573636f7065582001080f161d242b323940474e555c636a71787f868d949ba2
               a9b0b7bec5ccd3da6664696765737448020910171e252c3365636f756e74036466756c6cf466626f756e
               6473a3696d61784672616d65731901906574746c4d731a0a4cb800676d6178426c6f621a00010000
listRequest  = a36174646c6973746171026573636f7065582001080f161d242b323940474e555c636a71787f868d949b
               a2a9b0b7bec5ccd3da
listResponse = a56174646c6973746171026573636f7065582001080f161d242b323940474e555c636a71787f868d949b
               a2a9b0b7bec5ccd3da67626c6f62496473825820030a11181f262d343b424950575e656c737a81888f96
               9da4abb2b9c0c7ced5dc5820040b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8cf
               d6dd6a746f6d6273746f6e6573815820050c131a21282f363d444b525960676e757c838a91989fa6adb4
               bbc2c9d0d7de
pull         = a461746470756c6c6171036573636f7065582001080f161d242b323940474e555c636a71787f868d949b
               a2a9b0b7bec5ccd3da67626c6f62496473815820030a11181f262d343b424950575e656c737a81888f96
               9da4abb2b9c0c7ced5dc
blob         = a4617464626c6f626573636f7065582001080f161d242b323940474e555c636a71787f868d949ba2a9b0
               b7bec5ccd3da66626c6f6249645820030a11181f262d343b424950575e656c737a81888f969da4abb2b9
               c0c7ced5dc64646174615830060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1
               d8dfe6edf4fb020910171e252c333a41484f
push         = a6617464707573686171046573636f7065582001080f161d242b323940474e555c636a71787f868d949b
               a2a9b0b7bec5ccd3da66626c6f6249645820030a11181f262d343b424950575e656c737a81888f969da4
               abb2b9c0c7ced5dc64646174615830060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bc
               c3cad1d8dfe6edf4fb020910171e252c333a41484f63706f77a2616e182a61641950c8
event        = a46174656576656e746573636f7065582001080f161d242b323940474e555c636a71787f868d949ba2a9
               b0b7bec5ccd3da66626c6f6249645820030a11181f262d343b424950575e656c737a81888f969da4abb2
               b9c0c7ced5dc64646174615830060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3ca
               d1d8dfe6edf4fb020910171e252c333a41484f
okBare       = a26174626f6b617103
okMissing    = a36174626f6b617103676d697373696e67815820040b121920272e353c434a51585f666d747b82899097
               9ea5acb3bac1c8cfd6dd
errScoped    = a461746365727264636f64656a746f6d6273746f6e65646171046573636f7065582001080f161d242b32
               3940474e555c636a71787f868d949ba2a9b0b7bec5ccd3da
errRate      = a461746365727264636f64656472617465636d736769736c6f7720646f776e6772657472794d73197530
```

Attachment records (§7.3; `aid = fixture(32, 7)`, `cid = fixture(32, 8)`, `data = fixture(48, 6)`):

```
helloSpoolAttach = a561746568656c6c6f617601636d696e01666c696d697473a9676d6178426c6f621a00010000696d
                   61785265636f72641a00020000696d617853636f7065731840676d617850756c6c18406c6d617846
                   72616d65734361701903e8686d617854746c4d731a240c84006e6d61784174746163684279746573
                   1a01000000696d6178414368756e6b19c045676d617841676574182067706f774269747314
ahave            = a461746561686176656171056573636f7065582001080f161d242b323940474e555c636a71787f86
                   8d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d545b626970777e858c939a
                   a1a8afb6bdc4cbd2d9e0
ahas             = a6617464616861736171056573636f7065582001080f161d242b323940474e555c636a71787f868d
                   949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d545b626970777e858c939aa1
                   a8afb6bdc4cbd2d9e065746f74616c0364626974734109
ahasDead         = a7617464616861736171056573636f7065582001080f161d242b323940474e555c636a71787f868d
                   949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d545b626970777e858c939aa1
                   a8afb6bdc4cbd2d9e065746f74616c006462697473406464656164f5
aget             = a6617464616765746171066573636f7065582001080f161d242b323940474e555c636a71787f868d
                   949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d545b626970777e858c939aa1
                   a8afb6bdc4cbd2d9e06466726f6d00616e02
achunk           = a7617466616368756e6b6573636f7065582001080f161d242b323940474e555c636a71787f868d94
                   9ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d545b626970777e858c939aa1a8
                   afb6bdc4cbd2d9e0636964780165746f74616c03636369645820080f161d242b323940474e555c63
                   6a71787f868d949ba2a9b0b7bec5ccd3dae164646174615830060d141b222930373e454c535a6168
                   6f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f
aput             = a9617464617075746171076573636f7065582001080f161d242b323940474e555c636a71787f868d
                   949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d545b626970777e858c939aa1
                   a8afb6bdc4cbd2d9e0636964780165746f74616c03636369645820080f161d242b323940474e555c
                   636a71787f868d949ba2a9b0b7bec5ccd3dae164646174615830060d141b222930373e454c535a61
                   686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f63706f77a261
                   6e182a61641950c8
```
