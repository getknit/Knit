# Roadmap / out of scope (deferred, by design)

What's deliberately deferred, and what has since shipped. Update this as scope lands (the BLE + digest-pull
notes below moved from "deferred" to "implemented" — that evolution is why this is memory, not a static
doc). **Don't start a deferred item without explicit direction.**

## Already shipped (was deferred)

- **The Bluetooth LE plane is implemented** (`mesh/bluetooth/`) and runs *simultaneously* with Wi-Fi Aware
  behind `CompositeMeshTransport` (wired in `di/MeshModule.kt`): BLE advertise/scan presence + persistent
  L2CAP CoC data links, *preferred* over NAN's ephemeral NDP, with per-peer escalating connect backoff and
  A2DP-audio instrumentation. It is a co-plane, **not** a fallback, and BLE-capable devices use it
  regardless of Wi-Fi Aware support.
- **Digest/pull anti-entropy** — the cue-plane `StoreDigest`/`DigestTracker` + the data-path
  `LinkFraming.Type.DIGEST` id-diff (`docs/DIGEST_PULL_REATTACH.md`).
- **Inbound key-request** for a frame received from a not-yet-pinned sender (the inbound complement of
  retransmit-on-key-arrival) — now `KeyExchange`; see `context/store-and-forward.md`.
- **R8 obfuscation (name mangling)** is enabled on release/staging (was shrink + optimize only, behind
  `-dontobfuscate`). The wire stays safe by construction — kotlinx.serialization compile-time descriptors +
  the frozen wire/identity DTOs pinned unrenamed in `keepRules/knit-r8.keep` — and `FileKind`'s file-header
  token is decoupled from its enum constant name (`FileKind.wire`). See decisions ADR 012. Deferred: tighten
  the broad library `{ *; }` keeps to minimal targeted keeps (bigger size win, larger test surface).
- **Forward secrecy for DMs is implemented** — the epoch-rekey ratchet (crypto scheme v2,
  `docs/FORWARD_SECRECY_RATCHET.md`; ADR 016): X3DH-style bootstrap off a signed prekey published in the
  profile, per-epoch X25519 rekeying, session state in the `ratchet_*` tables, capability-gated dual-stack
  (v1 static wrap remains for groups and pre-ratchet peers, and inbound v1 is accepted forever). Also
  supplies the `pairwiseRoot` export the internet-relay scope derivation consumes
  (`docs/SPOOL_PROTOCOL.md` §3, `ScopeCrypto`).
- **The spool (internet-relay) protocol is specified** — `docs/SPOOL_PROTOCOL.md` (ADR 019, public,
  normative) plus the pure reference implementation and vector anchors (`mesh/crypto/scope/`
  `ScopeCrypto`/`SpoolPow`, `mesh/spool/` `SpoolRecords`; API-only, zero runtime consumers). Names
  committed: spool / scope / `ScopeSync` / `knit-spool` (AGPL-3.0).

## Still deferred (by design)

- **The spool plane beyond the spec** — everything that makes the protocol run, in order: the
  `knit-spool` reference daemon + conformance suite; the client `ScopeSync` plane (OkHttp/WSS dep +
  lockfile regen, a validated-Internet `ConnectivityManager` seam — which needs a
  `rules/mesh.md` amendment, since `ConnectivityManager` is currently NAN-only — the scope-config
  ctl producer/consumer, global opt-in toggle, spool-list settings, Tor SOCKS toggle, diagnostics
  counters); group scopes (the `GroupKeyPayload.gr` wire field + root mint/gossip/adopt +
  departure re-mint, per the spec's §3.2); sealed attachments over spools. Each lands additively
  per `docs/WIRE_COMPAT.md` with its golden vectors and precedent entry when it ships.

- **BLE promotion gate on A2DP audio** — the adaptive scan throttle now drops the **scan** to its floor
  while streaming (`ScanDemandPolicy` / the demand-gated `scanLoop`), but **connects** are still not gated
  on `contended` (it remains diagnostic-only for the connect path).
- **Connectionless BLE side-channel for small frames** — the BLE analogue of the NAN coordination/fast-fanout
  plane: carry small floodable frames (broadcast chat, receipts, reactions, typing) over BLE **extended
  advertising** so they bypass an in-flight L2CAP file transfer entirely instead of head-of-line-queuing
  behind it on the one ordered stream. The shipped `TransferPacePolicy` feed-cap (`FramedLink.paceBytesPerSec`)
  *mitigates* the stall by pacing the blob feed below link capacity; this would *structurally* split
  interactive frames from bulk. DMs stay on L2CAP. See knit/knit-next#13.
- **True DM routing** — DMs still flood; only the addressed recipient delivers/acks. Store-and-forward now
  *carries* undelivered DMs (`context/store-and-forward.md`), but there is still no routing table.
- **Group key-gap retransmit (v1-fallback residual only)** — the group ratchet's outbox +
  key-request loop subsumed this for ratchet-capable groups (docs/GROUP_FORWARD_SECRECY.md §7); the
  original gap — a member whose key arrives later never gets a re-seal — persists only for groups
  still pinned at v1 by a pre-ratchet member, and shrinks as capability floods.
- **E2E hardening (what remains)** — encrypting the broadcast room (its fate is a deliberate
  separate decision — an Internet-wide plaintext room is a different product question). Receipts and
  reactions shipped sealed 2026-08-15 as v2 ctl frames (ADR 018,
  docs/ENCRYPTED_RECEIPTS_REACTIONS.md — DM vaccine-purge retired for the sealed era; the residual is
  the cleartext fallback toward pre-ratchet peers, counted by `receiptsSealedFallback`/
  `reactionsSealedFallback`). (Group forward secrecy shipped as the v2 group form — the sender-key
  ratchet over the pairwise sessions, ADR 017, docs/GROUP_FORWARD_SECRECY.md; it also supplies the
  per-sender `epochSeal` export reserved for the spool plane's `sealv = 2` extension; the shared
  group root is now specified by `docs/SPOOL_PROTOCOL.md` §3.2, client machinery deferred with the
  group-scope milestone above.) See `context/e2e-encryption.md`.
