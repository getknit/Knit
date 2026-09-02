---
id: "2026-09.un9n"
slug: a-never-drawn-window-is-recovered-by-recreating-it
title: "A never-drawn window is recovered by recreating it"
date: 2026-09-01
topics: [ui, android, resilience, back]
---

# ADR 2026-09.un9n — A never-drawn window is recovered by recreating it

Status: Accepted (2026-09-01; `ui/WindowWedgePolicy` + `MainActivity.watchForUndrawnWindow`, and
`enableOnBackInvokedCallback` in `AndroidManifest.xml`)

A field tester reported Knit "frozen on a white screen" after swiping back out of a chat: reopening from
the launcher showed the same blank white, and tapping a Knit notification brought it back. No crash under
Diagnostics.

## What was observed, and what it was mistaken for

The natural first read is an **empty `NavHost`** — `NavHost` renders nothing at all when it has no visible
entry (`backStackEntry == null`), and one `popBackStack()` too many on the start destination empties the
stack without finishing the Activity. What shows through is `windowBackground`, which `themes.xml` sets to
`@color/splash_background` = **`#FFFFF8F6`**: near-white in light mode, exactly what was reported. That read
was wrong, and three separate lines of device evidence say so.

**The process never died.** `dumpsys activity exit-info` recorded nothing after a 12:55 install force-stop,
and pid 8524 was still up 3 h 53 m later. Not the `WifiAwareTransport` NAN-wedge self-kill, not the LMK, not
an ANR.

**The back stack was healthy the whole time.** `CoreBackPreview` logs every change to an app's registered
back callback, and on this build the registration tracks NavHost depth exactly: `mPriority=-1` (framework
default only) on the chat list, `mPriority=0, mIsAnimationCallback=true` one destination deeper. Through the
whole episode it toggled normally and backs committed as `backType=4` (TYPE_CALLBACK). Navigation was
working. So were touches, and so was composition — at 14:54:57.414/.425 pid 8524 **drew a Compose popup**
(window type 1002, `TYPE_APPLICATION_SUB_PANEL`), which is what the tester's taps on a "blank" screen were
opening.

**The main window never drew.** Across the whole incident there is not one `viewroot_draw_event` for
`VRI[MainActivity]`. A control on the same device, process and build emits `applyTransactionOnDraw` +
`reportDrawFinished` on **every** launch and every hide→show — 16 pairs in six minutes. The incident had six
launches and zero pairs. The one `VRI[MainActivity]` line in the window, at the final stop, names the cause:

```
14:55:12.963  Not drawing due to not visible. Reason=!mAppVisible && !mForceDecorViewVisibility
```

`ViewRootImpl.getHostVisibility()` returns `GONE` whenever `!mAppVisible`, so every traversal skipped the
draw for **94 seconds** while WindowManager kept that same window focused and dispatching input. A live,
fully interactive app that presents nothing.

The trigger was a task race. The tester made four back-to-home gestures — three cancelled
(`triggerBack=false`), one committed — and tapped the launcher **96 ms** after the close transition
finished, while the old task was still being torn down. AMS opened a *second* task for this `singleTask`
Activity: `Add Task{#12257} to hidden list because adding Task{#12260}`. The replacement Activity came up
with a window that was never made visible. Every later launcher tap re-resumed that same window
(`singleTask`, same `ActivityRecord`), which is precisely why reopening the app could not help.

## What changed

`MainActivity.watchForUndrawnWindow` polls every 500 ms **while RESUMED only** — `repeatOnLifecycle`
cancels the loop at ON_PAUSE — and hands three observations to [`WindowWedgePolicy`](../../../app/src/main/java/app/getknit/knit/ui/WindowWedgePolicy.kt):
resumed, `hasWindowFocus()`, and `decorView.windowVisibility == VISIBLE`. All three must say "wedged" for an
unbroken 2.5 s; then the Activity is recreated, which is the only thing that produces a new window.

**The loop guards are process-scoped, and that is the whole trick.** A 60 s cooldown and a ceiling of three
recreates live in `MainActivity`'s companion, not in the instance: what they guard against is the
*replacement* window wedging too, so an Activity field would be reset by the very recreate it is counting.

