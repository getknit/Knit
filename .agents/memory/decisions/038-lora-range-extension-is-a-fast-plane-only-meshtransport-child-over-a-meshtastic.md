---
id: "038"
slug: lora-range-extension-is-a-fast-plane-only-meshtransport-child-over-a-meshtastic
title: "LoRa range extension is a fast-plane-only `MeshTransport` child over a Meshtastic board (BLE GATT)"
date: 2026-08-24
topics: [lora, mesh, architecture]
---

# ADR 038 — LoRa range extension is a fast-plane-only `MeshTransport` child over a Meshtastic board (BLE GATT)

Status: Accepted (2026-08-24; `mesh/lora/`, `mesh/bluetooth/meshtastic/`, `BuildConfig.LORA_PLANE`)

A Meshtastic LoRa board attached over BLE extends the reach of the **Nearby room** beyond BLE/NAN range.
The board is driven as a third `CompositeMeshTransport` child (`LoraMeshTransport`, last = lowest
send-preference) that has **only a fast plane**: `neighbors` is always empty, so the reliable flood,
custody digest sync, `keyreq`, blob pulls and the `watchNeighbors` hooks never touch a ~1 kbps link;
`send`/`sendFile`/`sendDigest` are no-ops. Only `fastFanout`/`fastSend` ride it. Locked with the
maintainer: broadcast `chat` + its `reaction`, the ✓✓ delivery `receipt`, and the cleartext `profile`
(the far side must pin the author's key to verify) — nothing else (`LoraFramePolicy`). Decisions worth
not relitigating:

1. **Not a wire change — the ADR 030 argument reused.** Outbound decodes `wire.signed` only to apply the
   policy, then reuses `FastFrameCodec` to compact/fragment; `sig`/`signed` pass through byte-exact, so
   the originator's Ed25519 signature verifies unchanged at the far endpoint. Meshtastic's `Data.payload`
   cap is 233 B, so a frame splits into ≤ 3 fragments (`LoraFrameCodec`, ceiling 3 × 229 = 687 B ≈ a
   400–500-char post); a larger frame is `loraTooBig` and rides the radios/custody. The profile bootstrap
   fits ≤ 3 packets (pinned in `CoordinationPlaneSizeBudgetTest`).
2. **Hand-rolled protobuf, zero new dependencies.** `MeshtasticProto` + `ProtoIo` speak the dozen fields
   the board API needs (`ToRadio`/`FromRadio`/`MeshPacket`/`Data`/`QueueStatus`/`Routing`); vendoring a
   protobuf runtime or a codegen plugin would fight the toolchain (ADR 001/002) for no benefit. Golden
   byte vectors pin the field numbers; every decode is total (malformed → null, never a throw).
3. **The `shortRange` flag (new `MeshTransport` member, LoRa = false).** A LoRa sighting doesn't imply
   physical proximity, so `CompositeMeshTransport` excludes non-short-range children from every
   `onForeignReachable` union (else BLE scan-chases a peer kilometres away and NAN's wedge watchdog
   corroborates a Tier-2 self-kill for it) and exposes `shortRangeReachable`, which feeds
   `AttachmentDeferPolicy` (a LoRa-only sighting can't carry an image, so it must not defer a spool
   upload). `TransportKind` stays diagnostics-only.
4. **The ✓✓ tick is sealed after first contact, so the targeted path admits it.** Post-profile the author
   is ratchet-capable and `AckSync` seals every tick as a `CTL_RECEIPT` (a `relay = false` chat frame
   addressed to the author, its kdoc forbidding a cleartext downgrade), so `LoraFramePolicy`'s targeted
   path admits `receipt` **and** `chat && !relay && recipientId == to` — which does not open DMs (a real
   DM is always `relay = true` and never reaches `fastSend`).
5. **Sig-keyed dedup (first 8 B of `wire.sig`, 10 min = SeenSet TTL), recorded on send AND receive.** It
   stops a frame heard over LoRa from being re-fanned back over it (the composite re-calls `fastFanout`
   on relay), and bounds `AckSync`'s verbatim 24 h tick retries (a re-send inside the receiver's SeenSet
   window is a duplicate anyway).
