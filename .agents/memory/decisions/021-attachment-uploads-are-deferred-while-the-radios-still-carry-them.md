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

*Amendment (2026-09-14, ADR 2026-09.xmte, issue #52).* "The delivery tick — proof a data path actually
worked" held only while the radios were the only thing that carried a receipt. Once the Internet plane
began carrying them, a spool ack satisfied that half of the AND for a peer that had already left radio
range, and its bytes waited out the rest of the sighting window. The second signal is now the tick **plus
its plane**: `MessageDao.attachmentAckedOverRadio` requires `receivedVia` to name a short-range radio, so
a receipt that crossed a spool or a board is not evidence. LoRa is excluded for the same reason the
sighting set is narrowed to `nearbyPeers` — a board carries a frame and never a blob.

*Amendment (2026-09-15, ADR 2026-09.p7j8, issue #46).* The gate above never fired for the case it was
written for, and the owed device trial is why we know: the mesh-in-a-box lab ran it and the chunk went up
while the peers were still in range. Two causes, one per end. The **sender** was judged in the round the
send itself woke (`ScopeSync.onCustodyChanged`), when the recipient's receipt was still a round trip away
and could not exist — so "the radios have not finished carrying this" was read as "the radios never
carried this". `AttachmentDeferPolicy.ACK_GRACE_MS` (60 s, bounded by the frame's own age) now separates
them. The **recipient** re-uploaded: the evidence was sender-shaped only, so a member who had just pulled
a photo off a BLE link pushed those same bytes to the relay. `MessageDao.attachmentCarriedByRadio` now
reads the one fact from either end — our send they acked over a radio, or their send that arrived over
one — which narrows "a carrier never defers" to the true carrier it always meant. A carrier holds sealed
bytes and no message row at all, so it still pushes, and so do avatars and group photos.

*Amendment (2026-09-16, ADR 2026-09.e8yw).* The recipient's row is a proxy for the fact that matters, and
CI showed the two orderings where the proxy is wrong: the bytes land before the row is committed (custody
asks for the blob before the sealed content is opened), and the frame comes off the spool before the radio
delivers it (the row then says `Internet`). The evidence is now read where it occurs —
`AttachmentDeferPolicy.noteRadioArrival`, from the transport's file channel, *before* the bytes are stored —
and the row's plane is consulted after it.
