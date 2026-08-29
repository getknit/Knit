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
  **The recipient gate (ADR 054):** a DM-form frame addressed to us, or to a peer BLE/NAN holds a **live link**
  to (`coveredByLink`, read off `linkedPeers` — links, never sightings), is skipped on this path and on the
  bridge backfill before the sig-dedup slot is spent: the link carries it. Counted `loraSkippedLinked`. The
  originator's `FanoutHint` (`CONTENT`/`TICK`) rides beside the frame — see Pacing.
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
                              LoraPacePolicy (class shedding + LoraAirtime budget) · reachable(45min linger) ·
                              beacon + reofferTo on first hearing · LoraGatewayPolicy/LoraGossipPolicy/LoraCtl (the bridge)
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

## Bridging pockets (ADR 044)

Two BLE/NAN cliques ("pockets") out of range of each other, one board-holder in each, boards in LoRa range.
**Live traffic already crossed** before ADR 044 and still does with no new machinery: `InboundPipeline.onDeliver`
re-fans every first-seen *relayed* frame, and `fanout` never checked authorship — so a post by any pocket-A
member reaches A's gateway over BLE, crosses the hop, and floods pocket B from B's gateway. ADR 044 adds the
three things that were missing.

**A gateway role.** `LoraGatewayPolicy`: anything publishing a `LoraCtl` OFFER has a board; a publisher we
hold a **live link** to (`suppressDataPath`, the higher-preference planes' `neighbors`) is a co-pocket rival,
one we do not is the bridge peer and is never suppressed. Lowest publisher key wins; the rest go PASSIVE and
suppress the **floodable** paths (fan-out, beacon, offer, backfill) — inbound is untouched, so a spare board
still feeds its pocket, and `fastSend` is untouched too (a `relay = false` targeted tick is owed by one node,
never flooded, so no co-pocket board has a copy to relay *or* to duplicate). Recovery: on a lost link, on a
gateway ageing past `STALE_MS` (45 min), and on the 60-s sweep — being wrongly passive is total silence, so it
must never need an event to recover. Closes ADR 038's "one board per clique" residual.

> **It must be the link set, never `reachable`.** BLE publishes presence adverts far beyond L2CAP range and
> Wi-Fi Aware keeps a peer reachable for 150 s after its last cue, so a sighting is not a data path — and
> standing down is only safe toward a board our frames can actually be handed to. Electing on `reachable`
> was field-observed (2026-08-25, two Pixels across a field): the higher-keyed one showed "listening", sent
> nothing in either conversation, and its ✓✓ ticks never landed, with no peer carrying any of it. The same
> audit moved ADR 039's two `foreignReachable` guards (`fastSend`'s "another plane covers this peer" and the
> re-offer's "custody syncs there for real") onto the link set for the identical reason — custody's digest
> exchange runs off `neighbors`, so a sighting never triggers it, and the `fastSend` one alone would have kept
> those receipts stranded. `…debug.LORA` reports `role` with both its inputs plus `pocketSightings`, so the
> gap between heard and linked is visible rather than inferred.

**An airtime governor.** `LoraAirtime` (pure): time-on-air from the LoRa formula at the board's own preset
(233 B at LongFast ≈ 2 s), a rolling **15-minute window** (ADR 054 — it was an hour, and a burst of chat then
blacked the plane out for the rest of it; the hourly total is unchanged, the worst straddling hour ≤ 6.25 %),
and one allowance = `min(region duty cycle, 10 % politeness) × 0.5` of the window — **45 s of air at LongFast**.
`AirBucket.LIVE` may spend all of it; `AirBucket.BRIDGE` (offers + backfill + the ADR 039 re-offer) is capped
at 30 %, so backfill degrades before live chat does; `AirBucket.BOOTSTRAP` (a live `profile` fan-out, ours or
relayed — paired to `FrameClass.BOOTSTRAP` by `AirBucket.defaultFor`) is capped at 25 % and is the **one class
judged outside the total**, so the key bootstrap still rides a spent window (ADR 056). It used to be admitted
unconditionally *and* recorded, which is a budget with no floor: on the lab gateway 79 % of every LoRa frame
ever sent was a profile, because a relayed one is gated only by the 10-minute signature dedup and
`MeshRouter`'s SeenSet lapses on the same 10 minutes. A backfilled profile stays on `BRIDGE` — re-served
history, not bootstrap. `FrameClass.TICK` (our own delivery receipts, see Pacing) never spends the last 25 %
of a window. Region + preset
are read off the board (`FromRadio.config` → `Config.LoRaConfig`, pinned by `MeshtasticProtoTest`; conservative
5 % until reported). `LoraPacePolicy.take` consults it, skipping a refused frame rather than blocking behind
it, and **dequeue is now by class then FIFO** (a reversal of ADR 039 §5 — bursts of backfill must not queue-jump
a live message).

**Digest-driven backfill.** `LoraCtl` (tag **`0x10`**, beside `FastFrameCodec`'s `0x03`/`0x04`; an older build
drops it as `UNKNOWN_TAG`, which is what makes this additive) carries an OFFER: publisher key + ≤ 48 4-byte id
prefixes (`StoreDigest.hash64` truncated), never fragmented, one packet. `LoraGossipPolicy` is Trickle —
5 → 15 min doubling, transmit in the interval's second half, snap to the floor on news, suppress only on **set
equality** (a superset has not said what we needed to say). On a far gateway's OFFER: `BridgeFrameSource`
(`MeshManager` over `ForwardStore.liveFrames`, already TTL- and quota-bounded, so no extra age gate) returns
what the prefixes don't name, ranked profile → DM → room newest-first; ≤ 4 per offer, ≤ 12 per publisher per
hour, sig-deduped, and hard-bounded by the BRIDGE budget. Frames are re-wrapped verbatim like any custody
re-serve — no wire change, no custody rule touched. `SettingsStore.loraBridgeEnabled` (default **on**, the
"Bridge distant groups" switch) gates offering and serving together.

**Multi-hop is Meshtastic's job.** A frame injected at a far gateway can't be re-transmitted (sig dedup) and a
second board there is PASSIVE, so a third pocket is reached by the board's own 3-hop flood, not by a second
phone-level send.

## Pacing

`LoraPacePolicy` (pure): 3 s min inter-packet gap, a 32-frame queue (ADR 054 — sized to hold a 15-minute
wait), NAK back-off (rate/duty → a 60 s cool-down), hold while `queueFree == 0`, and (ADR 044) a hold while the
frame's [AirBucket] budget is spent — a refused frame is skipped rather than blocking the queue behind it.
When full the queue **sheds by class** (`FrameClass`: BOOTSTRAP > GOSSIP > DM > ROOM > TICK): the oldest
**whole** frame (never a lone fragment) of the lowest class present goes, the newcomer included — a room post
alone at the bottom is `REFUSED` rather than evicting a DM, and nothing ever evicts the profile bootstrap
(queue order only — since ADR 056 the bootstrap is metered on the air, and a profile refused by its share
waits in the queue for the next window rather than being dropped).
`TICK` is a frame **we** originated as a delivery receipt, said so by `FanoutHint.TICK` on
`MeshTransport.longRangeFanout` (the transport cannot read a sealed frame); a relayed DM-form frame stays `DM`,
and every `fastSend` frame is a tick by policy. Dequeue runs the same class order forwards since ADR 044
(highest class first, FIFO within it — bursts of gossip/backfill must not queue-jump a live message). Both
eviction and refusal count as `loraDroppedQueue`.

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
no re-offer (no routing table).

**The ✓✓ is coalesced (ADR 054).** A DM that arrived over the board does not seal its receipt at once:
`InboundPipeline.acknowledge` holds it in `mesh/DmAckCoalescer` for ≤ 45 s (anchored on the oldest held id;
re-deliveries inside the hold add nothing), then `MeshManager.flushDmAcks` originates **one** sealed tick
(`ack`/`acks`, ≤ 12 ids — pinned to fit 3 packets at the ESP32 cap), hinted `TICK`. If we send the author a
DM meanwhile, `sendChat` carries up to 4 pending ids inline as `MessageContent.acks` on the plain DM instead
(`Protocol.CAP_INLINE_ACK` on the author's profile; 23 B each reserved out of the composer's LoRa body budget
so a reply can never become `loraTooBig` to save a tick; a v1 fallback gives them back) and no tick goes out.
DMs off the phone radios keep the instant receipt; an author who cannot read a sealed tick keeps the cleartext
form. Airtime: ~2 packets per DM; a receipt is ~0.2 s riding a reply, ~3 s standalone — an exchange costs
~4 s of the 45-s window instead of ~7 s. `heal()` is the flush backstop; a process death inside the hold
loses the pending acks and the peer's next re-offer heals it. Counted `loraTickDeferred`, `receiptsCoalesced`.

**Metadata.** Content stays end-to-end sealed, but a DM's cleartext `senderId`/`recipientId`, timing and
size now travel on the public-PSK rendezvous channel at kilometre range. `SettingsStore.loraDmEnabled`
(default **on**, the "Private messages over LoRa" switch on the LoRa radio screen, `…debug.LORA --ez dms`)
rides into `LoraConfig.dms`; off, the transport refuses DM-form on fan-out and skips the re-offer while the
room keeps riding. Each side gates its own sends. The confidentiality fix remains the deferred private PSK.

## Board provisioning (Knit configures the board)

`LoraMeshTransport.provisionKnitChannel()` (the settings button "Set up this board for Knit", or
`…debug.LORAPROV`) configures the board over the Meshtastic **admin** API so the user never hand-configures
anything. The mechanics, all inside `MeshtasticSession` (serialized with sends): an admin `get_channel` to
the local node (`to = myNodeNum`, portnum ADMIN=6, `want_response`) yields the `session_passkey` (field 101,
300 s TTL) that every following write echoes; then `begin_edit_settings` → the writes → `commit_edit_settings`.
The commit reboots the board to apply the edit, so the session ends (reset backoff) and re-handshakes,
reloading the channel table; the result returns as soon as the writes are accepted. A
`Routing.ADMIN_BAD_SESSION_KEY` NAK triggers one fresh-passkey retry of the whole transaction. **What** gets
written is the next section.

**`KnitChannel`** (`mesh/lora/KnitChannel.kt`): name `"Knit"`, 16-byte AES128 PSK **derived** (pinned +
guarded by `KnitChannelTest`) via `HKDF-SHA256(ikm="nearby", salt=0³², info="knit/lora/channel/psk/v1")`.
The seed is public, so the PSK is public — deliberately: the Nearby room is cleartext, so this channel is a
**rendezvous** (any two Knit boards converge with zero coordination), not a confidentiality boundary. Knit's
per-frame Ed25519 signatures remain the integrity boundary. A confidential per-deployment PSK (shared
out-of-band via a channel QR/URL) is deferred — see `roadmap.md`.

> **Knit shares the public frequency on purpose.** The firmware derives its RF slot from
> `hash(primary channel name) % numChannels` whenever `lora.channel_num` is 0
> (`RadioInterface::getChannelNum`), so writing Knit into a **secondary** slot leaves the board on whatever
> frequency its primary picks — for a stock board, the public LongFast one. That is deliberate: the default
> `rebroadcast_mode = ALL` "rebroadcast[s] any observed message… **from another mesh with the same lora
> params**", so stock Meshtastic nodes repeat Knit's packets up to their hop limit without being able to read
> a byte of them. Free hops through somebody else's infrastructure are worth more than a quiet channel to a
> plane whose whole job is reach. (ADR 038 claimed the *channel* kept Knit off LongFast; it never did, and
> ADR 045 decided it should not.)
>
> **The catch: convergence rests on the primary.** Two boards meet only if their primary names hash alike —
> automatic for stock boards, false for anyone who renamed theirs. `LoraRadioUiState.customPrimary` warns.

## Setting a board up (ADR 045)

A board is **either set up for Knit or a stock Meshtastic node** — there is no lighter option and no
hand-editable channel index, so every Knit board is configured identically and any two meet without
coordination. `provisionKnitChannel(ProvisionMode.Setup)` — the "Set up this board for Knit" button, or
`…debug.LORAPROV` — does all of it in one `begin_edit`/`commit_edit` transaction after three reads:

1. `get_config(DEVICE)`, `get_config(POSITION)`, `get_module_config(TELEMETRY)` — **before** the transaction.
   `AdminModule::handleSetConfig` assigns the whole sub-config, so every write is a read-modify-write over the
   board's own bytes (`spliceVarintFields`, `ProtoIo.kt`): only the intended fields change and everything this
   codec does not model (`role`, `gps_mode`, `tzdef`, …) is copied through byte-for-byte. A read that fails
   aborts with nothing written.
2. `set_channel { index N, settings { psk, name "Knit", module_settings {} }, role = SECONDARY }` into the
   lowest free secondary slot (1..7), reusing an existing Knit channel wherever it already sits. **Index 0 is
   never touched** — see the note above. The empty `module_settings` **is** `position_precision = 0`; an
   absent one reads as "unset" and the firmware defaults to full precision.
3. `set_config` / `set_module_config` stretching `node_info_broadcast_secs`, `position_broadcast_secs` (smart
   broadcast cleared) and `telemetry.device_update_interval` to `BoardQuiet.QUIET_SECS` (6 h), and setting
   `rebroadcast_mode = LOCAL_ONLY` so the board keeps relaying its own channels — all ADR 044's bridge needs —
   and stops spending its battery repeating the rest of the band. The GPS itself is not touched: silencing
   what the board *broadcasts* is Knit's business, powering down the user's hardware is not.
4. `set_owner` renaming the board **`Knit abcd`** / short name **`Knit`** (`BoardName`, ADR 049) — the suffix
   is the low two bytes of its node number, the same shape the firmware's own `Meshtastic abcd` default uses,
   so two boards in one pocket stay distinguishable. Read first (`get_owner_request`, **before** the
   transaction, like the configs) and spliced with `spliceStringFields`: `handleSetOwner` merges non-empty
   strings, but `is_licensed` is a presence-less bool whose absence clears `override_duty_cycle`, so only the
   two names may be written. Deliberately **not** the user's display name — a `NodeInfo` is cleartext on the
   public frequency.

The settings **and the name** the board had first come back as `ProvisionResult.Provisioned.previous` and are
persisted per board (`SettingsStore.loraBoardSetup`); **Restore** writes them back and disables every Knit
channel. With no name recorded, the restore writes the one the firmware itself would have chosen
(`BoardName.stock`) rather than leave a restored board saying Knit. It
leaves **no** Knit channel, so the caller switches the plane off with it — otherwise the next fan-out would go
out over whatever channel remains — and restoring a board that carries none is refused, since there is nothing
to undo and the config writes would push somebody's board to values it never had. Re-running the setup on a
board that already has the channel is a reported no-op **except for the rename**: a board set up before ADR
049 gets one `set_owner` and nothing else, carrying the caller's existing record forward with the old name
filled in, so the recorded settings are never overwritten with the quieted ones. The screen offers exactly that as a
**rename button** (`lora_rename`, no confirmation — one reversible field) whenever `LoraRadioUiState.needsRename`:
the board carries the Knit channel and `BoardInfo.owner` — its own `NodeInfo.user`, decoded off the same
handshake ADR 041's battery comes from — is a name other than `BoardName.forNode`'s. A board whose firmware
never sends its own `NodeInfo` reports no name and is left alone.

A board whose bound slot is **not** the Knit channel never transmits (`LoraMeshTransport.boundSlotIsKnit`,
counted as `loraSuppressed`): after a restore, or before a setup, sending would put Knit's cleartext frames
onto whatever channel the board is on — most likely the public one. A board that reports no channel table at
all is given the benefit of the doubt, since going mute on unreadable firmware is the worse failure.

**The cost, which the confirmation states out loud:** the board is renamed for Knit, stops broadcasting its
position and node info, and stops relaying other radios' traffic. Its own main channel is left alone and it stays on the public
frequency, so nothing else about its place in the Meshtastic network changes.

Admin wire (pinned by `MeshtasticProtoTest`): `AdminMessage{ get_channel_request=1, get_channel_response=2,
get_owner_request=3, get_owner_response=4, get_config_request=5, get_config_response=6,
get_module_config_request=7, get_module_config_response=8, set_owner=32, set_channel=33, set_config=34,
set_module_config=35, begin_edit_settings=64, commit_edit_settings=65, session_passkey=101 }`;
`User{ long_name=2, short_name=3 }`; `Channel{ index=1, settings=2, role=3 }` (Role SECONDARY=2, DISABLED=0);
`ChannelSettings{ psk=2, name=3, module_settings=7 }` with `ModuleSettings{ position_precision=1 }`;
`Config{ device=1, position=2 }` / `ModuleConfig{ telemetry=6 }` (the request enums number differently:
`ConfigType{ DEVICE=0, POSITION=1 }`, `ModuleConfigType{ TELEMETRY=5 }`);
`DeviceConfig{ rebroadcast_mode=6, node_info_broadcast_secs=7 }` (RebroadcastMode LOCAL_ONLY=2);
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
  (`refreshBoards`). A connected board shows the one setup section (`lora_setup` / `lora_restore`, ADR 045),
  firmware, **radios in range** (`lora_boards_heard`,
  distinct Meshtastic `packet.from` on our channel, control packets and part-fragments included) and — only
  when the two differ — **people reachable** (`lora_peers_heard`, distinct frame *authors*). The two diverge as
  soon as a gateway relays or backfills somebody else's frame, so "1 radio, 3 people" is normal; reporting only
  the author count read as phantom hardware in the field (2026-08-25: "3 peers heard" with two radios in
  existence). `LoraStatus.heard` stays the author count and remains what `reachable`/routing use. The
  Profile row reads "On · <board> · connected / not connected".
- **Chat.** `LoraNotice` (`chat_lora_notice`, `ui/chat/LoraReach.kt`) under the relay notice for a DM whose
  peer only the board has heard (`peerTransports[peer] == {LoRa}`, plane live, not relay-covered), with a
  DMs-off variant and (ADR 054) a **saturated** variant — `LoraFacts.airtimeSpent`, ≥ 90 % of the window's
  live budget while live — that says messages are delayed; the composer's "long message" hint (`chat_lora_size_hint`) when the draft exceeds
  `LoraSizeHint`'s budget for its `LoraCarry` form (room 400 B, DM 320 B, −260 B replying, −170 B with a
  photo; pinned in `CoordinationPlaneSizeBudgetTest`).
