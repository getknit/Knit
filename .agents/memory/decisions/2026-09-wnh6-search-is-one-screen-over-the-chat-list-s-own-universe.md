---
id: "2026-09.wnh6"
slug: search-is-one-screen-over-the-chat-list-s-own-universe
title: "Search is one screen over the chat list's own universe, and a hit opens the thread on one message"
date: 2026-09-10
topics: [ui, search, navigation]
---

# ADR 2026-09.wnh6 — Search is one screen over the chat list's own universe, and a hit opens the thread on one message

Status: Accepted (2026-09-10; ui/search/, ui/ConversationTitles, ui/contacts/ContactUniverse, the chat route's
`messageId` argument, ChatScreen's jump)

**What was observed.** Finding a person, a group or something someone said meant scrolling the chat list and
then the thread. Three questions with three different answers already existed in the app: which threads the
list shows and what it calls them (`ChatListAssembler`), who counts as a contact (`ContactsViewModel`), and
which thread is a stranger's request (`Conversations.isAccepted`, ADR 009). A search that restated any of
them would drift from the surface it was meant to mirror — a thread found by search but hidden from the
list, a name spelt one way here and another there.

**What changed.** One `search` route, reached from a magnifier in the chat list's top bar, with three
sections that answer one debounced query: **Chats** are exactly the threads the list draws, titled as it
titles them; **People** are exactly the picker's contacts, matched by name or alias and shown with the
alias outright, since a surface you search by alias is a precision surface (ADR 058); **Messages** are
ordinary bodies from those same threads, newest first, through the FTS index (ADR 2026-09.wdfz). The rules
moved, rather than being copied: `ui/ConversationTitles` now holds the list's membership
(`visibleConversations`), title (`conversationTitle`), speaker (`speakerLabel`) and request predicate
(`ConversationTable.isPending`), with `ChatListAssembler` delegating to all four; `ui/contacts/ContactUniverse`
holds `contactIds`, with `ContactsViewModel` delegating. The Messages section asks the index for the visible
threads only — an allow-list, so a stranger's request thread is searched in none of the three sections by the
same predicate that keeps it off the list, and a blocked peer's thread likewise. A message hit opens the
thread *on that message*: the chat route grew an optional `?messageId=` argument, absent everywhere else so
the notification route, the pickers and `demo_route` still match the path alone, and `ChatScreen` feeds the
id once into the machinery a tapped reply quote already had — `revealMessage` widens the window by `depthOf`,
the retry effect scrolls and flashes once the rows hold it. That effect gained two guards on the way: the seed
state (no rows, `hasOlder = false`, `isLoading = true`) must not read as "the end", and a target deeper than
`ChatWindow.MAX` must not be waited for forever. The search entry is navigated to plainly, so it and its
ViewModel — which holds the query — stay under the thread and Back returns to the results as they were.
Declined: an in-list filter on the chat list (no place for message hits, and the list's own top bar is
already full); asking the ViewModel to reveal the message in `init` (it cannot beat the first 60-row emission
without restructuring `windowed`, and the screen-level hand-over is fifteen lines that a content test can
drive); an indeterminate progress bar (an infinite animation on a settled screen hangs every `waitForIdle`;
`isSearching` is derived from typed-versus-answered instead and only suppresses the empty state).

**What it costs.** The bounds: a 250 ms debounce, a two-character floor before the index is asked (one
letter would prefix-match half the table; chats and people answer from one), ten chats, ten people, a
hundred messages. Every keystroke past the debounce re-folds the contact labels and the visible titles —
small lists, cheap folds — and runs one index read; a write to `messages` anywhere re-runs the current
query, because the snapshot combines the live conversation table (`mapLatest` cancels the stale one). A hit
beyond the first window draws the thread at its bottom for a frame before the widened window lands, and one
deeper than `ChatWindow.MAX` opens the thread at its newest with no flash; the jump reuses the raw
`animateScrollToItem` and the bubble's flash, both pre-existing ADR 047 debt. "Search in this chat" is
deferred (roadmap): a scoped hit must hand the id back to the thread already open rather than stack a second
chat entry, or two `ChatViewModel`s race the one-shot draft restore. What keeps this true:
`SearchViewModelTest` over a real in-memory database (the debounce, folded titles, a generated group title,
the alias, the contacts rule, a request thread and a left group absent, a blocked peer in no section, the
two-character floor, the moderation flag), `SearchScreenContentTest` (the field, the sections and their
taps, the empty states, one spoken node per row), `ChatScreenContentTest`'s jump cases (inside the window,
beyond it, while loading, absent), and the seeded `SearchInstrumentedTest` / a11y audits / UIAutomator
round-trip.
