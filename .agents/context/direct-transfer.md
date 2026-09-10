# Direct file transfer (`transfer/`)

A file handed straight to one nearby contact over a Wi-Fi Direct group the two phones raise between
themselves and then tear down (`MAX_TRANSFER_BYTES` = 8 GiB, against the 8 MiB an attachment gets).
**The bytes never touch the mesh and are never custodied**; the mesh carries only the conversation about
them, and the chat keeps a small record of what happened.

Decisions: ADR 2026-09.wtmz (the carrier and the protocol), ADR 2026-09.37ce (the seal), ADR 2026-09.7uqe
(the surfaces). Read those before changing behaviour — this file is the map, they are the reasoning.

## Shape

- `TransferManager` — the whole state machine, **pure**: no Android imports, no IO of its own. Everything
  platform-shaped sits behind `DirectWifi` (the radio), `TransferFiles` (source and sink) and
  `TransferSignals` (the mesh). That is what lets `TransferManagerTest` run two managers back to back over
  loopback sockets and drive every failure path on a virtual clock.
- `TransferStream` — the byte protocol, pure and JVM-tested. Proof → header → sealed chunks → trailer →
  verdict byte.
- `TransferListener` — binds one `ServerSocket` per group address and races the accepts. Extracted from
  `TransferManager` when detekt's `LargeClass` fired; keep it that way.
- `AndroidDirectWifi` — **the only `android.net.wifi.p2p.*` importer** (detekt-enforced, exempted by name in
  `config/detekt/detekt.yml`). Hosting, joining, the Aware pause/resume, and the leftover-group sweep.
- `AndroidTransferFiles` — MediaStore `Downloads/Knit` with `IS_PENDING`, committed only after the trailer
  verifies.
- Signaling is one sealed DM ctl (`MessageContent.CTL_TRANSFER` + `xf`), gated on
  `Protocol.CAP_DIRECT_TRANSFER`. The chat row is `MessageEntity.KIND_FILE_TRANSFER` carrying a JSON
  `TransferRecord`. No DB migration; no wire break.

## What will bite you

- **Wi-Fi Aware must be paused explicitly.** On Android 12+ our own P2P does *not* evict our own NAN session
  — same-app interface requests have equal priority — so `createGroup` returns `BUSY` while Aware is
  attached, and `WifiAwareManager.isAvailable()` stays true the whole time. There is no availability edge to
  react to. `MeshTransport.pause()`/`resume()` exist for exactly this and nothing else.
- **A fast-plane send is not a delivery.** `sendTransferSignal` returns when the frame is queued. READY once
  went out 17 ms before `mesh.pause()` closed the Aware socket mid-fragment, and BLE could not recover it
  because the fast plane had already accepted the frame. `TransferTimings.readyGraceMs` is the drain. Expect
  this shape anywhere the app hands the radio away right after a send.
- **The receiver may hold no IPv4 address at all.** IP provisioning mode is the *client's* choice (API 34,
  not 33 — lint is right and AOSP's `@RequiresApi` is wrong) and the host is never told which was used, so
  the host binds every address on the group interface. Interface names differ per phone, so re-pin a
  link-local scope id locally rather than trusting the one that arrives.
- **The stream key is never used directly.** HKDF splits it into an enc key and a mac key. Chunk nonces are
  the chunk index, which is only safe because the key is per-transfer; the AAD is derived by the receiver
  rather than read off the wire, which is what makes truncation and reordering fail their tags.
- **`setSmallIcon` is invisible in the Android 12+ shade** — that header circle is the launcher icon. The
  small icon reaches the status bar only.
- **A force-stop mid-transfer strands a group on air.** `dumpsys wifip2p` shows `curState=GroupCreatedState`
  with the app dead. The startup sweep in `KnitApplication` clears it 8 s into launch; `…debug.XFER --ez
  sweep true` runs it on demand.

## Driving it

`…debug.XFER` — see `context/debug-bridge.md`. Read its `refusal` field first when nothing happens: six
different gates fail a transfer before the radio is touched and all of them look identical from outside.

## Numbers worth not re-deriving

Pixel 7 → Pixel 9 Pro XL, both API 37: **~70 MB/s on 5.2 GHz**, 1 GB in 15.2 s, screen-off costing ~3%.
Accept→joined ~6.2 s, of which ~4.1 s is the framework's own `connect()` and is flat regardless of when it
starts. Aware teardown 120–146 ms. `createGroup`→formed ~650 ms. Sealing runs 1765 MB/s on the host JVM,
about 25× the link, so the crypto is not the bottleneck. A GO on a different channel from the STA costs
about 9× to multi-channel time-slicing.
