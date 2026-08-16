# Wire forward-compatibility

Read this before touching anything under `mesh/protocol/` or `mesh/crypto/`. Once the app has a real
installed base, a breaking wire change silently partitions the mesh (there is no version negotiation
that can route around it). The format is layered specifically so that almost every future change can be
**additive** instead. These rules keep it that way.

## The layered wire (why it is resilient)

`mesh/protocol/Wire.kt` defines three layers, serialized with one CBOR config
(`ignoreUnknownKeys = true`, `encodeDefaults = false`):

1. **`WireEnvelope`** — the on-radio unit, **frozen forever**. Holds the mutable routing counters
   (`ttl`/`hops`), a `relay` flag, the raw Ed25519 `sig`, and the opaque `signed` blob. It is the only
   layer a relay re-encodes: `WireEnvelope.relayed()` rewrites only `ttl`/`hops` and reuses `signed` +
   `sig` **by reference**, so the bytes the originator signed are forwarded verbatim at every hop.
2. **`RelayEnvelope`** — what `signed` decodes to: only the cleartext fields a relay or store-and-forward
   carrier needs to route (`type`, `id`, `senderId`, `sentAt`, `recipientId`, `group`) plus an opaque
   `payload`. Relays never re-encode it, so additive fields here survive an old relay too.
3. **Per-type content** (`ChatContent`, `ProfileContent`, …) inside `payload`. Only endpoints parse it.

Two load-bearing decisions you must not undo:

- **`sig`, `signed`, and `payload` are `@ByteString ByteArray` (opaque), never nested `@Serializable`
  objects.** If `signed` were a nested object, the outer re-encode on relay could legally reorder CBOR
  keys and break the signature — the exact bomb the layering removes.
- **`RelayEnvelope.type` is a plain `String` discriminator on a concrete class, not kotlinx sealed
  polymorphism.** An unknown `@SerialName` makes a polymorphic decode *throw* (→ frame dropped, not even
  relayed). A plain string decodes fine, so an old build still routes and forwards a `type` it doesn't
  understand — closing the new-frame-type black hole.

## The four version layers

Each evolves independently; bump the right one:

- **Endpoint-info `protoVersion` + `capabilities`** (`Protocol.VERSION` / `Protocol.CAP_*`): the
  advert/handshake hint (Wi-Fi Aware `serviceSpecificInfo` / the BLE service-data payload), known at
  connection time, **unauthenticated** — a routing/degradation hint only, never a trust input.
- **`RelayEnvelope.type` registry**: `chat`, `groupupdate`, `groupleave`, `profile`, `receipt`,
  `reaction`, `blobreq`, `keyreq`, `typing`.
- **`EncEnvelope.v`**: the E2E crypto scheme — `1` = static keys (AES-GCM + per-recipient HPKE wrap),
  `2` = the ratchet schemes (AES-GCM under a derived key, `keys` empty; the DM form's header rides
  `EncEnvelope.r` — `docs/FORWARD_SECRECY_RATCHET.md` — and the group sender-key form's rides
  `EncEnvelope.g` — `docs/GROUP_FORWARD_SECRECY.md`; forms split on addressing, not on `v`).
- **`MessageContent.v`**: the decrypted plaintext schema.

## Rules that keep changes additive

1. **Add only nullable/defaulted fields.** New fields on any wire/content/envelope type MUST be
   `T? = null` or have a default — `encodeDefaults = false` then omits them on the wire and
   `ignoreUnknownKeys = true` makes an older peer ignore a newer peer's extra field. Precedent:
   `ProfileContent.deviceTag`, `protoVersion`, `capabilities`. (Exception: `@ByteString ByteArray` fields
   are kept non-default — kotlinx can't cheaply detect a default `ByteArray` — so add a new opaque blob
   as its own type, not a defaulted `ByteArray` field.)
2. **Never rename, re-type, or repurpose an existing field.** CBOR keys by the Kotlin property name;
   changing a name, type, or *meaning* in place silently mis-decodes against deployed peers. To change
   semantics, add a new field and deprecate the old.
3. **Never recycle a `type` string** (or a capability bit position). A retired `type` is burned forever;
   reusing it makes an old peer decode the new frame as the old type. Capability bits and version numbers
   are append-only; versions only increase.
