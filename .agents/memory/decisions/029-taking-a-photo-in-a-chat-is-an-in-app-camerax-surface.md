---
id: "029"
slug: taking-a-photo-in-a-chat-is-an-in-app-camerax-surface
title: "Taking a photo in a chat is an in-app CameraX surface, entered by long-press, ingested in memory"
date: 2026-08-20
topics: [ui, attachments, camera]
---

# ADR 029 — Taking a photo in a chat is an in-app CameraX surface, entered by long-press, ingested in memory

Status: Accepted (2026-08-20)

Sending a photo of what is in front of you meant leaving Knit — camera app, back, attach, find it in the
picker. Issue #6 proposed fixing that in two stages: `ActivityResultContracts.TakePicture()` first, an
in-app viewfinder later. We built only the viewfinder.

**No `TakePicture` intent, and therefore no FileProvider change.** The intent contract needs a Uri the
*camera app writes to*, and `res/xml/file_paths.xml` today exposes only outbound staging directories
(`apk/`, `crash/`). Adding an inbound-writable path is a new exposure class, permanently, in exchange for
a flow that still hands the screen to another app — most of what the issue complained about. The photo
picker remains the fallback when the camera can't open, so nothing is left without a route.

**The bytes never reach disk.** `ImageCapture.takePicture(executor, OnImageCapturedCallback)` yields an
in-memory JPEG, which goes straight to `AttachmentStore.ingest(bytes, mime)`. Staging a plaintext JPEG in
`cacheDir` would have broken the invariant in `AttachmentStore`'s KDoc — attachment bytes live in the
encrypted blob store only — for the window between shutter and ingest, and would have left a photo behind
on a process death mid-flight. This is what the byte-source overloads of `decodeOrientedBounded` and
`ingest` exist for; note `decodeBoundedFromBytes` is *not* a substitute, since it skips EXIF orientation
(it only feeds the classifier) and would store every photo sideways.

**In-place composable, per ADR 015.** `ui/camera/PhotoCaptureContent.kt` renders in place of the chat's
content, exactly as `QrScanner` does, for the reasons recorded there. The hardware probe, the `CAMERA`
permission state machine and the non-camera messages moved to `ui/camera/CameraSupport.kt` (`CameraGate`
/ `CameraMessage`) and are now shared by both surfaces rather than duplicated. Consequence, inherited
from the scanner: with no nav route, `demo_route` cannot deep-link it, so the seeded UI, UIAutomator and
ATF suites cannot reach it. The composer button itself *is* ATF-covered, since the chat routes are.

**Long-press the attach button, and only in attach mode.** The composer has no dedicated attach button —
one trailing button morphs between Attach and Send — so a camera action had to either take layout space
or hide in a gesture, and we chose the gesture. Long-pressing *Send* does not open a camera: it would be
surprising and could interrupt the send it appears to trigger. TalkBack parity comes from
`onLongClickLabel`, which names the camera in the actions menu; sighted discoverability is the accepted
cost of this choice.

**That button is now a `Surface` + `combinedClickable`, not a `FilledIconButton`.** `FilledIconButton`
wraps `Surface(onClick = …)`, whose own `clickable` sits *inside* whatever modifier the caller passes and
consumes the gesture — an outer long-press never fires. The colours and shape are `FilledIconButton`'s
defaults, so it is visually unchanged. A long press in Send mode still resolves to an ordinary click on
release, exactly as before.

**A failed capture speaks up; a failed pick still doesn't.** `IngestResult.Failed` has always been
swallowed silently, which is fine when the image is still sitting in the picker. A photo that was just
taken exists nowhere else, so `attachCaptured` surfaces a toast where `attach` stays quiet.

Nothing changed on the wire, in custody, or in the crypto envelope: once ingested, a captured photo is an
ordinary image blob. No new dependency either — `ImageCapture` is in the already-locked `camera-core`
1.6.1 that ADR 015 pinned, so `app/gradle.lockfile` is untouched.
