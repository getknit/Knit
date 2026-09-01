---
id: "030"
slug: coordination-plane-compaction-is-transport-local
title: "Coordination-plane compaction is transport-local: compact framing + preset-dict deflate + ≤3-part fragmentation, capability-gated per peer"
date: 2026-08-21
topics: [wire, mesh, compaction]
---

# ADR 030 — Coordination-plane compaction is transport-local: compact framing + preset-dict deflate + ≤3-part fragmentation, capability-gated per peer

**Date:** 2026-08-21 · **Status:** shipped

The Wi-Fi Aware fast path framed every message as `[0x01][CBOR WireEnvelope]` under a ~255 B/message
radio cap, which silently excluded every v2 sealed frame: measured legacy sizes (pinned executable in
`CoordinationPlaneSizeBudgetTest`) are 374 B for a steady-state sealed receipt, 436 B with the X3DH
init attached, 388 B sealed reaction, 376 B for a 40-char sealed DM, 554 B for a full profile — so
AckSync's sealed ticks and sealed reactions only ever landed over a live link, and full profiles never
fast-fanned at all.

The fix is three transport-local re-encodings in `mesh/link/FastFrameCodec` (+ `FragReassembler`),
deliberately **not** a wire change: only the outer envelope — whose ttl/hops/relay are unsigned mutable
routing metadata every relayer already rewrites — is re-framed, and `sig`/`signed` pass through
byte-exact, so the frame signature verifies unchanged (WIRE_COMPAT rule 4 holds by construction).
Tag `0x03` = 3-B header + raw sig + `signed`; deflate (`java.util.zip`, raw/nowrap, preset dictionary
`DICT_V1`, stored-flag fallback so expansion is impossible) runs over `signed` only — the 64-B sig
stays outside the stream so its randomness can't poison the Huffman table; tag `0x04` fragments a
compact frame into ≤3 parts reassembled per (discovery session, peer handle, fragId) in a bounded
(8-entry, 5 s, lazily-swept) store. Emission is gated per peer on the new `Protocol.CAP_FAST_COMPACT`
(0x20) read from the SSI-advert copy in `reachablePeers` — a cue-only peer reads caps 0 and keeps the
legacy `0x01` framing forever, so mixed fleets interoperate with **no `SERVICE_NAME` bump** (the
deliberate counter-example to the "cue format change = hard cut" rule, recorded in ARCHITECTURE §3.2).
The tag registry (0x01 forever, 0x02 burned, 0x03/0x04) is append-only like capability bits.

Measured outcome: cleartext metadata gains ~25% headroom (receipt 214→171 B, typing-group 229→154 B);
sealed ctl frames land at **2 fragments** (steady receipt 374→316 B compact) — deflate's ceiling is
real, ~99 B of a sealed frame is incompressible crypto (ct/nonce/ek), so single-message sealed ticks
were never on the table and the plan's honest expectation held. Frag loss² is acceptable because the
plane is best-effort by contract (flood/custody backstops floodable frames; AckSync's owed-entry retry
loop stays the reliability mechanism for relay=false ticks — its no-cleartext-downgrade rule is
untouched). `DICT_V1` is frozen under a SHA-256 golden (`FastFrameCodecTest.dictV1IsFrozen`): a dict
edit would make shipped receivers inflate garbage that dies misattributed at signature verify, so
tuning mints `DICT_V2` under the header's dictId field instead. Deliberately NOT done here:
`shouldFastFanout` still excludes DM/group chat frames (policy unchanged — relaxing it to a size probe
is a one-line follow-up now that the transport size-gates per encoding), and the ~8-deep aware tx
queue stays unhandled (parts go out consecutively per peer so overflow loses whole frames, not
part 2 of everyone; `nanMsgSendsFailed` is the field signal). New counters
(`fastCompactSent`/`fastLegacySent`/`fastFragSent`/`fastReassembled`/`fastTooBig`/`fastDropsByReason`,
incl. the previously-invisible unknown-tag drop) ride `…debug.STATE`. No new dependency; lockfile
untouched.
