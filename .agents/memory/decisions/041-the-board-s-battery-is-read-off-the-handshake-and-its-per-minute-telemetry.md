---
id: "041"
slug: the-board-s-battery-is-read-off-the-handshake-and-its-per-minute-telemetry
title: "The board's battery is read off the handshake and its per-minute telemetry, never polled"
date: 2026-08-25
topics: [lora, telemetry]
---

# ADR 041 — The board's battery is read off the handshake and its per-minute telemetry, never polled

**Context.** On a bench a Meshtastic board is USB-powered; in the field it runs on a cell the phone can't
see, and nothing in ADR 040's face said when the board was about to go dark. The firmware already tells the
phone: the config handshake streams `FromRadio.node_info` for every NodeDB entry, the board's own first, with
`device_metrics { battery_level, voltage, … }`, and `DeviceTelemetryModule` sends the phone (and only the
phone) a fresh `Telemetry.device_metrics` on `PortNum.TELEMETRY_APP` about once a minute while connected.
The bridge decoded neither: `node_info` fell into `FromRadio.Other`, and the telemetry packet — addressed
*from* the board's own node — died on the self-echo guard in `MeshtasticSession.onPacket`.

**Decision.**

1. **Decode only what the reading needs.** `MeshtasticProto` gains `FromRadio.NodeInfo(num, metrics)` and
   `decodeTelemetry` → `DeviceMetrics(batteryLevel, voltage)`; every other `NodeInfo`/`Telemetry` field is
   skipped and the other telemetry variants (environment, power, …) decode to null. Golden vectors pin the
   field numbers (node_info 4 / device_metrics 6; Telemetry.device_metrics 2 / battery_level 1 / voltage 2)
   like every other message the bridge speaks.
2. **Only the board's own entry counts.** The session reads a `NodeInfo` whose `num` is `my_info`'s and a
   TELEMETRY packet whose `from` is — in the handshake path and in the session drain — and surfaces neither
   as an inbound packet. A neighbour's telemetry stays what it was: a foreign-port packet the transport
   ignores.
3. **A `StateFlow<BoardBattery?>` beside `rxQuality`, not in `LinkState`.** A once-a-minute reading must not
   churn `Ready` (which re-derives `maxPayload`, counts a session-up and re-beacons the profile). Cleared on
   `handshake()` and `stop()`, so a reading never outlives its board.
4. **The firmware's conventions are folded once, in `BoardBattery.of`.** `battery_level` 0–100 is a charge;
   above 100 means external power (`percent = null, powered = true`); absent, negative (the int8 "unknown"
   cast through a uint32), or 0 with no voltage is *no reading* — a board without battery sense, not an empty
   cell (which still shows a voltage). `low` is ≤ 20 % on battery.
5. **Shown where the board is, plus the glance.** The LoRa radio screen's status row reads "Battery 78% ·
   3.92 V" / "Plugged in · 4.10 V" (`lora_battery`, error-coloured when low) under the firmware line; the
   Profile row appends "· battery 78%" / "· plugged in" while the link is live. `LoraFacts.battery` carries
   it for the Profile row only, is null unless the plane is `Live`, and is never a reach input. The header
   glyph is unchanged — a low-battery badge there is the obvious follow-up once the reading has been seen on
   hardware.

Not a wire change; no setting, no persistence — the reading is at most a minute stale and evaporates with
the link. No poll: the firmware pushes on its own schedule, so Knit adds no GATT traffic to get it. Surfaces +
tags: `context/lora-bridge.md`.
