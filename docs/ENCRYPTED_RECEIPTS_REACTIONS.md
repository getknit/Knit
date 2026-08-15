# Encrypted receipts and reactions — sealed ctl frames (crypto scheme v2 addition)

Status: **implemented** · plan approved 2026-08-15 · ADR 018. Ships in the same unreleased v2 train
as the DM ratchet (`docs/FORWARD_SECRECY_RATCHET.md`, ADR 016) and the group sender-key form
(`docs/GROUP_FORWARD_SECRECY.md`, ADR 017): no new `EncEnvelope.v`, no new capability bit
(`CAP_RATCHET` covers all v2 forms), no DB change. This document is the normative spec;
`InboundPipeline` (ctl dispatch, `acknowledge`), `MeshManager` (`sealDeliveryTick`, `sealReaction`)
and `AckSync` are the reference implementation, `InboundPipelineTest`/`MeshManagerTest`/`AckSyncTest`
the executable anchors.

## 1. Why, and why this shape

Receipts and reactions were the last flooded frames walking the mesh cleartext (signed, unencrypted):

- `ReceiptContent { ackId }` — a DM receipt is broadcast-shaped on the wire (`recipientId == null`),
  floods mesh-wide, and every carrier parses the cleartext `ackId` to vaccine-purge custody. It
  leaks the delivery event and the recipient's activity timing to any observer, forever.
- `ReactionContent { messageId, emoji }` — flooded mesh-wide for **every** context, DMs and private
  groups included: non-members see who reacted with what to which message. The worst leak of the pair.

This is prereq 3 of the internet-relay plane (its relays hold frames at rest; shipping that layer
while the same frames walk the mesh naked is incoherent) and the roadmap "E2E hardening" item.

**Mechanism (the ADR 016/017 precedent):** both ride as `MessageContent.ctl` control frames inside
ordinary v2-sealed CHAT frames. A new frame type would lose custody on every deployed build
(`isCustodial` is a fixed list in `Wire.kt`); an unknown ctl value is a chain-advancing silent no-op
(pinned by test), which is exactly what makes new ctl values additive. On the wire a sealed receipt
or reaction is indistinguishable from conversation.

## 2. Wire form (additive; see docs/WIRE_COMPAT.md)

```
CTL_RECEIPT  = 5   MessageContent.ack: String?          // the acked frame id
CTL_REACTION = 6   MessageContent.rp: ReactionPayload?  // { messageId, emoji? }
ReactionPayload { messageId: String, emoji: String? }   // emoji null = retraction
```

`MessageContent.VERSION` stays 1 (nullable additive fields, rule 1). `ReactionPayload` is
deliberately field-compatible with the cleartext `ReactionContent` (same names, same CBOR — one
codec for a port; golden vectors pin both forms). The DM form carries `CTL_RECEIPT`/`CTL_REACTION`;
the group form carries `CTL_REACTION` only (ticks are DM-addressed). A ctl payload is **never
v1-wrapped**: a pre-ratchet build would decrypt a v1 ctl, strip the unknown field
(`ignoreUnknownKeys`), and persist an empty message bubble — senders call `sealDm`/`sealGroup`
directly and fall back to the **legacy cleartext frame**, never to v1 (pinned by test).

