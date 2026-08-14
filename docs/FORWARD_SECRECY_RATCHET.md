# Forward secrecy for DMs — the epoch-rekey ratchet (crypto scheme v2)

Status: **implementing** · plan approved 2026-08-14 · lands in phases (crypto core → wire → schema →
receive → send → reset hardening → observability). This document is the normative spec for the v2 DM
crypto scheme; `mesh/crypto/ratchet/` is the reference implementation and
`RatchetCryptoTest`/`RatchetEngineTest` are the executable anchors (an iOS/CryptoKit port implements
this file, not the Kotlin).

## 1. Why, and why this shape

The v1 scheme (static keys: per-message random AES-256-GCM content key, HPKE/X25519-wrapped per
recipient — `docs/ARCHITECTURE.md` §crypto) has no forward secrecy: record ciphertext today,
compromise the identity key file whenever, decrypt everything ever sent. That is tolerable for a
proximity mesh and fatal for the planned internet relay plane, whose relays hold sealed frames at
rest — every relay disk would be a harvest-now-decrypt-later honeypot. The ratchet therefore ships
*before* that plane, and doubles as its key source: scope derivation needs a ratcheted pairwise
secret (§8).

Three decisions were locked by the maintainer up front:

1. **Epoch-rekey ratchet, not a full Double Ratchet.** PFS at *epoch* granularity (a bounded window
   of messages shares a compromise fate) in exchange for a much smaller state machine. The mesh's
   delivery model — verbatim ciphertext re-served for 24 h, permanent mid-chain holes from custody
   quota eviction, no ordering anywhere — punishes per-message DH ratcheting; epochs fit it.
2. **X3DH-style bootstrap via a signed prekey published in the profile.** DMs stay offline-first:
   the first message to a peer whose profile is pinned is self-contained (no round trip), exactly
   like v1's encrypt-to-static-key, but forward-secret once the prekey rotates.
3. **Session state in Room** (`knit.db`, SQLCipher): ratchet advance + message insert + skipped-key
   consumption commit in **one transaction**, which closes the classic crash window between "state
   advanced" and "plaintext persisted". Cost: a DB wipe loses sessions — handled by the session
   reset path (§7), which is required anyway for reinstalls.

Scope: **DMs only.** Groups keep the v1 per-member wrap (group key state is its own future design),
the Nearby broadcast room stays plaintext by design, receipts/reactions stay cleartext-signed
(their encryption is a separate roadmap item).

## 2. Keys

| Key | Lifetime | Where | Purpose |
|---|---|---|---|
| Identity X25519 (`IK`) | device lifetime | `filesDir/identity.key` (Tink HPKE keyset; raw scalar extracted for v2) | X3DH identity DH; also v1 HPKE. Public half is the `hpkePub` already in `PublicKeyBundle` — **nothing about identity or nodeId changes** |
| Identity Ed25519 | device lifetime | `identity.key` | frame signatures (unchanged); signs prekeys |
| Signed prekey (`SPK`) | rotates every 7 d; privs kept: current + 4 (~35 d) | `identity.key` (`Stored.prekeys`, additive field) | X3DH medium-term DH base; survives DB wipes so in-flight inits stay openable |
| X3DH ephemeral (`EK`) | one derivation | never stored (pub rides the init until confirm) | binds root freshness |
| Epoch keypair | until superseded + acked + 48 h (≤16/peer, 30 d hard cap) | `ratchet_local_epochs` | per-epoch DH; **its retention is the PFS window** |
| Chain / message keys | delete-as-you-go; skipped keys ≤48 h | `ratchet_recv_epochs` / `ratchet_skipped_keys` | per-message AEAD keys |

Reusing the HPKE X25519 identity key for X3DH is safe by domain separation: every v2 derivation is
labeled `knit/dm/v2/...` (HKDF-SHA256), disjoint from RFC 9180's internal labels. This is the same
posture Signal-family protocols take with their identity keys, and it keeps `PublicKeyBundle` —
whose hash **is** the nodeId — byte-identical.

### 2.1 Signed prekey publication

`ProfileContent` gains an additive nullable field:

```
PrekeyInfo { id: Int, pub: bytes(32), sig: bytes(64) }   // sig = Ed25519 over spkSigningBytes
spkSigningBytes(id, pub) = "knit/dm/v2/spk" ‖ u32be(id) ‖ pub
```

