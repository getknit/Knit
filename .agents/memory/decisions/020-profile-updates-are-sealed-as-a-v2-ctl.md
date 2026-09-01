---
id: "020"
slug: profile-updates-are-sealed-as-a-v2-ctl
title: "Profile updates are sealed as a v2 ctl; the cleartext profile frame keeps first contact"
date: 2026-08-16
topics: [crypto, wire, profile]
---

# ADR 020 — Profile updates are sealed as a v2 ctl; the cleartext profile frame keeps first contact

Status: Accepted (2026-08-16; `MessageContent.ctl = CTL_PROFILE (8)`, `MessageContent.pr`,
`ProfilePayload` — no `EncEnvelope.v` bump, no capability bit, no DB change)

Field testing after M5 found the honest hole the spool spec had booked and never argued: attachments
crossed the Internet plane but **profile updates did not**, so a peer reachable only over the Internet
kept the name, status and avatar they had at last radio contact. `docs/SPOOL_PROTOCOL.md` §4.4 excluded
profiles in one asserted sentence and pointed at "§11" for the alternative, which §11 never registered.

**A `profile` frame is doing two unrelated jobs and only one of them can ever be encrypted.** First
contact is self-certifying — `verifyInbound` authenticates a profile against the `pubKey` *inside its own
payload*, because the nodeId IS that key bundle's hash — so encrypting it is a contradiction: the
recipient has no key yet. An update to an *established* contact has no such constraint; a v2 session
already exists, which is the same precondition a scope has. So the second job moves to a sealed ctl and
the cleartext frame keeps the first, permanently. The two ship together; this is dual-stack, not
replacement, and a pre-ratchet peer still sees only the cleartext form.

Choosing a ctl over admitting `type = profile` into the scope frame-set rule pays three ways: **§4.4
needs no change at all** (a v2 chat frame between the pair already passes `eligibleForDm`, so the problem
dissolves into carriage that shipped at M3), §10's "content confidentiality is entirely the inner
schemes'" survives intact, and group members get it free over the pairwise ctl DMs that `CTL_GROUP_KEY`
seeds already use. It is the ADR 016/018/019 custody argument a fourth time — `isCustodial` is a fixed
list on deployed builds, so a new frame type would flood and never be carried.

Four decisions worth not relitigating:

1. **The payload carries the sender's profile `version`, not the carrying frame's `sentAt`.** Both
   propagation paths must order against one number or a name silently reverts, and `sentAt` is the wrong
   one: a re-sent ctl is stamped later than a genuinely newer cleartext profile and would gate it out.
   The cleartext frame already puts the profile version in its envelope `sentAt`
   (`maxOf(clock(), previous + 1)`, so wall-clock-scaled and monotonic), and `PeerEntity.updatedAt`
   already stores it — so the sealed path reuses `InboundPipeline`'s existing
   `sentAt < existing.updatedAt` gate rather than inventing a second convention. A payload with
   `version <= 0` is ignored outright: an unorderable update must not be applied at all.
2. **The sealed payload is narrower than `ProfileContent` on purpose.** No `pubKey` (an identity re-pin
   is a TOFU event that must ride the self-certifying cleartext frame — the session proves *who sent
   this*, not what their key is) and no `prekey` (its job is to *start* a session, so sealing it under
   one that must already exist is circular). Presentation fields only. The ingest path likewise never
   touches the pin, the device tag, or the advertised capabilities, and never inserts a peer row — a
   missing row is a no-op, so this path cannot mint a peer that skipped the key pin.
3. **The avatar hash is repeated in cleartext `ChatContent.attachmentHash` on the carrying frame.** The
   DB v19 precedent reapplied verbatim — populating an existing field in a new case, its meaning
   unchanged. It is what lets a blind carrier custody the avatar bytes, and it is why the Internet
   plane's attachment pass needed **no** avatar special case at all: `ScopeAttachments.refFor` already
   reads that field.
4. **Group photos needed no wire change whatsoever.** `groupupdate` was already scope-eligible (§4.4), so
   the group's name, roster and `photoHash` have crossed since M4 — only the bytes were missing.
   `refFor` gained one branch reading `GroupInfo.photoHash`. Two corrections to the record fall out of
   this: a scope has always carried cleartext-payload frames (`groupupdate`, `groupleave`), so "a scope
   holds no cleartext payloads" was never true; and sealed avatars need no per-avatar key and no
   `peers.avatarKey` column, because the §4.5 chunk seal already encrypts under the scope key —
   `AttachmentCrypto`'s inner key exists to blind *mesh relays*, and a spool is blinded already.

Fan-out targets every peer with a confirmed v2 session rather than "accepted conversations": a sealed
profile discloses strictly less than the cleartext frame already floods to everyone, so narrowing it
would cost propagation and buy no privacy. It is deduped per `(peer, version)` rather than floored on a
timer — a profile edit is rare and user-visible, so a time floor would suppress a real second edit, and
one send per version suffices because custody and the Internet plane both carry it to an offline peer.
Prekey rotation bumps the version but deliberately does **not** seal, since it changes no presentation
field and would burn chain keys.

Stated rather than overclaimed: this continues ADR 018's "the last cleartext flooded metadata goes dark"
but does not finish it. Profile *updates* go dark for ratchet-capable peers; the initial cleartext
disclosure at first contact is structural and stays.

Scheme: this file plus `docs/SPOOL_PROTOCOL.md` §4.4; wire precedent: `docs/WIRE_COMPAT.md` (the fourth
additive `MessageContent` change, and the second use of the DB v19 field-reuse rule).
