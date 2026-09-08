---
id: "2026-09.qgk4"
slug: the-startup-profile-ships-beside-the-baseline-one
title: "The startup profile ships beside the baseline one, from the same journey"
date: 2026-09-07
topics: [performance, build, distribution]
---

# ADR 2026-09.qgk4 — The startup profile ships beside the baseline one, from the same journey

Status: Accepted (2026-09-07)

**What was observed.** The shipped baseline profile was ahead-of-time compiling the wrong half of the chat
screen. Its `ChatScreen` rules were `EmptyState`, `ChatSkeleton`, `BubbleSkeleton` and `MessageInput` — the
placeholder and the loading shimmer — with **zero** rules for `MessageBubble`, `timeLabel`, `relativeTime`
or the emoji path. The journey opened the Nearby room on a fresh install, the room was empty, so the code
that draws a message never ran and never got compiled. It also produced almost no *hot* (`H`) rules at all,
because nothing scrolled.

**What changed.** The journey now sends a message through the ordinary composer and flings the list before
backing out. Baseline went 32,989 → 36,904 lines (6,196 → 7,119 Knit rules) and gained 533 `HSP` + 13 `HP`
rules; `MessageBubble` and the timestamp path are in it for the first time.

Seeding was the obvious route and it is closed, twice over. `-PseedDemo=true` cannot reach this variant —
`release` (and so `nonMinifiedRelease`) hard-codes `SEED_DEMO=false`, the seeder lives only in `src/debug`,
and `src/release` supplies a no-op `seedDemoIfEnabled`. Even if it could, `BuildConfig.SEED_DEMO` gates
real startup branches (`KnitApp`'s onboarding check, `BootReceiver`, `ReviewPrompter`), so a seeded run
would faithfully profile a path the shipped app never takes — the same error as collecting against a
minified build. Sending through the shipped composer is the only route that profiles shipped code.

The startup profile (deferred since ADR 048) ships in the same change, because it is the same journey and
it makes the journey's narrowness load-bearing: it feeds R8's **dex layout**, not ART, and marking a tour of
the app as "startup" would tell R8 nothing about what to put next to what. Narrow in screens, deep in
content, is now a rule rather than a preference.

**What it costs, and the traps.** Two, both silent.

`includeInStartupProfile` is a **flavour switch, not an additive flag** — a collection emits
`-baseline-prof.txt` *or* `-startup-prof.txt`, never both, named after the test method. Hence two `@Test`
methods over one shared `collectJourney`, and a ~20-minute run.

The two files do **not** live in symmetrical places. `baseline-prof.txt` is a flat file in `src/main`;
`startup-prof.txt` must be in the `src/main/baselineProfiles/` **directory**. Put it beside the baseline
file and `MergeStartupProfileTask` never sees it: the build stays UP-TO-DATE, the APK comes out
byte-identical, and nothing warns you. That is exactly how this was almost shipped as a no-op. The roadmap
entry that deferred this named the wrong path.

Because it changes dex layout it had to clear `context/distribution.md`'s reproducibility contract, which
it does: the APK moved (`7a4791eb…` → `172e2258…`, R8 re-ran), a full `clean` rebuild reproduced it
byte-for-byte, and so did a rebuild inside `registry.gitlab.com/fdroid/fdroidserver:buildserver` with a
different JDK, F-Droid's own Gradle shim and no `.git`. The layout pass is a deterministic function of the
committed file, like the baseline half.

The two profiles never mix: 503 rules exist in the startup file that are absent from the baseline one, yet
`merged_art_profile` is exactly `baseline-prof.txt` + the AAR profiles. If that stops holding, something
has started feeding the startup file to ART.

Still uncovered by construction: file attachments, link-preview cards and replies. Adding them means
deciding they are first-impression code, which they are not.