The profile frame's Ed25519 signature already covers the field; the *detached* `sig` exists so the
prekey stays re-verifiable once stored apart from its frame (the `peers` row), and so a non-Knit
implementation can verify a prekey in isolation. Receivers verify `sig` against the (self-certified,
immutably pinned) bundle before adopting. Rotation mints `id+1`, bumps `profileVersion`, and rides
the ordinary profile re-flood; a *newer* profile carrying `prekey = null` clears the pin (peer
downgraded — outbound falls back to v1).

## 3. Session bootstrap (X3DH, no one-time prekeys)

Initiator A → responder B (B may be offline; A holds B's pinned profile = `IK_B` + `SPK_B`):

```
DH1 = X25519(IK_A, SPK_B)      // binds A's identity
DH2 = X25519(EK_A, IK_B)       // binds B's identity + EK freshness
DH3 = X25519(EK_A, SPK_B)      // the FS core: both inputs deletable/medium-term
sessionRoot = HKDF-SHA256(ikm = 0xFF*32 ‖ DH1 ‖ DH2 ‖ DH3, salt = 0*32, info = "knit/dm/v2/x3dh", L = 32)
```

One-time prekeys are a deliberate omission: a serverless mesh cannot track consumption. The cost is
X3DH's standard replay caveat (a captured init can be replayed to re-create the same root — §7's
idempotence rules make that a no-op, and the frame signature prevents third-party forgery).

B computes the mirror (`x3dhRespond`) from the init riding the first frame(s): `EK_A` pub +
`pkid` + the sender's pinned bundle. Everything B needs arrives with the frame — `verifyInbound`
already requires the sender's profile before any DM is processed, so "profile pinned" is an
invariant, not a new requirement.

## 4. Epochs and message chains

Each direction advances through numbered **epochs**. The sender of epoch `se` mints a fresh X25519
pair `(ek, ekPriv)` and DHs against the peer's newest contribution (`pe` = which of the *receiver's*
epochs supplied the base; `pe = 0` means the receiver's SPK, which is only legal while an init is
attached):

```
epochSecret = HKDF-SHA256(ikm = X25519(ekPriv, basePub), salt = sessionRoot,
                          info = "knit/dm/v2/epoch" ‖ dir ‖ u32be(se) ‖ u32be(pe), L = 64)
  dir        = 'i' when the epoch's sender initiated the session, 'r' otherwise
  chainKey_0 = epochSecret[0..31];  epochExport = epochSecret[32..63]
msgKey_n     = HKDF(chainKey_n, salt = 0*32, info = "knit/dm/v2/mk", 32)
chainKey_n+1 = HKDF(chainKey_n, salt = 0*32, info = "knit/dm/v2/ck", 32)
```

AEAD: AES-256-GCM (12-byte random IV, 128-bit tag), key `msgKey_n`, **AAD = the unchanged v1
header** `"$id|$senderId|$sentAt|$thread"`. A tampered ratchet header changes the derived key (the
AEAD fails), and the whole content — header included — is under the frame's Ed25519 signature, which
is verified before decrypt; the header needs no second integrity mechanism.

**`sessionRoot` is static per session — there is deliberately no cumulative root chain.** A chained
root (`RK_n = KDF(RK_{n-1}, DH_n)`) requires processing a peer's epochs in order, and this mesh
*guarantees* permanent holes: custody evicts oldest-by-`(sentAt, id)` under a 200-per-sender quota,
so a wholly-evicted epoch would wedge the session forever. Independent epoch derivation means any
subset of epochs, in any order, decrypts; a lost epoch loses only itself. Post-compromise healing
survives at **round-trip granularity**: each side's next epoch uses a fresh DH pair, so once both
sides have rotated after a compromise (and the attacker has lost live access), new epoch secrets are
out of reach even with the root.

**Advance rules** (sender starts a new epoch when any fires):

1. session init (epoch 1);
2. first send after the peer's newest contribution advanced past the current epoch's base — the
   healing half of the ratchet: every conversational turnaround rekeys;
3. `sendCount ≥ 200` — one epoch can never exceed the per-sender custody quota, so a re-served
   backlog spans at most ~2 epochs of skipped-key work;
4. epoch age ≥ 24 h — the custody TTL: no legitimately re-deliverable frame belongs to an epoch
   more than one retention generation old;
