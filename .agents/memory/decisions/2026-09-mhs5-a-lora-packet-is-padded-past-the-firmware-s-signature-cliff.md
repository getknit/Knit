---
id: "2026-09.mhs5"
slug: a-lora-packet-is-padded-past-the-firmware-s-signature-cliff
title: "A LoRa packet is padded past the firmware's signature cliff"
date: 2026-09-01
topics: [lora, airtime, link]
---

# ADR 2026-09.mhs5 — A LoRa packet is padded past the firmware's signature cliff

Status: Accepted (2026-09-01; `LoraAirtime.padTo` + `PacketCost`, `LoraFrameCodec.encodeBest(cost = …)`,
`FastFrameCodec.deflated`/`deflatedForm`, `MeshMetrics.onLoraPadded`, `…debug.LORA` `signing`/`loraPadded`)

Meshtastic 2.8 signs every broadcast the board originates — 66 bytes of `Data.xeddsa_signature` Knit
neither asks for nor can decline — but only while the signed encoding still fits, so a `Data.payload`
of **≤ 165 B** gets the signature and 166 B does not. Measured on a Heltec V4 / 2.8.0.7239fe8
(2026-08-31) against the firmware's own `Packet TX:` figure: at LongTurbo a 165-byte payload and a
231-byte one both cost **1655 ms**, and 166 B costs **1262 ms**. ADR 054 charges for that surcharge;
this is the other half — the cliff cuts both ways, and a frame just under it can trade the board's
66-byte signature for a handful of pad bytes.

## What changed

`LoraFrameCodec.encodeBest` now takes a `PacketCost` (the `LoraAirtime` governor, which already prices
the cliff in `timeOnAirMs`). Given one, it grows the **last** packet of a frame to
`MAX_SIGNED_PAYLOAD + 1`. Only the last: every earlier fragment is a full `cap`-sized chunk and past the
cliff already, and touching only the tail means the fragment count cannot change, so nothing downstream
re-derives. Without a `PacketCost` — the default — the encoding is byte-for-byte what it was.

**This is not a wire change and needs no capability bit.** `WireEnvelope`, `WireCodec`, the signing
input and `sig`/`signed` are untouched; the pad lives inside `Data.payload`, past the end of the frame's
deflate stream, and `FastFrameCodec`'s `inflate` loop stops at the stream marker and never reads further.
Every build already in the field contains that loop, so a padded frame decodes on all of them.

Two things make it work in practice.

1. **The gate is the DEFLATED flag, not a guess.** A *stored* body has no end marker: on 0x03 the pad
   becomes the tail of `signed` and the frame dies later at CBOR decode or signature verification — a
   silent corruption — and on 0x05 `FrameTranscoder.rebuild` rejects it outright. So `FastFrameCodec`
   now answers `deflated(frame)` and the codec pads nothing else.
2. **A stored frame is re-deflated so it *can* be padded.** This is the part the design missed until it
   was measured. The frames with most to gain are exactly the ones that store: the transcoder (ADR 060)
   has already thrown the compressible CBOR keys away, so the 157-byte unsigned v3 tick — the smallest,
   most frequent frame on the plane — has nothing left to compress and rides stored. Deflating it anyway
   costs a **measured 5 bytes** of framing (pinned by `FastFrameCodecTest`) and makes the pad legal.
   `deflatedForm` is taken only when the priced result is cheaper than the stored original, so a frame
   with nothing to gain is left exactly as it encoded. Single-packet frames only: re-deflating a
   fragmented frame changes its size and could change the part count.

Yield, from the shipped `timeOnAirMs` (the same formula that matched the bench to ≤ 1 ms):

| frame | on the air | LongFast | LongTurbo | MediumFast |
|---|---|---|---|---|
| unsigned v3 tick, `0x05` | 157 → 166 B | 2074 → **1665 ms** | 1590 → **1262 ms** | 631 → **498 ms** |
| room post ≥ 45 chars, `0x05` | 155 → 166 B | 2074 → **1665 ms** | 1590 → **1262 ms** | 621 → **498 ms** |
| room post ~23 chars, `0x05` | 141 → 166 B | 1951 → **1665 ms** | 1491 → **1262 ms** | 590 → **498 ms** |