6. **Key bootstrap = the existing `watchReachable` reflood + a floored self-profile beacon.** `LoraMeshTransport`
   beacons its signed profile (via `ProfileFrameSource` ← `MeshManager.sign(currentProfileEnvelope())`)
   on session-up and on first hearing a peer, under a 5-minute floor. **No periodic beacon** (N × 3
   packets × the board's 3-hop rebroadcast). The floor check is overflow-safe against a `NEVER` sentinel
   — the naive `now - Long.MIN_VALUE` wraps and would have blocked the first beacon in production too.
7. **`reachable` lingers 45 min** (no periodic cues on LoRa; a short linger would make every message a
   "newcomer" and re-trigger profile refloods). `Peer.capabilities = 0` for a LoRa sighting is harmless
   (the composite's `richer()` keeps any BLE/NAN peer; a NAN cue-only peer already looks like this).
8. **Pacing is the transport's job; the board only reports back-pressure** (`LoraPacePolicy`: 3 s min gap,
   12-frame queue dropping the oldest **whole** frame, NAK back-off widening the gap, `queueFree == 0`
   hold). The board session (`MeshtasticSession`, a pure actor over the `MeshtasticGattDialer` seam)
   handles the want_config handshake, drain-until-empty reads on FromNum, the 180 s keep-alive heartbeat,
   client-assigned packet ids for `queueStatus`/NAK correlation, and reconnect-with-backoff.
9. **Gated like ADR 031, not stripped.** `BuildConfig.LORA_PLANE` (debug true, release/staging false,
   `-PloraPlane=` override) gates the composite child, the `relays`-style settings route, and
   `SettingsStore.loraEnabled`; the classes stay in the APK (R8 prunes the `if (LORA_PLANE)` branches).
10. **Knit provisions its own channel (2026-08-24 addendum).** "Set up Knit channel" (or `…debug.LORAPROV`)
    writes the derived `KnitChannel` (name "Knit" + a 16-byte AES128 PSK) as a **secondary** channel over
    the Meshtastic **admin** API — `get_channel` for a `session_passkey`, then `begin_edit → set_channel →
    commit_edit` echoing it, into a free slot (reusing an existing same-named channel; one fresh-key retry
    on `ADMIN_BAD_SESSION_KEY`). The commit reboots the board to apply it, so the session re-handshakes.
    The PSK is HKDF-SHA256-derived from **public** constants (`"nearby"` + a domain label), so it is
    deterministic and shared but **not secret** — a **rendezvous** channel, honest for the cleartext Nearby
    room, never a confidentiality boundary. Written SECONDARY so the board's primary/radio config (region,
    modem preset) is untouched. Pinned by `KnitChannelTest` (the derivation — changing it strands
    already-provisioned boards) + `MeshtasticProtoTest` (the admin wire). A confidential per-deployment PSK
    (shared out-of-band via a channel QR/URL) is deferred.

Import boundary honoured: `mesh/lora/` is pure/Android-free and JVM-tested end-to-end (a `FakeMeshtasticAir`
floods bytes between two fake boards); the only `android.bluetooth.*` importer is
`mesh/bluetooth/meshtastic/MeshtasticGatt` (device-verified only). A `BleConnectArbiter` lets the board
dial pause the mesh BLE scan for its connect window, since scanning starves connects.

Honest residuals (accepted for the MVP): one board per BLE clique (two would each re-transmit every
locally-seen frame — the board dedups `(from,id)`, so that doubles airtime); a Nearby-only LoRa peer
appears in the contact picker but a DM to it strands in custody until radio/spool contact; and a sealed
tick over LoRa establishes a ratchet session with a far author over a plane that can't carry the DMs it
enables (harmless). Deferred: a user-set/shared **private** PSK (the shipped channel is a public
rendezvous), DM-over-LoRa, a periodic beacon, and multi-board dedup. Scheme + device bring-up:
`context/lora-bridge.md`. *Addendum (2026-08-24): DM-over-LoRa shipped as ADR 039, which also relaxes
point 6 (a 60-s first-hearing beacon gap) and makes point 8's "profile never dropped" true.*
