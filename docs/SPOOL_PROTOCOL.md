# The spool protocol

**Scoped, blinded store-and-forward relays for Knit's Internet plane.**

|                    |                                                                                                                                                                         |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Protocol version   | 1                                                                                                                                                                       |
| Status             | Normative. Implemented and shipping (see Appendix A)                                                                                                                    |
| This revision      | 2026-08-30                                                                                                                                                              |
| Decision record    | ADR 019 (+ M4/M5/M6 amendments, ADR 020, ADR 021, ADR 042, ADR 062)                                                                                                              |
| Client reference   | `mesh/crypto/scope/` (`ScopeCrypto`, `SpoolPow`), `mesh/spool/` (`SpoolRecords`, `ScopeFrames`, `ScopeAttachments`, `GroupRootPolicy`, `ScopeRegistry`, `ScopeSync`)    |
| Spool reference    | [`knit-spool`](https://github.com/getknit/knit-spool) (AGPL-3.0, separate repository) + its conformance suite                                                           |
| Executable anchors | `ScopeCryptoTest`, `ScopeVectorTest`, `SpoolPowTest`, `SpoolRecordsTest`, `ScopeAttachmentsTest`, `ScopeFramesTest`, `GroupRootPolicyTest`, `AttachmentDeferPolicyTest` |

Both implementations implement *this file*, not each other. §13's vectors are the anchor tests'
pinned
constants, verbatim; change one and you change the other in the same commit.

## 0. How to read this document

### 0.1 Normative language

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY carry their RFC 2119 / RFC 8174 meanings
when
capitalised. Lowercase uses of those words are prose.

### 0.2 Requirement identifiers

Every normative statement carries a stable identifier so a conformance suite, a bug report or a code
comment can cite one line rather than a paragraph. Identifiers are append-only: a retired
requirement
keeps its number and is marked withdrawn.

| Prefix | Applies to                       |
|--------|----------------------------------|
| `S-`   | A spool implementation           |
| `C-`   | A member (client) implementation |
| `B-`   | Both                             |

### 0.3 Rationale is not normative

Blocks introduced by **Why.** explain a decision. They bind nobody. Everything else in a numbered
section is either a requirement or a definition.

### 0.4 Audience tags

Section headings carry `[Spool]`, `[Client]` or `[Both]`. A relay implementer needs no crypto at
all:
a spool never decrypts anything, so the entire key schedule is `[Client]`.

## 1. Overview [Both]

### 1.1 Purpose

Knit is an offline mesh messenger, and radio proximity is the product. This protocol extends
*existing*
conversations across the Internet when no radio path exists. It is a continuity layer for contacts
already made over the mesh, by QR, or by exchanging a **contact card** out of band (§3.5 — two people
who hold each other's card can meet at a spool before any session exists). It is not a discovery
network, not an account system and not a server in the sense that word usually implies: nothing here
finds a stranger, and nothing here is registered.

### 1.2 The moving parts

A **scope** is one conversation's Internet presence, either a DM pair or a group. Members derive its
id
and keys from secrets they already share (§3). Nothing about a scope is registered anywhere.

A **frame** is the mesh's frozen custody unit, `signed` + `sig` (ADR 005). A **blob** is one frame,
AEAD-encrypted under scope keys (§4) and addressed by the hash of its own ciphertext.

A **spool** is a small store-and-forward daemon (yarn waits on a spool). Per scope it holds a
bounded
set of blobs plus a digest over that set (§6), streams new arrivals to connected subscribers, and
heals
divergence by digest anti-entropy (§9). This is the delay-tolerant custody model the mesh already
runs,
scoped per conversation and blinded.

An **attachment** is the image bytes a frame references rather than carries: a second object class
on
the same scope, sealed and chunked (§4.5), stored under a per-scope byte quota (§6.5), and fetched
on
demand by whoever holds the referencing frame (§9.5). It sits outside the frame digest on purpose,
for
the reason §6.5 gives.

Two structural facts follow from the shape:

- Spools never talk to each other. A scope names several spools; every member pushes to and pulls
  from
  all of them, so **the client union is the federation**. A fresh or wiped spool is refilled by any
  one
  member, and no spool is load-bearing.
- A frame pulled from a spool re-enters the local mesh through the ordinary re-serve path (§9.4), so
  one
  Internet-connected member bridges a whole radio island in both directions with no new delivery
  semantics.

### 1.3 The trade, stated as loudly as the ratchet docs state theirs

A spool is a custody peer that can never read what it custodies. It learns opaque scope ids, blob
sizes
and timings, and subscriber IPs. It never learns node ids, content, rosters or delivery facts.
Content
moderation is impossible by construction, which is the privacy story and the abuse story at once,
and
§6.4's quotas and proof of work are the entire toolkit that remains. Spools are cattle: losing one
loses
nothing a member cannot refill.

### 1.4 Non-goals in v1

- Carrying the plaintext Nearby broadcast room (proximity semantic, spam surface).
- Contact discovery, or any server-side identity. (A pair that already holds each other's identity —
  from a card exchanged out of band — may rendezvous, §3.5; a spool still learns no identity from it.)
- Spool-to-spool federation.
- Resistance to a global passive network observer. Tor optionally covers the IP edge; padding is
  future
  study (§11).

## 2. Conventions and encodings [Both]

| ID        | Requirement                                                                                                                                      |
|-----------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| **B-2-1** | CBOR uses definite-length encoding only.                                                                                                         |
| **B-2-2** | Unknown map keys MUST be ignored on decode.                                                                                                      |
| **B-2-3** | Fields equal to their declared default MUST be omitted on encode.                                                                                |
| **B-2-4** | Map keys are the short field names given in §7.                                                                                                  |
| **B-2-5** | Every `ByteArray`-valued field, including byte-array list elements, MUST encode as a CBOR byte string (major type 2), never as an integer array. |
| **B-2-6** | Scope ids and blob ids are raw 32-byte strings on the wire. Lowercase hex is the display form in logs, diagnostics and this document.            |
| **B-2-7** | Digests are raw 8-byte big-endian byte strings, never CBOR integers.                                                                             |

Notation used in derivations:

- `u32be(n)` and `u64be(n)` are unsigned big-endian, 4 and 8 bytes.
- `‖` is concatenation; `A[i…j)` is a half-open byte range.
- The KDF is HKDF-SHA256 (RFC 5869) with salt = 32 zero bytes and `L` as stated per derivation. Info
  strings are the ASCII label concatenated with a context: `label ‖ context`.
- The AEAD is AES-256-GCM with a 12-byte nonce and a 128-bit tag.
- The hash is SHA-256 everywhere: blob ids, the seal's synthetic nonce input, proof of work.

| ID        | Requirement                                                                                                                                                                                                                                    |
|-----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **B-2-8** | Labels live under `knit/scope/v1/…` (key plane) and `knit/spool/v1/…` (transport plane). These namespaces are disjoint from the app's `knit/dm/v2/…` and `knit/group/v1/…`, and no derivation here may cross-derive with the message ratchets. |
| **B-2-9** | Context strings use the house pipe separator, ASCII `0x7C`.                                                                                                                                                                                    |

> **Why B-2-7.** A high-bit digest carried as an integer drags every implementation into
> signed-integer
> encoding questions. A byte string has one representation.
>
> **Why B-2-9.** `|` cannot occur in a 26-character base32 node id or in a `g-`-prefixed hex group
> id, so
> delimited fields cannot alias each other.

## 3. Scopes and keys [Client]

### 3.1 DM scopes

The input is the pair's **pairwiseRoot**: the stable per-session export secret both sides derive
from the
DM ratchet (`docs/FORWARD_SECRECY_RATCHET.md` §8, `HKDF(sessionRoot, "knit/dm/v2/export/root")`).
With
`idLow`/`idHigh` the two node ids sorted lexicographically:

```
ctx      = UTF8(idLow ‖ "|" ‖ idHigh ‖ "|")
scopeId  = HKDF(ikm = pairwiseRoot, info = "knit/scope/v1/dm/id"  ‖ ctx, L = 32)
sealOkm  = HKDF(ikm = pairwiseRoot, info = "knit/scope/v1/seal"   ‖ ctx, L = 64)
sealKey  = sealOkm[0…32)        nonceKey = sealOkm[32…64)
```

| ID          | Requirement                                                                                                                                  |
|-------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.1-1** | A DM scope follows the *active session*. A session replacement yields a new pairwiseRoot and therefore a new scope.                          |
| **C-3.1-2** | The retiring scope stays derivable from the retiring root for the ratchet's 48 h drain window, during which a member MAY keep it subscribed. |
| **C-3.1-3** | A retiring scope MUST be drained but never refilled: a member MUST NOT seal fresh frames into it.                                            |

> **Why the ids appear in the context.** Defence in depth only. The ikm is already pair-secret, so a
> spool holding both public node ids still cannot compute the scopeId.
>
> **On losing session state.** A device that loses its own session state cannot recover its scopes.
> Continuity dies with the session and re-establishment takes an out-of-band re-meet, over the mesh
> or by
> QR. That is coherent with a continuity layer for existing contacts, and it is a privacy property:
> scope
> ids are unlinkable across session eras.

### 3.2 The shared group root

The group sender-key scheme has no shared secret; `docs/GROUP_FORWARD_SECRECY.md` §8 defers exactly
this
object to here. The **group root** supplies it:

```
GroupRoot { root: 32 random bytes, version: Int (from 1), minter: nodeId }
```

Ordering, used throughout this section: `(version, minter)` compares `version` numerically, then
`minter` lexicographically.

#### Minting

| ID          | Requirement                                                                                                                                                             |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.2-1** | Any current member MAY mint `version = 1`, and only when it holds no root, has the Internet plane enabled for the group, and the group is fully ratchet-capable (§3.3). |
| **C-3.2-2** | The **preferred minter** is the creator if still a member, otherwise the smallest remaining node id. The preferred minter mints immediately.                            |
| **C-3.2-3** | Any other eligible member MUST wait `mintGrace` (§12) from when it first became eligible.                                                                               |
| **C-3.2-4** | The wait MUST be persistent state, not a process timer. A device that restarts hourly must not restart the clock.                                                       |

Competing v1 mints resolve by `(version, minter)`. The losing lineage's blobs are orphaned at spools
and
age out on `ttlMs`; members refill the winning scope through the ordinary §9.1 push half.

> **Why not creator-only.** The draft rule was creator-only, and its accepted gap was that a group
> whose
> creator never opts in gets no scope at all. The grace closes that gap while still damping
> concurrent
> mints, and it is the same mechanism that unfreezes a departure re-mint whose re-minter never comes
> back.

#### Distribution

Distribution is gossip on the existing seed channel. The root rides as additive fields of the
group-key
control payload (`GroupKeyPayload.gr`, `CTL_GROUP_KEY`, pairwise-sealed ctl DMs).

| ID          | Requirement                                                                                                                       |
|-------------|-----------------------------------------------------------------------------------------------------------------------------------|
| **C-3.2-5** | Every seed send and key-request response from a member that holds a root MUST carry the newest root that member holds.            |
| **C-3.2-6** | A member receiving a distribution that carries a root older than its own, or none while it holds one, SHOULD answer with its own. |
| **C-3.2-7** | A member SHOULD NOT echo a root straight back to the member it just learned it from.                                              |

> **Why C-3.2-6.** The root has no acknowledgment, so a stale gossip is the only evidence that an
> earlier
> distribution to that member was lost. Without the correction, a sender that believes it already
> delivered will never retry. The answer is self-terminating: once the lagging member adopts, its
> next
> distribution carries the same `(version, minter)` and the branch stops.
>
> **Why gossip at all.** The seed outbox, key-request and re-send machinery is already the delivery
> system, so root healing costs no new mechanism, and a wiped minter passively recovers the current
> root
> from the first seed DM it receives.

#### Adoption

| ID           | Requirement                                                                                                 |
|--------------|-------------------------------------------------------------------------------------------------------------|
| **C-3.2-8**  | A member MUST adopt a carried root only when its `(version, minter)` is strictly greater than the held one. |
| **C-3.2-9**  | The carrying DM's sender MUST be in the pinned founding roster and not departed.                            |
| **C-3.2-10** | `minter` MUST itself be in the pinned founding roster.                                                      |
| **C-3.2-11** | `version` MUST satisfy `version ≤ held.version + maxRootVersionJump` and `version ≤ maxRootVersion` (§12).  |
| **C-3.2-12** | A root MUST NOT be v1-wrapped.                                                                              |
| **C-3.2-13** | Adoption MUST be idempotent, and MUST NOT be rate-limited.                                                  |

> **Why C-3.2-10.** Otherwise any member wins every tie forever by naming a lexicographically
> maximal
> minter id belonging to nobody.
>
> **Why C-3.2-11.** Otherwise one grief-mint at `2³¹ − 1` puts every future legitimate re-mint out
> of
> reach and freezes the scope permanently. A legitimate version never exceeds the founding roster
> size:
> one mint plus at most `size − 1` departures, and rosters never grow. §12's ceiling of 16 is double
> the
> model's maximum. The residual, stated honestly: a member willing to burn the version space before
> departing can still freeze rotation. That is the same insider tier as grief-rotation below, and it
> leaves the mesh path untouched.
>
> **Why C-3.2-13.** Refusing a strictly greater root strands the device on a dead lineage with no
> way
> back, since it would keep gossiping a root everyone else ignores. Outbound chatter is bounded on
> the
> *send* side instead, by the per-(group, member) seed-send floor, which is the safe place for the
> bound.
> Authenticity comes from the carrying v2 session plus the frame signature, and a root is never
> v1-wrapped for the same harvest argument the seed rule uses.

#### Re-mint on departure

| ID           | Requirement                                                                                                                                                   |
|--------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.2-14** | On processing a signed `groupleave`, a member MUST record that a re-mint is due, in the same transaction as the leave-rekey send-chain reset.                 |
| **C-3.2-15** | The re-mint follows C-3.2-2/3/4: the deterministic re-minter mints `(fresh 32 bytes, version + 1)` immediately, any other remaining member after `mintGrace`. |

> **Why split the record from the mint.** That split is what makes rotation crash-safe, and the
> grace is
> what keeps a re-minter who is offline, or who has the plane switched off, from freezing rotation
> for
> everyone else. The leave-rekey already fans seed ctl DMs to every remaining member, so the new
> root
> rides them for free.

#### Convergence

Divergent departure views, and now several members reaching the end of their mint grace at once, can
transiently mint competing same-version roots. `(version, minter)` resolves them deterministically
and
the next processed departure mints strictly higher, so lineages collapse. A malicious member can
grief-rotate, bounded by C-3.2-11. That is the insider spam tier, the same posture as "a member can
spam
its own scope" (§6.4).

### 3.3 Group scopes

```
ctx      = UTF8(groupId ‖ "|") ‖ u32be(rootVersion)
scopeId  = HKDF(ikm = groupRoot, info = "knit/scope/v1/group/id" ‖ ctx, L = 32)
sealOkm  = HKDF(ikm = groupRoot, info = "knit/scope/v1/seal"     ‖ ctx, L = 64)  → sealKey ‖ nonceKey
```

| ID          | Requirement                                                                                                           |
|-------------|-----------------------------------------------------------------------------------------------------------------------|
| **C-3.3-1** | There is no separate scope-epoch field. `rootVersion` **is** the epoch.                                               |
| **C-3.3-2** | A member MAY keep the rotated-away scope subscribed for the 48 h drain window, under C-3.1-3's drain-not-refill rule. |
| **C-3.3-3** | Blobs MUST NOT be migrated between scope generations.                                                                 |
| **C-3.3-4** | A group that is not fully ratchet-capable has no root channel and therefore MUST have no scope.                       |

> **Why rotate both together.** A departure re-mint rotates root and version as one, so scopeId and
> seal
> keys rotate as one. A removed member knows the old id and could otherwise keep watching ciphertext
> flow: undecryptable, but observable. To a spool, the rotated scope is an unrelated fresh id.
>
> **Why no migration.** The new scope refills from members' custody by anti-entropy, re-sealed under
> the
> new keys with new blob ids. A migrated blob would link the generations, which is precisely what
> the
> rotation exists to prevent.

### 3.4 Key summary

| Key                                    | Derived from            | Rotates when                        | Held by                                          |
|----------------------------------------|-------------------------|-------------------------------------|--------------------------------------------------|
| DM `scopeId`, `sealKey`, `nonceKey`    | pairwiseRoot            | session replacement (wipe/reset)    | the two members                                  |
| group `scopeId`, `sealKey`, `nonceKey` | groupRoot + rootVersion | departure re-mint                   | current members, plus departed until the re-mint |
| groupRoot                              | minted at random        | never in place; replaced by re-mint | same                                             |
| pair `scopeId`, `sealKey`, `nonceKey`  | pairSecret (§3.5)       | never (identity-bound); dropped     | the two members                                  |

### 3.5 Pair scopes

A DM scope needs a confirmed session, a session needs the peer's prekey, and the prekey travels only on
a `profile` frame inside a scope the pair already shares — so two people who have only ever exchanged a
**contact card** (their identity bundle as a signed link, `docs/CONTACT_CARD.md`) have no scope to meet
at. The **pair scope** is that meeting point: a scope both can derive from the two identities alone.

```
pairSecret = X25519(IK_self, IK_peer)                    // the identity DH keys — the hpkePub half of each bundle
ctx        = UTF8(idLow ‖ "|" ‖ idHigh ‖ "|")             // the §3.1 context, verbatim
scopeId    = HKDF(ikm = pairSecret, info = "knit/scope/v1/pair/id" ‖ ctx, L = 32)
sealOkm    = HKDF(ikm = pairSecret, info = "knit/scope/v1/seal"    ‖ ctx, L = 64)  → sealKey ‖ nonceKey
```

| ID          | Requirement                                                                                                                                                                                  |
|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.5-1** | A member MUST subscribe a pair scope only while an intro with that peer is pending, or for a bounded grace (`pairGrace`, §12) after its own session with the peer confirms — then drop it.       |
| **C-3.5-2** | A pair scope carries exactly the §4.4 DM frame set, with the pending peer as the counterpart. It has no rule of its own.                                                                      |
| **C-3.5-3** | A pair scope exists to establish the session. Once the DM scope (§3.1) exists it supersedes the pair scope; a member MUST NOT rely on a pair scope for anything else.                        |
| **C-3.5-4** | A member SHOULD hold at most `maxPairScopes` (§12) pair scopes at once.                                                                                                                       |
| **C-3.5-5** | The pair secret is a static-static agreement used for nothing else; every derivation from it is labeled under `knit/scope/v1/…` (B-2-8) and never mixes with the ratchet's `knit/dm/v2/…`. |

> **Why the identity keys.** They are the one input both parties hold *before* a session: the card is
> self-certifying (the node id is the hash of the bundle it carries), so a pinned card is exactly the
> same key the peer's own `profile` frame would deliver. X3DH has no identity-identity term and HPKE
> pairs the identity key with an ephemeral, so the static-static agreement is fresh material, and the
> HKDF labels keep it disjoint from both.
>
> **Why the grace.** The responder's session confirms on the initiator's first frame; the initiator's
> only on the responder's answer. The answer must reach a peer that holds no DM scope yet, and the pair
> scope is the only scope it holds — so the pair scope stays pushed into until the answer had time to
> land (the spool's own retention, 48 h), then goes away.
>
> **What it costs, stated honestly.** §10.3's "identity file only → no scope key" narrows to
> *conversation* scopes: a stolen identity file plus the peer's public bundle yields this one scope's
> id and outer seal, exposing the routing metadata of bootstrap-era frames (profiles and the first sealed
> ctl frames) while the scope is subscribed — never content, which is inner-sealed, and never a DM or
> group scope. And unlike a DM scope the id is stable per pair across eras, so a spool could link two
> bootstraps of the same pair; the subscription window (pending + grace) is what bounds that. A spool, a
> node-id holder and a card holder still cannot compute it.
>
> **Why no new frame or ctl.** The intro is an ordinary sealed `CTL_PROFILE` DM (ADR 020): its X3DH init
> rides every copy until confirmed, every deployed build reads it, and the pair scope carries it under
> the DM rule unchanged. The whole feature is a derivation and a driver; no spool changes.

## 4. Sealing [Client]

### 4.1 The sealed plaintext

```
pt = sig(64) ‖ signed
```

`signed` is the canonical `RelayEnvelope` CBOR, byte-for-byte what the mesh floods (ADR 005). No
wrapper
is needed: the signature is always exactly 64 raw Ed25519 bytes and every custodial frame is signed.
After unsealing, the ordinary inbound verification applies unchanged (§4.4).

### 4.2 Key schedule: the outer seal is scope-static

| ID          | Requirement                                                                                |
|-------------|--------------------------------------------------------------------------------------------|
| **C-4.2-1** | One seal key per scopeId, derived in §3, rotating exactly when the scopeId rotates.        |
| **C-4.2-2** | A blob's leading byte is the seal-scheme version. For the frame seal it is `sealv = 0x01`. |

Key selection is therefore a bijection with the scope: a blob pulled from scope S opens under S's
one
key. No hints, no trial decryption.

> **Why this amends the design-phase intent.** The design phase said "sealing keys rotate with
> ratchet
> epochs". Recorded here so it is not relitigated: per-epoch outer keys fail twice.
>
> 1. **DM fresh-epoch bootstrap.** The epoch identifiers needed to select a key (`se`/`ek`/`pe` in
     the
     > ratchet header) live *inside* the sealed blob, and a fresh epoch's key depends on a new DH
     public key
     > also inside it. The receiver of a first-of-epoch blob could neither select nor enumerate the
     key.
     > Deadlock by construction.
> 2. **Group seed-lag visibility inversion.** A blob sealed under a sender's epoch export would be
     > unopenable by exactly the seed-lagging member: the member for whom the frame must stay
     *visible* so
     > it custodies, re-floods and counts the undecryptable-frame signal that drives seed
     key-requests.
     > Epoch-sealing would starve the group scheme's own recovery loop.
>
> The cost is stated in §10: the outer seal protects **routing metadata** with a scope-generation
> horizon,
> not an epoch horizon. **Content** confidentiality and forward secrecy belong entirely to the inner
> v2
> schemes and are untouched, since what is inside `signed` is already epoch-ratcheted ciphertext. An
> epoch-keyed outer seal stays reachable as `sealv = 2` (§11), and the ratchet `exportEpochSeal`
> surfaces
> are reserved for it.

### 4.3 The deterministic seal

Spools dedupe by blob id, and any member may independently push the same frame, so sealing MUST be a
pure
function of (scope, frame).

```
nonce   = HKDF(ikm = nonceKey, info = "knit/scope/v1/nonce" ‖ SHA-256(pt), L = 12)
aad     = "knit/scope/v1" ‖ scopeId
ct      = AES-256-GCM(key = sealKey, nonce, pt, aad)
blob    = 0x01 ‖ nonce(12) ‖ ct
blobId  = SHA-256(blob)
```

| ID          | Requirement                                                                                        |
|-------------|----------------------------------------------------------------------------------------------------|
| **C-4.3-1** | Sealing MUST be deterministic: identical `(scope, frame)` MUST produce identical blob bytes.       |
| **C-4.3-2** | The synthetic nonce MUST be keyed by `nonceKey`. An unkeyed `SHA-256(pt)` nonce is non-conforming. |
| **C-4.3-3** | The aad MUST be `"knit/scope/v1" ‖ scopeId`.                                                       |

> **Nonce-reuse analysis.**
>
> - The nonce is a keyed synthetic IV, SIV-style, built from HKDF and SHA-256. No new primitive is
    > introduced. Identical frame ⇒ identical `(key, nonce, pt)` ⇒ identical blob ⇒ identical
    blobId, so
    > cross-uploader dedup and digest convergence hold by construction and a re-push is
    byte-identical.
> - Two *distinct* plaintexts collide on `(key, nonce)` only through a SHA-256 collision, or the
    2⁻⁹⁶
    > birthday over a per-scope set bounded by `maxFrames`. Negligible. The only parties able to
    *attempt* to
    > manufacture one are seal-key holders, that is, scope members, who can already read every blob
    in the
    > scope. GCM's nonce-reuse failure mode grants them nothing they lack.
> - The keying is load-bearing against a **confirmation oracle**. An unkeyed nonce would let a spool
    that
    > holds candidate frame bytes (say a cleartext-payload `groupleave` observed on the mesh)
    recompute the
    > nonce and link a mesh identity to a scopeId. `HKDF(nonceKey, …)` closes it.
> - The aad binds the scope and the scheme label, so a blob replanted into another scope, or fed to
    a
    > future scheme, fails authentication before any content parses.

### 4.4 Unseal validation and the frame-set rule

| ID          | Requirement                                                                                                                                                                                                                                                                                    |
|-------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-4.4-1** | On a pulled or evented blob a member MUST, in order: verify `blobId = SHA-256(blob)`; open the AEAD; split `sig`/`signed`; decode the `RelayEnvelope`; verify the Ed25519 frame signature against the **pinned** sender key exactly as mesh inbound does; then apply the frame-set rule below. |
| **C-4.4-2** | A blob failing any step MUST be discarded and quarantined (§9.3).                                                                                                                                                                                                                              |
| **C-4.4-3** | The same rule governs the push side: only frames matching it MAY be sealed into a scope.                                                                                                                                                                                                       |
| **C-4.4-4** | A scope with neither a DM peer nor a group id carries nothing.                                                                                                                                                                                                                                 |

**DM scope.** The envelope's group field MUST be unset, and then per type:

| ID          | Requirement                                                                                                                                                                                                   |
|-------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-4.4-5** | `type` is `chat` or `profile`; anything else is rejected.                                                                                                                                                     |
| **C-4.4-6** | For `chat`: sender and recipient are exactly this scope's two members, in either direction. For `profile`, which addresses nobody, the **sender** is one of the two members — there is no recipient to match. |
| **C-4.4-7** | For `chat`: the payload is v2-sealed, `EncEnvelope.v = 2` with the DM ratchet header (`r`) present. A `profile` carries no `EncEnvelope` and is exempt.                                                       |

**Group scope.** The sender MUST be in the pinned **founding** roster, and then per type:

| Frame type          | Where the group id lives                                    | Extra condition                                         | ID           |
|---------------------|-------------------------------------------------------------|---------------------------------------------------------|--------------|
| `chat` (group form) | envelope roster field                                       | `EncEnvelope.v = 2` with the group header (`g`) present | **C-4.4-8**  |
| `groupupdate`       | envelope roster field                                       | —                                                       | **C-4.4-9**  |
| `groupleave`        | its **payload**; the envelope field is unset                | —                                                       | **C-4.4-10** |
| `profile`           | names no group; the founding-roster check is the whole rule | —                                                       | **C-4.4-13** |
| anything else       | —                                                           | rejected                                                | **C-4.4-11** |

> **Why the per-type id location matters.** A rule that only reads the envelope silently excludes
> departures, which is the one frame remaining members most need over the Internet: it is what
> drives the
> leave-rekey and the scope rotation.
>
> **Why the founding roster, not the effective one.** A leaver is already departed by the time its
> own
> `groupleave` is evaluated, and a departed member's pre-departure frames stay legitimately
> re-servable.
> Admitting them is safe because the departure re-mint rotates the scope id: a departed member
> cannot
> reach the new scope at all, whatever the frame rule says.
>
> **Why v1-wrapped group chat is excluded.** A group with a scope is fully ratchet-capable by
> construction (C-3.3-4), so a v1 frame inside one is a peer that has since regressed, not a case to
> carry.
>
> **Why `profile` is scope-carried.** It carries `ProfileContent.prekey`, and the prekey is the one
> thing
> a sealed `CTL_PROFILE` can never carry: sealing a session-starter under a session that must
> already
> exist is circular. Until this rule admitted it, a peer reachable only over the Internet could not
> learn
> a rotated prekey at all, so a DM session that broke could never be re-established — and the group
> sender-key seeds that ride as ctl DMs never arrived either, which made a co-member's group frames
> permanently unreadable. The earlier reasoning here held that a profile's job is first contact, "
> which a
> scope by definition never has"; that is true of the *first* contact and irrelevant to every later
> one,
> since prekeys rotate for the life of a contact.
>
> Admitting it grants a sender nothing a flood does not. The frame is authenticated against the
`pubKey`
> *inside its own payload* (a node id is that key bundle's hash), so it is self-certifying inside a
> scope
> exactly as it is on the mesh, and it discloses strictly less than the cleartext copy already
> floods to
> everyone in radio range. It addresses no recipient and no group, which is why the DM half matches
> it on
> sender alone and the group half rests entirely on the founding-roster check. Receipts and
> reactions
> still ride as sealed chat-shaped ctl frames, since a scope-eligible pair is ratchet-capable by
> construction.

| ID           | Requirement                                                                                                                               |
|--------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| **C-4.4-12** | A frame that passes MUST re-enter delivery inside a fresh mesh envelope with a full hop budget, through the custody re-serve path (§9.4). |

Dedup, idempotent delivery and roster vetting are the existing inbound gates, unchanged.

### 4.5 Attachments

A chat frame does not carry its image; it names one. The name is the *ciphertext* hash of the
attachment
and it rides the mesh in cleartext on the frame, so a carrier blind to the sealed content can still
custody the bytes. Two frame shapes name one (§9.5 lists them). The plane carries the bytes as a
second
object class beside frames.

The input is that attachment ciphertext `A`: already AEAD-encrypted end to end under a fresh
per-send key
that lives inside the sealed `MessageContent`, and already content-addressed by
`aHash = SHA-256(A)`.
This layer does not re-protect content that is already opaque. It blinds the *routing* of it,
exactly as
§4.2's outer seal does for frames.

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

| ID          | Requirement                                                                                                                                                                  |
|-------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-4.5-1** | `aid` MUST be keyed by `nonceKey`. Using `aHash` itself as the id is non-conforming.                                                                                         |
| **C-4.5-2** | The attachment chunk seal version is `0x03`.                                                                                                                                 |
| **C-4.5-3** | `aChunkBytes` is structural, not tunable (§12).                                                                                                                              |
| **C-4.5-4** | The header `aHash ‖ index ‖ total` MUST be sealed inside each chunk.                                                                                                         |
| **C-4.5-5** | Chunk sealing MUST be deterministic, on C-4.3-1's terms.                                                                                                                     |
| **C-4.5-6** | Chunks are addressed by `(aid, index)`, never by `cid`.                                                                                                                      |
| **C-4.5-7** | A fetcher MUST verify, in order: the AEAD; the sealed header against what it requested; and finally that the reassembled bytes hash to `aHash`, the address the frame named. |
| **C-4.5-8** | A member MUST bound every allocation sized by a peer-supplied `total` (§12's chunk ceiling).                                                                                 |
| **C-4.5-9** | An attachment failing any of C-4.5-7 is handled as §9.3 handles a bad blob.                                                                                                  |

> **Why the id is keyed.** `aHash` is public on the mesh. An unkeyed attachment id would hand a
> spool with
> *any* source of candidate hashes (an operator who also runs a node in radio range, a harvested
> disk) a
> confirmation oracle linking a mesh frame to a scope id. This is §4.3's known-plaintext argument
> applied
> to the object that actually travels in the clear, and it is the one place where the attachment
> plane is
> more exposed than the frame plane if you get it wrong.
>
> **Why `0x03`.** Distinct from the frame seal's `0x01` and from the `0x02` reserved for the
> epoch-keyed
> frame seal (§11), so the two openers can never be fed each other's blobs. The aad prefixes differ
> too
> and cannot alias: the scope id that follows is fixed-width, so a frame aad is always 45 bytes and
> a
> chunk aad 52.
>
> **Why fixed-size chunking.** A constant `aChunkBytes` is what makes a chunk's position a function
> of the
> attachment alone. There is no manifest object, and therefore nothing for two members to disagree
> about.
> A sealed chunk is `1 + 12 + 40 + 49152 + 16 = 49221` bytes, comfortably inside the 64 KiB
`maxBlob`.
>
> **Why the header is inside the seal.** It binds each chunk to its attachment and its position, so
> a
> chunk cannot be replayed elsewhere even by a scope member.
>
> **Why C-4.5-6.** A fetcher cannot know a chunk's content address before fetching it. `cid` exists
> so a
> spool can verify what it is asked to store, the way `blobId` does for a frame.

## 5. Scope configuration [Client]

A scope's operating parameters ride *inside the conversation itself*, end-to-end sealed like any
message.
A spool never sees spool lists or bounds provenance, and the config propagates over mesh and spools
alike
with no side channel.

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

| ID        | Requirement                                                                                                          |
|-----------|----------------------------------------------------------------------------------------------------------------------|
| **C-5-1** | Carriage is a `MessageContent.ctl` value, not a new wire frame type.                                                 |
| **C-5-2** | The issuer is the ctl frame's authenticated sender, never a payload field.                                           |
| **C-5-3** | Any member MAY issue; in a DM, either party.                                                                         |
| **C-5-4** | Conflict resolution is last-writer-wins: highest `version`, ties broken by highest issuer node id lexicographically. |
| **C-5-5** | Convergence-relevant bounds MUST come from the config, not from app constants.                                       |
| **C-5-6** | v1 defines no config acknowledgment and no capability bit.                                                           |

> **Status.** `ctl = 7` is **reserved and specified, not yet on the wire** (Appendix A). Until it
> ships,
> the reference client syncs every scope against a **device-local** spool list and declares §12's
> default
> bounds at SUB. A stock spool clamps those defaults to themselves, so the interim behaves as the
> configured case with one publisher per device.
>
> **Why a ctl and not a frame type.** ADR 016/018's lesson: `isCustodial` is a fixed list on
> deployed
> builds, so a new frame type floods but is never custodied, and the config is precisely the frame
> that
> must survive store-and-forward to reach offline members. As a ctl inside a sealed v2 chat frame it
> is
> custodied by every build, and an old build consumes it as the pinned chain-advancing silent no-op.
> Mesh-side wire stubs land additively, per `docs/WIRE_COMPAT.md`.
>
> **Why bounds live in the config.** ADR 006's lesson: convergence-relevant bounds baked into app
> versions
> diverge silently across upgrades. Pinned in signed per-scope state, every member and, via SUB,
> every
> spool reads the same numbers from the same place.
>
> **Why no ack.** The plane is purely additive continuity. The mesh path works regardless, and the
> LWW
> rule makes redundant delivery harmless, so delivery cadence stays client policy.

## 6. The spool [Spool]

### 6.1 Data model

Per scope, a spool holds nothing but:

- `blobId → (blob, arrivedAt)`, the live set;
- a **tombstone set** of evicted and expired blob ids, bounded, expiring on the same `ttlMs` clock;
- the rolling **digest** over the live blob-id set (§6.3);
- subscriber connections.

| ID          | Requirement                                                                                 |
|-------------|---------------------------------------------------------------------------------------------|
| **S-6.1-1** | A spool MUST NOT keep accounts, user rows, or any cross-scope index beyond the scope table. |

A spool that loses its disk is refilled by any member through §9. Spools are cattle.

### 6.2 Bounds, eviction, tombstones

A sealed frame hides its send time, so a spool orders by what it can see.

| ID          | Requirement                                                                                                                                      |
|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-6.2-1** | A spool MUST evict oldest-by-`arrivedAt` when a scope exceeds `maxFrames`, and MUST expire a blob at `arrivedAt + ttlMs`.                        |
| **S-6.2-2** | Applied per-scope bounds are the most recent SUB's declaration, **clamped** to the spool's HELLO-advertised hard caps.                           |
| **S-6.2-3** | DIGEST MUST echo the applied bounds plus a `full` flag.                                                                                          |
| **S-6.2-4** | Evicted and expired ids MUST enter the tombstone set; a PUSH matching a tombstone MUST be refused `tombstoned` and MUST NOT re-enter the digest. |
| **S-6.2-5** | LIST responses MUST carry the tombstone ids.                                                                                                     |
| **S-6.2-6** | On PUSH a spool MUST verify `blobId = SHA-256(data)` (refuse `bad_id`) and MUST enforce `maxBlob`.                                               |
| **S-6.2-7** | Tombstone sets MUST be count-bounded as well as TTL'd, dropping oldest first (§12).                                                              |

Different spools may therefore hold different sets. That is fine: spools never sync with each other
and
clients union them.

> **The re-push churn loop**, a client re-uploading what a spool just evicted, is closed by three
> guards
> together: S-6.2-4 refuses it, S-6.2-5 tells the client before it wastes an upload, and C-9.2-1
> stops the
> client emitting it at all.
>
> **Why S-6.2-6.** A third party must not be able to poison an honest spool's digest.
>
> **Why S-6.2-7.** Without a count bound, a member cycling unique blobs through eviction grows the
> set at
> push rate for a whole `ttlMs`. Dropping a tombstone early merely re-admits a blob that then ages
> out
> through the ordinary TTL: bounded churn, never divergence.
>
> **Delivery facts do not exist at this layer.** Receipts are just more sealed frames inside a
> scope, and
> spool copies of everything age out on the TTL uniformly.

**Forgotten scopes.** A spool may forget a scope entirely, through watermark shedding (§6.4), an
operator wipe, or a restart of a non-persistent spool, while connections still hold subscriptions
for it.

| ID           | Requirement                                                                                                                                                              |
|--------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-6.2-8**  | The subscription survives on the connection. `list`/`pull` against a forgotten scope MUST answer empty (an empty `list` response, all ids `missing`) and MUST NOT error. |
| **S-6.2-9**  | The next `push` **recreates** the scope, applying §6.4's creation gates exactly as for an unknown scope id.                                                              |
| **S-6.2-10** | On recreation the bounds re-apply from the connection's most recent declaration, and the spool MUST send the recreated scope's fresh `digest` before the push's `ok`.    |

### 6.3 The digest

```
digest(S) = XOR over b ∈ S of FNV1a64(b)      // b = the raw 32-byte blobId
FNV1a64: h = 0xcbf29ce484222325; per byte: h = (h XOR byte) × 0x00000100000001b3 (mod 2⁶⁴)
digest(∅) = 0
```

Order-independent and self-inverse, so add and remove are O(1). Wire form is 8 bytes big-endian as a
byte
string (B-2-7). This is the mesh's custody digest with raw-byte input instead of UTF-8 frame-id
strings.

### 6.4 Abuse posture

| ID          | Requirement                                                                                                                                                                                                                                                        |
|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-6.4-1** | Per-scope quotas (§6.2) apply, so a spamming member thrashes only its own conversation.                                                                                                                                                                            |
| **S-6.4-2** | The first SUB or PUSH for an unknown scope id MAY demand a PoW stamp (§8), at the difficulty advertised in HELLO.                                                                                                                                                  |
| **S-6.4-3** | A spool SHOULD cache accepted `(scopeId, day)` pairs so honest clients pay roughly once per scope.                                                                                                                                                                 |
| **S-6.4-4** | Per-IP and per-connection rate limits are signalled with `rate` + `retryMs`.                                                                                                                                                                                       |
| **S-6.4-5** | A global storage watermark with oldest-scope shedding is operator policy. When a spool sheds, the recommended shape is a **whole-scope drop, tombstones included**, followed by an empty `digest` (count 0, digest 0) to that scope's still-connected subscribers. |

> **Why shedding drops tombstones too.** A shed scope is exactly the "wiped spool" §9.1 refills, and
> surviving tombstones would refuse the very re-pushes that refill it. The empty digest makes
> subscribers
> refill immediately instead of on the next reconnect.
>
> **Private spools.** A bearer token in the WSS URL (§7.1) is zero-config access control for
> self-hosters,
> and the URL lives only inside the sealed scope config anyway.

### 6.5 Attachments at the spool

A spool that supports attachments (it says so in HELLO, §7.3) keeps, per scope, a second table
alongside
§6.1's: per `aid`, the declared `total`, the bytes held, an `arrivedAt`, and the stored chunks keyed
by
index. Chunks are opaque, exactly like frame blobs.

| ID          | Requirement                                                                                                                                           |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-6.5-1** | The attachment quota is per-scope **bytes** (`maxAttachBytes`), taken from the spool's own HELLO. It MUST NOT be part of the SUB-declared bounds.     |
| **S-6.5-2** | Over budget, a spool MUST evict the oldest whole `aid` by `arrivedAt`, never individual chunks.                                                       |
| **S-6.5-3** | An attachment that cannot fit the budget even alone MUST be refused `quota`.                                                                          |
| **S-6.5-4** | TTL is stamped at the first chunk and MUST NOT be extended by later chunks.                                                                           |
| **S-6.5-5** | Tombstones apply in §6.2's shape, with the same TTL and count bound: `aput` against a dead `aid` is refused `tombstoned`, and `ahave` answers `dead`. |
| **S-6.5-6** | On `aput` a spool MUST verify `cid = SHA-256(data)` (refuse `bad_id`) and MUST enforce `maxAChunk` and the byte quota.                                |
| **S-6.5-7** | First write wins at a position: an identical `cid` is acked idempotently, a differing one is refused `conflict`.                                      |
| **S-6.5-8** | A whole-scope shed drops that scope's attachments and their tombstones with it.                                                                       |
| **S-6.5-9** | Attachments MUST NOT be folded into the scope digest.                                                                                                 |

> **Why the quota is the spool's alone.** Members must agree on frame bounds because the frame
> digest
> folds over them. Attachments are outside the digest, so a per-scope declaration would buy no
> convergence
> and only add a field two members could disagree about.
>
> **Why whole-`aid` eviction.** Half an attachment is useless to every member and would show up in
> the
> bitmap as progress that can never complete.
>
> **Why S-6.5-4.** Otherwise a member trickling one chunk an hour pins an attachment indefinitely.
>
> **Why S-6.5-7.** Honest members never differ, because §4.5's seal is deterministic. The refusal
> exists
> so one member cannot poison an attachment for the rest of the scope.
>
> **Why S-6.5-9 is the load-bearing decision of this section.** It is the mesh's own lesson
> restated:
> anything a digest folds over must be bounded by a rule identical on every node, or the two sides
> never
> converge and re-attempt forever. A byte quota is precisely the kind of operator- and
> device-tunable knob
> that cannot be identical, which is why the mesh keeps `ForwardEntity.attachmentHash` out of
> `StoreDigest` and bounds carrier blobs with a purely local budget. Attachment presence is
> therefore
> discovered by **asking** (`ahave`), not by anti-entropy, and a spool holding fewer attachments
> than a
> member is not divergence. It is the quota working.

## 7. The record layer [Both]

### 7.1 Binding and connection lifecycle

| ID          | Requirement                                                                                                                                                                                                                                   |
|-------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **B-7.1-1** | Transport is WSS (TLS WebSocket). URL shape: `wss://host[:port]/spool/v1[?k=<token>]`.                                                                                                                                                        |
| **C-7.1-2** | A client MUST refuse a non-TLS spool URL. Development builds MAY allow `ws://`; release builds MUST NOT, at entry and again at dial time.                                                                                                     |
| **S-7.1-3** | A private spool MUST compare `k` in constant time and close `4001` before HELLO on mismatch.                                                                                                                                                  |
| **B-7.1-4** | Exactly one CBOR record per WebSocket binary message. WS provides the framing.                                                                                                                                                                |
| **B-7.1-5** | Each direction sends `hello` first. The spool advertises `v` (highest supported record-layer version), `min`, `limits` and `powBits`; the client answers with the chosen `v` in `[min, v]`, and nothing else identifying in either direction. |
| **B-7.1-6** | No version overlap closes `4002`.                                                                                                                                                                                                             |
| **B-7.1-7** | A `hello` *after* negotiation is malformed in-band traffic: answer `err malformed` and keep the connection. Close `4000` covers pre-hello traffic only.                                                                                       |
| **B-7.1-8** | The client stamps `q`, monotonically increasing per connection; terminal responses (`ok`/`err`) echo it. Server-initiated records (`digest`, `event`, `blob`, `achunk`) carry no `q`.                                                         |
| **B-7.1-9** | All scope operations require a prior `sub` for that scope on the same connection, `not_subscribed` otherwise.                                                                                                                                 |
| **S-7.1-10** | A spool at its connection cap MAY refuse the upgrade at the transport layer with `503`, and SHOULD send `Retry-After`. It MUST NOT use a close code to say so.                                                                              |
| **C-7.1-11** | A client MUST treat a refused upgrade as a transient transport failure and keep its own reconnect backoff. It SHOULD apply a `Retry-After` as a *floor* on the next wait, and MUST keep its jitter on top.                                  |

WS close codes: `4000` malformed pre-hello traffic · `4001` auth · `4002` version · `4003` abuse.

*Rationale (S-7.1-10, C-7.1-11).* None of the four close codes means "come back later", and `4003`
would tell a client it misbehaved when it did not — being full is a property of the box, not of the
protocol, so it is reported in the layer that owns capacity. The floor-not-replacement rule is what keeps
a long refusal from getting cheaper over time, and the jitter is what stops a population of clients one
spool turned away in the same instant from returning in the same instant.

### 7.2 Frame records

Field names are the CBOR map keys. `bstr32`/`bstr8` are byte strings of that length.

| Record   | Direction   | Fields                                                                                                                       | Semantics                                                                                                                                                                                                                       |
|----------|-------------|------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `hello`  | both, first | `t, v: Int` (+ spool→client: `min: Int, limits, powBits: Int`)                                                               | Version negotiation. `limits = { maxBlob, maxRecord, maxScopes, maxPull, maxFramesCap: Int, maxTtlMs: Long }`, plus `maxAttachBytes, maxAChunk, maxAget: Int` on an attachment-capable spool (§7.3). `powBits = 0` disables PoW |
| `sub`    | c→s         | `t, q: Long, subs: [ { scope: bstr32, bounds: { maxFrames: Int, ttlMs: Long, maxBlob: Int }, pow?: { n: Long, d: Long } } ]` | Subscribe and declare bounds. An unknown scope with PoW on requires a valid stamp or answers `err pow`. Response: one `digest` (or scoped `err`) per scope                                                                      |
| `digest` | s→c         | `t, scope: bstr32, digest: bstr8, count: Int, full: Bool, bounds`                                                            | The anti-entropy cue, sent on sub and whenever the spool chooses. The client treats the latest as the anchor                                                                                                                    |
| `list`   | c→s / s→c   | `t, q, scope` / `t, q, scope, blobIds: [bstr32], tombstones: [bstr32]`                                                       | The id exchange behind a digest mismatch                                                                                                                                                                                        |
| `pull`   | c→s         | `t, q, scope, blobIds: [bstr32]` (≤ `maxPull`)                                                                               | Answered by `blob`* then `ok { q, missing?: [bstr32] }`                                                                                                                                                                         |
| `blob`   | s→c         | `t, scope, blobId: bstr32, data: bstr`                                                                                       | One pulled blob                                                                                                                                                                                                                 |
| `push`   | c→s         | `t, q, scope, blobId: bstr32, data: bstr, pow?`                                                                              | Store. The spool verifies hash, size, quota and tombstone, folds the digest, fans out `event`                                                                                                                                   |
| `event`  | s→c         | `t, scope, blobId: bstr32, data: bstr`                                                                                       | Live delivery to every *other* subscriber of the scope, uploader excluded. Best-effort: a spool may disconnect a slow consumer, and correctness rests on §9                                                                     |
| `ok`     | s→c         | `t, q: Long, missing?: [bstr32]`                                                                                             | Terminal ack                                                                                                                                                                                                                    |
| `err`    | s→c         | `t, code: String, q?: Long, scope?: bstr32, msg?: String, retryMs?: Long`                                                    | Terminal error, connection-scoped when `q` is absent                                                                                                                                                                            |

**Error registry** (append-only; unknown codes are terminal-generic): `version`, `pow`,
`tombstoned`,
`quota`, `too_large`, `bad_id`, `rate`, `not_subscribed`, `malformed`, `internal`, `conflict`.

| ID          | Requirement                                                                                                                                                                           |
|-------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **B-7.2-1** | `version` is reserved and MUST NOT be emitted in v1. A hello mismatch is close `4002`, not a record. The code exists so a future record-level versioning use never has to recycle it. |
| **B-7.2-2** | `conflict` is emitted only by `aput` (§7.3).                                                                                                                                          |
| **S-7.2-3** | A spool SHOULD send a fresh `digest` to a scope's subscribers whenever the live set changes beyond a single acked push: eviction pressure, TTL expiry, a watermark shed.              |
| **C-7.2-4** | A client MUST NOT *rely* on unsolicited `digest`. It is best-effort fan-out like `event`; §9.1 on reconnect is the correctness anchor.                                                |
| **S-7.2-5** | A `pull` beyond `maxPull` MUST be truncated to `maxPull`, never answered with an error. Ids beyond the cap appear in neither `blob`s nor `missing`.                                   |
| **C-7.2-6** | A client that overshoots `maxPull` re-pulls the remainder.                                                                                                                            |
| **S-7.2-7** | A duplicate `push` (blobId already live) MUST be acked `ok` with **no** `event` fan-out. The push row's fan-out applies to newly stored blobs only.                                   |

> **Why S-7.2-7 is safe.** Content addressing makes the duplicate byte-identical, so subscribers
> either
> already have it or will heal via digest.

### 7.3 Attachment records

| Record   | Direction | Fields                                                         | Semantics                                                                                                                                          |
|----------|-----------|----------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `ahave`  | c→s       | `t, q, scope: bstr32, aid: bstr32`                             | What does this spool hold for this attachment? Answered by `ahas`                                                                                  |
| `ahas`   | s→c       | `t, q, scope, aid, total: Int, bits: bstr, dead: Bool`         | `total = 0` ⇒ never seen; `dead` ⇒ tombstoned (§6.5); `bits` is the presence bitmap — chunk *i* is bit *i mod 8*, **MSB-first**, of byte *i div 8* |
| `aget`   | c→s       | `t, q, scope, aid, from: Int, n: Int` (≤ `maxAget`)            | Answered by `achunk`* then a bare `ok { q }`                                                                                                       |
| `achunk` | s→c       | `t, scope, aid, idx: Int, total: Int, cid: bstr32, data: bstr` | One sealed chunk. Carries no `q`, exactly like `blob`                                                                                              |
| `aput`   | c→s       | `t, q, scope, aid, idx, total, cid: bstr32, data: bstr, pow?`  | Store one sealed chunk                                                                                                                             |

| ID          | Requirement                                                                                                                                                 |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-7.3-1** | Indices the spool lacks simply do not arrive from `aget`. Nothing enumerates them back, and `ok.missing` stays a frame-only field.                          |
| **S-7.3-2** | An `aget` overshooting `maxAget` MUST be truncated, never answered with an error (S-7.2-5, reapplied).                                                      |
| **S-7.3-3** | `aput` follows §6.5: `cid` verified, `maxAChunk` and the byte quota enforced, tombstones refused, first write wins with `conflict` on a differing re-write. |
| **S-7.3-4** | Attachments MUST NOT be fanned out with `event`.                                                                                                            |
| **S-7.3-5** | A spool advertises attachment support by including `maxAttachBytes`, `maxAChunk` and `maxAget` in HELLO's `limits`: all three, or none.                     |
| **C-7.3-6** | A client MUST NOT send `ahave`/`aget`/`aput` to a spool that omitted them.                                                                                  |

> **Why S-7.3-4.** A member learns an attachment exists from the frame that names it, not from the
> spool,
> so a push-time fan-out would deliver bytes nobody has a reference for yet.
>
> **Why capability negotiation is a gate, not a hint.** B-2-2 says unknown records are skipped, and
> a
> skipped request is never answered, so an optimistic `ahave` to a v1 spool leaves that `q`
> outstanding
> until the client's request timeout, once per attachment, per scope, per heal round. Support is
> additive
> and needs no record-layer version bump precisely because the flag carries it.

## 8. Proof of work [Both]

Stateless Hashcash, the Nostr NIP-13 family, over data both sides already share. No server challenge
round-trip.

```
input   = "knit/spool/v1/pow" ‖ scopeId(32) ‖ u64be(day) ‖ u64be(n)
valid   ⇔ leadingZeroBits(SHA-256(input)) ≥ powBits           // powBits from HELLO; 0 = off
day     = floor(unixMillis / 86 400 000)                       // UTC day number
```

| ID        | Requirement                                                             |
|-----------|-------------------------------------------------------------------------|
| **S-8-1** | A spool MUST accept `day ∈ {today − 1, today, today + 1}` and no wider. |
| **S-8-2** | A spool SHOULD cache accepted `(scopeId, day)` pairs.                   |
| **B-8-3** | The stamp binds no spool identity.                                      |

> **Why no spool binding.** Spools have no protocol identity beyond a URL, so there is nothing sound
> to
> bind to, and the per-scope-per-day cache bounds replay value anyway. The ±1-day window covers
> clock skew
> and bounds pre-mining.
>
> **Difficulty.** 20 bits is the recommended default: about 10⁶ hashes, sub-second on a phone, and a
> mass
> scope-squatter pays it per scope per day.

## 9. Anti-entropy: the client procedure [Client]

### 9.1 The heal loop

| ID          | Requirement                                                                                                                                                                                                                           |
|-------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-9.1-1** | On a `digest` mismatch against the local expectation for a scope, a client MUST request `list`, diff the spool's live set against local custody minus tombstoned ids, `pull` what is missing locally and `push` what the spool lacks. |
| **C-9.1-2** | The loop MUST be bidirectional.                                                                                                                                                                                                       |

> **Why bidirectional.** This is the "custody peer per scope" doing real work. A member that carried
> frames over the mesh while the spool was unreachable refills it; a fresh spool added to the config
> heals
> from any one member; members converge through the union of whatever every spool holds.

### 9.2 The outward dead-on-arrival guard

| ID          | Requirement                                                                                                                                |
|-------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| **C-9.2-1** | A client MUST NOT push a frame whose cleartext-to-members `sentAt + ttlMs` (the scope's TTL, not the mesh custody TTL) has already lapsed. |

The mesh's custody store applies the same rule inward, so an expired frame neither enters local
custody
nor bounces between client and spool eviction. §6.2's guards close the loop from the spool side.

### 9.3 The invalid set

| ID          | Requirement                                                                                                                                                   |
|-------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-9.3-1** | A pulled blob that fails the hash check, the AEAD, the signature or the frame-set rule (§4.4) MUST enter a bounded per-spool **invalid set** keyed by blobId. |
| **C-9.3-2** | An invalid-set entry MUST never be re-pulled, never counted as held and never re-pushed.                                                                      |

> **Why this is load-bearing.** Spools are untrusted storage. Without the set, one garbage blob at a
> spool
> folds into the spool's digest but never the client's: permanent divergence and infinite re-pull.
> With
> it, the divergence is accounted and inert.

### 9.4 The mesh bridge

| ID          | Requirement                                                                                                                                                                                                              |
|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-9.4-1** | A blob that passes §4.4 MUST re-enter delivery wrapped in a fresh mesh envelope with a full hop budget — the custody re-serve shape: same `signed`/`sig`, ttl reset, hops 0 — flowing through the ordinary inbound path. |
| **C-9.4-2** | Symmetrically, frames the member custodies for a scope, whether from the mesh or from its own sends, are sealed and pushed.                                                                                              |

Flood-dedup, idempotent persistence, roster vetting and custody capture are all unchanged. One
Internet-connected member thus bridges a whole radio island in both directions with zero new
delivery
semantics.

### 9.5 Attachments: fetch and refill

Both working sets are derived from what the member already holds. This plane persists nothing about
attachments.

**want(scope)** is the live custodied frames that pass §4.4 for the scope and name an attachment
this
scope may carry, whose bytes are absent locally. **have(scope)** is the same set with the bytes
present.

Two frame shapes name an attachment, and where the name lives differs per type exactly as the
frame-set
rule's group id does:

| Frame type    | Field                                             | Covers                                                                                                |
|---------------|---------------------------------------------------|-------------------------------------------------------------------------------------------------------|
| `chat`        | `ChatContent.attachmentHash`                       | message images, and peer **avatars**, since a sealed `CTL_PROFILE` frame sets the same cleartext hint |
| `groupupdate` | `GroupInfo.photoHash`                             | the group's own picture; a groupupdate is already scope-eligible, so only the bytes were missing      |

`groupleave` names no image, and everything else fails the frame-set rule first.

| ID          | Requirement                                                                                                                                                 |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-9.5-1** | References MUST be deduped by hash, keeping the newest `sentAt` and the first mime seen. A frame need not name a mime at all — a `groupupdate` never does, and a `chat` frame does so only on an older sender — so a fetcher MUST tolerate its absence.                                                       |
| **C-9.5-2** | Per heal round, per (spool, scope), a client works a small bounded number of attachments at a time.                                                         |
| **C-9.5-10** | When no reference names a mime, a fetcher SHOULD take the type from its own record of the message that names the attachment, and MUST fall back to a default when it has none. The type is never needed to *fetch* — only to store and render.                                                             |

The round, per attachment:

1. `ahave` for the `aid` (§4.5). `dead` ⇒ give up on this spool for this attachment. `total = 0` ⇒
   nothing to pull, though the push half may still apply.
2. `aget` the indices the bitmap marks absent locally, in `maxAget` batches. Open each chunk, check
   its
   header against what was requested, buffer it.
3. When complete, verify `SHA-256(reassembled) = aHash` and hand the bytes to the ordinary local
   blob
   store, so screening, the message row and the UI all update exactly as they do after a radio pull.
4. Push half: `aput` the indices the bitmap marks missing at the spool, bounded by the byte budget
   and by
   §9.2's guard applied to the newest frame that references the attachment.

| ID          | Requirement                                                                                                                                                |
|-------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-9.5-3** | A retiring scope (§3.1, §3.3) is pulled but never refilled, mirroring frames.                                                                              |
| **C-9.5-4** | Any failure — AEAD, a header that does not match the request, a final hash mismatch — MUST quarantine the `aid` per (spool, scope), extending §9.3's rule. |

> **Why a reference may carry no mime.** The mesh frame used to repeat the attachment's MIME beside its
> hash so a blind radio carrier could label what it custodied. That told a carrier whether a message was a
> photo or a voice note while buying nothing — custody addresses bytes by hash — so the client withdrew it
> (ADR 035) and a sealed `chat` frame now names the hash alone, converging on the shape `groupupdate` has
> always had. **Nothing at a spool changes:** §4.3 seals the whole frame, so a spool never read the field
> in the first place. A member holds the decrypted message and can simply look the type up locally, which
> is also more trustworthy than a sender's cleartext claim.
>
> **Why C-9.5-4.** The argument is identical to §9.3's: a spool is untrusted storage, and without an
> accounted invalid set a single bad chunk is re-fetched every round forever.
>
> **Partial downloads live in memory and are not persisted.** A deliberate cost: a process death
> mid-transfer re-fetches that attachment. It buys the plane's no-new-persistence property (the
> blob-id
> set is derived, never stored), and the spool-side bitmap already makes the *upload* half resume
> for
> free. Persisting them is registered in §11.
>
> **One honest bound.** The want set comes from **custody**, whose TTL (24 h on the mesh) is shorter
> than
> a scope's (48 h). A frame that has aged out of local custody stops driving an attachment fetch
> even
> though the spool may still hold the bytes. This matches the mesh's own carrier behaviour and keeps
> the
> derivation seam small. It is not a convergence problem, only a missed opportunity at the tail.

#### Deferring the push half

Attachments are the expensive object class, and a photo that crossed a radio link needs no second
copy at
a relay.

| ID          | Requirement                                                                                                                                                                          |
|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-9.5-5** | A member MAY defer the push half for an attachment while it holds positive evidence that the mesh is already carrying those bytes.                                                   |
| **C-9.5-6** | A deferral is a delay, never a refusal. It MUST re-open on its own when the evidence lapses, without waiting for new local activity.                                                 |
| **C-9.5-7** | A deferral MUST end while the referencing frame can still drive a push, since want and have are derived from custody and an attachment stops being nameable once its frame ages out. |
| **C-9.5-8** | The evidence MUST be per-recipient, and it MUST be able to expire. A permanent signal such as a delivery ack cannot satisfy C-9.5-6 on its own.                                      |
| **C-9.5-9** | This option covers attachments only. Frames MUST NOT be deferred on any such signal.                                                                                                 |

> **Why C-9.5-8.** A frame can be acked while its bytes were never fetched, because an attachment
> travels
> by a separate demand-driven pull. Consequently a **group** scope cannot satisfy this rule in v1:
> its
> sealed delivery tick flips on the first receipt from *any* member, so "acked" never means "every
> member
> holds it", and deferring on it would silently strand whoever was not reached. The reference client
> therefore defers on DM scopes only, and pushes unconditionally for carried frames (nothing we
> authored)
> and for avatars and group photos (no message row, so no per-recipient signal exists).
>
> **Why C-9.5-9.** Gating frames would make the scope digest a function of local mesh state, and it
> would
> never converge again.
>
> **Reference-client policy, non-normative.** Defer while the peer was on the presence plane within
> the
> last 15 minutes, and stop 2 h before the frame leaves custody. Under-deferring costs relay bytes;
> over-deferring strands an image, so every uncertain case must resolve to push, including a fresh
> process, which has seen nobody yet and therefore defers nothing.
>
> **Observability.** Nothing here is visible at a spool beyond a later `aput`, so a deferring member
> and
> an eager one are the same client to the same server, and conformance is unaffected. It does
> sharpen
> §10's timing signal, priced there.

### 9.6 The accounted set

| ID          | Requirement                                                                                                                                                                          |
|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-9.6-1** | A pulled blob that passed §4.4 and was bridged per §9.4, but that local custody does **not** hold once delivery returns, MUST enter a bounded per-(spool, scope) **accounted set**. |
| **C-9.6-2** | An accounted id MUST be folded into the client's local scope digest and counted as held, and MUST NOT be pulled again.                                                            |
| **C-9.6-3** | An accounted id MUST be dropped as soon as a `list` for its scope no longer names it, and MUST NOT be folded in while the same id is also held in custody.                        |
| **C-9.6-4** | The accounted set MUST outlive a connection. Retaining it across a process restart is permitted, not required.                                                                    |

> **Why this exists.** §12.2 sets the scope TTL at 48 h against a 24 h mesh custody TTL *deliberately* —
> "longer retention stores frames the inner ratchet may no longer decrypt". That band is not an edge case,
> it is half the retention window, and every blob in it is one the client pulled, delivered and then aged
> out of custody. Without C-9.6-2 those ids sit in the spool's digest and can never enter the client's:
> the scope reports diverged for the whole second day, `list` runs on every tick, and each pull re-bridges
> a message the inner ratchet has already consumed. This is §9.3's failure exactly — "folds into the
> spool's digest but never the client's: permanent divergence and infinite re-pull" — arriving through a
> door §9.3 does not cover, because these blobs are *valid*: they fail nothing in §4.4, they die at the
> custody store's own dead-on-arrival rule, which is downstream of the bridge and reports nothing back.
>
> **Why it is not the invalid set.** §9.3's entries are "never counted as held" because a spool that
> plants garbage must not be able to move our digest. These blobs are the opposite: they are frames we
> genuinely received, and counting them as held is what the digest needs to be *true*. Keeping the two
> sets apart also keeps §9.3's quarantine counter meaning what it says — spool misbehaviour, expected 0.
>
> **Why "custody did not keep it" rather than a TTL comparison.** The client already owns one copy of the
> expiry rule, in its custody store, and that rule is convergence-critical on the mesh. A second copy in
> the spool client would be a second thing to keep in step; asking the store what it did is exact by
> construction, and it covers quota eviction and any future refusal for free.
>
> **Why C-9.6-3.** Both halves are the same divergence mirrored. An accounted id the spool has since
> expired leaves the client's fold carrying something the spool no longer counts; an id that is both
> accounted and held would XOR its own contribution out of an XOR-folded digest and cancel itself.
>
> **Why C-9.6-4.** A connection-scoped set is not a fix: a client reconnects on Doze, a network change or
> a spool restart, and each reconnect re-pulls and re-bridges the entire band. Losing the set on a process
> restart costs one such band, once, which is bounded by `maxFrames` and self-limiting — a durable set is
> therefore permitted rather than required, so the plane may stay storage-free.
>
> **Observability.** A spool sees a client that stops asking for blobs it will not take, which is what a
> converged scope looks like from the outside. Nothing new is exposed.

## 10. Security and privacy claims [Both]

### 10.1 What a spool, or whoever takes its disk, observes

- Opaque scope ids and their activity rhythm: sizes, timings, a long-lived pseudonymous channel per
  conversation era. §3's rotations bound the eras.
- Subscriber **IPs** and connection patterns. "These k IPs touch the same scope" is an edge between
  IPs
  and is the honest residual leak. **Tor removes it**, at battery and latency cost; a later
  milestone
  ships the toggle. Scope multiplexing over one WSS already blurs per-scope timing somewhat, and
  padding or cover traffic is future study (§11).
- For attachments, a **stronger size signal than frames give**: that a scope holds an object of
  roughly
  `total × 48 KiB`, when it was uploaded, and how many subscribers fetched it. Chunking quantises
  this to
  48 KiB and the keyed `aid` keeps the attachment unlinkable across scopes and unconfirmable against
  a
  candidate hash. Even so, "this conversation exchanged a ~4 MB image at 09:14" is visible in a
  way "this
  conversation exchanged some frames" is not. That is the price of carrying bytes at all, and it is
  why
  the byte quota is per scope.
- For a **pair scope** (§3.5), an id that is stable per pair rather than per session era — two
  bootstraps of the same pair, years apart, share it. Bounded by the subscription window: pending plus
  `pairGrace`, after which the scope is gone and the pair lives on its rotating DM scope.
- Where a member defers the §9.5 push half, a **proximity** signal on top of that: an upload that
  only
  happens once the radios stopped carrying the bytes tells a spool roughly when a conversation's
  members
  were apart. Frames stay unconditional (C-9.5-9) precisely so this does not generalise. It is
  scoped to
  the object class that already leaks a size and a time, and it buys not shipping a second copy of
  every
  photo that already crossed a radio link. A member that never defers gives up nothing here.

### 10.2 What it cannot do

- Read content, rosters or delivery facts, or map a scopeId to node ids. The KDFs are keyed by
  member
  secrets, receipts are indistinguishable sealed frames, and §4.3's keyed nonce denies the
  known-plaintext confirmation oracle.
- Forge or tamper: AEAD outside, the mesh's Ed25519 frame signature inside, verified byte-exact
  after
  unsealing.
- Replay usefully: content-addressed ids, idempotent delivery, the dead-on-arrival TTL.
- Withhold *undetectably* when the scope is multi-homed: members see spool divergence via digests.

### 10.3 Compromise horizons

| Compromise                                                                                  | Reach                                                                                                                                                                                                                                                                                                                                                                                                                                              |
|---------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Content**                                                                                 | Inner v2 ratchet ciphertext with its own epoch-granularity forward secrecy. Seize-the-disk-then-compromise-a-key-later yields no message bodies beyond what the inner schemes already concede. Untouched by this layer                                                                                                                                                                                                                             |
| **Routing metadata** (the `RelayEnvelope`: ids, sender/recipient/roster, send times, types) | The scope-static outer seal (§4.2) gives it a **scope-generation** horizon. A member-device compromise plus a harvested spool disk reveals that era's envelope metadata, including frames the device itself no longer holds. A member-device compromise reveals the conversation and roster anyway, so the marginal exposure is the metadata of aged-out frames. `sealv = 2` (§11) is the reserved upgrade if that margin ever warrants epoch keys |
| **Identity file only**                                                                      | No *conversation*-scope key: every DM and group scope input is a database-tier session secret. The one identity-derived scope is the pair scope (§3.5): with the peer's public bundle it yields that scope's id and outer seal — the routing metadata of bootstrap-era frames while the scope is subscribed, never content, never a DM or group scope                                                                                             |
| **Device wipe**                                                                             | Scopes are unrecoverable (§3.1). Continuity is a property of live sessions, never of any server                                                                                                                                                                                                                                                                                                                                                    |

### 10.4 Insider threats

Spam is bounded by the scope's own quotas. Key leakage is E2E's universal caveat. A departed group
member
watches old-scope ciphertext flow only until the departure re-mint rotates the id (§3.3), bounded by
the
drain window.

Scope ids and, in v1, SUB and PUSH are otherwise unauthenticated toward the spool: anyone who
*learns* a
scopeId can subscribe to its ciphertext and burn its quota. That is accepted in v1: ids are
unguessable
KDF outputs, and multi-homing plus rotation bound the damage. A TOFU scope-auth extension is
registered
in §11.

## 11. Extension register [Both]

Deliberately open, additively reachable, in no particular order.

| Extension                                  | Note                                                                                                 |
|--------------------------------------------|------------------------------------------------------------------------------------------------------|
| Cleartext `sentAt` hint on PUSH            | True-age eviction, at the cost of upload-time metadata. Revisit with soak data                       |
| UnifiedPush wake-ups                       | Per-scope random topics, to dodge endpoint linkability                                               |
| QUIC binding                               | Would define its own framing (§7.1)                                                                  |
| Storage watermark trim                     | Operator-side                                                                                        |
| Padding / cover traffic                    | Against the §10.1 timing signal                                                                      |
| Per-conversation opt-out UX                | The plane is currently global                                                                        |
| **Scope auth**                             | A TOFU `HKDF(scopeRoot, …)` credential closing the leaked-scopeId subscribe/flood hole §10.4 accepts |
| Time-based group root re-mint              | Periodic metadata-PFS and spool unlinkability, using the existing §3.2 machinery                     |
| **`sealv = 2`**                            | The epoch-keyed outer seal. The ratchet `exportEpochSeal` surfaces are reserved for it               |
| Client→spool `digest`                      | The record is direction-agnostic already                                                             |
| `CAP_SPOOL` capability bit                 | Only if client UX ever needs a peer-support signal                                                   |
| **Persisted partial attachment downloads** | §9.5 keeps them in memory, so a process death re-fetches                                             |

## 12. Constants [Both]

### 12.1 Structural constants: these are the protocol

| Constant                        | Value                                             | Tied to                                                                                  |
|---------------------------------|---------------------------------------------------|------------------------------------------------------------------------------------------|
| record-layer version            | 1                                                 | HELLO negotiation                                                                        |
| scopeId / blobId / roots / keys | 32 B                                              | HKDF / SHA-256 native width                                                              |
| seal nonce / tag                | 12 B / 128 bit                                    | AES-256-GCM profile (§2)                                                                 |
| frame `sealv`                   | `0x01`                                            | Blob leading byte; `0x02` reserved (§11)                                                 |
| attachment `sealv`              | `0x03`                                            | §4.5                                                                                     |
| digest                          | FNV-1a-64 XOR fold, empty = 0, 8 B BE byte string | §6.3; the mesh custody digest's shape                                                    |
| `aChunkBytes`                   | 49152 (48 KiB)                                    | §4.5 — **not tunable**: it is what makes a chunk's position derivable without a manifest |
| sealed chunk size               | 49221 B (`1 + 12 + 40 + 49152 + 16`)              | §4.5 — sized to stay inside the 64 KiB `maxBlob`                                         |

### 12.2 Defaults and suggestions: operator- or config-tunable

| Constant                                 | Value                                                   | Tied to                                                                                                                 |
|------------------------------------------|---------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| default scope `ttlMs`                    | 48 h                                                    | 2× mesh custody TTL = the rotation drain window. Longer retention stores frames the inner ratchet may no longer decrypt |
| default `maxFrames`                      | 400                                                     | 2× the mesh's 200-per-sender custody bucket                                                                             |
| default `maxBlob`                        | 64 KiB                                                  | Comfortably above any mesh frame                                                                                        |
| tombstone TTL                            | = scope `ttlMs`                                         | §6.2                                                                                                                    |
| suggested tombstone count bound          | max(2 × `maxFrames`, 1024) per scope, oldest-first drop | §6.2 — caps the eviction-cycling growth vector                                                                          |
| rotation drain (old scope subscribed)    | 48 h                                                    | DM prev-root TTL mirror (§3.1, §3.3)                                                                                    |
| `mintGrace` (non-preferred minter waits) | 6 h                                                     | §3.2 — one waking day's slack before a second lineage appears                                                           |
| `maxRootVersion` / `maxRootVersionJump`  | 16 / 8                                                  | §3.2 adoption bound. The roster caps at 8, so legitimate versions never approach it                                     |
| PoW default / window                     | 20 bits / ±1 day UTC                                    | §8                                                                                                                      |
| suggested `maxScopes` / `maxPull`        | 64 / 64                                                 | HELLO-advertised, spool-tunable                                                                                         |
| max attachment / `total`                 | 8 MiB / ≤ 171 chunks                                    | The app's own attachment cap. Bounds every allocation sized by a peer-supplied `total` (C-4.5-8)                        |
| default per-scope `maxAttachBytes`       | 16 MiB                                                  | §6.5 — 2× one maximal attachment, so a scope holds a little history without becoming storage                            |
| suggested `maxAget`                      | 32                                                      | ≈1.5 MiB per batch. HELLO-advertised, spool-tunable                                                                     |
| `pairGrace`                              | 48 h                                                    | §3.5 — a pair scope outlives our own confirmation by the spool's default retention, so the answer can still be pulled |
| `maxPairScopes`                          | 8                                                       | §3.5 — headroom under `maxScopes`                                                                                       |
| suggested invalid / accounted set bound  | 512 ids per (spool, scope), oldest-first drop           | §9.3, §9.6 — above a full scope (`maxFrames` = 400), so eviction is the pathological case, not the ordinary one       |
| intro re-send / answer floors            | 20 h / 1 h                                              | §3.5 client policy: re-send an unconfirmed intro under the 24 h custody TTL; answer an init-bearing peer at most hourly |

## 13. Test vectors [Both]

Pinned verbatim by `ScopeVectorTest` and `SpoolRecordsTest`; regenerate only with an intended scheme
change, updating both together. Byte-array fixtures follow
`fixture(n, seed)[i] = (7·i + seed) mod 256`;
all values lowercase hex.

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

Pair scope (§3.5; appended 2026-08-25 — the rows above did not move). `IK_A` is the X25519 scalar
`fixture(32, 3)`, `IK_B` the scalar `fixture(32, 8)`; the secret is `X25519(IK_A, pub(IK_B))`, and the
context is the DM context of `nodeIdA`/`nodeIdB`:

```
pairSecret      = 536a5e63f420ed78cd6166913a87d57562938cc18d2992f443ded7e7eca0f744
pairScopeId     = bf46c96f08e53c8db14c1343c3fac9e5863732addae8baa0de2cf7681ca26855
pairSealKey     = a9fc082b054b4e903b304143996471960eb3cd3b075e6537e2dc556f4856de95
pairNonceKey    = e560060de754aa7d3759188568cbbb1cc2a7eccdeba81bfb9bdd68bc35b81285
```

Seal (§4; keys and scopeId are the DM values above; deterministic, so sealing twice is
byte-identical):

```
blob   = 01e6844e8145bbc9581e53f9b0c4019dba5968bb7216685432e7412e1e9a56c8136af0829fdea4c8613b18b7
         df038f08613fa34fd474f93da53da1a05c0350bf9b681290b880083a5593839b08b2496f79d7ddcaefc40943
         d1c0757ce594a12326d551e07a62528d62744ef30b9b24bea8a58856a6545436d099519a1706e1308b3ffe432e
blobId = 8e5c2b6d8be66bb1204b644ebcc62f923bb27b659ecffb9344d35f7eb930d9c2
```

Attachment seal (§4.5; same DM keys and scopeId; `aHash = fixture(32, 6)`, a **one-chunk**
attachment —
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

## Appendix A. Implementation status

Non-normative. What the spec describes that runs today, and what is specified but not yet on the
wire.

| Section                                         | Status                                                                                                                                     | Where                                                            |
|-------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| §2 encodings, §6.3 digest                       | Shipped both sides                                                                                                                         | `ScopeCrypto`, `SpoolCodec`, `knit-spool`                        |
| §3.1 DM scopes                                  | Shipped                                                                                                                                    | `ScopeCrypto`, `ScopeRegistry`                                   |
| §3.2 group root, §3.3 group scopes              | Shipped                                                                                                                                    | `GroupRootPolicy`, `GroupRootStore`, `GroupKeyPayload.gr`, DB v3 |
| §3.5 pair scopes                                | Shipped                                                                                                                                    | `ScopeCrypto.pairSecret`, `ScopeRegistry.pairs`, `IntroSync`      |
| §4.1–§4.4 sealing and frame rules               | Shipped                                                                                                                                    | `ScopeCrypto`, `ScopeFrames`                                     |
| §4.5 attachments                                | Shipped                                                                                                                                    | `ScopeCrypto.sealChunk`, `ScopeAttachments`                      |
| §5 scope config ctl                             | **Reserved, not shipped.** `ctl = 7` is named and never recycled; the spool list is a device setting and bounds are §12 defaults meanwhile | `MessageContent`, `ScopeRegistry`, `SettingsStore.spoolUrls`     |
| §6 spool behaviour                              | Shipped                                                                                                                                    | `knit-spool` + conformance suite                                 |
| §7 record layer, §7.3 attachment records        | Shipped both sides                                                                                                                         | `SpoolRecords`, `SpoolConnection`, `knit-spool`                  |
| §8 proof of work                                | Shipped both sides                                                                                                                         | `SpoolPow`                                                       |
| §9.1–§9.4 heal loop, guard, invalid set, bridge | Shipped                                                                                                                                    | `ScopeSync`                                                      |
| §9.6 accounted set                              | Shipped, per-connection lifetime only — a process restart re-pulls the band once (C-9.6-4 permits it)                                       | `ScopeSync`                                                      |
| §9.5 attachment fetch and refill                | Shipped, minus persisted partial downloads (§11)                                                                                           | `ScopeSync`, `ScopeAttachments`                                  |
| §9.5 push-half deferral                         | Shipped, DM scopes only                                                                                                                    | `AttachmentDeferPolicy`                                          |
| §10 Tor for the IP edge                         | Deferred                                                                                                                                   | §11                                                              |

The plane is **off by default** and gated behind a one-time consent disclosure. With it off, or with
no
spool configured, the client opens no socket at all.

## Appendix B. Change log

Non-normative. Wire compatibility is stated per entry: no entry has changed a spool record, a
derivation,
or a §13 vector. Two entries touch a *mesh* frame payload rather than this plane — 2026-08-19 adds a
field, 2026-08-23 stops populating one — both additively and without moving an existing golden vector;
the plane itself was unaffected either time, since a spool never decodes a frame.

| Date       | Change                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | Asks of implementers                                                                                                                                                                               |
|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 2026-08-15 | v1 draft published with ADR 019                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | —                                                                                                                                                                                                  |
| 2026-08-16 | Eight ambiguities the daemon build surfaced, resolved as semantic clarifications (§6.2, §6.4, §7.1, §7.2, §12)                                                                                                                                                                                                                                                                                                                                                                                                  | None                                                                                                                                                                                               |
| 2026-08-16 | **Group scopes (M4).** §3.2's v1 mint opened from the creator to any member, with preferred-minter-plus-grace damping, which also unfreezes a departure re-mint whose re-minter never comes back. Adoption gained two mandatory insider-DoS bounds (C-3.2-10, C-3.2-11). §4.4 pinned where a group frame's id actually lives                                                                                                                                                                                    | None for spools: a spool never sees a root                                                                                                                                                         |
| 2026-08-16 | **Attachments (M5).** §4.5, §6.5, §7.3 and §9.5 added as fresh sub-numbers, so no existing cross-reference moved                                                                                                                                                                                                                                                                                                                                                                                                | **Spools only.** An attachment-capable spool advertises three new HELLO limits and answers five new records; one that does not omits them and is left alone                                        |
| 2026-08-17 | **Deferred attachment uploads (ADR 021).** §9.5's push half became a bounded MAY (C-9.5-5…9), priced in §10.1                                                                                                                                                                                                                                                                                                                                                                                                   | None. Invisible at a spool beyond a later `aput`                                                                                                                                                   |
| 2026-08-17 | **Formalisation and accuracy pass.** Requirement identifiers throughout, rationale separated from normative text, Appendices A and B added. Corrected against the implementations: the DM frame rule's unset-group and ratchet-header conditions and the group rule's exclusion of v1-wrapped chat (§4.4); `groupupdate` group photos as a second attachment reference shape (§9.5); the deferral rule's per-recipient evidence requirement, which is what confines it to DM scopes (§9.5); §5's shipped status | None. No wire field, derivation or vector changed                                                                                                                                                  |
| 2026-08-19 | **Profiles cross the plane (ADR 022).** §4.4 admits `type = profile` into both scope forms — matched on sender alone in the DM half (C-4.4-5…7), on the founding roster in the group half (C-4.4-13). It is the only carrier of `ProfileContent.prekey`, so without it an Internet-only peer could never learn a rotated prekey, re-establish a broken DM session, or receive the group sender-key seeds that ride as ctl DMs                                                                                   | None for spools. Clients: profile blobs now fold into the scope digest, so a member on an older build quarantines them (C-9.3-1) and reports itself unconverged for that scope until it is updated |
| 2026-08-23 | **The attachment MIME leaves the mesh frame (ADR 035).** §9.5's reference table drops `attachmentMime` from the `chat` row; C-9.5-1 generalises to "a frame need not name a mime"; new C-9.5-10 says where a fetcher gets one instead. Client-side only — §4.3 seals the whole frame, so a spool never observed the field                                                                                                                                                                        | None for spools, and no record, derivation or §13 vector changed. Clients: tolerate a reference with no mime (already required for `groupupdate`) and resolve the type locally                     |
| 2026-08-24 | **Capacity refusal (§7.1).** New S-7.1-10 and C-7.1-11 write down what a spool at its connection cap does — refuse the upgrade `503` with `Retry-After`, never a close code — and what a client does about it                                                                                                                                                                                                   | **Spools:** optional; a spool with no cap is unaffected. **Clients:** a refused upgrade is not a close code, so a client that reports only close codes shows a full spool as an unexplained transport error                     |
| 2026-08-25 | **Pair scopes (ADR 042).** New §3.5: a scope both members derive from their *identity* DH keys, so a pair that has only exchanged a contact card out of band (`docs/CONTACT_CARD.md`) can meet at a spool before a session exists. Carries the §4.4 DM frame set unchanged; subscribed only while an intro is pending plus a 48 h grace. §1.1/§1.4 wording, §3.4 row, §10.1 bullet, §10.3's identity-file row narrowed to *conversation* scopes, §12 constants, four §13 rows appended                                                                                                                                                                                              | None for spools: one more opaque id. Clients: a new label family under `knit/scope/v1/pair/…`; no record, no existing vector moved                                                                                                |
| 2026-08-30 | **The accounted set (ADR 062).** New §9.6: a pulled blob that passed §4.4, bridged, and that local custody did not keep is counted as held and never pulled again (C-9.6-1…4), with §12.2 gaining the set bound. Closes the divergence §9.3 was written for, arriving through the one door §9.3 does not cover — a *valid* blob in the 24–48 h band between the mesh custody TTL and the scope TTL, which no client could ever fold into its digest | None for spools. Clients: a scope that has been reporting `converged = false` for the back half of the spool's retention should now settle, and stop re-pulling that band on every reconnect |
