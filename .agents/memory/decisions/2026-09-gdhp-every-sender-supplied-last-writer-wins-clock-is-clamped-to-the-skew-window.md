---
id: "2026-09.gdhp"
slug: every-sender-supplied-last-writer-wins-clock-is-clamped-to-the-skew-window
title: "Every sender-supplied last-writer-wins clock is clamped to the skew window"
date: 2026-09-11
topics: [mesh, groups, profile, reactions]
---

# ADR 2026-09.gdhp — Every sender-supplied last-writer-wins clock is clamped to the skew window

Status: Accepted (2026-09-11; work item 25, `InboundPipeline.clampFuture`, `Protocol.MAX_FUTURE_SKEW_MS`)

A security audit of `v2.2.3..HEAD` read the sealed ctl writers (ADR 020/022, ADR 018) and found that both
order last-writer-wins on a number the sender chooses, with no ceiling. `applySealedProfile` wrote
`ProfilePayload.version` straight into `PeerEntity.updatedAt`, so one `Long.MAX_VALUE` froze that peer's
name, status and avatar on our device for good — every later profile of theirs, cleartext or sealed, read as
stale, and the only way out was deleting the contact. `applySealedReaction` stamped the row with the frame's
`sentAt`, so a group member's far-future reaction won every later race on that message, on every recipient,
including against their own retraction. Neither was a regression: both mirrored the cleartext writers they
were built to converge with, which had the same hole. And the clamp already existed in the same file —
`deliverChat` had bounded a chat row's `sentAt` with `minOf(env.sentAt, now + MAX_FUTURE_SKEW_MS)` since the
custody hardening — it had just never been read as a rule about *watermarks* rather than about one column.

Reading it that way turned up the rest of the family. The pipeline kept six sender-supplied numbers as
last-writer-wins clocks, and every one of them was stored raw:

| writer | clock | what a far-future value froze |
|---|---|---|
| `handleProfile` / `applySealedProfile` | `ProfileContent.version` / `ProfilePayload.version` (`sentAt` fallback for a pre-field peer) | the peer's presentation, and on the cleartext path `prekeyProfileAt` too |
| `handleReaction` / `applySealedReaction` | the frame's `sentAt` | that (message, reactor) row, retraction included |
| `reconcileGroup` | the carrying frame's `sentAt` | the group's name, plus the rename notice's place in the thread |
| `groupPhotoDecision` | `GroupInfo.photoUpdatedAt`, a payload field every member re-asserts in every frame | the group's photo |
| `handleGroupLeave` → `recordDeparture` / `rejoinBy` → `recordRejoin` | the member's own `sentAt` | their own way back into the group (`rejoinBy` needs `sentAt > leftAt`) |

`ForwardRepository.store` refusing a future-dated frame protects none of these: `MeshRouter.handleInbound`
calls `onDeliver` before anything custody-shaped, so a far-future frame is delivered and relayed on the fast
plane whether or not anyone keeps it.

**What changed.** One private helper, `clampFuture(stamp) = minOf(stamp, clock() + MAX_FUTURE_SKEW_MS)`, on
the pipeline's injected clock, applied at every site in the table plus `deliverChat` (which moved off
`System.currentTimeMillis()` onto the same helper — identical in production, deterministic in the `Rig`) and
the two status-notice stamps that took a raw `sentAt` (`keyPinRefused`, and the profile-version-keyed
rename/avatar notices through the clamped `version`). The profile clamp happens once, before either watermark
comparison, so `updatedAt` and `prekeyProfileAt` stay ordered against one number as ADR 022 requires; the
group clamp happens at `reconcileGroup`'s two call sites rather than by shadowing its parameter, and feeds
the name, `createdAt`, the notices and `recordRejoin` from that one value. The envelope is never touched: the
era gate (ADR 026), ack ids and the signature all need the raw `sentAt`. No wire field, no DB change, nothing
folded into a digest — receiver-local only, so `WIRE_COMPAT` does not apply and no `mesh/lab/` scenario is
owed (nothing two nodes exchange changed; `MeshLab` nodes share the real clock and have no seam to originate
a far-future frame).

**The alternative a reader reaches for first** is refusing the frame, as custody does. That is wrong for the
one frame that matters most: the cleartext `profile` is the only carrier of the key pin and the prekey, so
refusing a skewed peer's profile would leave them unable to DM anyone at all, forever, for a clock they may
not know is wrong. A ceiling keeps the pin and bounds the damage. The second alternative — comparing the
*stored* side against the window too, so a row already holding a poisoned watermark heals on the next honest
update — was considered and **deliberately not done**: an honest update (≈ `now`) can never beat a floor of
`now + 5 min`, so the natural rule does not heal at all, and the rule that would (treat a stored value past
the window as absent) is a second convention for the same thing. Rows poisoned before this build stay as they
are; the audit found no exploitation and the field report is nil.

**What it costs.** Inside the window nothing changes: an honest value (≤ `now + 5 min`) is stored as-is, so
honest peers, honest skew and every existing test are untouched. Past the window the clamp turns *sender*
order into *arrival* order for that one sender: a peer whose clock runs an hour ahead now has every update
land at "our now + 5 min", so a custody re-serve of their *older* frame arrives later and briefly wins, until
their next real update lands. Bounded (five minutes of stickiness for a far-future stamp, versus forever) and
self-healing (their next edit takes the row back), which the raw value was not; and since every node clamps
against its own `now`, no two nodes disagree about anything custody folds over. The trap for the next writer:
a new sender-supplied last-writer-wins clock **must** go through `clampFuture`, and a new consumer of one of
these clocks must read the bounded value, not `env.sentAt` — `adoptAdvertisedGroupPhoto`'s equality check
against `PhotoDecision.clock` is the example of why. `InboundPipelineTest`'s `aFarFuture*` block pins one
writer each: the edge it stores (`nowMs + MAX_FUTURE_SKEW_MS`), that it holds inside the window, and that it
yields to an honest update after it; all eight fail on the parent commit.