4. **Signature input is the whole `signed` blob, verbatim.** `MeshManager.sign` signs
   `WireCodec.encodeEnvelope(env)`; `verifyInbound`/`canCarry` verify against the exact `wire.signed`
   bytes received. Do not reintroduce a per-field canonicalization or re-encode-before-verify step — the
   verbatim-bytes contract is what makes additive fields safe through old relays. `ttl`/`hops` are the
   only mutable-in-flight fields and they live in the (unsigned) `WireEnvelope`, never in `signed`; if
   you ever need another in-flight-mutable field, it MUST go in `WireEnvelope` (unsigned), not in
   `RelayEnvelope`.
5. **A version gate is a *delivery* gate, not a *relay* gate.** An unknown `EncEnvelope.v` /
   `MessageContent.v` → drop locally + `metrics.onDropped(...)`, but still relay/carry (never throw out
   of `onDeliver`; never gate `canCarry` on a scheme version) so a peer that *can* read it still
   receives it.

## Wire-breaking vs. additive changes

**Breaking** (needs a coordinated one-time bump of **both** discovery markers — Wi-Fi Aware
`SERVICE_NAME` *and* BLE `SERVICE_UUID` — plus the DB version) if it: removes, renames, re-types, or
repurposes a field or a `type`; changes `WireCodec`'s config or the `@ByteString` opacity of
`signed`/`sig`/`payload`; changes what `signed` is signed over, the AEAD `header`, the `NodeId`
derivation, or a discovery marker; or makes `RelayEnvelope.type` polymorphic.

**Additive** (safe) if it only adds a nullable/defaulted field to a content/envelope type, a new `type`
string with its own content class, or a new capability bit — and rule 4 holds.

> **Pre-1.0 alpha history.** The precedents below (DB v19 / v21 / v22) document the coordinated wire/discovery
> breaks taken *during pre-release alpha*, when the app had no installed base and every schema bump wiped
> destructively. They are retained as the historical break record and cross-platform rationale. **v1 is the
> production launch baseline** — the markers were reset in lockstep (`SERVICE_NAME` `_knitmesh1._tcp`, BLE
> `SERVICE_UUID` `0xFE30`, `Protocol.VERSION` `1`, DB `v1`); from v1 on the DB migrates forward (no destructive
> fallback) and any wire change either follows the additive rules above or is a real, coordinated break with a
> genuine installed base to protect.

**Precedent — populating an existing field in a new case is additive, not a rule-2 repurpose.** DB v19
began setting the already-existing `ChatContent.attachmentHash`/`attachmentMime` on E2E DM/group frames
too (with the message's *ciphertext* hash), where they were previously null — only the plaintext
broadcast room filled them. The field's *meaning* is unchanged ("the content address to pull for this
message's image"), so an old peer harmlessly ignores it (and on delivery overwrites it with the identical
value decrypted from `MessageContent`), and the frame still verifies byte-exact. The decryption key stays
sealed inside `MessageContent`. This lets a relaying **carrier** — blind to the encrypted refs — see the
blob and custody it (store-and-forward for images). No `SERVICE_NAME` bump; the DB bump is local.
*Metadata cost:* a carrier learns a message carries an image (~size); a fresh per-send attachment key
means the ciphertext hash never correlates identical images across sends.

**Precedent — a coordinated break (DB v21): the 128-bit nodeId.** The nodeId was widened from an 8-char
`[a-z0-9]` (~41-bit) hash to **128 bits** of SHA-256, RFC4648-base32-encoded to a 26-char `[a-z2-7]`
string (`NodeId.kt`, salt bumped to `knit-node-id-v2:`). Since the `NodeId` derivation is a breaking
change (§ above) — every node re-derives a different id from the *same* keypair, so signatures/pins/
custody against the old ids no longer verify — all three markers bumped in lockstep: `SERVICE_NAME`
`.v6 → .v7`, BLE `SERVICE_UUID` `0xFE30 → 0xFE31`, DB `version 20 → 21` (destructive wipe clears the
stale pins + old-format custody). The BLE advert also changed shape (the id now rides as its raw 16
bytes, and the redundant service-UUID-list AD was dropped to keep the payload inside the 31-byte legacy
budget — see `BleAdvertPayload`), which the `SERVICE_UUID` bump already partitions. `Protocol.VERSION`
went `1 → 2` for honesty (nothing gates on it). The keypair itself is untouched (it lives outside the DB),
so no identity is lost — every device just re-derives a wider id.

