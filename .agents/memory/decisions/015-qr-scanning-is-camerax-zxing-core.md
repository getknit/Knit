---
id: "015"
slug: qr-scanning-is-camerax-zxing-core
title: "QR scanning is CameraX + zxing core, not zxing-android-embedded"
date: 2026-07-31
topics: [ui, contacts, camera]
---

# ADR 015 — QR scanning is CameraX + zxing core, not zxing-android-embedded

Status: Accepted (2026-07-31)

`com.journeyapps:zxing-android-embedded:4.3.0` hard-crashed the "Scan their code" flow for an F-Droid
reviewer ("Knit has stopped") on hardware the maintainer's Pixel 7/8/9 XL could not reproduce. Cause,
found by tracing the shipped 2.2.1 APK: the library runs its decode loop on a bare `HandlerThread` with
**no `try`/`catch` anywhere in the chain** —
`DecoderThread.handleMessage → decode → createSource → RawImageData.cropAndScale` — and crops the preview
buffer using the frame geometry the camera *reported* rather than what it *delivered*. Where those
disagree (ordinary camera-HAL variance) `System.arraycopy` throws `ArrayIndexOutOfBoundsException` off the
main thread and the process dies. Its one guard compares the Y-plane size against a 1.5x NV21 buffer, so a
mismatch slips through. R8, resource shrinking, the manifest merge and the permission declarations were all
cleared first — this was never a build-config problem.

It could not be patched in place: `BarcodeView.startDecoderThread()` is `private` and constructs
`DecoderThread` directly, so the only public seam (`DecoderFactory`) sits *downstream* of the throw. The
library was last released Feb 2021 and still drives the deprecated Camera1 API, which is where the device
variance comes from in the first place.

So we own the analyze loop: **CameraX** (`camera-core`/`camera2`/`lifecycle`/`view`) drives the camera and
**zxing core** — already a dependency, it renders the identity QR in `ui/image/QrCode.kt` — decodes.

- **`ui/scan/QrDecoder.kt` is deliberately Android-free**, so the arithmetic that broke the old library is
  a plain-JVM unit-test target (`QrDecoderTest` pins padded `rowStride`, `pixelStride > 1`, truncated
  buffers and degenerate geometry). Robolectric was avoided on purpose — it intermittently crashes Gradle
  9.5's test-result serialization in this suite.
- **`QrDecoder.decode` never throws.** Any frame — mis-strided, truncated, absurdly sized, or simply
  without a code — yields `null`. A camera frame must not be able to take the app down. Rotation handling
  is gone too: zxing finds QR finder patterns in any orientation, which deletes the rotate-and-crop stage
  that crashed.
- **The scanner is a composable, not an Activity or `Dialog`** (`ui/scan/QrScannerContent.kt`), rendered in
  place of the calling screen's content. Screens here take lambdas and `KnitApp` owns navigation; a camera
  `SurfaceView` in a `Dialog` window has z-ordering quirks on exactly the hardware this exists to support.
- **CameraX 1.6.1 is pinned against four constraints** — `minCompileSdk=36`, no upgrade pressure on the
  pinned lifecycle/core, 16 KB-page-aligned `.so`, and its own consumer R8 rules. Re-check all four before
  bumping; they are spelled out at the `cameraX` pin in `gradle/libs.versions.toml`.
- **Never add `camera-mlkit-vision`** — it pulls GMS, which this app does not ship.

The four-ABI `.so` CameraX adds ride the existing `keepDebugSymbols` no-strip opt-out, so ADR 014's
reproducibility contract is unaffected (verified: unstripped, timestamps normalized). Detail:
`context/distribution.md`.
