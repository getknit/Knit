---
id: "2026-09.k68y"
slug: diagnostics-reads-a-relay-s-build-from-its-source-offer
title: "Diagnostics reads a relay's build from its /source offer, not from HELLO"
date: 2026-09-15
topics: [spool, diagnostics, ui]
---

# ADR 2026-09.k68y — Diagnostics reads a relay's build from its /source offer, not from HELLO

Status: Accepted (2026-09-15)

Diagnostics could say whether a relay was connected, what it would carry and what it
cost to push there, but not what it was running. Operating a relay fleet means knowing
which host is still on last month's daemon, and the screen that answers every other
question about a spool could not answer that one.

The first reach is a HELLO field, because HELLO is where every other fact about a spool
already arrives — `limits`, `powBits`, the commons. It is the wrong door twice over.
`docs/SPOOL_PROTOCOL.md` B-7.1-5 pins the handshake at version negotiation and
"nothing else identifying in either direction", so a build string is a normative spec
amendment; and a field only helps once each relay ships a daemon that sends it, which is
never for a third-party host that will not upgrade. Meanwhile every knit-spool already
publishes `{name, version, commit, source, license}` unauthenticated at `GET /source` —
its AGPL §13 source offer, deliberately outside `SPOOL_TOKEN` because a private spool's
users are still its users. The build was already public; only the client had to ask.

So the client asks, once per completed handshake, on the connection's own OkHttp client:
`SpoolUrl.sourceUrl` swaps `wss`→`https` and the `/spool/v1` suffix for `/source` (a
relay mounted under a proxy prefix answers at that prefix), `parseSpoolSoftware` reads
the body, and `SpoolStatus.software` carries it to one row under the relay's host.

What it costs and does not cover:

- **One GET per successful handshake**, off the session's own path (`Worker.readSoftware`)
  so a slow HTTP route cannot delay the heal loop. A failure of any kind — unreachable,
  404, a proxy's HTML, a body that is not ours — is the same null and never marks the
  relay unhealthy.
- **The row is a fact about the live connection**, like the attachment budget beside it:
  fetched per session rather than remembered, and `null` the moment the connection goes,
  because a redeploy is exactly what drops the session. The straggler case (a GET that
  lands after its session ended) is held off the next connection's row by the identity
  check in `readSoftware`, not by the fetch being fast.
- **The token never rides along.** `SpoolUrl.sourceUrl` redacts before it builds the
  request: the route is unauthenticated, so sending `?k=` would put the credential in a
  reverse proxy's access log to read a public document. Cleartext is refused in a release
  build by the same `isAcceptable` rule that gates the socket.
- **A relay that publishes nothing shows nothing** — no row, never the word "unknown"
  beside a perfectly healthy spool. That also covers an unstamped daemon, which honestly
  reports `unknown` for values it was never given.
- No wire field, no spec change, no daemon release: this reads what every deployed relay
  has served since the AGPL route existed.

Kept true by `SpoolSoftwareTest` (the URL derivation and what a document renders as) and
the two `ScopeSyncTest` scenarios that connect, read the row, and watch it go when the
spool drops the socket.
