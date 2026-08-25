# LoRa bridge (Meshtastic over BLE) — the long-range Nearby-room + DM plane

How Knit carries **broadcast (Nearby-room) frames and sealed 1:1 DMs** over LoRa via a Meshtastic board
attached over BLE. The design rationale is ADR 038 (the plane) and ADR 039 (DMs); this file is the
operational detail. Off by default, behind `BuildConfig.LORA_PLANE` (debug on, release/staging off,
`-PloraPlane=true|false`).

## Shape

`LoraMeshTransport` (`mesh/lora/`, pure) is a **fast-plane-only** `CompositeMeshTransport` child, added
LAST (lowest send-preference). `neighbors` is always empty, so the flood / custody digest sync / keyreq /
blob pulls never touch the ~1 kbps link — `send`/`sendFile`/`sendDigest` are no-ops. Only
`fastFanout`/`longRangeFanout`/`fastSend` ride it, gated by `LoraFramePolicy`:

- **FANOUT** (`fastFanout` — the composite's coordination-plane blast — and `longRangeFanout` — the seam
  reserved for a plane with no data path, ADR 039; both land in one internal `fanout`): broadcast `chat` +
  broadcast `reaction` (recipientId == null && group == null), `profile`, and **DM-form chat** (`chat &&
  recipientId != null && group == null`, any `relay`). The DM form is admitted opaque: a DM, its sealed
  receipt/reaction, a session reset, a group-key seed and an escalated group tick are wire-indistinguishable
  and all ride. `shouldLongRangeFanout` (`mesh/FrameFanout.kt`) is what feeds it, from `originateSigned` and
  `onDeliver` — never widen `shouldFastFanout` for this; that predicate is the NAN coordination plane's.
- **TARGETED** (`fastSend`, unchanged): `receipt`, and `chat && !wire.relay && recipientId == to` (AckSync's
  sealed `CTL_RECEIPT` tick — a flooded DM never rides this path, so no `fastSend` caller can widen it).

Everything else (group-form chat, `groupupdate`/`groupleave`, `typing`, `blobreq`/`keyreq`) is refused.

Outbound decodes `wire.signed` only to apply the policy, then reuses `FastFrameCodec` (ADR 030) to
compact/fragment: `sig`/`signed` pass through byte-exact, so this is **not a wire change** and the
originator's signature verifies unchanged. Meshtastic `Data.payload` cap = 233 B → ≤ 3 fragments (ceiling
`3 × 229 = 687 B` compact). Inbound mirrors `WifiAwareTransport.emitFastWire`: decode/reassemble →
`_inbound.tryEmit(InboundFrame(wire, env, fromNodeId = env.senderId))`, so the router's dedup / verify /
custody / relay all run unchanged.

## The layers

