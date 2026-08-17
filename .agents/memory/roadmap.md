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

- **The spool plane beyond the spec** — everything that makes the protocol run, in order: ~~the
  `knit-spool` reference daemon + conformance suite~~ (**done 2026-08-16** in the `knit-spool`
  repo — full v1 daemon with SQLite persistence, rate limits, watermark, ops surface, plus the
  22-check TAP conformance CLI; its implementation pass fed eight semantic clarifications back
  into `docs/SPOOL_PROTOCOL.md` §6.2/§6.4/§7.1/§7.2/§12, no wire or vector change — ADR 019
  amendment); ~~the client `ScopeSync` plane~~ (**MVP done 2026-08-16**, `mesh/spool/` — DM scopes
  only, off by default, OkHttp behind the `SpoolLink` seam, the §9.1 heal loop, §9.3 quarantine,
  §9.4 bridge into `handleInbound`, metrics + Diagnostics rows + the `…debug.SPOOL` bridge action;
  ADR 019's M3 amendment records the four shape decisions). **What the client plane still
  owes**, roughly in order:
  - **the scope-config ctl** — `CTL_SCOPE_CONFIG = 7` / `MessageContent.sc` / `ScopeConfigPayload`
    with LWW on `(version, issuer)`. The one *wire* change the plane needs, so it lands additively
    per `docs/WIRE_COMPAT.md` with golden vectors and a precedent entry. Until it ships, the spool
    list is a device setting and bounds are §12 constants in `ScopeRegistry`.
  - ~~a spool-list editor~~ (**done 2026-08-16** — `ui/relay/InternetRelayScreen`, route `relays`,
    reached from a Profile summary row. **The switch is un-gated**: `BuildConfig.DEBUG` is gone from
    `ProfileScreen`, because the hard prerequisite is now met — a release user can edit or remove the
    seeded default. Ships with it: a one-time consent sheet (`SettingsStore.spoolConsented` /
    `acceptSpoolConsent`, which records consent and enables in one write), per-relay health rows off
    `SpoolStatus`, and the shared `SpoolUrl` validator so the editor refuses at entry exactly what
    `OkHttpSpoolDialer` refuses at dial time. ADR 019's M6 amendment records the UX rules.)
  - a validated-Internet `ConnectivityManager` seam (the MVP reconnects on backoff instead, which
    is why `rules/mesh.md` still reads NAN-only and `ACCESS_NETWORK_STATE` is still undeclared);
    the Tor SOCKS toggle; per-conversation opt-out (deliberately **not** built — the plane is
    all-or-nothing by product decision, 2026-08-16).
  ~~Then: group scopes~~ (**done 2026-08-16** — the `GroupKeyPayload.gr` wire field, `group_roots`
  at DB v3, `GroupRootPolicy`/`GroupRootStore`, group scopes in `ScopeRegistry`/`ScopeFrames`, and
  the mint/gossip/adopt/re-mint wiring in `MeshManager`/`InboundPipeline`. The spec's §3.2 was
  amended in the same pass: **any member** may mint version 1, damped by preferred-minter-plus-grace
  rather than restricted to the creator — ADR 019's M4 amendment records why, plus the two mandatory
  adoption bounds and the never-rate-limit-adoption rule). Still owed on the group half: nothing
  structural, but it has **not been exercised on devices** — the lab bridge trial (two islands, one
  real spool, a departure rotating the scope) is the outstanding verification.
  ~~Then: sealed attachments over spools~~ (**done 2026-08-16** — spec §4.5/§6.5/§7.3/§9.5, the
  `ScopeCrypto` chunk seal + keyed `aid`, `mesh/spool/ScopeAttachments`, five records, and the
  attachment pass in `ScopeSync`; `knit-spool` gained both stores, the server handlers and four
  conformance checks. **No mesh wire change, no capability bit, no DB migration** — the cleartext
  `ChatContent.attachmentHash`/`attachmentMime` of the DB v19 precedent is the whole reference a
  fetcher needs. ADR 019's M5 amendment records the five shape decisions). Still owed on the
  attachment half: **persisted partial downloads** (they are in memory today, so a process death
  mid-transfer refetches — the upload half already resumes off the spool's bitmap), and the same
  two-island device trial the group half is waiting on.

- **Sealed profile updates SHIPPED 2026-08-16** (ADR 020, was never a roadmap item — the gap surfaced in
  field testing after M5): `CTL_PROFILE = 8` carries name/status/avatar to established contacts inside v2
  chat, so profile changes now cross the Internet plane and stay off the cleartext plane for
  ratchet-capable peers. Avatars ride the carrying frame's cleartext `attachmentHash` (the DB v19
  precedent), and group photos needed no wire change since `groupupdate` was already scope-carried. The
  cleartext `profile` frame keeps first contact permanently — it is self-certifying and cannot be
  encrypted — so ADR 018's "last cleartext flooded metadata" goal is advanced, not finished.

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
