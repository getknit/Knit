---
id: "2026-09.m7vn"
slug: settings-and-your-profile-are-two-screens
title: "Settings and your profile are two screens"
date: 2026-09-09
topics: [ui, navigation, settings, profile]
---

# ADR 2026-09.m7vn — Settings and your profile are two screens

Status: Accepted (2026-09-09; route `settings` + `ui/settings/SettingsScreen` + `SettingsViewModel`,
`ProfileScreen` keeps the `profile` route and loses six rows)

**What was observed.** One 748-line screen did both jobs. It was titled "Settings", registered under the route
`profile`, named `ProfileScreen`, reached from an overflow item labelled "Settings" — while
`profile_details_title` ("Profile") already named a *different* screen, the read-only view of a peer. The split
identity dates to `55c96430` (2026-08-17), which relabelled the entry point without moving anything. Underneath
it was a flat `verticalScroll` Column with no section headers, mixing what peers see (avatar, name, status, node
id, alias) with how the app behaves (content filtering, Material You, link previews, the relay and LoRa rows,
battery optimisation), and it had grown a row per plane and a switch per feature over three releases. ADR
2026-09.m9h8 had already paid for this: it hid the Material You switch below API 31 rather than disabling it,
reasoning that a switch that can never move needs a reason beside it "and the settings screen has nowhere to put
one". That was a statement about the layout, not about Material You.

**What changed, and what it was not.** Two screens, one job each. `settings` is what the overflow menu opens —
still one item, still labelled "Settings" — carrying the three switches, the two rows that hand off to a plane's
own screen, the battery line, and a profile header row at the top that navigates to `profile`. `profile` keeps
the avatar, the two text fields, node id, alias, the open-to-chat flag and the one Save button. Save stays
Profile-only and unchanged: it exists to batch the name/status DataStore write that republishes the profile, and
every switch on both screens still persists on toggle, so the two halves never share a dirty state. Open-to-chat
went to Profile because it is a carried profile field (ADR 2026-09.74fq) that peers read off your card, not an
app preference; its two strings were renamed `settings_open_to_chat_*` → `profile_open_to_chat_*` to match.
The alternative a reader reaches for first is **one screen with section headers** — rejected because it already
scrolled past a phone's height, and because a Save button at the foot of a column of instantly-persisting
switches reads as though it saves them too. The second is **giving Settings the `profile` route and minting a
new one for the profile** — rejected because `profile` is cited by the ATF audit, `ProfileInstrumentedTest`, the
debug `demo_route` extra and the maintainer screenshot script, and the screen it names is the one that stayed a
profile.

**The trap, and what keeps this true.** `SettingsViewModel` is scoped to the `settings` back-stack entry, which
stays alive while Profile sits on top of it — and Save there pops *straight back onto the header row*. So the
header must observe `SettingsStore.displayName` and `ownAvatarHash` continuously, not read them once in `init`
the way `ProfileViewModel` deliberately does for its editable fields (the write-through-local-state rule in
`rules/coding.md`, which exists because binding a `TextField` to a DataStore flow lets you type one character).
A one-shot read leaves the old name on the row forever and nothing else in the build notices;
`SettingsViewModelTest.theHeaderFollowsALaterNameChange` is the only guard. `identity.nodeId()` genuinely is
one-shot, so it is resolved in `init` into a `MutableStateFlow` and combined, keeping the id read out of the
path that has to stay live. Second trap: with no name stored, `displayNameFor` returns the alias, so a header
that always showed the alias underneath would print the same word twice and make TalkBack say it twice —
`ProfileHeader.alias` is therefore null in exactly that case
(`theHeaderFallsBackToTheAliasAndDropsTheAliasLine`, and
`SettingsScreenContentTest.theProfileRowShowsTheAliasOnlyOnceBeforeANameIsSet`). The seeded demo build always
has a name, so the a11y suite would never have caught it. Third: the three `showInternetRelays` /
`showLoraRadio` / `showDynamicColor` defaulted booleans had to survive the move intact (ADR 031's house style) —
lose one and a release build draws a row that navigates to a route `KnitApp` never registered. The three
"row is absent" tests moved with them.

**What it costs.** `ProfileScreen` (yours) and `ProfileDetailsScreen` (theirs) now share a package and a
rendered title; the two string keys stay separate so they can diverge, and `profile_title` was reintroduced
rather than reusing `profile_details_title`. The four moved test tags were renamed `profile_*` → `settings_*`,
which `context/debug-bridge.md` publishes as an automation surface; the black-box suites anchor on
`screen_settings` now, and `OverflowNavigationUiAutomatorTest`'s claim that "Settings reuses its existing
`profile_name` field" went with them. **What it does not cover:** Profile still shows one toggle-persisted
switch directly above a Save button that does not apply to it. Alone on a four-control screen that reads worse
than it did buried in a settings block, and the code comment explaining it is not something a user can see. Left
as-is deliberately — moving open-to-chat to Settings would separate a broadcast profile field from the name and
status it travels with — but it is the next thing to fix here, with copy on the row rather than another move.
