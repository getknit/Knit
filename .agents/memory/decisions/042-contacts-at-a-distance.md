---
id: "042"
slug: contacts-at-a-distance
title: "Contacts at a distance: a signed contact card, the `CTL_PROFILE` intro, and an identity-derived pair scope"
date: 2026-08-25
topics: [contacts, crypto, spool]
---

# ADR 042 — Contacts at a distance: a signed contact card, the `CTL_PROFILE` intro, and an identity-derived pair scope

Status: Accepted (2026-08-25; `mesh/crypto/ContactCard`, `contacts/`, `mesh/IntroSync`,
`ScopeCrypto.pairSecret`/`pairScopeId`, `ScopeRegistry.pairs`, `ui/addcontact/` — no mesh-wire change, no DB
migration, `knit-spool` untouched)

Two people far apart — reachable only over the Internet plane, or across a LoRa hop — could not become
contacts: the only message-less pin was the QR scan (co-presence), a DM scope needs a *confirmed* session,
a session needs the peer's prekey, and the prekey travels only on a `profile` frame inside a scope the
pair already shares. `docs/CONTACT_CARD.md` is the scheme; `docs/SPOOL_PROTOCOL.md` §3.5 the rendezvous.

Three pieces, and what each deliberately is *not*:

1. **The contact card is the QR payload as a signed link, not a token.** `{v, id, pk(64 B), name?, sp?,
   iat}` under an Ed25519 signature over the opaque body (`"knit/card/v1" ‖ body`, the `WireEnvelope`
   discipline — never re-encoded to verify). Self-certifying like a profile. Shared via the share sheet or
   the clipboard as `https://getknit.app/c#…` (the fragment never reaches the server) and `knit://c/…`;
   the legacy `knit-id:v1` QR string parses too, and the QR composer keeps emitting it so older scanners
   are not broken. **Import pins + accepts but never verifies** (Briar's posture): the channel is
   unauthenticated and the name attacker-chosen, so the safety number stays the verification. Relay hints
   (`sp`) are displayed, never applied — adding a relay hands it every scope id and IP, a phishing vector
   for a hostile card. Tokened `?k=` URLs never leave the minter. A mutual exchange is the product: each
   person shares theirs and imports the other's; the OOB channel is two-way already.
2. **The handshake is the existing sealed `CTL_PROFILE` DM (ADR 020), not a new ctl.** `sendProfileDm`
   to a peer with no session makes `ratchet.sealDm` run the X3DH initiation off the card-pinned prekey;
   the init rides every copy until confirmed; every deployed build reads the frame; a stale or version-0
   payload is the receiver's ordinary no-op. Accept and verify never enter the mechanism — ADR 009/032
   already made the scope follow the session, not acceptance, and the session confirms with or without a
   request UI. `IntroSync` (pure, `KeyExchange`-shaped) owns the *when*: send as soon as the peer's pinned
   profile carries `CAP_RATCHET` + a prekey from any plane; re-send every 20 h while unconfirmed (under the
   24 h custody TTL); answer a frame whose header still carries the init — proof its sender is
   unconfirmed — once per hour, which also cures the pre-existing wedge where a wiped initiator stayed
   unconfirmed until the responder happened to edit its profile (`broadcastSealedProfile` dedups per
   version). State is two settings-store sets (ADR 028/037's posture for ids-not-rows); ≤ 8 pending.
3. **The rendezvous is a pair scope derived from the two identity keys, not a random invite token.**
   `pairSecret = X25519(IK_self, IK_peer)` (the `hpkePub` half of each bundle — a static-static agreement
   used nowhere else; X3DH has no identity-identity term, HPKE pairs the identity key with an ephemeral)
   → `HKDF(…, "knit/scope/v1/pair/id" ‖ dmContext)` and the shared seal label. Computable by exactly the
   two parties; a spool, a node-id holder or a card holder cannot. It is an ordinary DM-form `Scope`
   (`peerId` set), so `ScopeFrames.eligibleForDm`, the push half, the attachment pass and the relay
   indicator need no change, and — because a party cannot derive it before pinning the peer — every
   pulled frame passes `canCarry`. Subscribed only while the intro is pending plus a 48 h **grace** after
   our own confirmation (the responder's answer must still reach a peer that holds no DM scope yet).

**Why not the invite-token design the brief started from.** A token-derived scope any link holder can
compute has four costs the skeptic review made concrete: the owner's *whole* DM set would seal into it
under an owner-endpoint frame rule (any link holder reads the owner's correspondent graph); a chat blob
from a not-yet-pinned requester is quarantined forever by `ScopeSync.accept` (`canCarry` fails on the
unpinned sender, the `accepted` slot is never released, `processed` skips it) and needs a defer-not-
quarantine rewrite of a delicate path; token state, expiry-in-flight deadlocks and reinstall loss; and a
labelling leak (link ↔ scope ↔ connection ↔ every other scope). The pair scope has none of them. What it
costs instead, recorded in §10.3: the "identity file only → no scope key" claim narrows to *conversation*
scopes — a stolen identity file plus the peer's public bundle yields this one scope's id and outer seal,
i.e. the routing metadata of bootstrap-era frames while subscribed, never content, never a DM or group
scope — and the id is stable per pair (bounded by the subscription window).

**What the mesh sees.** Nothing new: a `CTL_PROFILE` is wire-indistinguishable from any sealed DM, it
floods, custodies and rides LoRa's DM-form path (ADR 039); a LoRa listen-only peer is reached once its
beacon pins the profile. `GoldenVectorTest`, `SpoolRecordsTest`, `KnitDatabaseMigrationTest` are untouched;
`ScopeVectorTest` gained four appended rows; `ContactCardTest` pins the card's golden bytes.

**Deferred, with reasons** (roadmap): the one-sided invite (a *profile-only* token scope + a contact-request
inbox — safe only with per-token caps, revoke, expiry, and the observability caveat); a prekey in the card
gated on `iat < 7 d` (lets the importer seal at once and reach a LoRa listen-only peer; a stale one wedges
silently at `EPOCH_GONE`); node-id-only import over the radios via `KeyExchange.want`; session recovery
over the pair scope for existing contacts (no `unsub` record, `maxScopes` pressure). Out of repo:
`getknit.app/.well-known/assetlinks.json` with **both** signing certificates and a `/c` landing page
building the `knit://` link client-side — until then Android 12+ opens the https link in the browser.
