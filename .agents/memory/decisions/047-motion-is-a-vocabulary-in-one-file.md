---
id: "047"
slug: motion-is-a-vocabulary-in-one-file
title: "Motion is a vocabulary in one file, gated on the platform's own reduce-motion setting"
date: 2026-08-26
topics: [ui, a11y, motion]
---

# ADR 047 — Motion is a vocabulary in one file, gated on the platform's own reduce-motion setting

Knit shipped with three authored animations in the whole app (the typing indicator's knit-stitch sweep, the
chat-list skeleton's pulse, the reply-jump bubble highlight). Everything else changed between frames: a new
message appeared, a thread jumped up the list, ✓ became ✓✓ silently, the connection dot swapped colour, the
send button hard-cut between send / attach / spinner, and all eighteen navigation destinations used
navigation-compose's bare default. This ADR records how that was fixed and, more importantly, the two rules
that keep it from rotting.

**Call sites name a meaning, not a duration.** `ui/theme/Motion.kt` holds the whole vocabulary — `KnitMotion`
with `spatial`/`fastSpatial`/`effects`/`fastEffects` specs and `enterFade`/`enterPop`/`enterReveal` (plus
their exits), and `rememberPressScale`. Nothing anywhere else in the app writes a `tween`, an easing or a
duration. That is what stops the app from accumulating twelve slightly different fades.

**The specs are Material 3's own standard motion tokens, copied.** `MotionScheme` and
`MaterialTheme.motionScheme` are still `internal` in Material3 1.4.0 — the JVM symbols are public, which is
why `javap` says otherwise, but the Kotlin metadata is not — so they cannot be referenced yet. The damping
and stiffness constants in `Motion.kt` are `StandardMotionTokens` verbatim: **spatial** motion is slightly
underdamped (0.9) so a moving thing settles with a hint of follow-through, **effects** are critically damped
(1.0) so a fade or a colour change never overshoots into a value nobody asked for. The *standard* scheme, not
the expressive one, which is louder than this app wants. When `MotionScheme` goes public those four functions
are the only place that changes.

**Reduce-motion is the platform's setting, not ours.** `LocalReduceMotion` is provided by `KnitTheme` from
`Settings.Global.ANIMATOR_DURATION_SCALE` — what Android's accessibility **Remove animations** setting
zeroes — read the same permission-free way `ConnectionStatusRow` reads airplane mode, but observed with a
`ContentObserver` rather than sampled, because toggling that setting does not recreate the activity. Every
spec collapses to `snap()` and every transition to `None`. **There is deliberately no in-app toggle**: the
user has already told the system once, for every app, and a second switch in Settings would be a second
answer to the same question.

**Rule 1 — an infinite animation may only ever be composed transiently.** `rememberInfiniteTransition` on a
screen that has *settled* never lets Compose go idle, and both `SeededUiTest` (`createEmptyComposeRule`,
every `awaitTag`/`awaitText`) and the Robolectric `*ScreenContentTest`s (`createComposeRule`, default
`autoAdvance = true`) settle through Compose's idling resource — so it does not merely look wrong, it hangs
the suite until the 25 s timeout and reds the ATF audit for that screen. The two that exist get away with it
because the typing indicator only composes while someone is typing and the skeleton only while
`ChatListUiState.isLoading`. Finite animations are safe: the test clock advances them to completion. Nothing
in `.agents/` said this before, and it is the trap most likely to catch the next contributor.

**Rule 2 — when a hard swap becomes an `AnimatedContent`, hoist the `contentDescription` to the container.**
Mid-transition both the outgoing and incoming children are composed, so two labelled `Icon`s means the
delivery tick announces "Sent" and "Delivered over the Internet" at once — and on the send button, whose
`combinedClickable` merges descendants into the button node, it means the button announces two actions.
Putting the single description on the `AnimatedContent` and making the children decorative leaves exactly one
labelled node at every instant, with spoken output unchanged. This is strictly better than what was there.

Three smaller rulings worth keeping:

1. **The message list fades; it does not slide.** `Modifier.animateItem(placementSpec = null)` in the chat
   thread, because three `LaunchedEffect`s already drive `animateScrollToItem(0)` when a message or a typing
   peer arrives — a placement animation would slide rows one way while the scroll slid the viewport the
   other. The **chat list** is the opposite (`placementSpec = KnitMotion.spatial()`): `ChatListViewModel`
   sorts by `lastMessageAt`, so a thread receiving a message genuinely travels, and watching it move is the
   clearest signal in the app that something arrived.
2. **Bands that come and go use `animateContentSize` on the band, not `AnimatedVisibility` on the content.**
   The pinned relay/LoRa notices and the radio-warning banner all early-`return` when they have nothing to
   say, so an `AnimatedVisibility` would need a retained copy of the text to draw while collapsing. Animating
   the height of the `Column` they sit in gets the same "the list slides rather than jumps" result with no
   retained state and no write-during-composition.
3. **Press feedback scales the drawing, never the layout.** `rememberPressScale` returns a factor for
   `graphicsLayer` rather than a `Modifier`, so a pressed control keeps its declared touch target and the
   48dp minimum the ATF audit checks can't be quietly shrunk by a press.

Haptics arrived with the same change and needed no permission — Compose routes `LocalHapticFeedback` through
`View.performHapticFeedback`, not the `Vibrator` API. They are used only where the app had no other way to
confirm something: `Confirm` on send, `ToggleOn`/`ToggleOff` on a reaction chip (a tap, unlike a long-press,
gets nothing for free), and `GestureThresholdActivate` / `Reject` / `GestureEnd` on the voice recorder's
lock, cancel and stop — a raw `pointerInput`, so `combinedClickable`'s automatic long-press haptic never
applies. **Do not add a manual `LongPress` haptic** to the message bubble, the chat-list row or an
attachment: `combinedClickable`'s `hapticFeedbackEnabled` already defaults true, and a second one double-buzzes.

Deliberately not done: no shared-element transition between the chat list and the thread (fragile with a
ViewModel per route, and a much larger change than polish); no `Shapes` scale in the theme (a static design
decision, not motion); no `core-splashscreen` (it would touch the locked dependency set and the F-Droid
reproducible-build path); and no motion on the low-traffic settings screens, where it would buy nothing. The
whole change added **no dependency** — `androidx.compose.animation` was already on the classpath
transitively, so the lockfile is untouched.