**Precedent — a coordinated break (DB v22): de-Tink the crypto wire layout.** The published key bundle and
the crypto byte layouts were made Tink-free for cross-platform (iOS) interop: `PublicKeyBundle` now carries
two **raw 32-byte** keys (CBOR `{sigPub, hpkePub}`, `@ByteString`) instead of serialized Tink `Keyset`
protos; `IdentityKeyStore` uses the `_RAW` (NO_PREFIX) templates so `WireEnvelope.sig` is bare 64-byte RFC
8032 (was 69 = `0x01‖keyId[4]‖sig`) and `WrappedKey.wk` is bare RFC 9180 `enc‖ct` (was Tink-prefixed); and
both CBOR codecs (`WireCodec.cbor`, `cryptoCbor`) pin `useDefiniteLengthEncoding = true` (kotlinx's default
is indefinite-length, the awkward case for a Swift codec) + explicit `encodeDefaults = false`. Android keeps
Tink internally — the raw bytes are extracted from / re-imported into `KeysetHandle`s (the reconstruction
`HpkeParameters` are asserted to match the `_RAW` template in `PublicKeyBundleTest`). Because the bundle
bytes are hashed into `NodeId`, every node re-derives a different id (breaking, § above), so all markers
bumped in lockstep: `SERVICE_NAME` `.v8 → _knitmesh3._tcp` (also adopting the Apple-`WiFiAwareServices`
`_name._proto` form — name label ≤15 chars, `_tcp` matching the NDP's TCP data path; the trailing digit is
the version marker now), BLE `SERVICE_UUID`
`0xFE31 → 0xFE32`, `Protocol.VERSION` `2 → 3`, DB `version 21 → 22` (destructive wipe clears stale pins +
old-format custody — the **last** pre-launch destructive bump, before the production reset to the v1 launch
baseline; see `docs/ARCHITECTURE.md` §9 for the migrate-forward posture). The keypair is untouched (only its
public-key *encoding* changed). Golden
vectors (`GoldenVectorTest`) pin the definite-length bytes of every wire type + the raw-key bundle so a
future iOS codec has byte-exact fixtures. Also bundled: the two-way responder HELLO (`LinkHandshake`) so a
link's peer identity is confirmed over the socket, not parsed from the (unauthenticated) discovery advert.

**Precedent — an additive crypto-scheme bump (`EncEnvelope.v` 1 → 2, the DM epoch ratchet).** The whole
forward-secrecy scheme (`docs/FORWARD_SECRECY_RATCHET.md`) shipped without touching a discovery marker,
`Protocol.VERSION`, or any v1 byte: `EncEnvelope` gained the nullable `r: RatchetHeader?` (rule 1 — its
`@ByteString` fields live inside the new `RatchetHeader`/`RatchetInit` types, honoring rule 1's
exception), `ProfileContent` gained the nullable `prekey: PrekeyInfo?`, `MessageContent` gained the
nullable `ctl` marker *inside* the ciphertext (same schema version — additive there too), and
`Protocol.CAP_RATCHET` took the next capability bit. A v1-era build decodes a v2 envelope fine
(`ignoreUnknownKeys` drops `r`), rejects it at the **version gate** (rule 5: drop-locally + count,
still relay/carry — `canCarry` never looks at `v`), and keeps custodying it for peers that can read it.
The v2 fixtures ride alongside the untouched v1 golden vectors in `GoldenVectorTest`; the
old-decoder-ignores-`r` behavior is pinned in `WireSerializationTest`. Senders gate on the peer's
pinned profile carrying **both** `CAP_RATCHET` and a `prekey` (one signed frame — no stale-capability
window), so a v2 frame is only ever addressed to a build that can open it.

