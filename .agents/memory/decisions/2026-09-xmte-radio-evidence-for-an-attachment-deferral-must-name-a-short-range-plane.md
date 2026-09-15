---
id: "2026-09.xmte"
slug: radio-evidence-for-an-attachment-deferral-must-name-a-short-range-plane
title: "Radio evidence for an attachment deferral must name a short-range plane"
date: 2026-09-14
topics: [spool, attachments, receipts]
---

# ADR 2026-09.xmte — Radio evidence for an attachment deferral must name a short-range plane

Status: Accepted (2026-09-14; `DeliveryPlane.shortRange`, `MessageDao.attachmentAckedOverRadio`,
`AttachmentDeferPolicy.ackedOverRadio` — no wire change, no DB migration, no spec vector change.
Amends ADR 021. Issue #52, the third follow-up from the 2026-09-14 spool-image field investigation)

ADR 021 composed the §9.5 push-half deferral out of two signals that fail in opposite directions: a
sighting on the presence plane, which expires, and the delivery tick, which it called "proof a data path
actually worked". That second claim was true when it was written. It stopped being true the moment the
Internet plane began carrying receipts: `ackedBySender` was
`messages.attachmentAcked(aHash, identity.nodeId())`, a bare `MessageEntity.received == 1`, so a receipt
that came back across a **spool** — or over a board — counted as evidence the **radios** moved the bytes.

The cost was bounded and self-limiting, which is why it went unnoticed: a deferral is a delay and the
15-minute sighting half still lapsed on its own (C-9.5-6), so nothing could strand. What it bought was
the wrong window. A peer that had genuinely left radio range kept the ack half of the AND satisfied
forever, so its bytes waited out the remainder of the sighting window instead of going up at once. In the
lab scenario below, four consecutive rounds deferred on an ack that had only ever crossed a spool.

**The evidence now has to name the plane it arrived on.** That information was already there and already
written in the same transaction as the tick — `MessageReceiptRepository.record` calls
`messages.markReceived(messageId, via)`, which stores the `DeliveryPlane` code on
`messages.receivedVia` — so the fix is a predicate on the query the policy already ran, not a new signal:

```sql
... AND received = 1 AND receivedVia IN (:planes)   -- DeliveryPlane.shortRangeCodes
```

`Nearby` and its two reserved siblings (`Bluetooth`, `WifiAware`) count; `LoRa` and `Internet` do not,
and they are excluded for different reasons that both matter. A board carries a frame and never a blob,
so a LoRa ack says nothing about bytes — the same reason `MeshManager` already narrows the policy's
`reachable` to `nearbyPeers` (H5). And the Internet plane is the one an attachment push *feeds*, so
deferring on a receipt that rode it is the gate arguing with itself.

**`receivedVia`'s first-evidence-wins rule is load-bearing here, not incidental.** It records the plane
of the receipt that actually flipped the tick, so a duplicate crossing later on the other plane leaves
the mark alone. Both directions come out right: a spool ack followed by a radio duplicate stays "not
radio evidence" and pushes, which is the safe direction the whole gate is priced toward; a radio ack
followed by a spool duplicate keeps deferring, which is the truth.

The obvious alternative is to join `message_receipts` on `ackerNodeId = scope.peerId`, which also stores
`via` and would be per-recipient by construction. It buys nothing today and costs a signature change to
the policy seam. The deferral runs on pair scopes only (`scope.peerId ?: return false`), and a DM's
attachment reference is the **ciphertext** hash of one sealed frame — `AttachmentCrypto.seal` draws a
fresh key and IV per send, so the same photo sent to two people yields two hashes and one hash names
exactly one authored row. Revisit the join if the deferral is ever extended past pair scopes.

**What it costs:** slightly more relay bytes. A photo the radios did carry to a peer who then acked from
the spool is now uploaded anyway. That is under-deferring, the cheap direction, and it is the direction
ADR 021 already committed to everywhere.

**The trap for the next person** is in the lab, not the code. `AttachmentDeferPolicy` samples the
presence plane **lazily**, from inside `defer` — correct in production, where a heal round runs
constantly, but it means a scenario with no attachment pending while the peers are linked records no
sighting at all and can never defer. `InternetPlaneLabTest.aPhotoAckedOnlyAcrossTheSpoolIsNeverDeferredOnThatAck`
works around it with an **avatar**, the one reference that stamps the sighting without polluting the
count (no message row, so it can never defer whatever its plane), and reads
`MeshMetrics.spoolAttachDeferred` rather than `chunksPut`, because finding #46 still stands — the
subject image's first round beats any ack, so its chunks go up either way and only the rounds *after*
the ack discriminate. The rule itself is pinned by `MessageDaoTest` (the real SQL, every plane) and
`AttachmentDeferPolicyTest`.

Scheme: this file, plus `docs/SPOOL_PROTOCOL.md` §9.5's non-normative reference-client paragraph.
C-9.5-5…9 are unchanged — they never named a plane — and no record, derivation or §13 vector moved: a
member that defers less is the same client to the same spool. Related: ADR 2026-09.y5f3 drew this same
"which plane vouches" line for the room tick's way home.
