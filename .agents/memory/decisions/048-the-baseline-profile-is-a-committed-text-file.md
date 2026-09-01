---
id: "048"
slug: the-baseline-profile-is-a-committed-text-file
title: "The baseline profile is a committed text file; everything that produces it is quarantined behind a flag"
date: 2026-08-26
topics: [build, release, perf]
---

# ADR 048 — The baseline profile is a committed text file; everything that produces it is quarantined behind a flag

Knit shipped with no baseline profile of its own. The release APK carried 5,507 rules — every one of them
merged out of the AndroidX AARs, which ship profiles for their own code — and **zero** for Knit's. So on a
fresh install ART interpreted `KnitApplication.onCreate`, Koin's graph, the SQLCipher open and every
composable on the chat path until the JIT caught up, which is exactly the window a user forms their opinion
in. Now: 36,066 merged rules, 4,572 of them Knit's own, and `dumpsys package dexopt` reports
`[status=speed-profile] [reason=install-dm]` — ahead-of-time compiled at install from the shipped profile.

**The release build consumes `app/src/main/baseline-prof.txt` and nothing else.** No Gradle plugin on
`:app`, no dependency, and `app/gradle.lockfile` is byte-unchanged. AGP picks that path up unaided —
verified on AGP 9.3.1 by adding one rule and watching `mergeReleaseArtProfile` go from 5507 to 5509 lines —
merges it with the library profiles, lets R8 rewrite the names through its own mapping, and packages
`assets/dexopt/baseline.prof`.

That shape is forced by ADR-level constraints, not chosen for tidiness. `.agents/context/distribution.md`'s
contract is that F-Droid rebuilds the tagged commit and byte-compares against our APK, so the release build
must not be a function of the build machine. **Generating a profile during the build would need a connected
device and would differ per run.** A committed text file is an input like any other source file. Verified:
two clean `assembleRelease` runs produce an identical APK
(`24a39291f2ad9a17a143f42ba9279c27bcd0b35cf01166323cb5d3c3843cd91a`), and `./gradlew projects` on a default
invocation lists only `:app`.

Everything needed to *produce* the profile is therefore off the default build entirely:

- `:baselineprofile` (a `com.android.test` module driving macrobenchmark's `BaselineProfileRule`) is
  included by `settings.gradle.kts` only under `-Pknit.baselineProfile=true`.
- `:app`'s `nonMinifiedRelease` build type is created under the same flag — which is also why the lockfile
  does not move: a new build type would otherwise add `nonMinifiedRelease*` configurations to every one of
  its ~600 lines, and `dependencyLocking` here is not in strict mode, so a flag-only variant resolves
  without lock state.
- `androidx.benchmark` is pinned in the catalog but reaches nothing that ships, which is why an `-rc` is
  acceptable there and would not be on a shipping path.

Two collection rules that are easy to get backwards, both encoded in the build type:

1. **Unminified.** Profile rules name classes and methods in source form and R8 rewrites them on the way
   into the APK, so collecting against an obfuscated build maps the names twice and matches nothing.
2. **Profileable, not debuggable.** A debuggable app is never AOT-compiled, so ART's profile for it does not
   describe how the shipped app runs. `isProfileable = true` opens the profile to the shell and nothing more.

**The journey is deliberately short** — cold start, chat list, into a thread, back — and must stay that way.
A baseline profile buys AOT compilation for the code it names, so naming everything dilutes dex locality and
lengthens install for no gain. Settings, diagnostics, LoRa, relays and verify are reached once by a user who
has already decided; they are not what a first impression is made of. The mesh transports are thin for a
different reason: the run drives a real, un-seeded app with no peers, so `MeshService` starts but never
completes discovery. That is the right trade — radio code runs over seconds in a foreground service, where
interpretation costs nothing anyone can feel, and the profile's job is the sixteen milliseconds after a tap.

The profile is generated against the **un-seeded** app on purpose. The demo seams live in `src/debug`, so a
seeded run would put classes in the profile that the shipped APK does not contain; `nonMinifiedRelease`
takes `src/release/java`'s no-op `DemoWiring` exactly as `staging` does, and those three stub methods are
the only `Demo*` entries in the committed profile — they ship.

Cost: **+253 KB on a 73 MB APK** (0.35%), most of it R8 laying dex out differently rather than the 4 KB the
profile itself grew. Regenerate when the startup or chat path changes shape, not per commit: a stale profile
is a smaller win, never a correctness problem, while a churning one is a large unreviewable diff every time.
Deliberately not done: `src/main/startup-prof.txt` (dex-layout reordering) — a real further win, but a
second thing to prove against the byte-comparison, and worth its own change. How-to lives in
`.agents/context/baseline-profile.md`.