Alternatives, in the order a reader will reach for them:

- **A `Choreographer` frame callback** — the obvious probe, and it does not work. `postFrameCallback`
  schedules its own vsync and fires whether or not the window draws; this process was demonstrably running
  frames (it drew a popup). The watchdog would never have tripped. The signal has to be the flag
  `ViewRootImpl` actually gates drawing on.
- **`ViewTreeObserver.OnDrawListener`** — closer, but "no draw for N seconds" is also the normal state of a
  settled screen. It would need a periodic `invalidate()` probe to mean anything: more moving parts for a
  strictly weaker signal than reading the visibility directly.
- **Forcing visibility instead of recreating** — `decorView.visibility = VISIBLE`, `requestLayout()`,
  re-setting window attributes: all no-ops. `getHostVisibility()` ignores the view's own visibility while
  `!mAppVisible`, and `mAppVisible` is written only by the `ActivityThread` visibility callbacks. Nothing
  public reaches it.
- **Doing nothing and only reporting upstream.** The report is filed either way, but the failure leaves a
  tester with an app that looks dead and no way out of it except uninstalling.

**Also declared, from the same trace:** `android:enableOnBackInvokedCallback="true"`. The platform defaults
it on only for apps targeting 35+, *and* only on a framework that knows that rule — so undeclared, we ran
the predictive dispatcher on Android 15+ and the legacy `onBackPressed` path on 13/14: two back
implementations split by OS version, of which only one was ever tested. We override no `onBackPressed`, and
both Compose's `BackHandler` and Navigation's own handler ride the dispatcher either way.

Measured through `CoreBackPreview`, whose callback registration tracks NavHost depth one-for-one
(`mPriority=-1` at the chat list, `mPriority=0, mIsAnimationCallback=true` one destination deeper):

| | chat list | inside a chat |
|---|---|---|
| Android 14, undeclared (Moto G) | *no registration at all* | *no registration at all* |
| Android 14, declared (API 34 AVD) | `-1` | `0, anim=true` |
| Android 17, either way (Pixel 7) | `-1` | `0, anim=true` |

## What it costs, what it does not cover, and the trap

`recreate()` is user-visible and drops any state not held in `rememberSaveable` — an unsent draft, most
of all. It can only fire after the window has been invisible for 2.5 s, so the user cannot have been
typing into it, but that is an argument about likelihood, not a guarantee.

**The watchdog is unverified against a real wedge.** 25 scripted attempts across two Android 17 Pixels —
including the incident device — hit the AMS two-task race 16 times and drew correctly every time. The
missing ingredient is the cancelled-gesture prelude: `adb input` gestures always commit
(`triggerBack=true`), so the three aborted swipes cannot be synthesized. That the wedge *presents* as
`windowVisibility != VISIBLE` is read off AOSP — `getHostVisibility()` returns `GONE` exactly when
`!mAppVisible`, which is the reason string the platform logged — not off an observed firing.

What **is** verified is the half that can go wrong on every device rather than one in ten thousand: the
policy (`WindowWedgePolicyTest`, 13 cases over the episode clock, the cooldown and the ceiling), and
silence in ordinary use. The watchdog logged nothing across a Pixel 7 (Android 17) and an API 34 AVD
driven through launch, navigate in/out, back-to-home, relaunch, two rotations, the notification shade,
the app switcher, screen off/on, a backgrounded stretch, and an open Compose overflow menu — that last
one being the state that most resembles a wedge from the outside, since our own popup takes window focus.

Not covered: a wedge that survives three recreates (deliberately — flickering at the user forever is worse
than a blank screen); the first 2.5 s of any wedge; and the wedge itself, which is a platform bug we can
only paper over. It is filed upstream against Pixel 7 / `CP2A.260705.006`; the Pixel 9 on the August build
(`CP2A.260805.005`) has never shown it, so a platform update may retire this watchdog entirely.

The trap for the next person is the pair of `@Volatile` fields in the companion. They look like state that
obviously belongs on the instance, and moving them there restores the infinite recreate loop this ADR
exists to prevent. `WindowWedgePolicyTest`'s cooldown and ceiling cases pin the policy half; the comment on
the companion is what defends the placement.
