# Baseline profile

Two committed text files, produced by one journey, consumed by two different parts of the build:

| file | consumer | what it buys |
| --- | --- | --- |
| `app/src/main/baseline-prof.txt` | `mergeReleaseArtProfile` → the ART profile in the APK | ART compiles those methods **ahead of time** at install instead of interpreting-then-JIT-ing them |
| `app/src/main/baselineProfiles/startup-prof.txt` | `mergeReleaseStartupProfile` → R8 | R8 **reorders dex** so startup classes sit together, for page locality |

Between them they are the single biggest lever on first-launch and first-navigation smoothness, and they
cost the shipped app nothing but their own size.

**Mind the two paths — they are not symmetrical, and getting it wrong fails silently.** The baseline
profile is a flat file in `src/main`; the startup profile lives in the `src/main/baselineProfiles/`
*directory*. A `startup-prof.txt` placed next to `baseline-prof.txt` is simply never read: the build stays
UP-TO-DATE, the APK is byte-identical, and nothing warns you. Verified on AGP 9.3.2 by doing exactly that.

The two never mix. 503 rules exist in the startup profile that are absent from the baseline one, yet
`merged_art_profile` is exactly `baseline-prof.txt` + the AAR profiles (36,904 + 5,287 = 42,191 lines) —
the startup file reaches R8's layout pass and nothing else.

## The shape of it, and why

**The release build consumes two plain text files and nothing else.** No Gradle plugin is applied to
`:app` — the `androidx.baselineprofile` plugin is deliberately *not* used — no dependency is added, and
`app/gradle.lockfile` is untouched. AGP picks both files up on its own: `mergeReleaseArtProfile` takes
`src/main/baseline-prof.txt` (verified on AGP 9.3.1: adding a rule moved its output from 5507 to 5509
lines), merges it with the profiles the AndroidX AARs ship, hands the result to R8 — which rewrites the
rules through its own mapping — and packages `assets/dexopt/baseline.prof` + `.profm`.
`mergeReleaseStartupProfile` takes `src/main/baselineProfiles/startup-prof.txt` and feeds R8's dex layout.

That shape is chosen for `.agents/context/distribution.md`'s reproducibility contract. F-Droid rebuilds the
tagged commit and byte-compares against our APK, so the release build must not be a function of the build
machine. A committed text file is an input like any source file. **Generating the profile as part of the
build would not be** — it would need a connected device, and the output would differ per run.

Everything needed to *produce* the file is therefore quarantined:

- `:baselineprofile` is included in the build only under `-Pknit.baselineProfile=true`
  (`settings.gradle.kts`), so an ordinary build never configures it and never resolves
  `androidx.benchmark`.
- `:app`'s `nonMinifiedRelease` build type is created under the same flag, so the default build resolves
  exactly the configurations the lockfile records.
- `androidx.benchmark` is pinned in `libs.versions.toml` but reaches nothing that ships.

F-Droid's buildserver passes no `-P` flags, so it sees a three-build-type app with a text file in
`src/main` — which is the whole point.

## Regenerating it

Needs a connected device or emulator (API 29+; pin `ANDROID_SERIAL`, and see `.agents/rules/devices.md`
before pointing this at lab hardware).

**Collect on `Knit_Mesh_BT` with the BLE dongle passed through**, brought up by
`scripts/emulator-ble-mesh.sh up` (see *Real BLE from an emulator* in `context/testing.md`). It listens on
`emulator-5580`:

```bash
scripts/emulator-ble-mesh.sh up
ANDROID_SERIAL=emulator-5580 \
  ./gradlew -Pknit.baselineProfile=true :baselineprofile:connectedNonMinifiedReleaseAndroidTest
```

That runs **both** tests — `startupAndFirstConversation` and `startupProfileForDexLayout` — over the same
journey, which is why it takes ~20 minutes. `includeInStartupProfile` is a **flavour switch, not an
additive flag**: one collection emits `-baseline-prof.txt` *or* `-startup-prof.txt`, never both, and names
the file after the test method. Two methods is what gets two files.

Then copy both over the committed ones and rebuild:

```bash
OUT="baselineprofile/build/outputs/connected_android_test_additional_output/nonMinifiedRelease/connected/Knit_Mesh_BT(AVD) - 16"
cp "$OUT/BaselineProfileGenerator_startupAndFirstConversation-baseline-prof.txt" app/src/main/baseline-prof.txt
cp "$OUT/BaselineProfileGenerator_startupProfileForDexLayout-startup-prof.txt"   app/src/main/baselineProfiles/startup-prof.txt
./gradlew :app:assembleRelease
```

