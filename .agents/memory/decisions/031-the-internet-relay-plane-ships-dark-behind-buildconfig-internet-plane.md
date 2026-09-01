---
id: "031"
slug: the-internet-relay-plane-ships-dark-behind-buildconfig-internet-plane
title: "The Internet-relay plane ships dark behind `BuildConfig.INTERNET_PLANE`, gated at `spoolEnabled` — not stripped"
date: 2026-08-22
topics: [spool, release, flags]
---

# ADR 031 — The Internet-relay plane ships dark behind `BuildConfig.INTERNET_PLANE`, gated at `spoolEnabled` — not stripped

**Date:** 2026-08-22 · **Status:** shipped

The spool plane is feature-complete through M6 (editor, group scopes, sealed attachments, defer policy)
but has not been introduced publicly, and the two-island device trials the roadmap still owes it are
outstanding. So shipped artifacts hide it rather than carry a half-announced feature: a new
`buildConfigField("boolean", "INTERNET_PLANE", …)` reads **true in debug, false in release/staging**, with
`-PinternetPlane=true|false` overriding either way. Both defaults live in `app/build.gradle.kts` source
(the `?:` fallbacks) rather than in `gradle.properties` or CI, so F-Droid's rebuild — which passes no
`-P` — resolves the same OFF and stays byte-identical.

The load-bearing choice is **where** it is gated: `SettingsStore.spoolEnabled` reads
`BuildConfig.INTERNET_PLANE && stored`, and every consumer of the plane's liveness already goes through
that one flow — `ScopeSync`'s url supplier (no socket), `MeshManager.mintGroupRootsIfDue` (no group-root
mints), and `RelayStatusRepository.facts`, from which `planeFor`/`reachFor`/`attachmentReach` derive the
header cloud, the per-chat relay notice and the "nearby only" attachment markers. One gate is therefore
total, and a future consumer cannot forget it. The visible entry points are hidden on the same flag:
`ProfileScreen`'s row (via a defaulted `showInternetRelays` parameter, so the hidden case stays testable)
and the `relays` route, which is **not registered** in the `NavHost` at all — a screen whose switch would
be inert is better absent than reachable. `seedDefaultSpools` also no-ops **without writing its seeded
marker**, so the shipped default lands on the first run of the build that un-hides the feature, which is
the first run where the user can see and remove it. `RelayStatusRepository.statuses` emits once and stops
instead of ticking every 5 s forever for a plane that holds no workers.

Deliberately **not** a code strip. R8 constant-folds the `if (INTERNET_PLANE)` branches, but `ScopeSync`
and the whole `mesh/spool/` tree stay in the APK because `MeshManager` still constructs them — the plane
is one flag flip from live, and the unit suite (which builds debug, so the flag is true) keeps exercising
the real thing rather than a disabled shell. The stored `spool_enabled` preference is read but never
cleared, so a device that opted in under a flag-on build keeps its choice. The `…debug.SPOOL` bridge is
unaffected: it lives in `src/debug`, where the flag is on.

The globe beside the ✓✓ needs no gate — it is a function of the persisted `MessageEntity.deliveredVia`,
which nothing sets while the plane is parked. Nor do the Diagnostics spool rows (empty `status()` when
`ScopeSync` holds no workers) or its spool metrics (rendered only above zero).
