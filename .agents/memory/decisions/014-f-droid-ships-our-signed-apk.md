---
id: "014"
slug: f-droid-ships-our-signed-apk
title: "F-Droid ships *our* signed APK (reproducible `Binaries:`), not an F-Droid-signed rebuild"
date: 2026-07-21
topics: [release, fdroid, reproducible-builds]
---

# ADR 014 — F-Droid ships *our* signed APK (reproducible `Binaries:`), not an F-Droid-signed rebuild

Status: Accepted (2026-07-21)

F-Droid offers two publishing models: it builds from source and signs with **its own** key, or — with
`Binaries:` + `AllowedAPKSigningKeys` — it rebuilds from source, byte-compares against the APK we publish,
and on a match distributes **ours**. We take the second, which Meshtastic (`metadata/com.geeksville.mesh.yml`)
also uses.

The deciding argument is Knit-specific, not ideological. `ui/invite/ShareApk.kt` hands a nearby phone this
app's own APK over Quick Share/Bluetooth — offline app distribution is a *product feature*. Under
F-Droid-signed builds the F-Droid APK would carry a different certificate from the one we hand out, so a
peer who received Knit over the mesh could never take an in-place update from F-Droid. One
distribution-key-signed APK serving GitHub Releases, F-Droid, sideload, and mesh-share keeps that coherent.
It is also a **one-way door**: publishing an F-Droid-signed APK first and switching later would break every
existing install. Play is unavoidably separate — Play App Signing holds a key we don't have.

Consequences, all load-bearing (detail: `context/distribution.md`):

- **The release APK must not depend on the build machine.** Native-symbol extraction (`ndkVersion` +
  `debugSymbolLevel`) is gated behind `-Pknit.nativeSymbols=true` — the Play AAB path only — because AGP's
  strip step degrades *silently* without an NDK. `packaging { jniLibs { keepDebugSymbols } }` opts out of
  stripping explicitly instead. Measured cost: **+8 bytes**; the shipped `.so` were already stripped
  upstream, so the strip was always a no-op. Verified: `clean` + `assembleRelease` reproduces an identical
  sha256 across full rebuilds.
- **No VCS stamping.** `vcsInfo { include = false }` on release. AGP otherwise embeds the builder's HEAD
  revision in `META-INF/version-control-info.textproto`. Verified empirically: rebuilding this commit inside
  F-Droid's actual buildserver container reproduced the APK byte-for-byte *except* this single entry (1 of
  185, same total size) — it was the whole delta.
- **No JDK auto-provisioning.** The `foojay-resolver-convention` plugin is gone from `settings.gradle.kts`
  and the `toolchainUrl.*` lines are stripped from `gradle/gradle-daemon-jvm.properties`; both fetch an
  unpinned compiler over the network. Gradle now fails loudly and the recipe installs JDK 21 via `sudo:`.
- **Git LFS is banned.** F-Droid's buildserver has no LFS support (fdroidserver#1190), and the moderators
  degrade to allow-all on an unreadable model — LFS would ship a silently unmoderated app. `*.tflite` are
  plain blobs, and `checkModerationModels` hard-fails on a pointer stub.
- **A Gradle property, never a product flavor.** Adding a flavor would move Room's exported schema off the
  flat `app/schemas/<db>/<version>.json` path that `KnitDatabaseMigrationTest` reads (`context/toolchain.md`).
- **The tag must equal `v<versionName>`** — fdroiddata expands `%v` to versionName in both `Binaries:` and
  `AutoUpdateMode`. Hence 2.2.0, not the 2.1 / `v2.1.0` mismatch shipped at 2.1.0.
