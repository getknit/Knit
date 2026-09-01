---
id: "022"
slug: the-cleartext-profile-frame-rides-the-spool-plane
title: "The cleartext profile frame rides the spool plane, and its version leaves `sentAt`"
date: 2026-08-19
topics: [spool, profile, wire]
---

# ADR 022 — The cleartext profile frame rides the spool plane, and its version leaves `sentAt`

Status: Accepted (2026-08-19; `ScopeFrames.eligibleFor` admits `FrameType.PROFILE` into both scope forms,
`ProfileContent.version` added, `SettingsStore.profilePublishedAt` added — no DB change, no capability bit)

Field testing the plane found that **everything built on a pairwise DM session fails between peers that
have only ever met over the Internet.** One lab device carried a converged DM scope with a remote peer yet
dropped six of its DMs as `RATCHET_EPOCH_GONE`; another had no DM scope with that peer at all, and so also
sat on 67 undecryptable group frames, because `CTL_GROUP_KEY` seeds ride as v2 ctl DMs and the session they
need could not be formed.

**ADR 020 got one thing wrong, and it was load-bearing.** It argued a ctl beat admitting `type = profile`
into §4.4 partly because "§4.4 needs no change at all", and partly because "group members get it free over
the pairwise ctl DMs that `CTL_GROUP_KEY` seeds already use". The second is circular: those ctl DMs are
exactly what a member without the peer's prekey cannot send. Its own closing line named the failure without
following it through — *prekey rotation bumps the version but deliberately does not seal, since it changes
no presentation field*. So a rotation is invisible to an Internet-only peer, and the 7-day cadence makes
this a certainty rather than an edge case: any such pair eventually cannot re-establish a broken session.

Decision 2 of ADR 020 stands unchanged — the prekey still cannot ride a sealed ctl, because sealing a
session-starter under a session that must already exist is circular. That is precisely why the *cleartext*
frame has to reach the plane. It is safe there: `verifierBundle` resolves a profile's key from the `pubKey`
inside its own payload and `canCarry` re-derives the nodeId from it, so the frame is self-certifying inside
a scope exactly as on the mesh, and it discloses strictly less than the copy already flooded to everyone in
radio range. This is dual-stack, not a replacement: `CTL_PROFILE` still carries presentation updates.

Three decisions worth not relitigating:

1. **The profile version leaves the envelope `sentAt`, into `ProfileContent.version`.** Not cosmetic —
   without it the fix is inert. Custody expiry is frame-global (`sentAt + ttl`, ADR 006) and a profile's
   `sentAt` *was* its edit time, so `ForwardRepository.store` refused any profile older than the 24h TTL as
   dead on arrival. Against a 7-day rotation cadence that left roughly six days in seven with no profile in
   custody at all — nothing for `liveFrames()` to return and nothing for a scope to seal. `sentAt` is now a
   publish stamp `republishProfileIfStale` refreshes every 12h; `version` stays put, so a re-publish is not
   mistaken for an edit and cannot advance a receiver's watermark. This also fixes a bug that predates the
   plane: `seedOwnProfileCustody` was a silent no-op for any profile older than a day, so a radio late
   joiner could not pull one either. Keying custody expiry off local receipt was rejected — ADR 006 requires
   every node to expire the same frame at the same instant.
2. **Presentation and prekey are gated on separate watermarks.** `applySealedProfile` advances `updatedAt`
   from a ctl that deliberately carries no prekey, so one shared watermark let a sealed presentation update
   suppress the cleartext frame carrying the prekey — and a live spool `EVENT` outruns a heal-round pull, so
   the race lands exactly when the prekey matters. `handleProfile` now returns early only when *both* halves
   are stale, and `prekeyProfileAt` (already on the row) is the prekey's watermark. `updatedAt` advances
   monotonically so a prekey-only admission cannot drag presentation backwards.
3. **Digest divergence with older builds is the accepted cost.** Profile blobs fold into the scope digest,
   so a member on an older build quarantines them (§9.3) and reports that scope unconverged forever while
   re-`list()`ing each heal round. Messages still flow. Taken deliberately over modelling profiles as a
   separate out-of-digest object class like attachments (§4.5/§6.5), which would have needed new record
   types and a `knit-spool` change for a plane still only in testers' hands. A second consequence to expect
   in a mixed fleet: an older build reads `sentAt` as the version, so its `updatedAt` becomes a publish
   stamp and it then rejects sealed `CTL_PROFILE` updates.

Scheme: this file plus `docs/SPOOL_PROTOCOL.md` §4.4 (C-4.4-5…7, C-4.4-13) and `docs/WIRE_COMPAT.md` (the
fifth additive `ProfileContent`/`MessageContent` change). No spool record, derivation or §13 vector moved —
a spool never decodes a frame, so the plane cannot tell the difference.
