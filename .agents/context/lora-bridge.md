# LoRa bridge (Meshtastic over BLE) — the long-range Nearby-room plane

How Knit carries **broadcast (Nearby-room) frames** over LoRa via a Meshtastic board attached over BLE.
The design rationale is ADR 038; this file is the operational detail. Off by default, behind
`BuildConfig.LORA_PLANE` (debug on, release/staging off, `-PloraPlane=true|false`).

## Shape

`LoraMeshTransport` (`mesh/lora/`, pure) is a **fast-plane-only** `CompositeMeshTransport` child, added
LAST (lowest send-preference). `neighbors` is always empty, so the flood / custody digest sync / keyreq /
blob pulls never touch the ~1 kbps link — `send`/`sendFile`/`sendDigest` are no-ops. Only
`fastFanout`/`fastSend` ride it, gated by `LoraFramePolicy`:

- **FANOUT**: broadcast `chat` + broadcast `reaction` (recipientId == null && group == null) + `profile`.
- **TARGETED**: `receipt`, and `chat && !wire.relay && recipientId == to` (AckSync's sealed `CTL_RECEIPT`
  tick — *not* a DM, which is always `relay = true`).

Everything else (DM/group chat, group meta, `typing`, `blobreq`/`keyreq`) is refused.

Outbound decodes `wire.signed` only to apply the policy, then reuses `FastFrameCodec` (ADR 030) to
compact/fragment: `sig`/`signed` pass through byte-exact, so this is **not a wire change** and the
originator's signature verifies unchanged. Meshtastic `Data.payload` cap = 233 B → ≤ 3 fragments (ceiling
`3 × 229 = 687 B` compact). Inbound mirrors `WifiAwareTransport.emitFastWire`: decode/reassemble →
`_inbound.tryEmit(InboundFrame(wire, env, fromNodeId = env.senderId))`, so the router's dedup / verify /
custody / relay all run unchanged.

## The layers

```
LoraMeshTransport (pure)      fastFanout/fastSend · LoraFramePolicy · LoraFrameCodec · LoraPacePolicy · reachable(45min linger)
  └─ MeshtasticLink (seam)    state / packets / outcomes / queue / rxQuality · suspend send()
       └─ MeshtasticSession   pure actor: want_config handshake · drain-until-empty on FromNum · 180s heartbeat ·
          (pure)              client packet ids ↔ queueStatus/NAK · reconnect-with-backoff (ConnectBackoffPolicy)
            └─ MeshtasticGattDialer (seam)   dial() → connect · requestMtu(512, gate ≥263) · discover · resolve chars
                 └─ MeshtasticGatt           the ONLY android.bluetooth importer for the feature (mesh/bluetooth/meshtastic/)
```

`MeshtasticProto` + `ProtoIo` are a hand-rolled protobuf codec (zero new deps): `ToRadio{want_config_id,
heartbeat, disconnect, packet{Data{portnum=PRIVATE_APP 256, payload}}}` out; `FromRadio{my_info,
config_complete_id, packet, queueStatus, rebooted, channel, metadata}` in; `Routing.error_reason` NAKs.
Golden byte vectors pin every field number; malformed input decodes to null, never throws.

## Board facts (verified 2026-08-24)

- Service `6ba1b218-15a8-461f-9fa8-5dcae273eafd`; ToRadio `f75c76d2-…` (write), FromRadio `2c55e69e-…`
  (read; one protobuf per read, 0 bytes = drained), FromNum `ed9da18c-…` (notify, u32 LE counter).