```
LoraMeshTransport (pure)      fastFanout/longRangeFanout/fastSend · LoraFramePolicy (+ isFresh) · LoraFrameCodec ·
                              LoraPacePolicy (class shedding) · reachable(45min linger) · beacon + reofferTo on first hearing
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
session-up under a **5-min floor** and on first hearing a peer under a **60-s gap** (one timestamp, two
gaps — ADR 039 §8: a peer that just appeared has demonstrably never heard us, and without a periodic beacon
this is the only way a late arrival learns our key). The composite's self-profile `fastFanout` shares the
5-min floor. `PendingInbound` (~2 min) replays the parked chat once the profile pins the key. A **sig-keyed
SeenSet** (first 8 B of `sig`, 10 min) recorded on send *and* receive stops re-fanning a LoRa-received
frame and bounds AckSync's 24 h verbatim tick retries.

## Pacing

`LoraPacePolicy` (pure): 3 s min inter-packet gap, a 12-frame queue, NAK back-off (rate/duty → a 60 s
cool-down), hold while `queueFree == 0`. When full the queue **sheds by class** (`FrameClass`: BOOTSTRAP >
DM > ROOM): the oldest **whole** frame (never a lone fragment) of the lowest class present goes, the
newcomer included — a room post alone at the bottom is `REFUSED` rather than evicting a DM, and nothing ever
evicts the profile bootstrap. Dequeue stays FIFO. Both eviction and refusal count as `loraDroppedQueue`.

**Freshness gate** (fan-out paths only, room included): a `chat`/`reaction` whose `sentAt` is more than
15 min old (`LoraFramePolicy.FRESH_MS`) is a custody re-serve and is not fanned — without it a newcomer's
whole backfill re-fanned over the air, twelve frames at a time. Profiles (publish-stamped `sentAt`) and
receipts are exempt, as are the targeted path (AckSync's verbatim retries) and the re-offer. It reads the
injected `wallClock` (epoch) — the transport's `clock` is `elapsedRealtime` and is not comparable to a
frame's `sentAt`. Counted with the sig-window rejections under `loraSuppressed`. `BleConnectArbiter` lets
the board dial pause the mesh BLE scan for its connect window (scanning starves connects).

## DMs (ADR 039)

A 1:1 DM rides as its ordinary sealed frame — nothing is re-encoded, the signature verifies unchanged, and
the far side needs only the pinned profile (key + `CAP_RATCHET` + prekey) the beacon already carries; X3DH
attaches its init to every frame until the first reply, so no round trip is needed. The epoch ratchet
tolerates a lossy hop by design (independent epochs, ≤ 200 skipped keys per epoch). Sizes
(`CoordinationPlaneSizeBudgetTest.sealedDmsFitTheLoraHop`): a 100-char DM compacts to **387 B** steady-state
and **439 B** with the init — 2 packets either way; the 3-packet ceiling (687 B) is ≈ 400 characters steady /
≈ 335 with the init; an attachment *reference* costs ~167 B more and still fits; a `TextLimits.MESSAGE`-length
DM is `loraTooBig` and rides the radios/custody. The ✓✓ is the recipient's sealed `CTL_RECEIPT` — a DM-form
frame originated `relay = true`, so it crosses back on the same rule, and it re-runs on every re-delivery via
the pre-decrypt exists-gate (which is how a tick lost over LoRa heals when the DM is re-offered).

**Re-offer on first hearing.** The plane has no custody sync, so a DM sent while the peer's board was off
would be lost to it. On first hearing a peer (once per 45-min linger), after the beacon, the transport pulls
`FarPeerFrameSource.framesFor(peer)` (`MeshManager`: the newest 4 live custody frames addressed to the peer
via `ForwardStore.liveFramesTo`, minus our own frames it already acked via `MessageDao.unackedDmsTo`),
re-wraps them verbatim and enqueues them class DM through a private path (`reofferTo`). Skipped for a peer
another plane carries (`foreignReachable`) — custody syncs to it for real there. Bounded: ≤ 4 frames × ≤ 3
packets per sighting; a frame fanned inside the sig window is skipped. Counted as `loraReoffered`.

**What still doesn't cross:** group chat (group-form frames are refused; sealed group *machinery* — seeds,
key req/ack, escalated ticks — crosses opaquely and is bounded by the group logic), `typing`, attachment
bytes (a DM with an image arrives as text plus a loading placeholder until a radio/spool path exists —
`blobreq` never rides LoRa; `AttachmentDeferPolicy` already ignores LoRa sightings), and DMs beyond the
size ceiling. A board-less recipient behind another board-holder gets live DMs via that phone's relay but
no re-offer (no routing table). Airtime is SMS pace: ~2 packets per DM plus ~2 per receipt at ~2.5 s each.

**Metadata.** Content stays end-to-end sealed, but a DM's cleartext `senderId`/`recipientId`, timing and
size now travel on the public-PSK rendezvous channel at kilometre range. `SettingsStore.loraDmEnabled`
(default **on**, the "Private messages over LoRa" switch on the LoRa radio screen, `…debug.LORA --ez dms`)
rides into `LoraConfig.dms`; off, the transport refuses DM-form on fan-out and skips the re-offer while the
room keeps riding. Each side gates its own sends. The confidentiality fix remains the deferred private PSK.

## Channel provisioning (Knit writes its own channel)

`LoraMeshTransport.provisionKnitChannel()` (settings button "Set up Knit channel" or `…debug.LORAPROV`)
writes the well-known **`KnitChannel`** onto the board over the Meshtastic **admin** API so the user never
hand-configures a channel. Flow, all inside `MeshtasticSession` (serialized with sends): `get_channel` to
the local node (`to = myNodeNum`, portnum ADMIN=6, `want_response`) → grab the `session_passkey` (field 101,
300 s TTL) and pick a **free secondary slot** (reuse an existing same-named channel; else lowest 1..7 not
live) → `begin_edit_settings` → `set_channel{ index, settings{ psk, name }, role=SECONDARY }` →
`commit_edit_settings`, each echoing the passkey. The commit reboots the board to apply the edit, so the
session ends (reset backoff) and re-handshakes, reloading the channel table; the result returns as soon as
the write is accepted. A `Routing.ADMIN_BAD_SESSION_KEY` NAK triggers one fresh-passkey retry.

**`KnitChannel`** (`mesh/lora/KnitChannel.kt`): name `"Knit"`, 16-byte AES128 PSK **derived** (pinned +
guarded by `KnitChannelTest`) via `HKDF-SHA256(ikm="nearby", salt=0³², info="knit/lora/channel/psk/v1")`.
The seed is public, so the PSK is public — deliberately: the Nearby room is cleartext, so this channel is a
**rendezvous** (keeps Knit off stock LongFast; any two Knit boards converge with zero coordination), not a
confidentiality boundary. Knit's per-frame Ed25519 signatures remain the integrity boundary. It is written
as SECONDARY, so the board's primary channel and radio config (region, modem preset) are never touched. A
confidential per-deployment PSK (shared out-of-band via a channel QR/URL) is deferred — see `roadmap.md`.

Admin wire (pinned by `MeshtasticProtoTest`): `AdminMessage{ get_channel_request=1, get_channel_response=2,
set_channel=33, begin_edit_settings=64, commit_edit_settings=65, session_passkey=101 }`;
`Channel{ index=1, settings=2, role=3 }` (Role SECONDARY=2); `ChannelSettings{ psk=2, name=3 }`;
`Data.want_response=3`.

## UI surfaces (ADR 040)

- **Per message.** A frame off the board is stored `DeliveryPlane.LoRa` (`messages.receivedVia = 5`; the
  composite stamps `InboundFrame.kind`, `InboundPipeline.planeOf` maps it), and the receipt that flips our
  ✓✓ over LoRa records the same. `ui/chat/DeliveryStatus.kt` paints `Icons.Filled.Sensors` beside the ✓✓
  (`chat_tick_lora`) or on an arrival (`chat_arrived_lora`) with "Delivered over LoRa" / "Arrived over LoRa";
  the message-details screen swaps the icon the same way. Inbound rows are first-write-wins
  (`MessageDao.insertIfAbsent`), so a room post keeps the plane it first arrived on across re-serves.
- **Header.** `LoraPlane { Off, Down, Live }` (`mesh/lora/LoraPlane.kt`) from `LoraStatusRepository.facts`
  (pushed) → `ConnectionStatusRow(lora = …)`: `Icons.Outlined.Sensors` (Live, tertiary) / `SensorsOff` (Down)
  after the cloud, spoken as "LoRa radio connected / not connected"; 45 s grace on the Down edge.
- **LoRa radio screen.** `BondedBoardDirectory` marks each bonded device board-like (LE + `Meshtastic_xxxx` /
  `<short>_xxxx` / the service UUID in cache); `BoardFilter.visible` lists those plus the bound board, with
  "Show all paired devices" (`lora_show_all_boards`) for the rest; the list re-reads on resume
  (`refreshBoards`). A connected board shows "Channel N · name" (`lora_channel_title`), a mismatch warning
  (`lora_channel_warning`) when the slot is not `Knit`, firmware, and peers heard (`lora_peers_heard`). The
  Profile row reads "On · <board> · connected / not connected".
- **Chat.** `LoraNotice` (`chat_lora_notice`, `ui/chat/LoraReach.kt`) under the relay notice for a DM whose
  peer only the board has heard (`peerTransports[peer] == {LoRa}`, plane live, not relay-covered), with a
  DMs-off variant; the composer's "long message" hint (`chat_lora_size_hint`) when the draft exceeds
  `LoraSizeHint`'s budget for its `LoraCarry` form (room 400 B, DM 320 B, −260 B replying, −170 B with a
  photo; pinned in `CoordinationPlaneSizeBudgetTest`).
- **Seam.** UI code reaches the transport only through `LoraPlaneStatus` (`status`, `provisionKnitChannel`),
  bound to `LoraMeshTransport` under `BuildConfig.LORA_PLANE` and to `LoraPlaneStatus.Dark` otherwise.

## Board setup (once, Meshtastic CLI or app)

Flash `firmware-heltec-v4-<ver>`; `--set lora.region <US|EU_868|…>`; `--set network.wifi_enabled false`;
`--set bluetooth.enabled true --set bluetooth.mode RANDOM_PIN`; same `lora.modem_preset` (LongFast) on
both. **The channel no longer needs hand-setup** — pair the board in Knit, then tap "Set up Knit channel"
(or `…debug.LORAPROV`) on each phone and both converge on the derived `KnitChannel`. (The old manual path
still works: `--ch-add knit` on A, `--qr-all` → `--seturl <url>` on B, then pick the index in Knit.) Set the
Meshtastic app's device to **None** / `adb shell am force-stop com.geeksville.mesh`.

## On-device verification (physical devices only, with an explicit go-ahead — `rules/devices.md`)

- Pair: Profile → LoRa radio → pick the bonded board → status `Ready`. `adb logcat -s MeshtasticGatt
  MeshtasticLink LoraMeshTransport`: `lora dial … bonded=true` → `mtu 517` → `handshake nonce=…` →
  `my_info !… pio=heltec-v4` → `config complete` → `ready`.
- Provision the channel: tap "Set up Knit channel" (or `…debug.LORAPROV`) on **both** phones → log
  `lora provision wrote chN 'Knit'` (or `reuse`) → the board reboots and the link reconnects → the channel
  index in settings now points at the Knit slot. Both boards must be provisioned before frames cross.
- `…debug.LORA` (debug bridge): `--es address <MAC>` + `--es name <n>` binds a board, `--ei channel <idx>`,
  `--ez on <true|false>`; no extras dumps `state/boardNodeNum/snr/rssi/queueFree/heard/counters`. It is the
  two-board oracle. `…debug.LORATX --es text <s>` sends a raw payload straight to the board (board-side
  sanity via `meshtastic --noproto`). `…debug.LORAPROV` writes the Knit channel headlessly (reports the slot).
- Broadcast: `…debug.SEND --es conv nearby --es text …` on A → appears on B within ~5–10 s; A's tick flips
  ✓✓ (sealed tick over LoRa); a reaction crosses. Move B out of BLE/NAN range and repeat. Counters:
  `loraSent/loraReceived/loraReassembled` climb, `loraNak == 0`, `loraDroppedQueue == 0` at chat pace,
  `loraTooBig` only for long posts. Diagnostics tags a LoRa-reachable node `LoRa`.
- DM (ADR 039): `…debug.SEND --es conv <peerId> --es text …` on A → appears on B within ~10 s; A's tick
  flips ✓✓ (the sealed receipt crossed back); `loraDmSent`/`loraDmReceived` climb on both, `loraTooBig == 0`.
  Reply from B (turnaround epoch) and a DM reaction cross; a 500-char DM counts `loraTooBig` and lands later
  over BLE. Power B's board off, send two DMs from A, power it on → B beacons, A logs `reoffer` ×2, both land,
  ✓✓ returns, `loraReoffered == 2`. `…debug.LORA --ez dms false` on A → a new DM stays LoRa-silent
  (`loraDmSent` flat) while a room post crosses. Rejoin a BLE clique after an hour of history:
  `loraSuppressed` climbs, `loraDroppedQueue` stays 0.

## First-session unknowns to confirm (assumptions, not blockers)

Whether an empty FromRadio read returns immediately or blocks ~20 s (could cut the 30 s read timeout);
`phone_timeout_secs` default (180 s heartbeat is safe either way); whether `queueStatus` is pushed as the
TX queue drains; that `mesh_packet_id` echoes our client id; that PRIVATE_APP packets reach the phone and
our own broadcast is not echoed; the 600 ms bonded post-connect settle (drop if unneeded).
