---
id: "021"
slug: attachment-uploads-are-deferred-while-the-radios-still-carry-them
title: "Attachment uploads are deferred while the radios still carry them; the frame plane stays unconditional"
date: 2026-08-17
topics: [spool, attachments]
---

# ADR 021 — Attachment uploads are deferred while the radios still carry them; the frame plane stays unconditional

Status: Accepted (2026-08-17; `AttachmentDeferPolicy`, `ScopeSync.deferAttachment`,
`MessageDao.attachmentAcked` — no wire change, no DB migration, no capability bit, no spec vector change)

The Internet plane uploads every scope-eligible object as soon as it enters custody, whether or not the
radios already delivered it. For frames that is correct and should stay that way. For **attachments** it
means a second copy of every photo that already crossed a BLE or NAN link — frames are ~KB against a
64 KiB `maxBlob` ceiling, attachments run to 8 MiB — so the bytes are worth gating and the frames are
not.

Attachments are also the only object class where gating is *free of the plane's own invariants*: they
are deliberately outside the scope digest (§4.5/§6.5, ADR 019), so withholding one signals nothing and
costs no convergence. Gating frames would instead make `localFold` a function of local mesh state, and
the digest would stop converging against members whose radio history differs — the anti-entropy loop
would LIST every tick forever and `ScopeStatus.converged` would become noise.

**The gate is a deferral, never a veto, and it is self-reversing.** That is the whole decision, and it
is what rules out the obvious implementation. Gating on the delivery tick alone would be a *permanent*
veto — and a wrong one, because `MessageEntity.received` says the frame arrived, not the bytes: an
attachment travels by a separate demand-driven `BlobExchange` pull, so "acked but never fetched" is a
real state, and vetoing on the ack strands exactly the image the plane exists to rescue. So the rule
composes two signals that fail in opposite directions:

- **`MeshTransport.reachable`** — the presence plane, which expires. It is the *reversing* half: a peer
  that wanders off stops being recent and the upload happens on the next heal round, with no restart, no
  new custody event and no user action. On its own it would defer into a black hole, since the cue plane
  includes peers we hold no data path to at all.
- **The delivery tick** — proof a data path actually worked for this conversation. It is the half that
  keeps a merely-visible peer from being mistaken for a reachable one.

Every uncertain case resolves to *push*, and three of them are worth naming because they fall out of the
rules rather than being coded: a **carried** frame has no message row we authored, so a carrier never
defers (which is right — a carrier cannot read a sealed receipt at all, per ADR 018, so it has no
delivery knowledge to gate on); an **avatar** writes `PeerEntity` and no message row, so it never defers;
and a **fresh process** has no sightings, so a restart defers nothing. Under-deferring costs relay bytes,
over-deferring strands an image, and the asymmetry is priced in that direction everywhere.

Two bounds it needs and one exclusion:

1. **Last call.** Deferring is only safe while the referencing frame is still in custody to drive a later
   push — once it ages out, `ScopeAttachments.references` stops naming the attachment and the chance is
   gone. So the deferral ends `LAST_CALL_MS` (2 h) before the custody TTL, which is why
   `ForwardRepository.DEFAULT_TTL_MS` is injected rather than restated.
2. **A sighting window** (`RADIO_WINDOW_MS`, 15 min) above the cue plane's own quiet periods — the BLE
   scan floors to ~2 min in a settled clique and a dozing NAN peer goes dark for ~30 s ICM windows — so
   ordinary radio silence does not read as departure.
3. **Group scopes never defer.** `applySealedReceipt` flips one boolean on the *first* member's tick, so
   "acked" can never mean "every member holds it". Deferring on it would silently strand whoever was not
   reached, and a per-member ack matrix does not exist.

The honest cost, and the reason the frame plane keeps uploading unconditionally: a deferred upload tells
a spool roughly when the members were apart, which an unconditional one does not. It is scoped to the
object class that already leaks a size and a time (§10), and it is now written there. `spoolAttachDeferred`
is counted and surfaced in Diagnostics and the `SPOOL` bridge for the same reason — a silent gate reads
exactly like a broken upload.

Scheme: this file plus `docs/SPOOL_PROTOCOL.md` §9.5 (a MAY with two obligations) and §10. The spec's
§13 vectors and the `knit-spool` conformance suite are untouched: a deferring member and an eager one are
the same client to the same server.
