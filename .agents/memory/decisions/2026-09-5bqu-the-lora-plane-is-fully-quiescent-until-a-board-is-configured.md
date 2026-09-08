---
id: "2026-09.5bqu"
slug: the-lora-plane-is-fully-quiescent-until-a-board-is-configured
title: "The LoRa plane is fully quiescent until a board is configured"
date: 2026-09-07
topics: [lora, mesh, performance]
---

# ADR 2026-09.5bqu — The LoRa plane is fully quiescent until a board is configured

Status: Accepted (2026-09-07)

**What was observed.** Regenerating the baseline profile on a BLE-capable emulator (ADR 2026-09.6gtm put
`BuildConfig.LORA_PLANE` on everywhere) put **265 `mesh/lora` methods into the startup profile of a phone
that has never seen a radio** — `LoraAirtime.prune`/`snapshot`/`budgetMs`, `AirtimeSnapshot.equals`,
the whole `LinkState` hierarchy. All were `SP`-tagged, so ART had genuinely executed them, and they were
AOT weight spent on a plane most installs never arm.

The profile only made it visible. `LoraMeshTransport.start()` launches nine coroutines unconditionally,
and two of them are timed loops with no board guard. `lingerSweepLoop` ran `recomputeReachable` plus the
ADR 044 election **every 60 seconds for the life of the foreground service**, over a heard set that is
always empty when no board exists; `gossipLoop` woke on its Trickle interval to decide, every time, that
it had nothing to offer. Measured by counting clock reads across one idle virtual hour: **210 reads with
no board paired, against 0 once gated.** Neither uses an alarm, so this never woke a dozing CPU — it is
waste, not a battery bug, which is why it survived unnoticed since the plane shipped.

The rest of the plane was already correct and stays untouched: `pacerLoop` parks on `wake.receive()` at
`pending == 0`, the four `link.*` collectors never emit because `MeshtasticSession.start(address)` is
never called, and `mayTransmit()` refuses on `currentConfig == null` before doing any work — its comment
already names this as "the state most installs are in".

**What changed.** The two timed loops park on `awaitConfigured()`, a `MutableStateFlow<Boolean>` mirroring
`currentConfig != null`. It gates on the **plane's config, not the link's state**: a configured board that
is merely disconnected still has sweeping and gossiping to do, and both loops already handle a down link.

The obvious alternative — keep the child out of the composite until a board is paired — does not work, and
the reason is worth writing down. Something has to be collecting `config` to learn that a board *has* been
paired, and `LoraPlaneStatus` needs the transport to exist so Settings can show the plane at all. The
transport must therefore be constructed and started; only its periodic work can be deferred.

**What it costs, and the trap.** Parking the sweep is only safe because `recomputeReachable` is the *sole*
thing that ages `lastHeardAt` / `boardsHeardAt` out and shrinks `_reachable`. A naive gate strands the last
board's heard set in `reachable` **forever** when a user unpairs — nothing else ever expires it. So the
cleanup half of `stop()` is extracted into `quiesce()`, and `onConfig(null)` now runs it too: losing the
board and stopping the plane are the same state and used not to be. Reach therefore empties **at once** on
unpair rather than after `REACHABLE_LINGER_MS`, which is also the more honest answer — the route those
peers were reachable through is gone the moment the board is.

`quiesce()` owns `configured.value = false`, so a stopped plane is never left reading as configured.

Two `LoraMeshTransportTest` regressions hold it: `anUnconfiguredPlaneParksItsLoopsInsteadOfSweepingAndGossiping`
counts clock reads across an idle hour (it fails at 216-vs-6 with the gate removed), and
`losingTheBoardDropsWhatItHeardRatherThanLeavingItToTheSweep` is the unpair case that makes the parking
safe. Anything added to those loops that must run without a board has to move above the
`awaitConfigured()` call, and anything new that ages state out belongs in `quiesce()` as well.