5. (forced) after a root change — race adoption or session replacement nulls the send chain.

`ek` rides **every** frame, not just the first of an epoch: with permanent holes, no particular
frame's arrival can be load-bearing. Each frame fully describes how to derive its epoch.

**Numbering is monotone for the life of the local state**, across root replacements (§7): only a
device wipe resets `se` to 1 — and a wipe also discards the old epoch privs, so a reused `(peer,
se)` can only follow a session replacement, which purges the receiver's stale rows.

## 5. Wire form (additive; see docs/WIRE_COMPAT.md)

`EncEnvelope` v2 (same type, `MAX_SUPPORTED_VERSION = 2`; `keys = []` — the message key is derived,
never wrapped, so `WrappedKey` has no v2 role):

```
EncEnvelope { v = 2, nonce, ct, keys = [], r: RatchetHeader }
RatchetHeader { se: Int, ek: bytes(32), pe: Int, n: Int, init: RatchetInit? , flags: Int = 0 }
RatchetInit  { eph: bytes(32), pkid: Int, at: Long }    // attached to EVERY frame until confirmed
flags: bit0 = RESET (§7)
```

Old builds decode the envelope (ignoring `r`), hit `v > MAX_SUPPORTED_VERSION`, and take the
existing `UNKNOWN_ENVELOPE_VERSION` drop-locally-still-relay path; `canCarry` never inspects `v`
(rule 5 of WIRE_COMPAT), so mixed-version meshes carry v2 custody exactly like v1. Steady-state
overhead ≈ 50 B per frame (+ ~95 B while unconfirmed).

Capability gating: `Protocol.CAP_RATCHET = 0x10` (append-only). Outbound v2 requires the peer's
pinned, authenticated profile to carry **both** the capability bit **and** a valid `PrekeyInfo` —
they travel on one signed frame, which is the stale-capability mitigation. Otherwise outbound stays
v1 (kept indefinitely; inbound v1 accepted forever).

## 6. Receive ladder and delivery semantics

The mesh re-delivers verbatim ciphertext routinely (60 s re-offer loop; the flood-dedup SeenSet is
in-memory, 10 min, reset per mesh session). Two consequences are baked in:

- **The pre-decrypt exists-gate**: `decryptAndDeliver` short-circuits any encrypted chat whose
  `env.id` is already in `messages` (ack still runs — the vaccine-purge semantics are untouched).
  Without it, "delete the message key after use" would turn every custody re-serve into a
  `DECRYPT_FAILED`. It also spares v1 an HPKE unwrap per re-serve.
- **The open ladder** (engine, pure): stored skipped key → live chain of a known recv epoch
  (deriving-and-storing keys across any index gap, ≤200/epoch) → fresh epoch derivation, trying the
  active root then the draining `prevRoot`. Typed failures map to drop reasons:
  `RATCHET_NO_SESSION` / `RATCHET_EPOCH_GONE` / `DUPLICATE` (benign) / `BAD_HEADER` / `AEAD_FAIL`.
  All are delivery-local; the frame still relays and custodies (the no-throw contract of
  `onDeliver` holds — nothing in the v2 path throws out).

Persistence is atomic: the engine returns `(plaintext, StateDelta)` and the pipeline commits the
delta with the message row in one `withTransaction`. A crash before commit re-processes cleanly on
the next re-serve (state unchanged, message absent). The send side mirrors this (commit the chain
advance + local row, then flood); a crash between commit and flood is just a chain hole the
skipped-key path absorbs.

## 7. Sessions: races, replacement, reset

- **Both-initiate race.** Two unconfirmed roots meet. Winner (both sides compute it): the init
  whose *initiator* has the lexicographically smaller nodeId — deliberately not timestamp-based,
  since concurrent inits can carry identical `at`. The loser's root drains as `prevRoot` (48 h =
  2× custody TTL) so its in-flight epochs still open; numbering monotone, no collisions.
- **Init idempotence.** A session's resolved peer-init is remembered by its ephemeral key
  (`peerInitEphPub`). Re-served copies — which outlive the resolution by up to the custody TTL, and
  can carry a *newer* timestamp than the session they lost to — match the anchor and are treated as
  already-resolved, never as replacements.
