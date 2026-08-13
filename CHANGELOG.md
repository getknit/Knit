---
changelog: "0.1"
product:
  name: Knit
  vendor: Knit
  homepage: https://getknit.app
  id: knit
  description: Offline, serverless, end-to-end-encrypted mesh messenger for Android.
  platforms: [android]
  category: Communication
document:
  updated: 2026-08-13T02:38:41Z
  coverage: partial
  canonical: https://github.com/getknit/knit/blob/main/CHANGELOG.md
  locale: en
  older: https://github.com/getknit/knit/releases/tag/v2.1.0
---

# Knit changelog

## [2.2.3](https://github.com/getknit/knit/releases/tag/v2.2.3) — 2026-08-13T02:38:41Z

> A themed launcher icon, and a launch screen that follows the system light/dark theme.

### Added

- The launcher icon supplies a monochrome layer, so on Android 13 and newer it takes part in themed-icon
  colour schemes instead of falling back to a generic shape.

### Fixed

- Starting Knit on a dark-themed device no longer flashes a near-white screen before the app draws. The
  launch window and splash background now track the system theme, in lockstep with the in-app colours.

## [2.2.2](https://github.com/getknit/knit/releases/tag/v2.2.2) — 2026-07-31T22:22:03Z

> Fixes a crash in "Scan their code".

### Fixed

- "Scan their code" no longer crashes when the camera opens. The scanner has been rebuilt on CameraX;
  the library it used before could crash the app on devices whose camera reports one frame size and
  delivers another, which is why it worked on some phones and not others. Decoding now treats any
  unexpected frame as a frame without a code in it.
- Declining the camera permission, or running on a device with no camera, now shows an explanation
  instead of a blank screen. Showing your own code still works either way.

## [2.2.1](https://github.com/getknit/knit/releases/tag/v2.2.1) — 2026-07-23T03:08:44Z (routine)

> The first release available on F-Droid. A build-only change with no code or feature differences
> from 2.2.0.

The release APK no longer embeds Android's "dependency metadata" signing block, which F-Droid does
not permit and which was not reproducible. The app behaves identically to 2.2.0; upgrading matters
only if you want the build F-Droid distributes.

## [2.2.0](https://github.com/getknit/knit/releases/tag/v2.2.0) — 2026-07-22T22:20:29Z

> The first release published on F-Droid, and the first built reproducibly: F-Droid rebuilds Knit
> from source, byte-compares its result against ours, and ships ours.

### Added

- The Support Knit screen offers Liberapay alongside Ko-fi.

### Changed

- Release builds are reproducible. F-Droid rebuilds this release from source and byte-compares it
  against the APK on Knit's GitHub Releases page, then distributes ours — so one signed APK serves
  F-Droid, a direct download, and Knit's own offline "share the app" feature alike, and a phone
  handed Knit over the mesh can still take updates normally.

## About this file

This heading does not match the release-heading grammar, so a consumer skips it, the same way it
skips `## Unreleased` above.

This changelog follows the provisional changelog standard drafted at
[whatsnew.fyi](https://whatsnew.fyi/product/knit) — YAML frontmatter, one `##` heading per release
newest first, and [Keep a Changelog](https://keepachangelog.com)'s six categories. Releases before
2.2.0 are on the [releases page](https://github.com/getknit/knit/releases), which is what
`document.older` points at.
