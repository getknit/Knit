---
id: "016"
slug: dm-forward-secrecy-is-an-epoch-rekey-ratchet
title: "DM forward secrecy is an epoch-rekey ratchet (not Double Ratchet, not libsignal)"
date: 2026-08-14
topics: [crypto, pfs, dm]
---

# ADR 016 — DM forward secrecy is an epoch-rekey ratchet (not Double Ratchet, not libsignal)

Status: Accepted (2026-08-14; DB v2, crypto scheme `EncEnvelope.v = 2`)

Three locked choices: an **epoch-rekey ratchet** (PFS at epoch granularity — a far smaller state
machine than Signal's Double Ratchet, bought with a bounded compromise window instead of per-message
FS); **X3DH-style bootstrap off a signed prekey published in `ProfileContent`** (offline-first first
DM preserved; nodeId untouched — the prekey deliberately does NOT join `PublicKeyBundle`, whose hash
IS the nodeId); **session state in Room** (ratchet advance + message row commit in one transaction).
libsignal was rejected up front: its prebuilt Rust `.so`s break ADR 014's reproducible-build contract
and its server-shaped prekey model doesn't fit a mesh. Tink's public subtle API (X25519/HKDF) plus the
existing AES-GCM helper is the whole primitive set — no new dependency.

Two deviations from the obvious design, both forced by custody semantics: **no cumulative root chain**
(custody quota eviction leaves permanent mid-chain holes; an in-order root ratchet would wedge a
session forever — epochs derive independently off a static session root, and healing is round-trip-
granular via fresh DH), and **session reset rides as an ordinary v2 chat frame with a `MessageContent.ctl`
marker** (a new frame type would not be custodied by v1 relays — `isCustodial` is a fixed list — so
resets would lose delay tolerance exactly when they matter). The **pre-decrypt exists-gate** in
`decryptAndDeliver` is load-bearing: custody re-serves the same ciphertext routinely, and deleting used
message keys (the whole point) is only safe because a persisted frame never reaches decrypt again.
Own send-epoch numbering is monotone across root replacements so `(peer, se)` stays unambiguous; only
a device wipe restarts it, and a wipe also discards the state that would collide. Scheme spec + threat
model: `docs/FORWARD_SECRECY_RATCHET.md`; wire precedent: `docs/WIRE_COMPAT.md` (the additive
crypto-scheme bump); context: `context/e2e-encryption.md`.