- Bonding required in PIN modes; the V4's OLED shows a random 6-digit PIN. MTU 512 requested (gate ≥ 263 so
  the worst-case 259-B `ToRadio{packet}` is one write). ESP32 = **one BLE client** — the Meshtastic app must
  be disconnected from the board. Board **Wi-Fi must be off** (it disables the board's Bluetooth).
- Handshake: write `ToRadio{want_config_id=N}`, drain FromRadio until `config_complete_id=N` (no MeshPacket
  before that); then FromNum notify → drain until empty. `ToRadio{heartbeat}` every 180 s keeps the phone
  API alive; the node answers `queueStatus{free,maxlen,mesh_packet_id}` (also after each packet) — flow
  control. `rebooted`/unsolicited `my_info` → re-handshake.
- Send `MeshPacket{to=0xFFFFFFFF, channel=idx, id=client nonzero, hop_limit omitted(=default 3),
  want_ack=false}`. NAKs: portnum ROUTING_APP(5), `request_id` = our id, `error_reason` (NO_CHANNEL 6,
  TOO_LARGE 7, DUTY_CYCLE_LIMIT 9, RATE_LIMIT_EXCEEDED 38).

## Key bootstrap (the far side has never seen the author's profile)

Two paths: (1) `MeshManager.watchReachable` already refloods our profile on a new `reachable` peer;
(2) `LoraMeshTransport` beacons its own signed profile (`ProfileFrameSource` ← `MeshManager`) on
session-up and on first hearing a peer, under a **5-min floor** (no periodic beacon). Both stamp the same
floor. `PendingInbound` (~2 min) replays the parked chat once the profile pins the key. A **sig-keyed
SeenSet** (first 8 B of `sig`, 10 min) recorded on send *and* receive stops re-fanning a LoRa-received
frame and bounds AckSync's 24 h verbatim tick retries.

## Pacing

`LoraPacePolicy` (pure): 3 s min inter-packet gap, 12-frame queue dropping the **oldest whole frame**
(never a lone fragment), NAK back-off (rate/duty → gap ×2 for 60 s), hold while `queueFree == 0`. A
profile is never dropped for pacing (it's the bootstrap). `BleConnectArbiter` lets the board dial pause the
mesh BLE scan for its connect window (scanning starves connects).

## Board setup (once, Meshtastic CLI or app)

Flash `firmware-heltec-v4-<ver>`; `--set lora.region <US|EU_868|…>`; `--set network.wifi_enabled false`;
`--set bluetooth.enabled true --set bluetooth.mode RANDOM_PIN`; same `lora.modem_preset` (LongFast) on
both. Private channel: `--ch-add knit` on A, `--qr-all` → `--seturl <url>` on B; confirm `--info` lists it
identically. Set the Meshtastic app's device to **None** / `adb shell am force-stop com.geeksville.mesh`.

## On-device verification (physical devices only, with an explicit go-ahead — `rules/devices.md`)

- Pair: Profile → LoRa radio → pick the bonded board → status `Ready`. `adb logcat -s MeshtasticGatt
  MeshtasticLink LoraMeshTransport`: `lora dial … bonded=true` → `mtu 517` → `handshake nonce=…` →
  `my_info !… pio=heltec-v4` → `config complete` → `ready`.
- `…debug.LORA` (debug bridge): `--es address <MAC>` + `--es name <n>` binds a board, `--ei channel <idx>`,
  `--ez on <true|false>`; no extras dumps `state/boardNodeNum/snr/rssi/queueFree/heard/counters`. It is the
  two-board oracle. `…debug.LORATX --es text <s>` sends a raw payload straight to the board (board-side
  sanity via `meshtastic --noproto`).
- Broadcast: `…debug.SEND --es conv nearby --es text …` on A → appears on B within ~5–10 s; A's tick flips
  ✓✓ (sealed tick over LoRa); a reaction crosses. Move B out of BLE/NAN range and repeat. Counters:
  `loraSent/loraReceived/loraReassembled` climb, `loraNak == 0`, `loraDroppedQueue == 0` at chat pace,
  `loraTooBig` only for long posts. Diagnostics tags a LoRa-reachable node `LoRa`.

## First-session unknowns to confirm (assumptions, not blockers)

Whether an empty FromRadio read returns immediately or blocks ~20 s (could cut the 30 s read timeout);
`phone_timeout_secs` default (180 s heartbeat is safe either way); whether `queueStatus` is pushed as the
TX queue drains; that `mesh_packet_id` echoes our client id; that PRIVATE_APP packets reach the phone and
our own broadcast is not echoed; the 600 ms bonded post-connect settle (drop if unneeded).
