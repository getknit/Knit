---
id: "2026-09.fq6b"
slug: overscroll-and-ripple-take-their-colour-from-the-theme
title: "Overscroll and ripple take their colour from the theme"
date: 2026-09-09
topics: [ui, theme]
---

# ADR 2026-09.fq6b — Overscroll and ripple take their colour from the theme

Status: Accepted (2026-09-09; `KnitTheme`, `FileAttachmentBubble`, `LinkPreviewCard`, `LocationCard`,
`Avatar`, `GroupAvatar`, `ColorSchemeTest`)

ADR 2026-09.m9h8 swept the app for colours that came from somewhere other than `KnitTheme` and found two
dozen unset `ColorScheme` roles falling through to Material's baseline purple. That sweep read the theme.
Two colours are not in the theme to be read: one is compiled into Compose Foundation, the other is
inherited from wherever a composable happens to sit. Both were left over.

## The overscroll glow was a constant, not a role

`AndroidOverscroll.android.kt` declares `DefaultGlowColor = Color(0xFF666666)` and `EdgeEffectWrapper`
hands it to `EdgeEffect.setColor`. Nothing reads `MaterialTheme`, and nothing reads the XML theme's
`android:colorEdgeEffect` either — the launch theme (`Theme.Knit`, parent `android:Theme.Material.Light`)
could not have changed it. Every list in the app glowed flat grey when it ran out of content.

`KnitTheme` now provides `LocalOverscrollFactory` from
`rememberPlatformOverscrollFactory(glowColor = colorScheme.primary)`, beside `LocalReduceMotion` and
`LocalKnitColors`. `colorScheme` is the value `KnitTheme` just computed, so the glow tracks dark mode and
the Material You switch without a second branch, and `KnitTheme`'s single production call site carries it
to all thirteen `LazyColumn`s, the eight scrolling `Column`s, the four `ModalBottomSheet`s, the previews
and the Robolectric screen tests at once. `primary` is the role the platform's own themes pointed
`colorEdgeEffect` at.

**This is only ever visible on API 29–30.** `EdgeEffectCompat.create` switches to the stretch effect at
API 31, and the stretch ignores `setColor` entirely. Those two releases are what minSdk 29 still carries;
every lab Pixel is API 36 and cannot show either the bug or the fix. *Rejected:* `LocalOverscrollConfiguration`,
which carries the same `glowColor` and is deprecated in Foundation 1.12.0.

**Deliberately not gated on `LocalReduceMotion`**, and that is an exception to ADR 047's rule that every
animation in the app collapses under the platform's "Remove animations" setting. Overscroll tracks the
finger 1:1 — it is direct-manipulation feedback, not a decorative transition — and the platform's own
`RecyclerView` keeps stretching at animator scale 0. Suppressing it would make Knit the odd app out on a
device whose owner asked for less motion, not more consistency.

## Ripple reads `LocalContentColor`, so a painted container has to set one

Material3's `ripple()` resolves an unspecified `RippleConfiguration.color` to `currentValueOf(LocalContentColor)`
(`Ripple.kt`, 1.4.0). Six places painted their own container with `Modifier.background(...)` and never
provided the matching `on*` role, so the ripple — and every `Text` that had not spelled a colour out —
used a role belonging to a surface that composable does not draw.

The three chat cards are the sharp case. `FileAttachmentBubble`, `LinkPreviewCard` and `LocationCard`
paint `surface`/`surfaceVariant` but render *inside* the message bubble's `Surface`, whose content colour
is `onPrimaryContainer` on an outgoing message. So the same card had one filename colour when you sent it
and another when you received it — visible in the repo already, since the incoming case inherits
`onSurfaceVariant` and matches. Each now wraps its container in
`CompositionLocalProvider(LocalContentColor provides <the matching role>)`, which fixes the ripple, the
unstyled text and the icon tints in one move and let five hand-written `color =`/`tint =` arguments go.
De-emphasis that was real — a description or a host line at `onSurfaceVariant` against `onSurface` — stays.

`Avatar` and `GroupAvatar` already knew their own content colour and passed the ambient
`LocalIndication.current` anyway; they now name it, `indication = ripple(color = …)`, which is what that
parameter is documented for.

*Rejected:* providing `LocalRippleConfiguration` once in `KnitTheme`. It is the obvious-looking move and
material3's own KDoc rules it out — "an escape hatch for individual components… not intended to be used
for full theme customization", for which the documented route is `createRippleModifierNode`. It would
also be strictly worse: one pinned colour app-wide, where `LocalContentColor` already tracks the scheme,
Material You included, once the containers are paired correctly.

## What keeps it true

`ColorSchemeTest.overscrollGlowComesFromTheThemeNotComposesGrey` parses `Theme.kt` and fails if the
`LocalOverscrollFactory` line goes missing or its `glowColor` stops being a role off the scheme. It is a
source guard for the same reason the two beside it are: the absence of a value is not visible on a
`ColorScheme` instance, and here the *presence* of one is not visible on any device the lab owns.

The ripple half has no automated guard. Ripple colour is not readable from the composition, and the ATF
contrast checks that would catch the text colours report `NOT_RUN` on the headless emulator because they
need real pixels. Verifying it means the a11y suite on a physical Pixel, plus eyes on a file, link and
location card in both an incoming and an **outgoing** bubble — outgoing is the case that was wrong.

## Verified on the Pixel 9, 2026-09-09

A before/after install on the Pixel 9 Pro XL (API 37, dark, brand palette), on an outgoing location card
in the `nearby` room, sampling glyph pixels:

| | "Location" label | coordinates | "Accurate to 7 m" | bubble body |
|---|---|---|---|---|
| before | `#FFDCD1` | `#F9E0D9` | `#BCA6A0` | `#FFDCD1` |
| after | `#F9E0D9` | `#F9E0D9` | `#BCA6A0` | `#FFDCD1` |

The label wore `onPrimaryContainer` — the *bubble's* colour, identical to the body text below the card —
while the coordinates directly beneath it wore `onSurface`, because `CoordinatesLine` had spelled that role
out by hand. After the change both are `onSurface` and nothing else moved. The screencap colours are shifted
by the Pixel's display pipeline (`primaryContainer` `#7E2D17` reads back `#764636`), so read them as a
mapping, not as the literal role values.

**The glow is still unverified.** The lab's Moto G has been reflashed to API 34, so no device in the lab is
old enough to draw a glow at all, and the API-30 AVD cannot boot while a VirtualBox VM holds VMX root.
The **ripple itself** is also unverified as a pixel: `screencap` will not hold a frame mid-animation. Both
follow from the same `LocalContentColor` the table above measures, but neither was seen.

A trap worth naming, because it produced a confident wrong answer first: under AGP 9.4 there is no
`app/build/outputs/apk/debug/`, and `app/build/intermediates/apk/debug/app-debug.apk` is a stale file
`packageDebug` never rewrites. Installing it twice gave two "different" builds with byte-identical card
pixels. Use `:app:installDebug`. `git stash push -- <pathspec>` also left `assembleDebug` reporting
UP-TO-DATE until `--no-watch-fs`, so check the compiled class, not the source, before believing an A/B.

The trap for the next person: a `CompositionLocalProvider` has to sit *outside* the composable carrying
the `clickable`, not inside its content lambda. `Surface(modifier = Modifier.combinedClickable(…))` looks
equivalent and is not — the clickable lands on the Surface's own node, which is outside the content colour
the Surface provides, so the ripple would still read the parent's. `ChatScreen` already carries a comment
about the neighbouring version of this trap with `FilledIconButton`.
