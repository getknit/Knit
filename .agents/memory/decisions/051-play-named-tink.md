---
id: "051"
slug: play-named-tink
title: "Play named Tink; the unbounded decode was ours, in the notifier, on peer-supplied bytes"
date: 2026-08-26
topics: [security, release, images]
---

# ADR 051 — Play named Tink; the unbounded decode was ours, in the notifier, on peer-supplied bytes

Status: Accepted (2026-08-26)

Play Console's app-quality scan flagged the shipped bundle for "using BitmapFactory without downsampling"
and named two locations:

```
com.google.crypto.tink.hybrid.HybridKeyTemplates.<clinit>
com.google.crypto.tink.internal.MutableSerializationRegistry.<clinit>
```

**Both names are wrong, and the way they are wrong is the durable lesson.** Tink is pure crypto and never
touches `android.graphics`. R8 full mode — more so since ADR 050 dropped the broad keeps — moves and merges
methods across classes, and `mapping.txt` records the move but a retracer walking it method-first lands
wherever the residual name collides. In the current build `zm4` retraces to
`androidx.room.RoomDatabaseKt__RoomDatabase_androidKt` at class level while *physically holding*
`IconCompat.toIcon`. Play's scanner walks that same lossy path. **Nothing in such a report is reproducible
from the names it prints; go to the dex.**

The recipe, which is the reusable part:

```bash
unzip -o -q app-release.aab 'base/dex/*.dex' -d /tmp/dex   # or classes*.dex from an APK
$ANDROID_SDK_ROOT/build-tools/*/dexdump -d /tmp/dex/**/*.dex > dump.txt
grep -n 'BitmapFactory;.decode' dump.txt                   # then walk back to the enclosing
                                                           # `Class descriptor` / `name` / `type`
```

Run against both the Aug-23 upload and the current build it gives the same four Options-less call sites and
no others: two in `MessageNotifier` (one source line, `bitmapFor`, inlined into two callers) and two in
androidx.core — `IconCompat.toIcon(Context)` on its `TYPE_URI`/`TYPE_URI_ADAPTIVE_BITMAP` branch, and
`ShortcutManagerCompat.pushDynamicShortcut` with the same method inlined.

**Ours was not a memory-hygiene nit.** `bitmapFor` decoded a peer's avatar — `PeerEntity.avatarHash`, a
profile learned off the mesh — with `BitmapFactory.decodeByteArray(bytes, 0, bytes.size)`. The blob's *byte*
size is bounded; its *pixel* count is not, and a ~30 kB solid-colour PNG at 12000² decodes to ~576 MB. Every
other decode in the app already went through `data/ImageDecode.kt`; the notifier was the one that slipped,
on the one path that runs unattended in the background. It now calls `decodeBoundedFromBytes(bytes,
AVATAR_PX)` — the same helper `ImageScreeningService` uses — which does the `inJustDecodeBounds` pre-pass, a
power-of-two `inSampleSize`, and an exact downscale to 256². Dropping EXIF with it is correct: `AvatarStore`
already stores oriented 256² JPEGs.

The same edit fixes a smaller waste: `buildAndPost` builds a `Person` per message in the
`NotificationHistory` (8) plus self, then `postSummary` posts again, so one notification decoded the same
JPEG about ten times. Decoded avatars are now memoized in a 2 MB `LruCache` keyed by
`bytes.contentHashCode()` — content-keyed, so a peer *changing* their avatar simply misses, and
`LruCache`'s own synchronization covers `bitmapFor` running outside the `states` lock.

**The androidx.core pair stays, at 2.** Knit only ever builds icons with
`IconCompat.createWithAdaptiveBitmap(bitmap)`, so the URI branch is dead for us — but it is reachable on
paper, R8 keeps it, and a static scanner counts it. Not worth patching a library over.

**Guarded at both levels, because they catch different things.** detekt gains `style > ForbiddenImport` on
`android.graphics.BitmapFactory`, excluding `data/ImageDecode.kt` — an *import* rule rather than
`ForbiddenMethodCall` because detekt runs here without type resolution. That covers our sources and nothing
else, and the risk this ADR is really about arrives through a dependency. So `build:release` also ends with
`scripts/dex-bitmap-gate.sh`, a sibling of ADR 050's `r8-dex-gate.sh`: it dexdumps the release APK, counts
every `BitmapFactory.decode*` whose descriptor lacks a `BitmapFactory$Options`, prints the containing class
and method, and fails above a budget of 2. Verified in both directions — 4 and FAIL before the fix, 2 and OK
after, with the two survivors carrying the `IconCompat` signature.
