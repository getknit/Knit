# Baseline profile

`app/src/main/baseline-prof.txt` is a committed list of classes and methods ART compiles ahead of time at
install, instead of interpreting-then-JIT-ing them on first run. It is the single biggest lever on
first-launch and first-navigation smoothness, and it costs the shipped app nothing but its own size.

## The shape of it, and why

**The release build consumes a plain text file and nothing else.** No Gradle plugin is applied to `:app`,
no dependency is added, and `app/gradle.lockfile` is untouched. AGP picks up `src/main/baseline-prof.txt`
on its own (verified on AGP 9.3.1: adding a rule moved `mergeReleaseArtProfile`'s output from 5507 to 5509
lines), merges it with the profiles the AndroidX AARs ship, hands the result to R8 — which rewrites the
rules through its own mapping — and packages `assets/dexopt/baseline.prof` + `.profm`.

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

Needs a connected device or emulator (API 29+; the Gradle-managed ones are fine — pin `ANDROID_SERIAL`,
and see `.agents/rules/devices.md` before pointing this at lab hardware).

```bash
./gradlew -Pknit.baselineProfile=true :baselineprofile:connectedNonMinifiedReleaseAndroidTest
```

Then copy the generated profile over the committed one and rebuild:

```bash
cp "baselineprofile/build/outputs/connected_android_test_additional_output/nonMinifiedRelease/connected/<AVD> - <api>/BaselineProfileGenerator_startupAndFirstConversation-baseline-prof.txt" \
   app/src/main/baseline-prof.txt
./gradlew :app:assembleRelease
```

Regenerate when the startup or chat path changes shape — not on every commit. A stale profile is not a
correctness problem, only a smaller win; a *churning* one is a large, unreviewable diff on every PR.
**Regenerate on the same AVD as last time** (`Pixel_10_Pro_XL`) — the device is most of the diff otherwise:
the same change collected on `Knit_Mesh_BT` moved 2500 lines where `Pixel_10_Pro_XL` moved 600.

## Two things that are easy to get wrong

**Collect unminified.** Profile rules name classes and methods in source form, and R8 rewrites them into
the shipped profile itself. Collecting against an already-obfuscated build would map the names twice and
produce a profile that matches nothing. That is what `nonMinifiedRelease` exists for — release-shaped
(same code, same `BuildConfig`) with `isMinifyEnabled = false`.

**Collect profileable, not debuggable.** A debuggable app is never compiled ahead of time, so ART's
profile for it does not describe how the shipped app runs. `isProfileable = true` opens the profile to the
shell and nothing else.

## What the profile deliberately does not cover

The journey in `BaselineProfileGenerator` is cold start → chat list → a thread → back. It is not a tour of
the app, and should not become one: a baseline profile buys ahead-of-time compilation for the code it
names, so naming everything dilutes the dex layout's locality and lengthens install. Settings, diagnostics,
the LoRa and relay screens and the verify flow are all reached deliberately, once, by a user who is already
committed — they are not what first impressions are made of.

The mesh transports are also thin here by construction: the run drives a real, un-seeded app on an emulator
with no peers, so `MeshService` starts but never completes a discovery. That is the right trade. Radio code
runs in a foreground service over seconds and minutes, where interpretation costs nothing a user can feel;
the profile's job is the sixteen milliseconds after a tap.
