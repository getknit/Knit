---
id: "024"
slug: the-reset-heuristic-only-counts-frames-from-the-era-it-would-abandon
title: "The reset heuristic only counts frames from the era it would abandon; an explicit reset is never a race remnant"
date: 2026-08-19
topics: [crypto, pfs, recovery]
---

# ADR 024 — The reset heuristic only counts frames from the era it would abandon; an explicit reset is never a race remnant

ADR 023 gave every unreadable v2 DM the power to request a reset. A field test — one device carried out of
radio range, two DMs sent, neither delivered, and still undelivered after it came back and re-meshed — showed
that power feeding itself. The pair had re-rooted past each other and stayed dark for hours with every X3DH
input present, on both planes.

Two independent defects compose into the loop.

**The heuristic's own evidence is manufactured by its own remedy.** A reset discards the keys of the era it
leaves, so every frame already sealed under that era is unreadable *by construction* — and custody keeps
re-serving that tail for a full TTL, the spool for longer. Those re-serves carry **distinct** frame ids, so
the distinct-frame rule that bounds one stuck frame does nothing about a stuck era. Each side's tail trips the
other's heuristic, whose reset strands a fresh tail. Measured on the lab pair: 204 duplicates and 12 AEAD
failures on one device, 31 and 20 on the other, with the log showing a reset requested 70 ms after a duplicate
drop.

The gate is the era stamp, not wall-clock age: `env.sentAt < session.establishedAt` means the frame predates
the session it is being read as evidence against. `establishedAt` is already the number both peers converge
on — the initiator writes it into `InitPayload.at` and the responder adopts it — so the rule reads identically
on both ends without a new field. Frames sent since the era began still trigger, which is the whole population
that can prove anything. No session means no era and nothing to protect, so those pass unchanged.

**`RATCHET_DUPLICATE` comes back out of the trigger set**, reversing that half of ADR 023. The era gate does
not catch it — a consumed chain index is one we decrypted *in the current era*, so the re-serve is in-era by
definition. The reasoning that put it in was that several distinct frames landing on consumed indices means
the sender restarted its chain; the reasoning that takes it out is that a re-served backlog has exactly that
shape and is overwhelmingly more common, and that a consumed index is *proof the frame already decrypted* —
the one outcome that cannot mean divergence. It was a proxy for the half-adopted-replacement desync, and that
is now fixed at the source, at all three sites where a root era changes. This closes the question
`knit/knit-next#19` was opened to settle; the answer is the opposite of the guess recorded there.

**`unanchoredRaceWinner` must exempt `FLAG_RESET`.** ADR 023's escape hatch — "a genuine wipe of the higher-id
peer is still recovered, their undecryptable traffic trips OUR reset heuristic" — is what field testing
disproved. A confirmed race winner that never processed the loser's init refuses every later init from the
higher-id peer, and it refused the peer's explicit reset request along with the re-served remnants the guard
was written for. Recovery was therefore available only from the winner's side, behind its own 6 h floor: the
pair was dark for up to six hours per cycle, one-directionally, with nothing wrong that either side could act
on. A reset is the opposite of a remnant — minted fresh per request, rate-limited at the sender by that same
6 h floor, and once adopted its ephemeral becomes the idempotence anchor that makes its own re-serves inert.
It cannot defect to a losing root, because a reset abandons that root on the sender's side too.

Observed on the lab pair (P8 `cngt3uzz…` vs P9 `ke4vuj2…`, so P9 is the higher id): P9 originated a reset at
16:39:09.959 and P8 dropped a frame `AEAD_FAIL` 2.1 s later. Forcing a reset from the *low*-id side instead —
the direction the guard does not block — recovered both stranded field-test DMs and restored delivery ticks in
both directions. The one message that stayed dead was the probe sealed under the era P9 abandoned, which is
correct: forward secrecy means stranded ciphertext is stranded, and a reset repairs the channel, never the
backlog.

Scheme: this file only. `OpenContext.resetRequested` mirrors a wire bit that already exists (`FLAG_RESET`,
ADR 016) and keeps the engine wire-agnostic, exactly as `allowReplacement` does. No wire field, no derivation,
no vector, no spool record.
