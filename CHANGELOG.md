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

## Unreleased

> Forward secrecy for direct messages and group chats, encrypted delivery receipts and reactions, and
> optional Internet relays for when nobody is in radio range.

### Added

- Direct messages between updated phones are now forward-secret. Each conversation derives its own key
  material from a prekey published in your profile, then re-keys as the conversation goes back and
  forth, and drops the old keys. Someone who records traffic today and gets hold of a phone later
  cannot read the earlier messages.
- Group chats gain the same property. Every member drives their own key chain and hands its current
  seed to the others inside encrypted direct messages. A group's info screen says whether the group is
  running the new scheme or still waiting on somebody to update.
- Delivery receipts and reactions are encrypted. Until now they crossed the mesh readable by any phone
  relaying them, so an onlooker could tell when a message reached you, and who reacted to which
  message with what. They now travel sealed and look like ordinary messages on the wire.
- Knit can now use Internet relays, which you turn on yourself. Knit remains a messenger that works
  with no internet and no account, but a message to somebody out of radio range used to wait for the
  two phones, or a chain of phones between them, to meet. With relays on, Knit parks a sealed copy on
  a relay server, which hands it over the next time the other phone connects. A one-time explanation
  spells out what a relay can see — your IP address, when you send, and roughly how much — and what it
  cannot: your messages or photos, who you are talking to, or who else is in a group.
- A Settings screen for relays lists the ones you use, adds and removes them, and reports each one as
  connected, still connecting, or why it refused. Knit ships with one relay already listed and unused;
  it stays inert until you turn the feature on, and removing it sticks.
- Group chats travel over relays as well as direct messages, without anyone learning from the relay
  that a group exists or who is in it.
- Photos travel over relays too, in sealed pieces. A photo waits while a phone that can carry it is
  still in radio range, so an ordinary in-person exchange never touches a relay. Where a relay cannot
  take it — no photo support, or the photo is larger than any relay in your list will hold — the
  message says "Nearby only" rather than reporting a failure: it still arrives over Wi-Fi or Bluetooth
  as it always did.
- Changes to your name, status, or photo now reach people you have only ever met across a relay. They
  previously spread by radio alone, so such a contact kept whatever it saw when you were last in
  range.
- A conversation says how it is being carried. A globe appears beside the double tick when the other
  phone answered over the internet, and beside a message that arrived that way; the connection header
  says when the radios are dark and a relay is carrying the conversation. The Nearby room is exempt by
  design — it is public and unencrypted, so it is never uploaded, and says so.
- Diagnostics gains an Internet relays section: what has been sent and received over them, photo
  pieces moved, photos left to the radios, and any errors a relay reported.

### Changed

- Chats with a phone on an older version keep working, on the previous encryption scheme. A group
  falls back the same way while any one member has not updated, and moves over on its own once
  everyone has.
- The Profile entry in the overflow menu is now called Settings, since the screen also holds settings
  that are not about you, such as content filtering and the relay switch.

### Fixed

- A relay you have configured can no longer flood the app's memory. Knit now holds only what it
  actually asked a relay for, ignores anything else the relay volunteers, and treats the relay's
  stated size limits as a claim to be capped rather than a fact. Relays were always untrusted with
  the contents of your messages; now they are untrusted with how much of your phone they can use.

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