Old builds: a sealed receipt/reaction is an ordinary v2 CHAT frame — `UNKNOWN_ENVELOPE_VERSION`
drop-locally-still-relay, custodied opaquely (`canCarry`'s `enc != null` holds). Ratchet-era builds
without these codes: decrypt, unknown ctl, silent no-op, chain advanced. Inbound **cleartext**
receipt/reaction frames stay accepted forever (`handleReceipt`/`handleReaction` untouched).

## 3. Delivery semantics

Control-frame contract unchanged: never persisted as a message, never notified, never acked — a
sealed receipt can't draw a receipt-for-a-receipt, and the exists-gate can't re-ack a ctl id.
Row effects commit **inside the ctl transaction** (the ratchet/chain advance and the tick/reaction
land atomically; a crash re-processes cleanly on re-serve):

- `CTL_RECEIPT` → `markReceived(ack)` under the cleartext path's forged-ack guard —
  `recipientOf(ack) == null || == sender`. The null arm is load-bearing: a group/broadcast message
  has no `recipientId`, and that IS the sealed group tick.
- `CTL_REACTION` → `ReactionRepository.apply(messageId, sender, emoji, frame.sentAt)` — the same
  table and the same LWW clock as the cleartext path, so mixed-form retract/replace races converge
  regardless of which form each emit rode. Orphan-permissive (target may not have arrived; the 24 h
  reaper bounds junk). Group-form sender authenticity is the chain itself (an adopted seed implies
  roster membership at adoption); a departed member can still seal reactions under the draining
  chain for ≤48 h — the same window as their in-flight chats, accepted.

## 4. The vaccine-purge retirement (the structural trade)

Stated as loudly as ADR 016's "no cumulative root chain" and ADR 017's availability inversion:
**a carrier cannot parse what it cannot read, so sealed receipts never vaccine-purge. Nobody purges
— the delivered DM and its sealed receipt age out of custody on the frame-global 24 h TTL uniformly
on every node, exactly like group/broadcast custody always has.**

Convergence (ADR 006) holds because the custody rule keys on the receipt's **form** — a property of
the frame bytes, identical at every observer:

| Receipt form | Old build | Ratchet-era lab build | This build |
|---|---|---|---|
| cleartext | parses, purges + tombstones | same | same (path untouched) |
| sealed | opaque v2 chat: relays + custodies, no purge | decrypts, unknown ctl no-op, no purge | `markReceived` only, no purge |

Two composition rules keep the populations digest-convergent:

1. **The recipient custodies its own inbound DMs** (the carry gate's `isForMe` exclusion is gone).
   Without this, every carrier's digest holds a delivered frame the recipient's digest never folds —
   endless re-offers, and the exists-gate would re-ack each one (~a fresh sealed receipt per SeenSet
   lapse, per DM, for 24 h). With it, digests match and a re-serve means genuine divergence (e.g.
   quota eviction), self-healed by the re-custody that precedes dispatch.
2. **A cleartext ack self-vaccinates**: after originating the legacy receipt, the recipient runs
   `ForwardSync.onAck` on itself — its own fresh custody row follows the identical rule every
   carrier applies to that receipt, and the ack tombstone refuses re-plants.

Storage cost, accepted: a delivered DM plus its sealed receipt ride custody to TTL (two frames where
the purge left zero), bounded by the existing quotas (1000 global / 200 per sender / per-group 200).

## 5. Send-side forms (chosen by the sender of the receipt/reaction)

| Context | Condition | Receipt | Reaction |
|---|---|---|---|
| DM, author/peer capable | pinned bundle + `CAP_RATCHET` (+ prekey/session for the seal) | sealed ctl DM, `relay = true`, flooded + custodied, `sentAt` stamped (custody derives expiry from it) | sealed ctl DM, `relay = true` |
| DM, incapable / seal failed | — | cleartext receipt (still purges everywhere, incl. self-vaccinate) | cleartext reaction |
| Group message delivered | author capable | sealed ctl DM via AckSync: `relay = false`, sealed **once** at `owe()`, retries re-send verbatim, **live-link delivery only** | — |
| Group message delivered | author incapable | cleartext tick (fresh id per retry, coordination-plane capable) | — |
| Group reaction | every member ratchet-eligible | — | sealed group form via `sealGroup` (all-or-nothing; may mint + distribute a seed, like any group send) |
| Group reaction | any member ineligible | — | cleartext reaction |
| Broadcast room | always | cleartext tick (`ackBlockedRoomChat` unchanged, ADR 010) | cleartext reaction |

The sealed tick's two deliberate constraints: **seal-once-resend-verbatim** (sealing consumes a DM
chain key; per-retry re-sealing at the 15-min heartbeat would burn epochs and starve real DMs out of
the receiver's ≤200/epoch skipped-key budget — a duplicate is router-deduped inside the SeenSet
window and a benign `RATCHET_DUPLICATE` beyond), and **no cleartext downgrade when linkless** (a
sealed tick outgrows the ~255 B coordination plane, so `fastSend` no-ops and it waits for a live
link — form must not become an on-path observable of link state; the author needed radio proximity
for fastSend anyway, so this is latency, not reachability).

## 6. Blocked-sender posture (ADR 010)

Outbound: ticks and seeds to blocked authors still seal and send — withholding would reveal the
block. Inbound: a blocked peer's sealed ctl dies at the chat blocked gate, which diverges from their
cleartext receipt (accepted forever, no blocked gate) — a version-dependent asymmetry with no
observable tell (nothing is emitted either way; only the blocker's own tick display differs).
Residual, pre-existing class: a blocked member's sealed group *reaction* still draws a delivery tick
(`ackBlockedRoomChat` runs pre-decrypt; we hold no chain for blocked members, their seed DMs die at
the gate) — exactly as their undecryptable group chats already do since the group form shipped.

## 7. Security claim

- A mesh observer no longer learns which DM was delivered when, the recipient's ack timing, or any
  reaction's reactor/emoji/target in DM and group contexts — all of it is chat-shaped v2 ciphertext.
  Traffic analysis still sees a recipient-originated v2 chat frame shortly after a DM's delivery
  (timing correlation is out of scope, as in v2's §9).
- Broadcast-room receipts/reactions stay cleartext by design (the room itself is plaintext).
- Forward secrecy of the sealed forms is the carrying session's (v2 epoch granularity / sender-key
  epoch granularity). Nothing here adds key material or retention beyond the carrying scheme's.
- The v1-fallback residual (cleartext receipts/reactions toward incapable peers/groups) shrinks as
  capability floods; `receiptsSealedFallback`/`reactionsSealedFallback` count it (Diagnostics).

## 8. Constants

| Constant | Value | Tied to |
|---|---|---|
| `CTL_RECEIPT` / `CTL_REACTION` | 5 / 6 | `MessageContent.ctl` registry (append-only) |
| sealed receipt custody TTL | 24 h via stamped `sentAt` | frame-global custody expiry (ADR 006; the e11aa89 lesson) |
| tick seal budget | 1 chain key per owed tick | AckSync seal-once cache; ≤500 owed entries / 24 h |
| ack tombstone (cleartext era) | 24 h | unchanged `ForwardSync` |
