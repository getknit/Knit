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
  token is decoupled from its enum constant name (`FileKind.wire`). See decisions ADR 012. The broad library
  `{ *; }` keeps are **no longer deferred**: ADR 050 dropped the Tink / ARSCLib / apksig ones (97% rates,
  dex 9.9 → 6.0 MB) and added `scripts/r8-dex-gate.sh` to CI so a library's consumer keep rule can't quietly
  undo it. Still pinned by choice: `net.zetetic.**`, `org.tensorflow.lite.**`, `org.xmlpull.v1.**`.
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

- **The LoRa (Meshtastic-over-BLE) plane is hidden in shipped builds** (2026-08-24, ADR 038) —
  `BuildConfig.LORA_PLANE` true in debug, false in release/staging, `-PloraPlane=true|false` overrides.
  It gates the LoRa child in `CompositeMeshTransport`, the `lora` settings route + Profile row, and
  `SettingsStore.loraEnabled`. The code is **not** stripped (R8 prunes the `if (LORA_PLANE)` branches).
  MVP shipped: `mesh/lora/` (pure, JVM-tested end-to-end over a fake board/air) + `mesh/bluetooth/meshtastic/`
  (the GATT client, device-verified only). Carries the Nearby-room broadcast subset (chat, reaction, ✓✓ tick,
  profile) and, since ADR 039 (2026-08-24), **sealed 1:1 DMs** — the whole DM form, receipts/reactions/ctl
  included — via `MeshTransport.longRangeFanout`, with class-aware queue shedding, a 15-min freshness gate,
  a bounded re-offer of carried DMs to a peer first heard, and a default-on `loraDmEnabled` switch (the
  metadata-exposure control). Group chat/meta, typing and files stay refused. **Still owed:** the
  **two-phone device trial** (pair both boards, verify a Nearby post + ✓✓ + reaction cross, then a DM + its
  ✓✓ + a reply, and a DM sent while the far board was off landing via the re-offer once it returns — all
  with the phones out of BLE/NAN range) — the GATT layer has no host test. **Knit-provisioned channel
  SHIPPED** (2026-08-24): "Set up Knit channel" (or `…debug.LORAPROV`) writes the derived `KnitChannel` as a
  secondary channel over the Meshtastic admin API — the user no longer hand-configures the boards;
  region/modem-preset still set once at flash. **Board setup REWORKED** (2026-08-26, ADR 045): a single
  "Set up this board for Knit" writes the Knit channel into a free secondary slot, stretches the board's
  node-info / position / telemetry broadcasts and sets `rebroadcast_mode = LOCAL_ONLY`, all as one
  read-modify-write admin transaction, with a Restore that puts the board's own values back and switches the
  plane off (`BoardQuiet`, `spliceVarintFields`, `SettingsStore.loraBoardSetup`, `…debug.LORAPROV [--es mode
  restore]`). There is deliberately **no lighter mode and no hand-set channel index**: a board is set up for
  Knit or it is a stock Meshtastic node. The board's **primary is never touched**, which keeps it on the
  public frequency where stock nodes repeat Knit's packets for free — the reason the frequency-move design
  was reverted before shipping. **Still owed:** the on-hardware trial in `context/lora-bridge.md` — the
  frequency must be *unchanged*, the battery row must survive the telemetry stretch, and a stock node between
  two boards should extend the range.
  Still deferred: a **user-set/shared private PSK** (the shipped
  channel is a public rendezvous; with DMs aboard it is also what would hide their metadata — needs
  out-of-band PSK sharing, QR/URL — and, since the name feeds the slot hash, a private deployment would also
  land on its own frequency), a **periodic self-profile beacon** (a peer that only listens never
  triggers a beacon exchange or a re-offer), **Meshtastic unicast + `want_ack`** for DMs (needs a
  nodeNum↔nodeId map and a Routing `NONE`-is-success branch), **re-offer beyond the heard peer** (a
  board-less recipient behind another board-holder — the "true DM routing" deferral), an **in-app scan + bond flow**
  (`MeshtasticScanner`/`MeshtasticBonder` are written but unwired — device-only verifiable, and the scan must
  go through `BleConnectArbiter`; the picker filters bonded devices instead, ADR 040), and a **per-message
  `loraTooBig` marker** (no persisted evidence; ADR 040's composer hint covers the sending side). **The plane's
  UI SHIPPED** (2026-08-25, ADR 040): `DeliveryPlane.LoRa` + bubble glyph, the header glyph, the board-only
  picker with a channel verdict, the LoRa-only DM notice and the long-message composer hint; the board's
  battery in the status + Profile rows followed (ADR 041). See
  `context/lora-bridge.md`. **Bridging between mesh pockets SHIPPED** (2026-08-25, ADR 044): a `LoraCtl`
  gossip OFFER (tag `0x10`, ≤ 48 id prefixes, one packet), a gateway election off `foreignReachable` that
  closes the **multi-board-per-clique** deferral above, an airtime governor reading the board's region and
  modem preset, and digest-driven backfill of what a far gateway's offer shows it lacks — behind
  `SettingsStore.loraBridgeEnabled` (default on). Live traffic already crossed before this and was not
  rebuilt. **Still owed:** the **four-device two-pocket trial** in `context/lora-bridge.md`. **Airtime shaping
  SHIPPED** (2026-08-27, ADR 054): the recipient gate (a DM-form frame to a linked peer or to self never rides
  the board), a 15-min budget window at the same 5 %, a `TICK` class that sheds first and never spends a
  window's tail, coalesced DM receipts (`DmAckCoalescer`, ≤ 45 s hold, one tick per burst) piggybacked on a
  reply behind `CAP_INLINE_ACK`, and the saturated-chat notice. **Still owed:** its three-phone trial
  (`context/lora-bridge.md`). Still deferred
  here: an **IBLT/Bloom offer body** (48 prefixes is a window — the upgrade if a busy pocket's oldest frames
  start falling off it), **acknowledged backfill** (a served frame lost to the air waits for the next round),
  and **faster passive-to-active takeover** when an active gateway's phone dies without leaving
  `foreignReachable` (the 45-min `STALE_MS` is the whole blind spot today).

- **The spool plane is hidden in shipped builds** (2026-08-22, ADR 031) — `BuildConfig.INTERNET_PLANE`
  is true in debug, false in release/staging, `-PinternetPlane=true|false` overrides. It gates
  `SettingsStore.spoolEnabled` (which parks `ScopeSync`, group-root minting and every derived indicator
  in one place), the Profile row, the `relays` route, and the default-spool seed. The code is **not**
  stripped — flipping the release default to `true` is the whole of "introducing the feature", once the
  device trials below are done and the CHANGELOG's relay bullets are ready to be public.

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
  `ChatContent.attachmentHash` of the DB v19 precedent is the whole reference a
  fetcher needs — the mime rode alongside it until ADR 035 withdrew it, and the fetcher now resolves the
  type from its own decrypted row. ADR 019's M5 amendment records the five shape decisions). Still owed on the
  attachment half: **persisted partial downloads** (they are in memory today, so a process death
  mid-transfer refetches — the upload half already resumes off the spool's bitmap), and the same
  two-island device trial the group half is waiting on.

- **Attachment uploads are deferred while the radios carry them, SHIPPED 2026-08-17** (ADR 021,
  `mesh/spool/AttachmentDeferPolicy`, spec §9.5's MAY + §10): an attachment we authored, whose
  recipient acked it, waits while that peer is still on `MeshTransport.reachable`, so a photo that
  already crossed a radio link is not copied to a relay as well. Deliberately **attachments only** —
  gating frames would make the scope digest a function of local mesh state and it would never converge
  again — and deliberately a **delay, not a veto**: it re-opens on the sighting expiring and ends 2 h
  before the frame leaves custody. Groups never defer (the sealed group tick flips on the first
  member's receipt). Counted as `spoolAttachDeferred` in Diagnostics and the `SPOOL` bridge. Still
  owed: the same two-island trial — send a photo co-located (expect the deferred counter climbing and
  no `aput`), separate the devices, expect the upload within one 60 s heal round.

- **Sealed profile updates SHIPPED 2026-08-16** (ADR 020, was never a roadmap item — the gap surfaced in
  field testing after M5): `CTL_PROFILE = 8` carries name/status/avatar to established contacts inside v2
  chat, so profile changes now cross the Internet plane and stay off the cleartext plane for
  ratchet-capable peers. Avatars ride the carrying frame's cleartext `attachmentHash` (the DB v19
  precedent), and group photos needed no wire change since `groupupdate` was already scope-carried. The
  cleartext `profile` frame keeps first contact permanently — it is self-certifying and cannot be
  encrypted — so ADR 018's "last cleartext flooded metadata" goal is advanced, not finished.

- **Contacts at a distance SHIPPED 2026-08-25** (ADR 042, `docs/CONTACT_CARD.md`): a signed contact
  link (share/copy on the Verify screen; import by tapping it, sharing it to Knit, or pasting it on the
  Add-by-link screen), the `CTL_PROFILE` intro driven by `IntroSync`, and the identity-derived **pair
  scope** (spec §3.5) so a pair that has only exchanged cards meets at a spool before a session exists.
  **Still owed:** the two-device trial (both import, out of radio range, one shared spool — expect
  `introsSent ≥ 1` both sides within ~2 heal rounds, `confirmed: true` in `…debug.RATCHET`, the same DM
  scope id in `…debug.SPOOL`, the pair scope gone ≤ 48 h later; then the LoRa variant), the
  `getknit.app` assetlinks + `/c` landing page (out of repo — until then Android 12+ opens the https link
  in the browser; `knit://` and share-to-Knit work regardless). **Deferred, by design:** the **one-sided
  invite** (a *profile-only* token-derived rendezvous plus a contact-request inbox — needs per-token
  caps, revoke, expiry, and the "other link holders can see who requested" caveat); a **prekey in the
  card** gated on `iat < 7 d` (seal at import, reach a LoRa listen-only peer; a stale prekey wedges
  silently at `EPOCH_GONE`); **node-id-only import** over the radios via `KeyExchange.want`; **session
  recovery over the pair scope** for existing contacts (needs a probing strategy — no `unsub` record,
  `maxScopes` pressure); a chat-thread intro notice (the profile status line covers it; the pair scope
  already reads as relay-covered).

- **Audio moderation** — voice notes (ADR 034) ship **unscreened**: no on-device model classifies speech
  and the app has no cloud option, so `MODERATION_NONE` is the honest verdict and both screening hooks skip
  audio by MIME. Mitigated rather than solved: the mic is not offered in the Nearby room (the one surface
  that floods unencrypted to strangers), and block-sender plus the ADR 009 request gate are the remedies.
  If a small on-device speech classifier ever becomes practical, the hook point already exists —
  `InboundPipeline.onObtained` decrypts a landed attachment and is where the waveform derivation runs, so a
  verdict would cache under the same content hash the image path uses and the bubble's tap-to-reveal
  collapse would need no new UI. Gap recorded in `docs/CONTENT_MODERATION.md` §7.

- **Voice notes in the Nearby room** — deliberately not built, for the reason above. Reversing it is one
  flag (`MessageInput`'s `voiceEnabled`), and should not be reversed without an answer to "what screens it".

- **Startup profile (`app/src/main/startup-prof.txt`)** — the baseline profile landed (ADR 048) but the
  startup-profile half did not. It reorders dex so startup code sits together, which is a real additional
  win on cold launch, and it is the same collection run (`includeInStartupProfile = true`). Deferred only
  because it changes dex layout and so needs its own pass against F-Droid's byte-comparison before shipping.

- **BLE promotion gate on A2DP audio** — the adaptive scan throttle now drops the **scan** to its floor
  while streaming (`ScanDemandPolicy` / the demand-gated `scanLoop`), but **connects** are still not gated
  on `contended` (it remains diagnostic-only for the connect path). **Note before building it:** since
  ADR 034, *playing a voice note* also trips `AudioManager.isMusicActive`, so `contended` now goes true for
  a few seconds of local speaker playback that contends for nothing. Harmless while the flag is
  instrumentation-only; gating connects on it as-is would stall the mesh every time someone listens to a
  message. The gate needs to distinguish a real A2DP route from any active stream.
- **Connectionless BLE side-channel for small frames** — the BLE analogue of the NAN coordination/fast-fanout
  plane: carry small floodable frames (broadcast chat, receipts, reactions, typing) over BLE **extended
  advertising** so they bypass an in-flight L2CAP file transfer entirely instead of head-of-line-queuing
  behind it on the one ordered stream. The shipped `TransferPacePolicy` feed-cap (`FramedLink.paceBytesPerSec`)
  *mitigates* the stall by pacing the blob feed below link capacity; this would *structurally* split
  interactive frames from bulk. DMs stay on L2CAP. See knit/knit-next#13. The frame-codec half now
  exists (2026-08-21): `mesh/link/FastFrameCodec` (compact `0x03` / fragment `0x04`, ADR 030) is
  transport-neutral by design — this item still needs the ext-adv carrier plus its cap gate (BLE
  adverts carry the low 8 capability bits, which covers `CAP_FAST_COMPACT = 0x20`).
- **True DM routing** — DMs still flood; only the addressed recipient delivers/acks. Store-and-forward now
  *carries* undelivered DMs (`context/store-and-forward.md`), but there is still no routing table.
- **Group key-gap retransmit (v1-fallback residual only)** — the group ratchet's outbox +
  key-request loop subsumed this for ratchet-capable groups (docs/GROUP_FORWARD_SECRECY.md §7); the
  original gap — a member whose key arrives later never gets a re-seal — persists only for groups
  still pinned at v1 by a pre-ratchet member, and shrinks as capability floods.
- **E2E hardening (what remains)** — encrypting the broadcast room (its fate is a deliberate
  separate decision — an Internet-wide plaintext room is a different product question), and the
  **attachment MIME on the blob transfer**. The flooded-frame half shipped 2026-08-23 (ADR 035: a sealed
  frame names the ciphertext hash and nothing else, so a relay or carrier no longer learns photo-vs-voice
  from the frame). The residual is `LinkFraming.FileHeaderWire.mime`: `BlobExchange.onRequest` serves a
  blob to **any** neighbour that asks, so a carrier that actually pulls the bytes still learns the type.
  **Before building it:** `mime` is a required non-null `String` under `encodeDefaults = true` and
  `decodeFileHeader` returning null sets `rxAborted = true`, so *omitting* it hard-breaks blob transfer
  against deployed builds — substitute a constant instead (`image/jpeg` is already the universal fallback
  at `ScopeSync.FALLBACK_MIME`, `MeshBlobStore.fileFor` and `AVATAR_MIME`, so old builds degrade by
  nothing). Do **not** gate it on a capability bit: `Protocol.capabilities` is unauthenticated advert data,
  so gating a privacy control on the carrier's own claim hands the adversary the off switch. The knock-on
  this used to carry is **already closed**: knit/knit-next#30 (fixed 2026-08-23) moved
  `MeshBlobStore.saveIncoming`'s screening skip off the header entirely — it reads
  `messages.attachmentMimeForHash` plus `attachmentKeyForHash`, and `BlobExchange.onReceived` now re-serves
  the stored mime rather than the wire's — so substituting a constant here can no longer weaken screening.
  Receipts and reactions shipped sealed 2026-08-15 as v2 ctl frames (ADR 018,
  docs/ENCRYPTED_RECEIPTS_REACTIONS.md — DM vaccine-purge retired for the sealed era; the residual is
  the cleartext fallback toward pre-ratchet peers, counted by `receiptsSealedFallback`/
  `reactionsSealedFallback`). Group delivery ticks escalate into custody since 2026-08-22 (ADR 033 —
  batched `MessageContent.acks` toward an absent capable author; the residual is the
  never-escalating cleartext/broadcast tick, by design). (Group forward secrecy shipped as the v2 group form — the sender-key
  ratchet over the pairwise sessions, ADR 017, docs/GROUP_FORWARD_SECRECY.md; it also supplies the
  per-sender `epochSeal` export reserved for the spool plane's `sealv = 2` extension; the shared
  group root is now specified by `docs/SPOOL_PROTOCOL.md` §3.2, client machinery deferred with the
  group-scope milestone above.) See `context/e2e-encryption.md`.
