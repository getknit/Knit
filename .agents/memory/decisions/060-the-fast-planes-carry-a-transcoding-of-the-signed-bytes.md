---
id: "060"
slug: the-fast-planes-carry-a-transcoding-of-the-signed-bytes
title: "The fast planes carry a transcoding of the signed bytes, rebuilt and verified at the receiver"
date: 2026-08-29
topics: [wire, mesh, compaction]
---

# ADR 060 — The fast planes carry a transcoding of the signed bytes, rebuilt and verified at the receiver

**Date:** 2026-08-29 · **Status:** shipped (`mesh/link/FrameTranscoder` + `CborItems`, `FastFrameCodec` tag `0x05`,
`Protocol.CAP_FRAME_TRANSCODE = 0x80`, `mesh/link/FastFramePick`, `mesh/protocol/CanonicalText`)

**The mechanism.** After ADR 059 every *signed* sealed frame still took two packets on the LoRa hop and the
Wi-Fi Aware fast path (`CoordinationPlaneSizeBudgetTest`, `0x03`): the signed v3 ✓✓ tick 283 B, a sealed
reaction 291, a 40-char v3 DM 307, a 100-char one 371, the profile 453 (three parts at the MTU-255 boards'
228-B cap), a 12-ack tick 483. Deflate was exhausted — most of a sealed frame is random bytes — and what was
left was the *encoding*: CBOR text keys (`"recipientId"` alone is 12 B a frame), base64url/base32 ids (23 and
27 B for 16 bytes), hex hashes (65 for 32), a 9-B `sentAt`, the `"chat"` string, v3's empty-but-present
`nonce`/`keys` (13 B), and the payload's byte-string wrapper.

**The decision.** A fourth transport-local re-encoding beside `0x03`/`0x04`, the ADR 030 argument once more:
tag `0x05` carries [`FrameTranscoder`]'s form of `signed` — one-byte integer labels, raw ids and hashes, a 6-B
clock, the type as a small int, the payload inlined as a nested map, `nonce`/`keys` elided when empty — and
the **receiver rebuilds the byte-identical canonical CBOR and verifies the originator's Ed25519 signature over
it**. `signed` still arrives byte-for-byte; it just does not travel that way. Nothing signed, stored, or relayed
changes, so this is not a wire change. Four properties carry it: (1) it is a **generic, path-scoped CBOR
rewriter**, not a typed mirror — a key its table does not know rides as its text key plus the raw value, so a
newer build's additive field costs its name and never a fallback, which matters because `fastFanout` re-fans
frames *other* builds originated; (2) every value transform is **self-describing by CBOR major type with a
passthrough fallback** — the profile frame's `"profile-…"` id, a dirty-tail base64 string, an unknown frame
type all stay exactly what they were; (3) the sender's `transcode` is **self-verifying** — it returns null unless
`rebuild(out)` reproduces its input, so no caller can skip the round-trip and a frame it cannot reproduce keeps
`0x03` (counted `transcodeFallbacks`); (4) a receiver whose rebuild disagrees verifies nothing — the signature
fails over the wrong bytes, never a wrong-content acceptance (`FastPathDrop.TRANSCODE_FAILED`). The one
elision — `EncEnvelope`'s always-present `nonce`/`keys` when empty — applies only in a scope with no unknown
key, and the rebuild of such a scope walks declaration order and restores the two canonical defaults; a
hand-encoded scope in another order fails the self-check and rides `0x03` (pinned). "Smaller wins": the codec
emits whichever of `0x03` and `0x05` is fewer bytes (a long text post can still win on `0x03`'s dictionary
deflate); DEFLATED on `0x05` means raw deflate with **no** dictionary (dictId 0 — the text tokens `DICT_V1` was
built from are gone), and the dictId rules are tag-specific. Fragments (`0x04`) are unchanged; both
reassemblers now admit either whole-frame tag. **Schema 1 is frozen** — labels 1..n per scope in declaration
order, 0 reserved, pinned by six golden transcoded vectors and the literal label map in `FrameTranscoderTest`;
a richer schema is a new tag, never an edit. kotlinx 1.11 exposes no CBOR element model, so `CborItems` is a
~100-line definite-length scanner that refuses every indefinite form (`WireCodec` never emits one).

**Gating.** `Protocol.CAP_FRAME_TRANSCODE = 0x80` — the last capability bit a BLE advert carries, spent here
because the adverts are where a transport-local encoding choice is read: exactly `CAP_FAST_COMPACT`'s posture,
consumed from the SSI-advert copy, never a trust input, every receiver accepting every tag. The Wi-Fi Aware
transport picks per peer through the pure `FastFramePick` (transcoded ⊃ compact ⊃ legacy, each falling back
to the next when it cannot carry the frame). BLE is untouched — it carries raw CBOR over L2CAP with no size
pressure. **LoRa is a flag-day while `LORA_PLANE` is debug-only** (the user's call): a LoRa sighting carries
no capability and the OFFER has no field for one, so every frame the transcoder reproduces leaves as `0x05`
and an older debug build on the channel drops it as `UNKNOWN_TAG`. Before the plane ships to release it
needs a gate — every peer heard on the plane (45-min linger) having advertised the bit through the profile
frame it beacons here, newest-`sentAt`-wins, closed when no one is heard — with three residuals to record:
an unheard far-pocket node on an old build (heard via Meshtastic rebroadcast, its own frames never reaching
us) drops `0x05` silently until one of its frames arrives; an upgraded-but-not-rebeaconed peer keeps us on
`0x03` (safe); a downgraded peer keeping its nodeId is open until its older profile arrives.

**Measured** (2026-08-29, `CoordinationPlaneSizeBudgetTest`, `0x03` → `0x05`): the signed v3 ✓✓ tick 283 →
**221 B — one packet at 255, 231 and 228** (the DM ✓✓ under a packet with custody intact); the unsigned
live-link tick 218 → 157; a sealed v3 reaction 291 → 229 (one at 255 and 231, two at 228); a 40-char v3 DM
307 → 244 (one Wi-Fi Aware message, still two LoRa packets); a 100-char v3 DM 371 → 304 (two — the
structural floor); the profile 453 → 352 (**three parts → two at 228**); the 12-ack v3 tick 483 → 409 (three →
two at 228); a v2 signed tick 316 → 253 (one Wi-Fi Aware message). The roadmap's pre-implementation floors
(≈205 / ≈142) were ~15 B optimistic; every packet-count claim held. Counters: `fastTranscodedSent`,
`loraTranscoded`, `transcodeFallbacks`, `fastDropsByReason.TRANSCODE_FAILED`.

**What did not change.** The wire, the signing input, custody (`signed` + `sig` stored raw and re-served
verbatim), BLE, the fragment format, `DICT_V1`, every golden vector, and the size hints — `LoraSizeHint`'s
budgets, `INLINE_ACK_BYTES` (23) and `DmAckCoalescer.MAX_LORA_TICK_ACKS` (12) are calibrated to `0x03`, which
stays the fallback, and a hint may under-warn but never over-promise.

**Deferred.** The LoRa gate above (a release prerequisite, with the OFFER capability byte as the alternative);
the compact group form as v4 (its own bit, or `0x80` if it ships in the same release — ADR 059 amendment); the
`INLINE_ACK_BYTES` re-tune (23 → 17); `docs/NEXT_WIRE_BREAK.md` item 8 (making this layout the canonical
signed form at the break, which reclaims what a re-encode cannot: the 7-B nonce from the stored form, the text
ids inside sealed payloads, the millisecond clock).
