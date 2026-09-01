---
id: "2026-09.zu5t"
slug: content-capture-is-off
title: "Content capture is off"
date: 2026-09-01
topics: [privacy, ui, performance]
---

# ADR 2026-09.zu5t — Content capture is off

Status: Accepted (2026-09-01; `MainActivity.onCreate`)

Android's content capture streams what is on screen — every text and content description Compose reports —
to the platform's on-device intelligence service so it can offer "app content" features. Knit is an
offline, end-to-end-encrypted messenger: nothing it shows should leave the process for another service's
convenience, and until now it did, by default, from every screen.

It was found as a performance cost first. Profiling the emoji picker on a Pixel 9 (debug build) showed
`ContentCapture:changeChecker` and its semantics walk at ~260 ms of UI-thread time per fling over a
120-cell grid — the largest single avoidable item once the grid itself was fixed — because Compose re-walks
every on-screen semantics node whenever the tree changes.

## What changed

`MainActivity.onCreate` flips two switches. The platform one,
`ContentCaptureManager.setContentCaptureEnabled(false)`, is the documented way for an app to opt out and
silences the events. It does **not** stop the work: Compose keeps its own manager and still walks the tree
(measured unchanged with only that flag). `androidx.compose.ui.contentcapture.ContentCaptureManager.isEnabled = false`
— Compose's own, `@ExperimentalComposeUiApi`, documented as the kill switch for the feature — is what removes
the traversal (0 content-capture slices in the trace afterwards, janky frames per fling 7 → 2–4).

## What it costs

The experimental flag may move between Compose versions; it is one line, opted in explicitly, and a Compose
bump that removes it fails to compile rather than silently re-enabling capture. Accessibility is untouched:
TalkBack uses the accessibility delegate, not content capture, and every semantics node stays. What users
lose is the platform's "app content" suggestions drawn from Knit's screens, which is the point.
