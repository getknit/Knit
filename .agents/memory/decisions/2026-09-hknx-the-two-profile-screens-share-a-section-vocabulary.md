---
id: "2026-09.hknx"
slug: the-two-profile-screens-share-a-section-vocabulary
title: "The two profile screens share a section vocabulary, and Save moves to the app bar"
date: 2026-09-15
topics: [ui, profile]
---

# ADR 2026-09.hknx — The two profile screens share a section vocabulary, and Save moves to the app bar

Status: Accepted (2026-09-15; `ui/profile/ProfileScreen`, `ui/profile/ProfileDetailsScreen` +
`ProfileDetailsViewModel`, the promoted `ui/components/SectionHeader` and new
`ui/components/DetailRow` + `DetailCard`, `GroupRepository.observeGroupsWith`,
`MetPeerDao.observe`/`MetPeerRepository.observe`)

**What was observed.** Both screens titled "Profile" were the same shape: a `Scaffold`, a small `TopAppBar`,
and one `Column(verticalScroll, CenterHorizontally, spacedBy(16.dp))` with no headings and no grouping. On
yours that put a photo picker, two text fields, a centred `Node ID: …` readout, a broadcast-flag switch and a
form-submit button at one altitude — the switch-above-Save contradiction ADR 2026-09.m7vn named as the open
item it deliberately left. On theirs it stacked ten centred children, with the Message action tenth, *below*
`Alias:`, `Node ID:` and `LoRa radio:` — three consecutive `labelMedium`/`onSurfaceVariant` sentences a reader
cannot tell apart — and rendered as a 48dp `FilledIconButton` with a caption, visually weaker than the metadata
above it. Two facts were not on the screen at all: **verified**, which a reader had to scroll to
`EncryptionSection` to learn, and **blocked**, whose only tell was the overflow item reading "Unblock user".

