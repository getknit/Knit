---
id: "023"
slug: a-split-brain-ratchet-root-requests-a-reset
title: "A split-brain ratchet root requests a reset, like every other unreadable v2 DM"
date: 2026-08-19
topics: [crypto, pfs, recovery]
---

# ADR 023 — A split-brain ratchet root requests a reset, like every other unreadable v2 DM

Status: Accepted (2026-08-19; `DropReason.RATCHET_AEAD_FAIL` split out of `DECRYPT_FAILED` and added to the
reset trigger — no wire change, no DB change)

Field testing ADR 022 found two lab devices that had finally exchanged prekeys, established sessions, and
then could not read each other in **either** direction: symmetric `AEAD_FAIL`. Both held session state; the
roots disagreed. The reset heuristic that exists for exactly this class of trouble never fired.

`AEAD_FAIL` was folded into the generic `DECRYPT_FAILED` (shared with the v1 path), and the trigger tested
only `RATCHET_NO_SESSION` and `RATCHET_EPOCH_GONE`. Those two mean *we are missing something* and are
self-correcting — the peer's own traffic eventually supplies it. `AEAD_FAIL` means *we both have something
and it disagrees*, which nothing supplies: the pair re-serves the same undecryptable custody at each other
until the frames age out, and then does it again with the next message. It was the one ratchet failure that
could not recover, and it was the only one excluded.

Two things worth not relitigating:

1. **Acting on `AEAD_FAIL` is safe because the frame is already authenticated.** `verifyInbound` checks the
   Ed25519 signature against the pinned bundle *before* any decrypt, so a signature-valid frame that fails
   the AEAD is a real peer whose era diverged, never a tampered or corrupted one — those fail the signature
   first and never reach the ratchet. The trigger therefore cannot be driven by an off-path attacker, and
   the existing bounds (≥3 **distinct** frame ids, a 6 h per-peer floor, a pinned CAP_RATCHET peer with a
   prekey) still hold it to one X3DH init per burst.
2. **The group path already did this.** `GROUP_RATCHET_AEAD_FAIL` has always driven `maybeRequestGroupKey`
   alongside `GROUP_RATCHET_NO_KEY`. The DM path was the inconsistent one, so this is closing a gap rather
   than introducing a policy — which is also why `AEAD_FAIL` deserved its own `DropReason`: a split brain
   filed under the same counter as a v1 decrypt failure is invisible in Diagnostics and the `STATE` bridge,
   and that is precisely how it stayed unnoticed.

A second half surfaced the moment the first shipped: with resets finally firing, the pair deadlocked again
in **one** direction, now as `DUPLICATE`. `sealResetDm` abandoned the old root era but purged only its send
side — our **recv** epochs and skipped keys for that peer survived. The peer adopts our init, purges its own
rows (`OpenDelta.purgePeerRecvState`) and restarts its epoch numbering; its fresh epochs then meet our
surviving row from the dead era and are judged against its stale chain index. `DUPLICATE` is terminal by
construction — a duplicate is benign, so it drives no recovery at all, unlike the `AEAD_FAIL` above.
`RatchetStore.purgePeerRecvState` makes the initiator symmetric with the adopter: whoever abandons a root
era drops their receive state for it.

A third round, from the same lab pair, closed the loop: with both fixes deployed the receiver still sat at
**116 duplicates from 4 distinct frames** and had requested no reset at all. Its heuristic read 1, because
`DUPLICATE` fed nothing and its single `AEAD_FAIL` was one frame custody re-served three times. Every one of
the sender's five DMs had arrived and been discarded as benign.

So `DUPLICATE` joins the set, and the **distinct-frame-id** rule is what makes that safe rather than a reset
storm. A replayed frame is one id arriving repeatedly — custody re-serving it, two links delivering it — and
repetition can never advance a counter keyed on distinct ids. Several *distinct* frames landing on
already-consumed indices is a different statement: the sender restarted its chain while we kept ours. That
is precisely what the peer sees for our side of a half-adopted replacement, the mirror of the `AEAD_FAIL` we
see for theirs. The guard that was supposed to stop one stuck frame from triggering anything was, in the
stuck case, the thing stopping recovery — the pair could not produce three distinct *countable* failures
because the failures it could produce did not count.

The accepted cost: a peer whose skipped-key window evicted keys can accumulate distinct duplicates over a
long period and eventually draw a spurious reset. It is bounded by the 6 h floor and costs one X3DH plus a
skipped-key wipe, which is cheaper than the deadlock it replaces. `BAD_HEADER` stays out — a malformed frame
says nothing about our session state.

The last round was the one the earlier fixes made reachable. With every undecryptable outcome now able to
request a reset, **both** peers reset each other — 13 minutes apart, each landing inside the other's floors —
and the pair sat with every X3DH input present, two sessions, and neither confirmed. Two gaps kept it there:

- **`RatchetHeader.FLAG_RESET` was written and never read.** An explicit reset request was rate-limited as
  if it were an incidental init, so a peer that had waited out its own 6 h floor could still be refused for
  another 60 minutes, silently. It now gets its own short floor: the sender's floor is the real rate limit
  and is 6× stricter, and a peer ignoring it is a pinned contact churning the one conversation it is already
  party to.
- **`resolveRace` adopted the winner's root without purging the loser's receive state.** Its stated
  invariant — "send-epoch numbering continues monotonically either way, so no `(peer, se)` collision
  arises" — holds for an ordinary race and fails for a race between two *resets*, because `sealResetDm`
  restarts numbering by design. The winner's fresh epochs then landed on the loser's surviving rows and read
  as duplicates. The same omission as the `sealResetDm` one above, in the third of the three places a root
  era changes: whoever abandons an era must drop the receive state tied to it. The loser branch keeps its
  own root, changes no era, and correctly keeps its rows.

Verified end to end on the lab pair: a forced reset moved the receiver to `confirmed: true`, and messages
then flowed both ways with delivery ticks returning, after roughly a day wedged.

Scheme: this file only. No wire field, no derivation, no vector, no spool record — a reset request has
always been an ordinary v2 DM carrying `CTL_SESSION_RESET` (ADR 016). `FLAG_RESET` was already on the wire.
