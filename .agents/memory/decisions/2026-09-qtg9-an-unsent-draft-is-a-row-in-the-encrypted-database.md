---
id: "2026-09.qtg9"
slug: an-unsent-draft-is-a-row-in-the-encrypted-database
title: "An unsent draft is a row in the encrypted database, handed to the composer once"
date: 2026-09-09
topics: [ui, data, room, privacy]
---

# ADR 2026-09.qtg9 — An unsent draft is a row in the encrypted database, handed to the composer once

Status: Accepted (2026-09-09; `data/draft/`, DB v11, `ChatViewModel`, `ChatScreen`, `DraftRepositoryTest`,
`KnitDatabaseMigrationTest`)

Type a sentence into a chat, don't send it, go back to the list, open the chat again: the sentence was
gone. Not a bug in anything — nothing had ever kept it. `ChatScreen` holds the composer's text in a
`rememberTextFieldState`, which is a saveable, so the text survived a rotation and survived pushing a
profile screen on top of the chat. A **pop** takes the back-stack entry's saved state and its ViewModel
with it, and there was nothing behind either. `ChatViewModel` did hold the text in a `draft`
`MutableStateFlow`, but only to feed the link-preview loop; its KDoc said "Never persisted" and meant it.

## The row goes in the SQLCipher database, not the settings DataStore

`SettingsStore` already keeps per-conversation state under a dynamic key — that is how the read watermarks
work — so a `draft:<conversationId>` string preference is the shortest path and the first one to reach for.
It is the wrong one. A draft is *message text the user wrote*; the whole reason `messages` sits behind
SQLCipher is that its content is not for whoever gets hold of the device, and a sentence is not less
private for being unsent. The DataStore holds settings, watermarks and blocked ids: none of it is content,
and a preferences file is plaintext on disk. So: a `drafts` table, `conversationId` primary key, `text` —
DB **v11**, `KnitMigrations.MIGRATION_10_11`, an empty table on arrival with nothing to backfill because no
draft can predate it — and `updatedAt` beside them at **v12**, `MIGRATION_11_12` (why two bumps: the trap
at the bottom of this file).

The row carries `updatedAt` — our own clock, one reader, the chat list below. No expiry, though: a sentence
left in a chat is still that sentence a month later, which is what every other messenger does with one.
A draft written before that column existed carries 0, which is honest and simply loses the preview line.

Writes go through `DraftRepository` on the **application** scope, debounced 500 ms. Both halves matter and
both are the same point: the write worth having is the one started as the user leaves, and a
`viewModelScope` write is cancelled by the very act it exists to record. `clear` is on that scope for the
same reason — a send followed straight away by the back gesture must still empty the field next time.
Every entry point launches on `Dispatchers.Main.immediate`, which is what makes the pending-write map safe
without a lock; Room's suspend DAO functions run their SQL on the database's own dispatcher, so being on
the main thread costs nothing (the ViewModels already call Room from `viewModelScope`).

## The composer reports an empty field before the read lands

This is the trap the feature is built around. `MessageInput` collects a `snapshotFlow` over the field and
reports the **initial** snapshot — an empty string — the moment it composes. Persist that and opening a
chat erases the draft it is about to restore; open one and leave inside the read, and it is erased with
nothing on screen to show for it.

So the hand-over is explicit. `ChatViewModel` reads the row once at construction into a `Deferred`, and
`consumeRestoredDraft()` awaits it, returns the text **once**, and only then starts persisting edits. Once
is load-bearing in the other direction too: a rotation re-runs the screen's restore effect, and a draft the
user has since cleared must not come back with it.

*Rejected:* `SavedStateHandle`, which dies with the same back-stack entry, and hoisting the text into the
ViewModel as observable state, which `rules/coding.md` already bans for the keystroke-eating round trip —
and which would not have helped anyway, since the ViewModel is per-entry. The screen keeps the editable
text; the ViewModel keeps only what has to outlive it.

## The chat list shows it as the preview, and nothing else

A thread whose draft is newer than its newest message reads `Draft: <text>` in italics where its last
message would be, the way Signal's list does — `ConversationRow.draft`, non-null only when
`draft.updatedAt > last.sentAt`. Once a message lands after the draft, the preview goes back to the
conversation: the draft is still in the composer, waiting where it was typed, but it is no longer the newest
thing in the thread and the line that says what happened last should say what happened last. Ties go to the
message.

