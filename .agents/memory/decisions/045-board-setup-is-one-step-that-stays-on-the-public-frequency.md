---
id: "045"
slug: board-setup-is-one-step-that-stays-on-the-public-frequency
title: "Board setup is one step that stays on the public frequency, quiets the board, and stops it repeating"
date: 2026-08-26
topics: [lora, ui, provisioning]
---

# ADR 045 — Board setup is one step that stays on the public frequency, quiets the board, and stops it repeating

Status: Accepted (2026-08-26; `mesh/lora/` `BoardQuiet`/`ProvisionMode`, `MeshtasticProto` admin config
codec, `spliceVarintFields`, `SettingsStore.loraBoardSetup`, the LoRa screen's setup section)

*Three revisions the same week, none shipped — worth recording, because the reasoning moved twice.* The first
cut found that ADR 038's claim was wrong: a **secondary** channel does not move the radio, because the
firmware derives its RF slot from `hash(primary channel name) % numChannels` whenever `lora.channel_num` is 0
(`RadioInterface::getChannelNum`), so every Knit board was transmitting on the stock LongFast frequency. It
"fixed" that by writing Knit as the **primary**, and kept the old secondary write beside it as a lighter
mode. The second revision deleted the lighter mode — three states for one board, and two kinds of fleet that
cannot hear each other. The third, recorded here, deleted the frequency move itself, because the *cost* had
been read backwards:

1. **Sharing the public frequency is the feature, not the bug.** The stock `rebroadcast_mode = ALL` is
   documented as "rebroadcast any observed message, if it was on our private channel **or from another mesh
   with the same lora params**". A stock Meshtastic node that cannot decrypt a single byte of Knit therefore
   repeats our packets anyway, up to its hop limit, purely because we share frequency and preset. That is
   free range through infrastructure somebody else paid for and powers — which is the entire point of the
   LoRa plane. Moving to a Knit-derived slot bought congestion relief that Knit's traffic (SMS pace, capped
   by `LoraAirtime` at half a 10 % politeness ceiling) does not need, and paid for it in the only currency
   that matters here: who can hear us. On a sparse fleet the quiet slot is loudest — a Knit board would hear
   only other Knit boards, which early on is nobody. It bought nothing in privacy either: `KnitChannel`'s PSK
   is derived from public constants, so the slot is exactly as findable as the key. So Knit goes into a free
   **secondary** slot and the board's own primary is never touched — which is also the idiomatic Meshtastic
   private-group setup rather than a novelty.
2. **The board stops repeating everyone else's traffic.** The corollary of sharing the band is that a board
   left on `ALL` spends its battery relaying every packet it hears, invisibly, on hardware tethered to a
   phone. The setup writes `rebroadcast_mode = LOCAL_ONLY` — "only rebroadcasts messages on the node's local
   primary / secondary channels" — so a board still relays Knit between pockets (ADR 044 depends on exactly
   that) and stops paying for strangers. Mildly freeloading, stated plainly in the confirmation; the
   alternative is a user's board flattening its battery for a mesh it is not part of.
3. **It is one bargain, so it is one button — and no lighter setting beside it.** The setup also stretches
   `node_info_broadcast_secs`, `position_broadcast_secs` (with smart broadcast cleared) and
   `telemetry.device_update_interval` to six hours, and writes `position_precision = 0` on the Knit channel.
   The GPS is left alone: silencing what the board broadcasts is Knit's business, powering down the user's
   hardware is not. Restoring is the only other state — it disables the Knit channel and puts every setting
   back — and it leaves **no** Knit channel behind, so the plane is switched off with it rather than fanning
   frames out over whatever channel remains. Restoring a board that carries no Knit channel is refused.
4. **Every config write is a read-modify-write, at the byte level.** `AdminModule::handleSetConfig` assigns
   the whole sub-config (`config.device = c.payload_variant.device`), so a `Config { device { … } }` built
   from scratch would reset `role`, `gps_mode` and everything else this codec does not model — including,
   ironically, the GPS setting decision 3 promises to leave alone. The board's own `get_config` reply is
   therefore the base and `spliceVarintFields` replaces only the intended fields, copying every other field
   through byte-for-byte. A read that fails aborts the whole provision **before** `begin_edit_settings`, so a
   board that will not report its config is never half-written.
5. **The settings the board had are the user's, and are recorded.** A setup returns them
   (`ProvisionResult.Provisioned.previous`) and they are persisted per board address
   (`SettingsStore.loraBoardSetup`); restoring writes those back, falling back to the firmware defaults only
   when nothing was recorded. Re-running the setup on a board that already carries the channel is a no-op
   that reports `alreadyPresent` — overwriting the record with the quieted values would destroy the only copy
   of what the board looked like before. Forgetting the board forgets the record with it.
6. **Convergence now rests on the board's primary, so a renamed one is called out.** Two Knit boards meet
   only if their primaries hash alike — automatic for stock boards (an empty primary name falls back to the
   preset's own, e.g. `LongFast`) and false for anyone who renamed theirs, which is a silent and total
   failure. The screen says so when it sees one (`LoraRadioUiState.customPrimary`, against
   `ModemPreset.defaultChannelName`); it is the one warning worth keeping on a screen we deliberately
   stripped back. The transport also refuses to transmit when the bound slot is not the Knit channel, since
   Knit's frames are cleartext and the channel they would otherwise land on is very likely the public one.

Wire: none of Knit's. This is the Meshtastic admin API only — `get_config`/`set_config` (34),
`get_module_config`/`set_module_config` (35), `ChannelSettings.module_settings` (7) — pinned by golden vectors
in `MeshtasticProtoTest` beside the rest. `WIRE_COMPAT`/`NEXT_WIRE_BREAK` are untouched.

Honest residuals (accepted): the free-repeater effect is upside, not a guarantee — only nodes on `ALL` carry
us, community routers are often deliberately `LOCAL_ONLY`/`KNOWN_ONLY`, and `CORE_PORTNUMS_ONLY` drops us
outright since Knit rides `PRIVATE_APP`; our messages are heavier than a typical Meshtastic text (2–3 packets
plus a receipt) and each is amplified by every neighbour that repeats it, which the airtime governor bounds
but does not erase; a renamed primary is warned about, not fixed; the six-hour intervals are a judgement, not
a measurement; and stretching the *mesh* telemetry interval must not silence the phone-only telemetry ADR
041's battery row reads — a separate firmware timer, and the thing the device trial has to watch. If
congestion ever genuinely bites, the instrument is the board's own measured `channel_utilization` /
`air_util_tx`, not hiding on a frequency where nobody can hear us.
