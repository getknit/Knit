---
id: "2026-09.p7j8"
slug: a-missing-ack-is-not-evidence-until-an-ack-could-have-arrived
title: "A missing ack is not evidence until an ack could have arrived, and it is read from both ends of the DM"
date: 2026-09-15
topics: [spool, attachments]
---

# ADR 2026-09.p7j8 — A missing ack is not evidence until an ack could have arrived, and it is read from both ends of the DM

Status: Accepted (2026-09-15; `AttachmentDeferPolicy.ACK_GRACE_MS`, `MessageDao.attachmentCarriedByRadio`,
`MessageDao.attachmentAuthoredHere` — issue #46, amending ADR 021. No wire change, no DB migration, no
capability bit, no spec vector change)

ADR 021's gate never fired for the case it exists for, and the mesh-in-a-box lab is what showed it. Two
phones on one relay, linked, Alice sends Bob a photo: the chunk reached the relay while Bob was still in
range and `spoolAttachDeferred` stayed at zero. The scenario
(`InternetPlaneLabTest.aPhotoTheRadioCarriedIsNotUploadedUntilThePeersPart`) *is* the device trial ADR 021
owed, so it was parked `@Ignore`d as the finding rather than run.

Two independent causes, one at each end of the pair.

**The sender judged too early.** `ScopeSync.onCustodyChanged` wakes every worker the moment a frame is
custodied — that is the send itself — and both branches of `healLocked` end in `healAttachments`, which
asks `defer` before it spends an `ahave`. At that instant the frame is milliseconds old and the
recipient's receipt is a round trip away, so it cannot exist. `defer` read the missing ack as "the radios
never carried this" and pushed; by the time the real ack landed the bytes were already at the relay and
every later deferral was moot. The policy was conflating *never carried* with *not carried yet*.

**The recipient re-uploaded.** The evidence was sender-shaped only — our authored row plus its tick — so
a member who had just pulled a photo off a BLE link had no row that could satisfy it and pushed those
same bytes to the relay itself. With the sender fixed the lab still saw one chunk, and the metrics named
it: `alice pushed 0, bob pushed 1`. In a DM scope there are exactly two members, both now holding the
bytes, so that copy served nobody.

## What changed

`ACK_GRACE_MS` (60 s) separates the sender's two cases. Inside it, an attachment on a message *we*
authored holds on the sighting alone; outside it the ordinary rule resumes. The grace expires on the
frame's own age, with no new local activity, so the deferral stays the delay C-9.5-6 requires — and the
window is seconds, not the 15-minute sighting window, so it is not the black hole ADR 021's `reachable`
note rules out. One `ScopeSync.TICK_INTERVAL_MS` is the value: comfortably more than a co-located
deliver-and-ack round trip, and the same order as ADR 2026-09.y5f3's ride deadline.

The evidence itself is now symmetric, asking one question from whichever end this node is:
`attachmentCarriedByRadio` matches our own send acked over a short-range radio **or** the peer's send that
arrived over one. Both readings come off `receivedVia`, first-evidence-wins in each direction, so this is
the same column ADR 2026-09.xmte made load-bearing and there is nothing new to persist. The peer's row
needs no tick — `received` there is the peer's business, not ours.

The alternative a reader reaches for first is skipping the attachment pass in a round a wake triggered,
which is what the issue proposed as the smallest change. It does not work: the wake channel is conflated
and carries no reason, and the push and pull halves share one loop in `healAttachments`, so skipping a
woken round would also delay a *receiver's* image download by up to a tick. The rule, not the loop, is
what was wrong. The other alternative — deferring on reachability alone inside the window — is option 2
of the issue and the thing ADR 021 argues against at length: a co-located peer whose link never forms
would hold the image for the full 15 minutes.

## What it costs

An attachment whose peer is in sight but whose radios never delivered reaches a relay one grace plus one
tick later than it used to. That is the whole of the new latency, and it is the "under-deferring costs
relay bytes, over-deferring strands an image" trade taken in the cheap direction: nothing here can strand
anything, because both terms expire on their own.

The recipient half narrows what "a carrier never defers" meant. A true carrier is untouched — it holds
sealed bytes and no message row at all, so the query finds nothing between the pair and it pushes, as do
avatars and group photos. What changed is only the **addressee**, which ADR 021 had swept in with
carriers because neither had an authored row. Group scopes still never defer: `scope.peerId` is null and
the first guard returns.

The trap: `authoredHere` gates the grace and *nothing else*. Widening it into the main rule would defer a
photo the peer sent us before we knew a radio carried it, which is the sender-shaped mistake mirrored.
`AttachmentDeferPolicyTest` pins both roles (16 cases, including the hand-over from grace to real ack),
`MessageDaoTest` pins the SQL from both ends, and the lab scenario is the end-to-end oracle — un-ignored,
and the only place the two halves are proven to compose.