Two things it deliberately does not touch. The row's **timestamp and its place in the list** still come from
`lastMessageAt`, so typing does not float a thread to the top or restamp it; the list's order is about the
conversation, and status notices are already held to the same rule for the same reason. And the **prefix is
words, not styling** — the row is one accessible node whose description carries the preview, so italics
alone would say nothing to a screen reader.

A thread that has *only* a draft and no messages still needs a row to decorate: an empty group or the Nearby
room has one and shows the draft, but a DM you typed at and never sent to has no row in the list at all.
That is the list's existing rule (a DM appears once it has a message), left alone.

## What it does not cover

A draft is the **text**. A staged photo, file or location tile, the reply being quoted, and the mention
bindings are still draft-local to the screen and still go when it does — so a restored `@Ann` is text, not
a mention, exactly as one typed without touching the autocomplete has always been. Both rooms keep drafts
like any other thread.

A draft dies with the thread it belongs to: `ChatListViewModel.deleteConversation`,
`MessageRequestsViewModel.delete` and `GroupDetailsViewModel.leaveGroup` call `clear`. The *automatic*
prune of stale stranger threads (`MessageRepository.sweepRetention`) does not, which leaves a draft for a
thread whose messages went — invisible until that stranger writes again, and arguably what you want then.
Putting the purge in `MessageRepository.deleteByConversation` would have caught that path too and was
tempting for being one line, but it makes the message repository the owner of a second table's lifetime and
puts a delete on the drafts table outside `DraftRepository`, where a debounced write it does not know about
can land after it.

## What keeps it true

`DraftRepositoryTest` (plain JVM, fake DAO, virtual clock) pins the scheduling: one write per typing pause,
blank text deletes rather than stores an empty row, `clear` cancels a write still in the queue, one thread's
clear leaves another's alone, and the write is stamped with the clock. `ChatViewModelTest` pins the
hand-over — nothing is persisted before the screen takes the stored draft, the second take is empty, an
accepted send drops the row and a blocked send keeps it. `ChatListViewModelTest` pins the preview rule from
both sides (a newer draft speaks for the row; a message landing after it takes the line back, with
`lastPreview`/`lastMessageAt` untouched throughout) and `ChatListScreenContentTest` that the words reach the
row's description. `KnitDatabaseMigrationTest` covers v10 → v11 and v11 → v12.

A trap for whoever adds the next flow to that list: `ChatListViewModel`'s outer combine is at the typed
five-flow arity, so `drafts.all` rides inside `ListBundle` — and a **relaxed mock** of it hands back a Flow
that never emits, which stalls the whole combine and leaves every assertion reading the loading seed.
`ChatListViewModelTest.setUp` stubs it for exactly that reason.

## The trap: an unreleased schema bump is still installed somewhere

`updatedAt` was first folded **into** v11 rather than given its own bump, on the reasoning that v11 was
unreleased — the repo has a precedent for collapsing a never-released bump (the v2 comment in
`KnitDatabase`, and the group-ratchet fold before it). The reasoning was wrong by one device: v11 had
already been installed on the Pixel 9. Room compares a stored **identity hash**, not just the version, so
the phone came up on a v11 database whose hash no longer matched the app's and reported it as
`java.lang.IllegalStateException: Room cannot verify the data integrity. Looks like you've changed schema
but forgot to update the version number` — at every launch, with no way out, because the version *did*
match and no migration was eligible to run. Expected `7b5ce4f6…`, found `350606fc…`.

Collapsing a bump is only safe while *nothing has opened it*, and a debug install counts. The fix was the
ordinary one: v11 is exactly what that phone holds, v12 adds the column, both migrations tested. The
hashes are also the oracle — regenerating the v11 JSON reproduced `350606fc…` exactly, which is what
proved the device was on the two-column schema before anything was changed to suit it.

## Verified on the Pixel 9, 2026-09-09

`:app:installDebug` over the crashing build: the app cold-starts, the debug bridge answers with the node's
own id and a reachable peer, and the Nearby room still holds its three messages — the v11 → v12 migration
ran over real data with nothing lost.

**The feature itself is still unverified on hardware.** The phone was in use (the notification shade came
down mid-run), so the UI trial was stopped rather than fought: type into a thread, back out — the row
should read `Draft: …` in italics — re-open, and again with a send in the middle.

A gotcha that cost a red test run and is not specific to drafts: `advanceUntilIdle()` — on the `TestScope`
*or* on `testScheduler` — runs only **foreground** work, so a coroutine launched on `backgroundScope`
(which is how you model an application scope in a test) never runs and every assertion reads an empty
store. `testScheduler.advanceTimeBy(...)` does run it.
