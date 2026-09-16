---
id: "2026-09.ptv8"
slug: a-missing-attachment-is-re-wanted-from-the-database-at-every-link-up
title: "A missing attachment is re-wanted from the database at every link-up, not only at startup"
date: 2026-09-15
topics: [mesh, attachments, blob-exchange, lora]
---

# ADR 2026-09.ptv8 — A missing attachment is re-wanted from the database at every link-up, not only at startup

Status: Accepted (2026-09-15). Found in the 2026-09-15 LoRa field trial; the third stranded-blob shape
after #51 (the `fetching` mark, `cdab7b5c`) and #53 (`wanters`, ADR 2026-09.ywzn).

**What was observed.** The Pixel 9 was out of the house with a board; the Pixel 7 (board) and Pixel 8 were
in the lab. At 18:20:58 the Pixel 8 posted a picture in the Nearby room (frame `Ag0HPmd0…`, blob `7e55951a…`,
199 761 B). The Pixel 7 had frame and bytes over NAN within a second and fanned the frame over LoRa; the
Pixel 9 heard it at 18:21:01, saved the row with its `attachmentHash`, and called `BlobExchange.want`. It
had held no link since 18:17:05, and the LoRa plane contributes no neighbours (`LoraMeshTransport.neighbors`
is always empty — it has no data path), so the `blobreq` went to nobody and the hash sat in `fetching`. At
18:59:17 the 10-min prune loop ran `blobExchange.sweepExpired()`: the want was 38 min old, past
`FETCH_TTL_MS` = 30 min, and was reclaimed. At 18:59:55 the Pixel 9 linked to the Pixel 7 over BLE and at
19:03:00 to the Pixel 8, the author; `onNeighborAdded` re-asks each newcomer for everything in `fetching`,
which was now empty, so neither was asked. Neither lab phone logged a serve of the hash after 19:00. The
row spun in the chat with no bytes, and the only path that re-reads the database for missing attachments —
`resumePendingFetches`, at startup — would have cured it on the next app restart, and nothing sooner.

The sweep's comment said "a never-arriving blob is reclaimed and re-added on the next `want`", and that
was the mistake: for a received message there is no next `want`. The row is written once; every later
re-ask reads the in-memory memo. The hole is exactly the LoRa shape — a frame that arrives with no
neighbour, followed by more than half an hour before one appears — and a board is worth carrying because
that is the normal case for it, not the edge.

**What changed.** `MeshManager.rewantMissingBlobs()` is the want half of `resumePendingFetches` pulled out
on its own: `messages.hashesNeedingFetch()` (rows naming a hash the `blobs` table lacks), plus the
carrier-only custody hashes while under `CARRIER_BLOB_BUDGET_BYTES`, each through `BlobExchange.want`. It
now runs at every point a holder may just have appeared — startup as before, `watchNeighbors` when a
newcomer batch arrives (before the per-newcomer `onNeighborAdded`, so the re-ask has the full set), and
the 60 s `reofferToNeighborsPeriodically` tick while anyone is linked. `want` is idempotent for a hash
already in flight and returns at once for one the store holds, so the repeat costs one bounded query
(`messages` is retention-capped) and a duplicate request the receiver's 45 s `servedRecently` memo absorbs.

The alternative a reader reaches for first is to stop sweeping a want the database still backs. It would
need `sweepExpired` — non-suspending, under the exchange's lock — to consult Room, or a second liveness
flag per entry that the unsigned `blobreq` path could also set; the sweep exists to bound that unsigned
path and should stay blind to who asked. The database is the durable truth and the memo a cache of it;
refreshing the cache at the moments it is read is the smaller change and keeps `BlobExchange` pure.

Alongside it, the five TTL sweeps that `heal()`, `resumePendingFetches` and the prune loop each spelled out
are one `internal suspend fun sweepExpired()`, which is also the seam `mesh/lab` needed: a clock jump
moves the expiry, but the loops that would run the sweep are `delay()`-based and blind to it.

**What it costs.** One `SELECT DISTINCT attachmentHash … NOT IN (SELECT hash FROM blobs)` per link-up and
per minute while linked, and a `blobreq` re-sent to a newcomer that the ordinary `onNeighborAdded` would
also send in the same instant — bounded by the receiver's memo. What it does not cover: a phone with no
neighbour at all still has nowhere to ask, and a blob nobody in reach holds is still not fetched (the
carrier's eager pull, ADR 035's carried bytes, is what makes a relay a holder). Trap for the next person:
the in-memory `fetching` set is a memo, never the source — any new "what do we still need" path reads the
database. Kept true by
`AttachmentLabTest.aPictureHeardOverTheBoardWhileAloneIsStillFetchedAfterALongIsolation` — Bob hears
Alice's room picture over the board alone, the lab clock jumps 31 min, `sweepExpired()` reclaims the want,
and the re-link alone fetches the bytes — which fails on the fix's parent with the field signature ("the
picture was never fetched once a holder linked").
