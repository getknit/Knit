---
id: "2026-09.e8yw"
slug: the-bytes-own-radio-arrival-is-the-recipient-s-deferral-evidence
title: "The bytes' own radio arrival is the recipient's deferral evidence, noted before they are stored"
date: 2026-09-16
topics: [spool, attachments]
---

# ADR 2026-09.e8yw — The bytes' own radio arrival is the recipient's deferral evidence, noted before they are stored

Status: Accepted (2026-09-16; `AttachmentDeferPolicy.noteRadioArrival`, wired from
`MeshManager.watchIncomingFiles` — amends ADR 2026-09.p7j8. No wire change, no DB migration, no capability
bit, no spec vector change)

ADR 2026-09.p7j8 gave the recipient end of a DM its own reading of "the radios carried this": the plane
its row for the peer's message arrived on (`MessageDao.attachmentCarriedByRadio`, the `senderId = :peer`
arm). Its lab scenario, `InternetPlaneLabTest.aPhotoTheRadioCarriedIsNotUploadedUntilThePeersPart`, went
green the same day and then failed twice on GitHub the morning after — both times one chunk at the relay
with `alice pushed=0 bob pushed=1`. Under the throttled repro loop it fails about one run in six, and the
report added for it named the shape: `bob's row came via Nearby`. The row was there, it named the radio,
and Bob pushed anyway.

**The row is a proxy, and the recipient sees two orderings where the proxy is wrong.** Reading the plane
off the row assumes the row exists, and is settled, by the time the round that finds the bytes runs. Neither
holds.

- **The bytes land before the row.** `InboundPipeline.onDeliver` captures custody *before* it dispatches:
  `forwardSync.onSeen` → `onCarriedFrame` → `BlobExchange.want` fires the `blobreq` while the sealed content
  is still being opened and persisted. A neighbour serves 4 KB in the time a ratchet step and a SQLCipher
  write take on one slow core, so `BlobExchange.onReceived` stores the bytes while the row is not yet
  committed. Meanwhile the author's push of the frame changed the scope digest, the spool's digest woke
  Bob's worker, and that round found the bytes in hand (`mine`), asked `defer`, read no row, found nothing
  authored (no grace: not our send), and pushed. This is the CI failure.
- **The frame beats the radio across the relay.** With a live socket on both phones and a BLE connect
  still forming, the frame comes off the spool first and the row records `Internet`, first-write-wins.
  The sender deferred, so the bytes still cross the radio; Bob's round then reads an Internet row and
  pushes. Forced deterministically by the new scenario
  `aPhotoWhoseFrameBeatTheRadioAcrossTheRelayIsStillNotReUploaded`, which failed before the change.

In both, the fact the row was standing in for had already happened: the bytes reached this phone over a
short-range radio. `MeshTransport.incomingFiles` is exactly that fact — a board carries no file, the spool is
not a transport, and a Wi-Fi Direct transfer never goes through `BlobExchange` — so the evidence is read
where it occurs. `watchIncomingFiles` calls `attachmentDefer.noteRadioArrival(hash)` **before**
`blobExchange.onReceived`, so there is no instant at which the store holds the bytes and the policy does
not know how they got there. `defer` consults that memo ahead of the row read (no I/O) and the rest of
the rule is unchanged: the sighting still gates and still lapses, last call still ends it, group scopes
still never defer.

The memo is a bounded in-memory set of ciphertext hashes (`MAX_RADIO_ARRIVALS` = 256, oldest evicted). It
does not expire on its own because it records a fact, not a stamp; the sighting half is what makes the
deferral a delay. It is lost on restart, like `lastSeen`, and for the same reason: losing it only means
deferring less, which is the direction ADR 021 priced everything toward.

**The alternative a reader reaches for first** is to persist it — a column on `blobs` saying which plane
delivered the bytes. It is not needed: the deferral only matters while the frame is in custody and the
peer in sight, and a memo that survives a restart would only defer more, which is the expensive direction.
The other is to make the pipeline commit the row before custody asks for the blob. That reorders a path
that every other consumer depends on (the custody capture runs first so a decrypt failure still leaves the
frame carried) to serve one gate, and it does nothing for the relay-first ordering.

**What it costs** is one `synchronized` set lookup per attachment per round. What it widens: a peer's
avatar *pulled* over a radio (`FileKind.ATTACHMENT`, the multi-hop case) now defers on this node while its
owner is in sight. That is harmless — the owner's own copy never arrived anywhere, so the owner pushes it
regardless, and a DM scope has nobody else to serve. A direct avatar push (`FileKind.AVATAR`) is not
noted; it never reaches `BlobExchange`. The author's end is untouched: its evidence is still the grace and
then the receipt's plane, and a receipt that rode the spool still pushes after the grace (ADR 2026-09.xmte's
accepted cost). The symmetric memo — "we served these bytes to the peer over a radio" — would close that
too and is deliberately not taken here.

The trap is the ordering: `noteRadioArrival` must stay ahead of the store write, or the window this ADR
closes reopens one line later. `AttachmentDeferPolicyTest` pins the rule (five new cases: before the row,
Internet row, wrong hash, no sighting, the cap), and the two lab scenarios are the oracle — the forced
ordering and the original, which now carries a per-node push count and the row's plane in its failure
message so the next flake names itself.

Scheme: this file, ADR 021's amendment note, and `docs/SPOOL_PROTOCOL.md` §9.5's non-normative
reference-client paragraph. C-9.5-5…9 are unchanged.
