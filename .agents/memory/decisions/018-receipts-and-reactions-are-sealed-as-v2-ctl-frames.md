---
id: "018"
slug: receipts-and-reactions-are-sealed-as-v2-ctl-frames
title: "Receipts and reactions are sealed as v2 ctl frames; the DM vaccine-purge is retired for the sealed era"
date: 2026-08-15
topics: [crypto, wire, receipts]
---

# ADR 018 — Receipts and reactions are sealed as v2 ctl frames; the DM vaccine-purge is retired for the sealed era

Status: Accepted (2026-08-15; same unreleased v2 train as 016/017 — no new `EncEnvelope.v`, no new
capability bit (`CAP_RATCHET` covers it, the 017 precedent), no DB change)

The last cleartext flooded metadata goes dark: a reaction (which leaked reactor + emoji + target
mesh-wide, to non-members included) and a DM delivery receipt (which leaked the delivery event and
the recipient's activity timing) now ride as `MessageContent.ctl` control frames inside ordinary
v2-sealed CHAT frames — `CTL_RECEIPT = 5` (`ack` = the acked frame id) and `CTL_REACTION = 6`
(`rp = ReactionPayload{messageId, emoji?}`, null emoji = retraction; DM and group forms). The ADR
016 mechanism is the ADR 016 rationale: a new frame type would lose custody on every deployed build
(`isCustodial` is a fixed list), while an unknown ctl is a chain-advancing silent no-op, and on the
wire a sealed receipt/reaction is indistinguishable from chat. A ctl payload must NEVER take the v1
wrap — a pre-ratchet build would decrypt it, strip the unknown field (`ignoreUnknownKeys`), and
persist an empty message bubble; the fallback for an incapable/unsealable target is the legacy
cleartext frame (inbound cleartext stays accepted forever). Broadcast-room receipts and reactions
stay cleartext by design (the room is plaintext; ADR 010's blocked-ack invisibility untouched).

The structural trade, stated as loudly as 016/017's: **a carrier cannot parse what it cannot read,
so the recipient-authenticated carrier-executed vaccine-purge (`ForwardSync.onAck`) does not exist
for sealed receipts — nobody purges, and a delivered DM ages out of custody on the frame-global
24 h TTL uniformly, exactly like group/broadcast custody always has.** Convergence holds because the
rule keys on the receipt's FORM, a property of the frame bytes identical at every node: a cleartext
receipt purges everywhere it always did (old builds included), a sealed one purges nowhere (old
builds custody it as opaque v2 chat; ratchet-era lab builds no-op the unknown ctl). Two composition
rules keep ADR 006 honest: the recipient now custodies its OWN inbound DMs (dropping the carry
gate's `isForMe` exclusion — otherwise every carrier's digest holds a frame the recipient's never
folds, and the mesh re-serves a delivered DM at the recipient forever), and a cleartext ack
self-vaccinates (`onAck` locally after originating — the recipient's own fresh custody row must
follow the same rule every carrier applies, or its digest diverges the other way). Storage cost
accepted: a delivered DM + its sealed receipt ride custody to TTL (two frames where the purge left
zero), bounded by the existing quotas (1000 global / 200 per sender).

The broadcast/group tick keeps its shape (unicast `relay = false` via AckSync, never
flooded/custodied) but seals to a capable author — **once, at owe() time, cached and re-sent
verbatim** (`AckSync.sealTick`): sealing consumes a DM chain key, and re-sealing per retry
(15-min heartbeat × 24 h TTL × 500-entry cap) would burn epochs and starve real DMs out of the
receiver's skipped-key budget. A sealed tick outgrows the ~255 B coordination plane, so it lands
only over a live link — a latency regression, not a reachability loss (fastSend needed radio
proximity anyway), and deliberately NOT downgraded to cleartext when linkless (the form would
become an on-path observable). Blocked-sender posture: ticks to blocked authors still seal/send
(ADR 010, the seed precedent); a blocked member's inbound sealed ctl dies at the chat blocked gate
— diverging from their cleartext receipt (accepted forever, no blocked gate), a version-dependent
tell-free asymmetry; and a blocked member's sealed group reaction still draws a tick from
`ackBlockedRoomChat` (pre-decrypt, we hold no chain for them) — the pre-existing residual class
their undecryptable group chats already exhibit. Scheme doc: `docs/ENCRYPTED_RECEIPTS_REACTIONS.md`;
wire precedent: `docs/WIRE_COMPAT.md` (the third additive `MessageContent` change); context:
`context/e2e-encryption.md`, `context/store-and-forward.md`.
