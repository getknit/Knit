---
id: "2026-09.z58t"
slug: the-chat-list-reads-per-thread-summaries
title: "The chat list reads per-thread summaries, and an accepted thread has no retention cap"
date: 2026-09-10
topics: [data, ui, perf]
---

# ADR 2026-09.z58t — The chat list reads per-thread summaries, and an accepted thread has no retention cap

Status: Accepted (2026-09-10)

**What was observed.** `ChatListViewModel` collected `MessageDao.observeAll()` — `SELECT * FROM messages
ORDER BY sentAt ASC` — and folded the whole table into rows on every write to the table: the newest message
per thread, an unread count, the sender set, the "did I write here" flag. `MessageRequestsViewModel`,
`ContactsViewModel`, `ReviewPrompter` (a one-shot `first()` on every chat-list landing) and the
notification's mark-read action (a whole thread for a `MAX(sentAt)`) did the same. ADR 2026-09.hd5n had
fixed the thread screen with a newest-anchored window and left every one of these alone. The only bound on
them was the retention sweep, and its accepted-thread cap of 5,000 rows was being mistaken for a flood
defence. It is not one: the peer of an accepted thread is someone the user chose, so the cap protected
nothing but the cost of these reads while quietly deleting the user's own history. The defence is the rooms
(2,000 rows / 30 days) and strangers' request threads (50 rows, 7 idle days, 100 threads), because anyone
in radio range can write into those.

**What changed.** The accepted-thread cap is gone — `sweepRetention` skips a protected thread outright —
and every whole-table and whole-thread read is deleted from `MessageDao` and `MessageRepository`, so nothing
can regress to one. The list screens read per-thread summaries, shared through `ui/ConversationTable`: the
newest speaking row per conversation (a correlated `ORDER BY sentAt DESC, id DESC LIMIT 1` driven by the
distinct conversation set, so a burst at one instant cannot flip a preview), the conversation set, the
kind-0 senders per group, and the authored set. The chat list then fills each *drawn* row's unread badge
with one indexed `COUNT` against the DataStore watermark, inside `mapLatest` so a newer snapshot cancels a
stale fold. Three rules moved into SQL and were aligned with the notify gate on the way (ADR 009): group
senders are `kind = 0` (the list had counted notice subjects, which `aNoticeAloneDoesNotAcceptAStrangersGroup`
already called unintended), authored is `senderId = me AND originNode IS NULL`, and the requests inbox
leaves blocked senders out as the badge already did. Declined: a `read_marks` Room table — one `JOIN`
query, but a v12 migration that can never be merged away plus every watermark writer, for a number that
tens of index seeks produce; the SQLite bare-column `SELECT *, MAX(sentAt) … GROUP BY` idiom — an
arbitrary row on a tie and a per-row fetch of the whole table; one Room `Flow` per conversation for the
counts — N invalidation observers and a nested `flatMapLatest` as threads come and go; `LIKE 'g-%'` for the
group filter — the indices collate BINARY, so it walks the table where `GLOB 'g-*'` is a range; and
`distinctUntilChanged` on the Room flows — a write that leaves the heads alone can still change a count.

**What it costs.** Per write to `messages`, the chat list now runs three index-only walks (the DISTINCT
driver, the conversation set, the authored set), an index range over group rows, one seek for the Meshtastic
channel and one seek per drawn row. None is a per-row probe over the table, but the DISTINCT driver is
still O(rows) over the covering index; if a profile ever shows it, the loose-index-scan recursive CTE
(`WITH RECURSIVE … SELECT MIN(conversationId) … WHERE conversationId > c`) makes it O(conversations).
`ChatWindow.MAX` (5,000) stays as the thread screen's own ceiling and no longer mirrors any retention
constant; a quote deeper than that is not followed. The DataStore keys the table is parameterised by
(`blockedNodeIds`, `lastReadAll`) re-emit on every preferences write, so the `distinctUntilChanged` on each
is load-bearing — drop it and every read-watermark stamp from an open chat tears down and re-runs every
query. The ViewModel tests seed a real in-memory database (`ui/InMemoryMessages`, with Room's own work
pinned to the test dispatcher through `setQueryCoroutineContext`) rather than stubbing the summaries, which
would restate the SQL and prove nothing about it. What keeps this true: `MessageDaoTest`'s summary block
(the tie-break, the kinds, blocked senders, a notice-only thread, the unread rule with heard posts counted,
the channel scalar, the empty `IN ()`), `MessageRetentionTest`'s "a protected thread is never trimmed,
however large or stale", and a grep for `observeAll`/`observeForConversation`/`observeMessages(` under
`app/src` that must stay empty.