- **Battery (ADR 041).** The board's own `DeviceMetrics` — its `FromRadio.node_info` (the entry whose `num`
  is `my_info`'s) in the handshake, then the TELEMETRY_APP packet the firmware sends the phone about once a
  minute — land in `MeshtasticLink.battery` (`BoardBattery`: `percent` / `voltage` / `powered`, folded by
  `BoardBattery.of`; a level above 100 is "plugged in", 0 with no voltage is no reading) → `LoraStatus.battery`
  → the status row's "Battery 78% · 3.92 V" / "Plugged in · 4.10 V" (`lora_battery`, error-coloured at ≤ 20 %)
  and the Profile row's "· battery 78%" while live (`LoraFacts.battery`, never a reach input). Never polled;
  cleared with the link.
- **Seam.** UI code reaches the transport only through `LoraPlaneStatus` (`status`, `provisionKnitChannel`),
  bound to `LoraMeshTransport` under `BuildConfig.LORA_PLANE` and to `LoraPlaneStatus.Dark` otherwise.

## Board setup (once, Meshtastic CLI or app)

Flash `firmware-heltec-v4-<ver>`; `--set lora.region <US|EU_868|…>`; `--set network.wifi_enabled false`;
`--set bluetooth.enabled true --set bluetooth.mode RANDOM_PIN`; same `lora.modem_preset` (LongFast) on
both. **Nothing else needs hand-setup** — pair the board in Knit, then tap "Set up this board for Knit" (or
`…debug.LORAPROV`) on each phone: the channel and the housekeeping both follow from that one action, and
there is no hand-picked channel index any more (the debug bridge keeps `--ei channel <idx>` for lab work).
Leave each board's **primary channel at its default** — renaming it moves the radio to another frequency,
where no other Knit board is listening. Set the Meshtastic app's device to **None** /
`adb shell am force-stop com.geeksville.mesh`.

