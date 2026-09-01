---
id: "017"
slug: group-forward-secrecy-is-a-sender-key-ratchet-over-the-pairwise-dm-sessions
title: "Group forward secrecy is a sender-key ratchet over the pairwise DM sessions (not pairwise fan-out, not MLS-lite)"
date: 2026-08-14
topics: [crypto, pfs, groups]
---

# ADR 017 — Group forward secrecy is a sender-key ratchet over the pairwise DM sessions (not pairwise fan-out, not MLS-lite)

Status: Accepted (2026-08-14; folded into the unreleased v2 bump — DB v2, `EncEnvelope.v = 2` group
form (`g` header, split on addressing), `CAP_RATCHET` covering both forms — released version numbers
are append-only, unreleased ones are still editable)

Each member mints a random per-group epoch seed driving a forward-only chain
(`GroupRatchetCrypto.deriveEpoch` binds groupId + senderId + epoch); the seed travels pairwise as a
`MessageContent.ctl = CTL_GROUP_KEY` DM sealed under the v2 ratchet — never v1, which would void the
epoch against one harvested static-key DM. No DH, no sessions, no cross-member coordination: that is
the property the mesh demands (no ordering, permanent custody holes), and it is why the alternatives
lost — MLS-lite's shared epoch needs in-order commits (the ADR 016 root-chain wedge, times eight
parties), and pairwise fan-out either breaks id-keyed dedup/receipts (N frame ids) or entangles every
group message with N DM session lifecycles while still dying on session replacement. A ratcheted group frame
carries only `GroupRatchetHeader {se, n}` (~10 B vs v1's ~500 B of wraps at the 8-member cap).

The structural trade, stated as loudly as 016's "no cumulative root chain": **the DM form's key
material rides on every frame; sender-key inverts that** — a group frame is unreadable until a
separate DM with its own
custody fate delivers the seed. Availability is bought back with the persistent seed outbox
(`group_key_sends` + `CTL_GROUP_KEY_ACK`), proactive re-sends (profile arrival, neighbor join,
session reset — the only wipe-side seed plane, since ctl frames are never persisted), and the
age-gated, floored `CTL_GROUP_KEY_REQ` loop (which never advances an epoch — advance-on-request is a
rekey-fan-out amplifier). Custody accelerates seeds; the outbox is the source of truth. Wipe recovery
is mint-stamped (recv rows keyed by `(epoch, mintedAt)`, old era drains 48 h — the prevRoot pattern,
no era on the wire). Leave-rekey is atomic with the roster shrink (`GroupRepository.recordDeparture`
deletes the send chains in-transaction) and **eventual**, bounded by the signed `groupleave` frame's
convergence — never instantaneous revocation. Eligibility is all-or-nothing per message (any
non-capable member demotes that message, not the group, to v1); blocked members still receive seeds
(ADR 010 — withholding would reveal the block). Prerequisite shipped first: the roster-integrity pin
(`vetRoster` — the founding set only ever comes from a roster whose id IS its hash; membership
shrinks only via signed leaves), without which an insider could smuggle a seed recipient. One shared
ratchet `Mutex` serves both facades (seed adoption runs inside a DM commit; two locks would nest).
Scheme spec + threat model: `docs/GROUP_FORWARD_SECRECY.md`; wire precedent: `docs/WIRE_COMPAT.md`
(the second additive crypto-scheme bump); context: `context/e2e-encryption.md`.
