---
id: "059"
slug: crypto-scheme-v3
title: "Crypto scheme v3: the nonce is derived, the plaintext is compact, and the live-link tick is unsigned"
date: 2026-08-29
topics: [crypto, wire, compaction]
---

# ADR 059 — Crypto scheme v3: the nonce is derived, the plaintext is compact, and the live-link tick is unsigned

A steady sealed `CTL_RECEIPT` tick was 316 B compact — two packets on the LoRa hop (233 B nominal, 222 B at
the lab's MTU-255 ESP32 boards) and two Wi-Fi Aware messages (255 B) — and after ADR 030 there was nothing
left for deflate to do: its `signed` is 285 B of which 173 B are random (ids 74, nonce 12, ek 32, ct 55), a
no-dictionary deflate *expands* it, and `DICT_V1` buys 36 B. Measured 2026-08-29 in
`CoordinationPlaneSizeBudgetTest`. The whole cost of a two-packet tick — ~3.25 s of air, a 3-s pacer gap,
loss p² instead of p — sat on the one frame nobody types.

**The three savings, and the one that was not taken.** Crypto scheme **v3** (`EncEnvelope.v = 3`,
`Protocol.CAP_CRYPTO_V3 = 0x100`) is the v2 DM ratchet with nothing changed in its chain, epochs or
header, and two things removed from the bytes: the 12-byte AES-GCM nonce is *derived* rather than carried
(`RatchetCrypto.messageNonce` — HKDF of the single-use message key with the AAD mixed in, so a restored
database re-sealing under the same chain index still gets a distinct nonce from the fresh frame id), and the
plaintext inside `ct` is the labeled `MessageContentV2` layout — integer keys, raw 16-byte ids, raw hashes —
which takes a tick's plaintext from 39 B to 21 and a twelve-ack batch down by 72. The third saving is the
signature: AckSync's live-link tick for a room or group post (`MeshManager.sealDeliveryTick`, `relay =
false`, sealed with the pairwise ratchet, never flooded, never custodied) now travels **unsigned** toward a
v3 author. Its AEAD is the authenticator: v3 binds the ratchet header into the associated data beside the
v2 header (`id|sender|sentAt|recipient`), so the two header fields the derived key never bound — `flags`
and `init.at` — are covered too, and X3DH binds the initiator's identity key, so a forged init cannot open
either. Tick 316 → ~222 B: one message on Wi-Fi Aware, one packet on nominal LoRa, and one packet at the
ESP32 boards once `TORADIO_OVERHEAD` stopped carrying 6 B of hand-summed slack (it is now measured off
`MeshtasticProto.encodePacket`: 27, cap 228). The one saving **not** taken is the DM ✓✓'s signature:
`sealDmReceipt` originates `relay = true` — flooded and custodied since ADR 018 — and a carrier can verify a
signature but cannot open a session, so that tick stays signed and stays two packets until the transcoder
(roadmap, "frame compaction, round 2") takes it under a packet at ~205 B with custody intact.

**Why the nonce field stays, empty.** The first design omitted it (nullable, 19 B instead of 12). It cannot
be omitted: `InboundPipeline.canCarry` decodes the chat payload before custodying a frame, so an envelope a
pre-v3 build cannot decode is an envelope no fielded build will *carry* — a per-build custody rule, the
digest divergence ADR 006 forbids. v3 sends `nonce` as the empty byte string; every deployed shape decodes it
(`WireSerializationTest.everyOlderDecoderShapeDecodesAV3Envelope`), refuses it at the version gate, and keeps
carrying it — the ADR 035 un-populating posture, not a rule-2 re-type. `keys` stays `[]` for the same reason.

**Why the nonce derives from the message key, with the AAD.** `ratchet_skipped_keys` stores only message
keys, so a nonce derived from the *chain* key could not be recomputed for an out-of-order frame opened
later — the mesh's ordinary case. Deriving from the key being tried also means every rung of the open
ladder and every root candidate derives its own, with no store change.

**What an unsigned frame can and cannot do.** Exactly one shape passes `verifyInbound` without a signature:
`type = chat`, `relay = false`, no group, addressed to us by someone else, from a pinned sender. Everything
after that is judged only after the AEAD opens: the unsigned door is checked *before* the plaintext branch
and *before* the pre-decrypt exists-gate (otherwise anyone who overheard a DM id on the air could make us
seal and flood a receipt for it — a receipt oracle), a frame that opens must be a `CTL_RECEIPT` (a signature
stripped off a captured plain DM and re-injected point-to-point would open too, and must not deliver
through this door — refused before commit, so the chain index is untouched and the signed copy still
lands), and an open failure is counted but **never** fed to the reset heuristic — `onRatchetFailure`'s own
comment justifies `AEAD_FAIL` as a reset trigger *because* the frame was signed, and three forged frames
with distinct ids must not buy an attacker a session reset per pair. A relay=false frame never reaches
custody, the flood, the fan-outs or the spool; `canCarry` still demands a 64-byte signature. Accepted: a
forged unsigned frame costs ~3-5× an Ed25519 verify in pre-AEAD work (X25519 per root candidate, up to 199
skipped-key derivations) with nothing persisted; SeenSet dedup-before-verify is a pre-existing weakness of
every frame type; a peer that downgrades keeps the bit pinned until its profile version climbs back (the
same shape as `CAP_RATCHET`; seeding `profileVersion` from the wall clock is the roadmap fix).

**What did not change.** The group form (`g`) stays v2 — sender keys are symmetric across members, so a
group frame can never be unsigned, and its derived nonce is an all-members-gated change for later. Session
resets (`sealResetDm`) and the group-key ctl DMs stay v2, the most compatible form toward a peer that may
have reinstalled. `canCarry`, the DM ✓✓'s custody model, `PendingInbound`, and every v1/v2 golden vector.
The compact codec is canonical-or-nothing: an id, hash or key whose bytes do not re-encode to the exact
string makes `sealBytes` fall back to v2 rather than mangle it — no shipped build ever minted such an id
for anything acked or quoted, so this is hygiene, not compatibility.

**Codec and planes.** `FastFrameCodec` spends flags bit 4 (`FLAG_UNSIGNED`: the sig field is absent);
bits 5-7 stay reserved and an old receiver drops the frame, which is why the bit is only ever emitted
behind the capability. `LoraMeshTransport`'s ten-minute dedup keyed on signature bytes would have collapsed
every unsigned tick onto the empty key — it keys on the frame id for the unsigned form, exact because
AckSync seals a tick once and re-sends it verbatim. Counters: `dmSealedV3`, `ticksUnsigned`,
`DropReason.UNSIGNED_REFUSED`.

**Deferred.** Round 2 — the schema-aware transcoder that re-encodes the *signed* bytes (int keys, raw ids)
and carries the signed DM ✓✓ in one packet; `DICT_V2` (`DICT_V1` now holds a dead `nonce` token; frozen,
harmless); the profile-version seeding above; re-tuning `INLINE_ACK_BYTES` (23 B stays a conservative
reservation for a 17-B compact ack).

*Amendment (2026-08-29, lab).* Verifying this on the Pixel 9's board showed `loraNak = 10` for nine sends,
all inside the first minute after session-up, and the counter carried no reason. Counting NAKs per
`Routing.error_reason` (`loraNakByReason`) and reinstalling reproduced it at once: six `TOO_LARGE`. Raw
`…debug.LORATX` sends pinned the firmware's limit — a 231-byte `PRIVATE_APP` payload queues, 232 and 233 come
back `TOO_LARGE` — so the router transmits at most a 237-byte `Data`, and the proto's `DATA_PAYLOAD_LEN = 233`
(which assumes a one-byte portnum) overshoots Knit's two-byte portnum by 2. Two things followed. `MeshtasticProto.MAX_PAYLOAD`
is now derived, 237 less the measured 6 bytes of framing = 231, and every "233" downstream (the 687-B
ceiling, the size hint's arithmetic) moved with it. And the NAKs were never from the negotiated cap at all —
they were the frames the composite fans out while the board is still *connecting*, chunked at `maxPayload`'s
initial value, which was the protocol maximum; `Ready` then drained them straight into `TOO_LARGE`. The
initial cap is now the MTU-255 floor (`PRE_READY_PAYLOAD` = 228) and `Ready` evicts anything a smaller
negotiated cap could never write. Pre-existing on every build since ADR 038, and invisible because the
counter had no reason — which is the lesson, more than the two bytes.

*Amendment (2026-08-29, pre-release review).* Asked whether anything else should ride the v3 number before it
ships, the review found nothing scheme-bound: the AAD (`id|sender|sentAt|recipient` ‖ the labeled header
binding, so a v2↔v3 relabel fails the AEAD), the nonce derivation, X3DH (identity on both sides, weekly SPK
rotation with retention), the signing domains (SPK label, card label, scope `sig‖signed`, wire = a CBOR map —
mutually unparseable) and the attachment seal all stand. Rejected on the mesh's terms: post-quantum KEM
material (ML-KEM-768: 1184-B keys, 1088-B ciphertexts — the profile alone becomes ~8 LoRa packets), a
key-committing AEAD (+32 B, and the header selects the key, so there is no attacker-steered multi-key trial),
header encryption (relays and custody need the pair in the clear anyway), length padding and tag truncation.
Two things were pinned while the number was still free. **v3 is the DM form by executable rule**
(`InboundPipelineTest.aV3GroupAddressedEnvelopeIsABadHeaderNeverAGroupFrame`): a group-addressed v3
envelope is `RATCHET_BAD_HEADER` on every v3 build — refused before the group engine, the key-request
heuristic and the reset heuristic — so a compact group form cannot reuse the number; it takes **v4**,
roster-gated on every member's pinned capability, and rides round 2's capability bit if it ships in the same
release (else a bit of its own). And `MessageContentV2` **reserves labels 12 and 13**: `pad`, a length-hiding
byte string the reader discards, and `gk`, the group-key payload with raw 32-B seeds (44 B each as base64
today, which is why the group-key ctl DMs still seal v2). Both are additive under `ignoreUnknownKeys` — a
new *label* is additive, a new *form* is a new version. Noted as policy rather than scheme, for the roadmap:
marking a frame seen only after it verifies (a forged frame with a real id shadows the genuine one for the
SeenSet window — every type, pre-existing), and the epoch cadence (`MAX_EPOCH_AGE_MS` = 24 h bounds
post-compromise recovery; `ek` already rides every frame, so a shorter cadence costs nothing on the wire).