That is **~20 %** off the two highest-frequency frames the plane carries, in a plane whose whole budget
is 45 s of air per 15-minute window.

## The alternatives, and why they are not this

- **Lean on the board's signature and drop Knit's own.** Unsound four times over — wrong key (XEdDSA
  signs with the *board's* identity, so it attests "this board transmitted these bytes", never "this
  user authored this frame"), hop-scoped rather than end-to-end (field 10 exists only on the LoRa air and
  is gone the moment the board hands the frame up over BLE, while custody, relays and spools all verify
  `sig`), inverted (the firmware refuses to sign over 165 B, so the frames that would gain most have no
  signature to borrow), and per-packet rather than per-frame. Recorded so it is not re-proposed.
- **Balance the fragment split instead of padding.** Worse, not better: greedy `[228, 80]` collects one
  signature, balanced `[154, 154]` puts **both** halves under the cliff and collects two.
- **A byte-count rule instead of an airtime one.** `padTo` compares `timeOnAirMs` at the board's own
  preset, which gets the break-even exact under symbol quantization and makes the pre-2.8 board
  (`signing = false`, nothing to dodge) and the already-over-the-cliff frame fall out of the same
  comparison rather than needing their own branches.

## What it costs, and the trap

The tolerance this rides on is an **accident of the inflate loop, not a designed property**. Hardening
`FastFrameCodec.inflate` to reject trailing input would read as a safe cleanup and would silently break
every padded sender against older receivers. `FastFrameCodecTest.aDeflatedFrameIgnoresTrailingBytesBecauseThePaddedLoraSenderDependsOnIt`
is what stops that; if it ever has to change, the padding has to be retired first.

Not covered: **fragmented stored frames**. A ~328 B stored `0x05` splits into two ~166 B packets sitting
right at the cliff — exactly where padding would pay, and exactly where it is not allowed, because
re-deflating the whole frame could change the part count. Nor are `LoraCtl` OFFER packets padded:
`decodeOffer` would tolerate the trailing bytes, but `LoraGossipPolicy`'s suppression depends on two
offers over the same set being byte-identical, and a peer on pre-2.8 firmware would not pad — a separate
decision. And `packet_signature_policy` is still unhandled: under `STRICT` a board drops every unsigned
packet, which after this is *every* Knit frame rather than most of them. It defaults to `COMPATIBLE`,
and padding makes the `STRICT` behaviour uniform rather than newly broken, but a user who sets it
deliberately still loses the plane undiagnosably from inside Knit.

`…debug.LORA` reports `signing` (does this board's firmware sign for us at all) and the `loraPadded`
counter, because a padded frame and an unpadded one are otherwise indistinguishable anywhere but the air.

## Verified on hardware (2026-09-01)

Heltec V4 / 2.8.0.7239fe8 on a Pixel 9, with a **second board** listening over the air. In two halves:

- the codec pads a real Nearby-room post — `lora pad fanout:chat +46B past the signature cliff`,
  `loraPadded` 0 → 1, no NAK, one packet;
- a 166-byte payload leaves the board **unsigned** — heard on the second board as
  `Lora RX … encrypted len=190` / `Packet RX: 1262ms`, against `len=255` / `1655ms` at 165 B.

That `encrypted len` line is the cleanest signing oracle available and beats the `Packet TX:` duration used
in the ADR 054 bench: `len = payload + 24` unsigned and 66 more signed, so it reads the signature off by
arithmetic rather than by comparing a duration to a model.

Not yet observed: **one packet being both** — the phone's plane went `PASSIVE` under ADR 044's pocket
election (deterministic, lowest publisher key wins) before that could be caught. The halves compose, since
`…debug.LORATX` and the codec path share `link.send` and `signedDataFits` is length-based, not
content-based — but that is inference. Also field-observed: a **60-character** room post does not pad, being
already past the cliff once a real `FrameId`, `NodeId` and 64-byte sig are on it; the paddable band for room
posts is narrower in practice than the JVM probe suggested.
