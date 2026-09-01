---
id: "027"
slug: local-epoch-retention-orders-by-mint-time
title: "Local-epoch retention orders by mint time; a re-minted epoch number replaces the dead era's key"
date: 2026-08-20
topics: [crypto, pfs, retention]
---

# ADR 027 — Local-epoch retention orders by mint time; a re-minted epoch number replaces the dead era's key

ADR 024 stopped the reset heuristic from feeding itself, and the fleet relapsed anyway — pairs re-broke
within hours of every heal, one direction at a time, `EPOCH_GONE` on frames from a peer whose root matched
ours exactly. The forensic ratchet dump (added for this) showed both sides of a "diverged" pair holding the
**same root and the same `establishedAt`**: the sessions were healthy. What was missing was the local epoch
privs of the live era — on both devices, the table held exactly 16 rows, all numbered 46–62, all minted days
earlier, in eras long abandoned.

The sweep's "newest" was `ORDER BY epoch DESC`. That is correct only while epoch numbering is monotonic, and
a session reset restarts numbering at 1 by design (`sealResetDm` → `initiate`). After one reset, a long-lived
session's dead-era rows outrank every live-era row forever: the 16-per-peer cap keeps the 16 highest *numbers*
— all dead keys — and deletes each fresh epoch within one sweep cycle (≤15 min, the heal cadence) of minting
it. The peer, which received that epoch's pub off the wire, eventually bases its next epoch on it; we no
longer hold the priv; `EPOCH_GONE`; that direction dies, receipts die both ways. The failures are in-era, so
the (correct) ADR 024 heuristic fires a reset at the 6 h floor, the pair heals, the sweep eats the new era's
keys again, and the loop runs on the floor's cadence indefinitely. This was the recurring re-divergence engine
behind every relapse ADR 023/024 were opened on — the purge-site inventory in ADR 024 ("fixed at the source,
at all three sites") missed that retention GC is a fourth place ratchet state dies.

Two changes, both in the DAO:

- **`localEpochsNewestFirst` orders by `createdAt DESC`** (epoch number only as a same-millisecond
  tiebreak). "Keep the newest three", the 16-row cap, and the retire rule now operate on mint time, which is
  what the sweep's KDoc always claimed. Dead-era rows age out through the cap as live epochs mint, and stay
  long enough (≤48 h practical) to serve the prevRoot drain window.
- **`insertLocalEpoch` is `REPLACE`, not `IGNORE`.** Once time-ordering lets old rows survive properly, a
  restarted numbering *will* re-mint a colliding number while the dead era's row is still inside its
  retention window. IGNORE silently kept the dead key, making every peer frame based on the fresh epoch an
  unexplainable AEAD failure. The live era wins the collision; the frames the old key could still serve are
  pre-reset ciphertext, already stranded by the re-root itself.

No schema change (ordering and conflict strategy only), no wire change, no migration. `RatchetPeerState` and
the debug bridge's `RATCHET` dump gained era forensics — `rootHash` (8-hex SHA-256 prefix), `establishedAt`,
`weAreInitiator`, `highestPeAcked`, `prevRootExpiresAt`, `hasPeerInitAnchor`, and the `localEpochs`
(epoch@createdAt) table — because this bug was undiagnosable from drop reasons alone and took three field
sessions to corner: the header logging said which epoch was missing, but only the table said *why* it was
missing while its era's root matched.

Known residue, accepted: frames sealed under an epoch whose base priv was already swept are permanently
unreadable (forward secrecy working as designed). Custody re-serves them in-era, so they can still count
toward one more reset per pair; after that reset they are pre-era and the ADR 024 gate silences them. One
extra reset per historically-wedged pair, then stable.

Open question, deliberately not fixed here: `initiate` discards `peerInitEphPub`, so the resolved-init
idempotence anchor is lost on every reset and a re-served init from an era the peer already left can win
`resolveRace` on the resetter's side. With the sweep fixed this no longer self-sustains (the adopted-back era
still decrypts — the roots converge again on the next reset), and preserving the anchor across `sealResetDm`
is a small, testable follow-up.