- **Replacement.** A genuinely new init (`init.at > establishedAt`, unknown eph) from a confirmed
  peer means they lost state (wipe/reinstall): adopt the new root, archive the old to `prevRoot`,
  and **purge the peer's recv epochs + skipped keys** — their numbering restarts at 1. Old-era
  frames whose epoch numbers don't collide with new rows drain via `prevRoot`; colliding ones fail
  benignly (`DUPLICATE`/`AEAD_FAIL`) — anything delivered pre-wipe is exists-gated anyway, and the
  reset re-seal (below) covers the undelivered.
- **Reset request** (lands in the reset-hardening phase). Trigger: ≥3 distinct undecryptable-v2
  frame ids from a pinned peer (`RATCHET_NO_SESSION`/`RATCHET_EPOCH_GONE`), rate-limited to one per
  6 h (persisted). The request is an ordinary v2 DM carrying a fresh init, `flags = RESET`, and
  `MessageContent.ctl = CTL_SESSION_RESET` — deliberately *not* a new frame type, which v1 relays
  would refuse to custody (`isCustodial` is a fixed list); as a chat frame it floods, custodies,
  and reaches an offline peer. The receiver (frame-signed, newer `init.at`) adopts per the
  replacement rules and **re-seals its still-unacked DMs from the last 24 h** under the fresh
  session (the `flushPendingFor` mechanics: fresh seal, original `id`/`sentAt` header), recovering
  what the wiped device lost from custody. Control frames are never persisted, notified, or acked
  as messages; inbound replacements are additionally rate-limited (1/peer/h).

## 8. Export API (for the internet relay plane; no consumer yet)

```
pairwiseRoot = HKDF(sessionRoot, salt = 0*32, info = "knit/dm/v2/export/root", 32)   // symmetric
epochSeal    = HKDF(epochExport, salt = 0*32, info = "knit/dm/v2/export/epoch", 32)  // rotates per epoch
```

`pairwiseRoot` is what the relay ("spool") design derives scope ids from; `epochSeal` is the
rotating sealing-key input ("sealing keys rotate with ratchet epochs"). Epoch secrets are pairwise-
shared per (direction, epoch) — the receiver derives the same `epochSecret` to decrypt — so either
side can compute the other's seal keys for a scope. The exact consumer shape is pinned by the
relay-plane design doc, not here; until then this surface is API-only.

## 9. Security claim (honest, epoch-granular)

Full compromise of a device at time T exposes, per DM conversation:

1. **Stored plaintext history** (`messages`, SQLCipher at rest) — outside the crypto scheme's
   scope, as in every E2E messenger.
2. **Recorded ciphertext of epochs whose DH is still computable from retained material**: epochs
   whose own-side priv is still in `ratchet_local_epochs` (deleted at superseded + peer-acked +
   48 h; ≤16 per peer; 30 d hard cap), plus init-epoch traffic sealed against a retained SPK
   (≤ ~35 d).
3. **Future traffic until both sides complete one fresh epoch each** after the attacker loses live
   access (round-trip healing).

Identity-file-only compromise (no DB) exposes only slice 2's init-epoch portion. Anything beyond
the retention horizons is irrecoverable — that is the forward-secrecy guarantee.

Non-goals: per-message PFS; one-time prekeys; deniability changes (frames stay Ed25519-signed, as
v1); hiding DM routing metadata (`recipientId` and epoch metadata remain visible to relays, as
today); group/broadcast coverage (separate designs).

## 10. Constants (convergence-relevant ones mirror custody's — change together or not at all)

| Constant | Value | Tied to |
|---|---|---|
| `MAX_EPOCH_MESSAGES` | 200 | per-sender custody quota |
| `MAX_EPOCH_AGE_MS` | 24 h | custody TTL |
| `PREV_ROOT_TTL_MS` | 48 h | 2× custody TTL |
| recv-epoch / skipped-key sweep | 48 h after last use | 2× custody TTL |
| skipped keys | ≤200/epoch, ≤2000 global | epoch cap; DoS bound |
| local epoch privs | superseded+acked+48 h; keep newest 3; ≤16/peer; 30 d hard | the PFS window |
| SPK rotation / retention | 7 d / current + 4 | initiation-lateness window |
| reset trigger / outbound rate / inbound rate | 3 distinct ids / 6 h / 1 h | abuse + loop bounds |