**Precedent — extending an UNRELEASED version instead of bumping (the group sender-key ratchet
folded into v2).** Version numbers are only spent when a build that understands the old meaning has
shipped; the v2 crypto scheme never left this branch, so the group scheme
(`docs/GROUP_FORWARD_SECRECY.md`) rides the SAME `EncEnvelope.v = 2` rather than minting a v3 — the
two forms split on addressing (a DM carries `r`, a group frame the new nullable
`g: GroupRatchetHeader?` — two plain ints, no `@ByteString`), and `MAX_SUPPORTED_VERSION` stays 2.
Likewise `Protocol.CAP_RATCHET` covers both forms (they ship together; a second bit would never vary
independently) and the group state tables ride the same unreleased DB v2 migration. The additive
fields still follow rule 1: `MessageContent` gained the nullable `gk: GroupKeyPayload?` + three ctl
values *inside* the ciphertext (`CTL_GROUP_KEY`/`_REQ`/`_ACK` — legal precisely because unknown ctl
values were already consumed as silent no-ops), and `GroupInfo` gained the nullable `departed` list
(the roster-integrity phase). The epoch seeds themselves never touch a new wire surface: they ride
*inside* ordinary v2 DM ctl frames, which v1 relays already custody (the ADR 016 argument
re-applied — a new frame type would not be custodial to old builds). Senders gate the group form on
**every** other member's pinned profile carrying `CAP_RATCHET` + a prekey; any ineligible member
demotes that message to v1, so a ratcheted group frame is only ever addressed to a roster that can
open it. **The rule this precedent adds: released version numbers are append-only; unreleased ones
are still yours to edit.**

**Precedent — the third additive `MessageContent` change (sealed receipts/reactions, ADR 018).**
No new wire surface at all: `MessageContent` gained the nullable `ack: String?` and
`rp: ReactionPayload?` plus two ctl values (`CTL_RECEIPT = 5`, `CTL_REACTION = 6`) — all inside the
ciphertext, legal because unknown ctl values are consumed as chain-advancing silent no-ops (pinned by
`anUnknownCtlCodeAdvancesTheChainAndDoesNothing`), and byte-invisible on ordinary messages
(`encodeDefaults = false`; pinned by `aPlainMessageEncodingIsByteIdenticalWithTheNewFieldsDefaulted`).
The cleartext `receipt`/`reaction` frame types stay accepted inbound forever; `ReactionPayload` is
field-compatible with `ReactionContent` (same CBOR — `GoldenVectorTest` pins both, retraction form
included). One semantic split rides on the ciphertext boundary rather than any wire field: carriers
vaccine-purge on a cleartext receipt exactly as before but cannot on a sealed one, and the custody
rule keys on that form (a property of the frame bytes, identical at every node) — see
`docs/ENCRYPTED_RECEIPTS_REACTIONS.md` §4 for why that stays ADR 006-convergent.

**Precedent — the spool plane's first mesh-wire field (`GroupKeyPayload.gr`, group scopes / M4).** The
Internet plane got its DM half (`docs/SPOOL_PROTOCOL.md`) with *no* mesh wire change at all; group
scopes need exactly one, and it is additive: `GroupKeyPayload` gained the nullable
`gr: GroupRootPayload?` — the shared group root the group scope id and seal keys derive from
(SPOOL_PROTOCOL §3.2/§3.3). It rides the **existing** `CTL_GROUP_KEY` ctl DM rather than a new frame
type or a new ctl value, for the ADR 016/018 reason a third time over: `isCustodial` is a fixed list on
deployed builds, and the root is precisely the thing that must survive store-and-forward to reach an
offline member. `GroupRootPayload` is its own type because its `root` is `@ByteString` and rule 1's
exception keeps those non-default — the `GroupSeed`/`RatchetInit` shape. Two consequences worth pinning:
a distribution may now carry `keys` **empty** with only `gr` set (a member that holds a root but has
never sealed a group frame), which is byte-legal and is why `GoldenVectorTest` pins that exact fixture
alongside the seeds-and-root one; and an old build decodes the ctl, ignores `gr`, and still advances the
DM chain as the pinned silent no-op. No discovery marker, no `EncEnvelope.v`, no `MessageContent.v`, and
no new ctl value is spent. The DB **does** bump (v2 → v3, the `group_roots` table) — local state only,
with a tested `KnitMigrations` entry.

**When you bump a version layer:** add a round-trip test plus an "unknown higher version drops locally
but is counted" test. New crypto scheme ⇒ bump `EncEnvelope.MAX_SUPPORTED_VERSION` + branch in
`MeshManager.decrypt` (**together** — bumping MAX without the branch converts the clean
unknown-version drop into `DECRYPT_FAILED` noise). New content schema ⇒ bump
`MessageContent.MAX_SUPPORTED`.