Regenerate when the startup or chat path changes shape — not on every commit. A stale profile is not a
correctness problem, only a smaller win; a *churning* one is a large, unreviewable diff on every PR.
**Regenerate on the same AVD as last time**, because the device is most of the diff otherwise: collecting
the same change on `Knit_Mesh_BT` once moved 2500 lines where `Pixel_10_Pro_XL` moved 600. That is an
argument for consistency, not for a particular device — the profile moved to `Knit_Mesh_BT` on 2026-09-07
and stays there. Rules are symbolic, so the x86_64 host ABI costs nothing on arm64.

## Two things that are easy to get wrong

**Collect unminified.** Profile rules name classes and methods in source form, and R8 rewrites them into
the shipped profile itself. Collecting against an already-obfuscated build would map the names twice and
produce a profile that matches nothing. That is what `nonMinifiedRelease` exists for — release-shaped
(same code, same `BuildConfig`) with `isMinifyEnabled = false`.

**Collect profileable, not debuggable.** A debuggable app is never compiled ahead of time, so ART's
profile for it does not describe how the shipped app runs. `isProfileable = true` opens the profile to the
shell and nothing else.

**The startup profile changes dex layout, so it had to clear the reproducibility contract** before it could
ship (`context/distribution.md`). Verified 2026-09-07 on AGP 9.3.2: adding it moved the release APK
(`7a4791eb…` → `172e2258…`, R8 re-ran), a full `clean` rebuild reproduced that byte-for-byte, and so did a
rebuild inside `registry.gitlab.com/fdroid/fdroidserver:buildserver` — different JDK, F-Droid's own Gradle
shim fetching 9.7.1, fresh Maven downloads, no `.git` present. R8's layout pass is a deterministic function
of the committed profile, exactly like the baseline half.

## What the profile deliberately does not cover

The journey is cold start → chat list → a thread → **send a message** → scroll → back. It is not a tour of
the app, and should not become one: a baseline profile buys ahead-of-time compilation for the code it
names, so naming everything dilutes the dex layout's locality and lengthens install. Settings, diagnostics,
the LoRa and relay screens and the verify flow are all reached deliberately, once, by a user who is already
committed — they are not what first impressions are made of. The startup profile makes that discipline
load-bearing rather than tasteful: marking a tour of the app as "startup" tells R8 nothing about what to
put next to what.

**Narrow in screens, deep in content.** The room is empty on a fresh install, so the journey used to
profile `EmptyState` and `BubbleSkeleton` and never once compile the code that draws a message —
`MessageBubble`, `timeLabel`, `relativeTime` and the emoji path were all absent from the shipped profile,
which was ahead-of-time compiling the *loading shimmer* of a conversation nobody was having. It therefore
sends before it reads, and scrolls what comes back: item composition is only half of what a list costs, and
the recycle-and-rebind half never runs unless something scrolls. That is also where the profile's few
genuinely *hot* (`H`) rules come from — an empty room produces almost none.

Sending rather than seeding is forced, twice over. `-PseedDemo=true` cannot reach this variant at all:
`release` (and so `nonMinifiedRelease`) hard-codes `SEED_DEMO=false` and the seeder lives only in
`src/debug`, with `src/release` supplying a no-op `seedDemoIfEnabled`. And even if it could, `SEED_DEMO`
gates real startup branches — `KnitApp`'s onboarding check, `BootReceiver`, `ReviewPrompter` — so a seeded
run would faithfully profile a path the shipped app never takes, which is the same error as collecting
against a minified build.

What the journey still does not create: file attachments, link-preview cards and replies. Those bubble
kinds are absent from the profile by construction, and adding them means deciding they are first-impression
code, which they are not — a first-session user has not been sent a file.

The mesh transports are covered only as far as **bring-up**. On `Knit_Mesh_BT` the radio is real, so the
run does start the advertiser, open the L2CAP responder, and scan and parse advertisements off the air —
`BleAdvertPayload$Parsed`, `BleScanner` and `BlePresenceTracker$Sighting` are all in the profile because
they genuinely ran. What never happens is a *link*: the journey is over in seconds, long before a peer is
connected, so nothing past discovery is named. Wi-Fi Aware is absent entirely — there is no NAN in an
emulator, so the node is BLE-only.

That is the right trade rather than a gap to close. Radio code runs in a foreground service over seconds
and minutes, where interpretation costs nothing a user can feel; the profile's job is the sixteen
milliseconds after a tap. The bring-up path is worth having because it is on the cold-start critical path —
the rest of the transport is not.
