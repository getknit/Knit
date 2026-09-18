---
id: "2026-09.vej5"
slug: a-spool-is-connected-once-it-says-hello
title: "A spool is connected once it says hello, and a route that swallows the socket is unreachable"
date: 2026-09-18
topics: [spool, ui, diagnostics]
---

# ADR 2026-09.vej5 — A spool is connected once it says hello, and a route that swallows the socket is unreachable

Status: Accepted (2026-09-18). GitLab work item 50, from the 2026-09-14 field test.

**What was observed.** The Pixel 7 had auto-joined a public Wi-Fi that Android *validated* — the
`generate_204` probe came back, so `NET_CAPABILITY_VALIDATED` was set and it became the default — and
which then black-holed the `wss://` socket to the attachment relay. Twelve straight minutes of
`spool socket failed: SocketTimeoutException` at the 60 s backoff ceiling, and the app called the plane
connected the whole time. Three things were mistaken for each other. `SpoolStatus.connected` was
`connection != null`, and the worker assigned `connection` *before* the hello — so every 15 s OkHttp
connect window counted as connected, and `RelayFacts`, `coveredLabels`, `planeFor`, the chat header and
the relay row all followed that bit. The failure reached `lastError` as the raw throwable name, which the
relay row rendered as "Refused a request (SocketTimeoutException)" — a refusal nobody made. And a handshake
that timed out left `lastError` null, so a socket that opened and never said hello read "Connecting…"
for as long as the condition lasted. Meanwhile the frames-only public relay was up, the frame arrived,
and the photo's placeholder said only "appears once a device that has it is reachable" — true, but silent
about the Internet the header said was live.

**What changed.** Four things, each the smallest that makes the state honest.

- *Connected means the hello completed.* `SpoolConnection.isReady` is set from the hello reply and cleared
  with the socket; `Worker.status()` reports it, and nothing else about the worker changes — the private
  `connection` still gates `retainSubscriptions`, the commons filter and the `/source` identity check.
  Every reader improves: the plane stops saying Live during a black hole, coverage stops naming scopes
  nobody has SUBbed, and `SpoolPresence` stops counting a peer present through a spool that has not spoken.
- *A no-response failure is the dialer's `unreachable` verdict.* `failureReason` maps the `java.net`
  names (timeout, DNS, refused, reset, no route, EOF) to `ScopeSync.UNREACHABLE` when there was no HTTP
  response at all; a TLS failure keeps its name, because "the host answered and we refused it" is a
  different diagnosis. A socket that opened and never carried a hello is dropped through `abort` as the
  new `ScopeSync.NO_HELLO` — the ADR 2026-09.amzn rule, so the backoff grows — and *not* as
  `unresponsive`, which retires only on an answered request, which an idle converged session never makes;
  `NO_HELLO` retires on the next completed hello. `dialFailures` counts the run for the debug dump.
- *A new default network re-dials at once.* `InternetGate.routeChanges` is one event per new validated
  default `Network`, derived from the same callback `online` reads (one registration for both);
  `MeshManager` collects it only while a relay is in use, and a worker with no live hello dials now with
  its backoff reset. A spool's `Retry-After` is still the floor: its load did not change with our route.
- *The relay row and the chat say it.* `no_hello`/`unresponsive` read "Not answering", the two
  limits verdicts "Not compatible with this version of Knit", and a phone with no validated route at all
  reads "No Internet connection" with a neutral dot (from `InternetGate.online`; this does *not* cover
  the black hole, which the platform still calls validated — that one is `unreachable`, honestly). Under a
  missing attachment's spinner, `attachmentWait` adds one line from `RelayFacts`: a connected relay
  covering the thread carries photos, or the connected relays carry messages only.

The alternative a reader reaches for first is a probe of our own — fetch something over the route and
call it dead if nothing comes back. The work item forbids it for the right reason: the platform's
captive-portal verdict is the platform's, the spool socket is an application socket to one host, and
the evidence the plane already holds (no hello ever came) is the whole answer. Binding the spool socket
to the validated `Network` was refused for the same reason `rules/mesh.md` gives the preview fetcher's
binding — it is the preview's concern, and the spool must ride whatever route the phone has. And the chat
hint deliberately reads the *connected* set rather than remembering a down relay's last advertised
capability: "your relays carry photos" about a relay that is not there is a promise, and the honest line
flips by itself when it is back.

**What it costs, and the traps.** A transport death now reads "Cannot be reached" for the one or two
seconds a clean reconnect takes, where it used to quote an exception name for the same seconds; the
chat header's 12 s grace still damps the glyph. The route nudge registers a connectivity callback for
the mesh session while a relay is in use — none with the plane off. Not covered: a route that dies
*after* the hello is caught by OkHttp's 25 s ping, and lands on `unreachable` through the same
`failureReason`, so it is late rather than wrong. The traps: `retireFault(NO_HELLO)` must run *before*
the `carriedFault == null` clear at the top of the ready block, or the stale verdict outlives its
condition; the `redial` token is drained on `ready`, or a nudge that landed during a handshake that then
succeeded fires an instant re-dial hours later; and the `NO_HELLO` guard reads `socket.closeReason == null`
as well as `isOpen`, because the dialer writes the reason before the pump's completion closes the
connection. Kept true by `OkHttpSpoolDialerTest`, `ScopeSyncTest` (the black-hole pair, the no-hello
round trip, the two-relay shape, the three route-nudge cases), `AndroidInternetGateTest`,
`RelayReachTest`'s `attachmentWait` pair, `InternetRelayViewModelTest`'s row mapping,
`InternetRelayScreenContentTest`, `ChatRelayIndicatorTest`'s wait-line quartet, and
`InternetPlaneLabTest.aRelayWhoseRouteSwallowsTheSocketIsUnreachableNotConnected` over the real plane.