## On-device verification (physical devices only, with an explicit go-ahead — `rules/devices.md`)

- Pair: Profile → LoRa radio → pick the bonded board → status `Ready`. `adb logcat -s MeshtasticGatt
  MeshtasticLink LoraMeshTransport`: `lora dial … bonded=true` → `mtu 517` → `handshake nonce=…` →
  `my_info !… pio=heltec-v4` → `config complete` → `ready`.
- Battery: the status row shows the reading with `ready` (the handshake's `node_info`) and refreshes within a
  minute; unplug USB and "Plugged in" becomes a percentage on the next telemetry, replug and it flips back.
- Set the boards up (ADR 045): `…debug.LORAPROV` (or the screen's "Set up this board for Knit") on **both**
  phones → log `lora provision set up chN 'Knit'`, the board reboots, and the link returns. Then, over USB
  with the Meshtastic app disconnected: (1) `meshtastic --info` on both boards reports the **same, unchanged**
  frequency and an untouched primary channel — the setup must never move the radio; (2) a Nearby post crosses
  and `loraNak == 0`; (3) the **battery row still refreshes within a minute**, which is what proves stretching
  the *mesh* telemetry interval did not silence the phone-only telemetry ADR 041 reads (if it did, that
  interval is the wrong lever); (4) `meshtastic --get device.role`, `--get lora.region`,
  `--get position.gps_mode` are unchanged from before while `--get device.rebroadcast_mode` now reads
  `LOCAL_ONLY` — the read-modify-write proof; (5) a post crosses further than either board's own range when a
  stock node sits between them — the free-repeater effect this design is built on; (6) `…debug.LORAPROV --es
  mode restore` leaves no Knit channel, every setting back at its pre-setup value, and the LoRa switch off.
- Provision the channel: tap "Set up this board for Knit" (or `…debug.LORAPROV`) on **both** phones → log
  `lora provision wrote chN 'Knit'` (or `reuse`) → the board reboots and the link reconnects → the channel
  index in settings now points at the Knit slot. Both boards must be provisioned before frames cross.
- `…debug.LORA` (debug bridge): `--es address <MAC>` + `--es name <n>` binds a board, `--ei channel <idx>`,
  `--ez on <true|false>`, `--ez bridge <true|false>`; no extras dumps
  `state/boardNodeNum/snr/rssi/queueFree/heard/role/pocketLinks/pocketSightings/gatewaysHeard/radio/airtime/counters`
  (`airtime` carries `liveMs`/`bridgeMs`/`bootstrapMs` against their budgets — `loraSent − loraDmSent −
  loraOfferSent` is the profile + room count, and profiles are the fragmented ones). It is the
  two-board oracle. `…debug.LORATX --es text <s>` sends a raw payload straight to the board (board-side
  sanity via `meshtastic --noproto`). `…debug.LORAPROV` sets the board up headlessly; `--es mode restore` undoes it.
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

- Airtime (ADR 054), the three-phone trial: A + C linked over BLE, B far over LoRa, boards on A and B. (1) A ↔ C
  text 20 messages over BLE → on A `loraSkippedLinked` climbs by ~40 (DMs + ✓✓s), `loraSent` and
  `airtime.liveMs` stay flat, ✓✓ instant, B's board hears nothing. (2) A ↔ B text over LoRa: each ✓✓ lands on A
  within ~45 s or with B's reply; `loraTickDeferred`/`receiptsCoalesced` climb; a burst of three from A yields
  one tick from B. (3) A burst past the window: `airtime.liveMs` reaches `liveBudgetMs`, the chat notice
  appears on A for B, `loraDroppedQueue == 0`, the queue drains within 15 min, ticks yield first.
  (4) `loraNak == 0` throughout, EU boards included.
- Bridge (ADR 044), the four-device trial: pocket A = board-holder + one more phone, pocket B likewise, the
  two pockets out of BLE/NAN range of each other. `…debug.LORA` shows `role: ACTIVE` on both board-holders and
  a real `radio` (region/preset off the board, not `(assumed)`). (1) A room post from A's **board-less** phone
  reaches B's board-less phone in ~10 s — `loraSent`/`loraReceived` climb on the gateways only. (2) Power B's
  board off, post twice in A, wait past `FRESH_MS` (15 min), power it back on → B offers, A logs
  `lora bridge served=2`, both land, `loraBridged == 2`. (3) Pair a third board to A's second phone → it
  reports `role: PASSIVE`, `loraPassive` climbs, its `loraSent` stays flat, and the bridge keeps working.
  (4) Over an hour of chat pace: `airtime.liveMs` under `liveBudgetMs`, `bridgeMs` under `bridgeBudgetMs`,
  `loraNak == 0`, `loraDroppedQueue == 0`. (5) `…debug.LORA --ez bridge false` → `loraOfferSent` stops
  climbing and no backfill is served, while a live room post still crosses.

## First-session unknowns to confirm (assumptions, not blockers)

Whether an empty FromRadio read returns immediately or blocks ~20 s (could cut the 30 s read timeout);
`phone_timeout_secs` default (180 s heartbeat is safe either way); whether `queueStatus` is pushed as the
TX queue drains; that `mesh_packet_id` echoes our client id; that PRIVATE_APP packets reach the phone and
our own broadcast is not echoed; the 600 ms bonded post-connect settle (drop if unneeded).