**What changed, and what the alternatives were.** The two screens now share a vocabulary rather than a layout.
`SectionHeader` was promoted out of `DiagnosticsScreen` (where it was `internal` and already reused by
`CrashLogScreen`) into `ui/components/`, gaining `.semantics { heading() }` — which Search's own copy already
had and Diagnostics did not, so Diagnostics gets heading navigation for free. A new `DetailRow` renders the
label/value pairs, with an optional copy affordance; its halves are **weighted, not a fixed label column**, so
a translated label wraps instead of clipping, and it carries the same 16.dp inset as the heading, which is why
both Profile bodies pad only vertically and let their children own the horizontal gutter. Yours becomes
"What people see" (name, status, open-to-chat) over "This device" (node id); theirs becomes a centred header —
avatar, name, a wrapping `FlowRow` of badges, status, intro line — then a full-width Message button, then
"In common", "Details" and the unchanged `EncryptionSection`. **Save moved to the app bar** as a `TextButton`
still gated on `isDirty`. That is the fix m7vn deferred, and deliberately not the one it proposed: it suggested
explanatory copy on the switch row, which answers a visual contradiction with a sentence the reader has to find
and parse, where moving Save means nothing follows the switch and nothing can imply it saves it. Dropping Save
entirely and debouncing was rejected for a second reason — it exists to batch the DataStore write that
republishes the profile, one fan per publish (ADR 057). The alternative a reader reaches for first is **one
shared header composable for both screens**; it does not work, because yours shows the name as an editable
`DisplayNameField` and theirs as a `PeerNameText`, so a common hero would either render the name twice (the
same double-announcement trap m7vn hit with the alias line, which is also why no alias row repeats
`DisplayNameField`'s supporting text) or be a single interface with two mutually exclusive halves.

**The peer screen's top bar carries the contact's name**, not the word "Profile". The screen is about one
person; `profile_details_title` is retired rather than kept for a divergence that never came. A shared group
is a **row you can open** — the chat list's avatar (photo, else the member cluster), the chat list's title,
and a chevron into `groupDetails` stacked on top of the profile, unlike Message, which replaces it. The
comma-joined line it replaces was worse than terse: `GroupEntity.name` is **blank on the wire for an unnamed
group** by design, each device rendering its own default from the members it can name, so reading the column
raw printed three shared groups as `", , Sihaya"`. The row now titles through `groupTitle` + `groupFaces` —
the same two helpers `ui/ConversationTitles.kt` uses — which is why the ViewModel takes a `Context`, for the
one `group_unnamed` fallback string, exactly as `GroupDetailsViewModel` already does. Rows sit in a
`DetailCard` (a `surfaceContainerLow` surface, not an `OutlinedCard`): bare text straight on the background
left the sections reading as one column of loose lines.

**One verified mark.** The badge, the DM header's shield and a signed room post now all draw
`Icons.Filled.VerifiedUser` tinted `knitColors.positive`; `EncryptionSection` moved off `CheckCircle`, which
meant the one screen showing both drew the same fact as two different symbols.

**The two new sections add only facts already in the database, and they are split by whose fact it is.**
"In common" holds the shared groups and nothing else; the met stamps head **Details**, because when your
radios first saw each other is a fact about the contact, not something the two of you share — and inside
Details they lead, being the only rows there a person reads for their own sake rather than to look
something up. `InCommon` therefore carries no "is it empty" of its own: each section asks about the half
it draws. `GroupRepository.observeGroupsWith` is the flow form of
`groupsWith`, and `MetPeerRepository.observe` a passthrough to a new `MetPeerDao.observe` — no schema change.
**The met stamps are worded "met", never "seen".** `met_peers` is written by `MeshManager.watchMetPeers` from
the *nearby* set, so it is short-range evidence only: a contact reached over LoRa or a spool may have no row at
all, and `reach` stays the sole claim about now (ADR 2026-09.2ajk). They render as absolute dates rather than
"3 days ago" for the same reason — a relative phrase beside a live presence badge invites reading it as
presence — which also keeps the screen settled, with nothing that has to re-tick.

**`DetailRow` reserves its trailing slot whether or not the row copies.** The label/value weights split
what is left after the fixed children, so a row that simply omitted the copy icon handed its label column
that width and pushed its value right — which is how the un-copyable "LoRa radio" line sat out of line with
the two above it. An empty `Box` of the icon's size keeps every value column on one axis.

**What it costs, and the traps.** `GroupDao.observeAll` is `SELECT * FROM groups ORDER BY createdAt DESC` with
**no `left = 0` clause** — only the suspend `allActive()` has one — so `observeGroupsWith` filters `!it.left` in
Kotlin; drop that and a group you walked out of keeps listing itself as shared.
`GroupRepositoryTest.observeGroupsWith lists only groups the member shares and we have not left` is the guard,
with a second test pinning it against `groupsWith`. Second: `ProfileDetailsViewModel.state` was already at
Kotlin's five-source `combine` limit (which is why `reach` is pre-combined), so `inCommon` is pre-combined too
and rides in paired with `me` as `identityAndCommon` — widening the main combine would mean the untyped array
overload. Third: the mocks in `ProfileDetailsViewModelTest` are relaxed, and a relaxed `Flow` return never
emits, so `observeGroupsWith` and `metPeers.observe` must both be stubbed or `state` stalls at its initial value
with nothing naming the cause. Fourth: `profile_save` keeps its tag but is no longer inside a scrollable, so
`performScrollTo()` on it now throws — `ProfileScreenContentTest.saveIsEnabledAndFiresWhenDirty` records why.
Fifth: the ViewModel's `Context` means `ProfileDetailsViewModelTest` must stub `context.getString`, or every
unnamed group titles as the empty string the fix exists to avoid; and the black-box
`MessageDetailsUiAutomatorTest` asserted the literal title "Profile", so it now anchors on the contact's
name instead.
Every tag `context/debug-bridge.md` publishes survives; the app-bar Save takes an explicit `height(48.dp)`
because Material's text button is 40dp, which the ATF suite flags.

**What it does not cover.** The verified badge shows only when verified — "not verified" is the ordinary case,
and `EncryptionSection` still names it — so the header reports a good state rather than nagging about a normal
one, matching the shape open-to-chat already used. Blocked is now visible, but **Unblock stays in the overflow**:
making the badge or the primary action unblock is a behaviour change, not a layout one. `EncryptionSection`
keeps its own centred `titleMedium` heading rather than adopting `SectionHeader`; it is a self-contained
interactive block, and it is shared with `AddContactScreen`, which hides that heading entirely. And the screen
still offers no local nickname for a peer and no way to forget one — both genuinely absent from the schema.
