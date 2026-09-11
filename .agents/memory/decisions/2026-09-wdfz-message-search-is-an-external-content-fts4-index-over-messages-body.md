---
id: "2026-09.wdfz"
slug: message-search-is-an-external-content-fts4-index-over-messages-body
title: "Message search is an external-content FTS4 index over messages.body"
date: 2026-09-10
topics: [data, room, search, perf]
---

# ADR 2026-09.wdfz — Message search is an external-content FTS4 index over messages.body

Status: Accepted (2026-09-10; data/message/MessageFtsEntity, DB v12, MessageDao.searchBodies, data/search/,
KnitDatabaseMigrationTest, SqlCipherDriverUpgradeTest)

**What was observed.** Nothing in the app could find a message by what it said. `messages.body` is plaintext
inside the SQLCipher database (the drafts reasoning, ADR 2026-09.qtg9: the file is the boundary), but it has
no index, and the two indices the table does have are both led by `conversationId`, so any cross-thread
text match is a scan of the whole table. ADR 2026-09.z58t had just removed every whole-table read and left
accepted threads with no retention cap, so a search built on `LIKE '%term%'` would have reintroduced, per
keystroke, exactly the read shape that ADR deleted — and `LIKE` cannot use a BINARY-collated index in any
case (the same note that made the group filter a `GLOB`). SQLite's own `lower()` and `LIKE` fold ASCII only,
so `Café` would not have matched `cafe` either.

**What changed.** `messages_fts`, a Room `@Fts4` *external-content* virtual table over `messages.body` with
the `unicode61` tokenizer — DB v12, `MIGRATION_11_12`. It stores no text: tokens keyed by `messages.rowid`,
kept in step by the four `room_fts_content_sync_messages_fts_*` triggers Room generates, and backfilled once
by `'rebuild'` in the migration so history is searchable on arrival. One bounded read, `MessageDao.searchBodies`:
`rowid IN (SELECT rowid FROM messages_fts WHERE messages_fts MATCH ?)` so SQLite drives from the index and
seeks one row per hit, then `kind = 0`, the moderation flag, a blocked-sender exclusion and a conversation
*allow-list*, newest first under a `LIMIT`. The `MATCH` expression is never raw input: `data/search/SearchQuery`
tokenises on letters, digits and marks (every FTS operator character is a separator by construction),
lower-cases (so `AND`/`OR`/`NOT`/`NEAR` become terms) and emits only `term* term*`, which the standard and
the enhanced query syntaxes read identically — SQLCipher's build has `FTS3_PARENTHESIS`, Robolectric's
probably not, and the builder never emits anything the two would disagree on. Its fold mirrors the
tokenizer's: canonical decomposition per code point, the Latin combining diacritics dropped and nothing
else — a Hangul syllable decomposes under NFD too, into jamo the index never sees, and must stay whole.
`data/search/Snippets` windows the body around the first token-start hit with the same fold and an index
map back to the original characters. Declined: a `LIKE`/`instr` scan — no migration, but ASCII-only folding
and a cost that grows with the uncapped threads; FTS5 (trigram substring matching, a built-in `highlight()`)
— available in SQLCipher and Room 3, absent from Robolectric's SQLite, so every JVM Room test would die at
`createAllTables`; a copy-of-body FTS table — twice the text for nothing the external-content form lacks.

**What it costs.** A permanent v12 — the migration can never be merged away — and, verified in the Gradle
cache before the shape was minted: `androidx.room3` 3.0.3 ships `Fts4`, `libsqlcipher.so` (SQLite 3.53.4)
has `ENABLE_FTS4` + `unicode61`, and Robolectric 4.17's native runtime (SQLite 3.32.2) has FTS4 + `unicode61`
too. Room's sync triggers fire on *every* UPDATE of `messages`, not `UPDATE OF body`, so `markReceived`,
`clearPending` and `setVoiceMeta` each re-tokenise one short body; do not hand-tune the trigger bodies —
Room drops every `room_fts_content_sync_*` trigger in `onPreMigrate` and re-creates its own in `onPostMigrate`.
The migration creates them itself all the same, because `MigrationTestHelper`'s delegate does neither and a
migrated test file would otherwise never index a write. Two invariants ride on `rowid`: `messages` has a
TEXT primary key, so a `VACUUM` may renumber its rowids and silently desynchronise the index — never VACUUM,
or follow one with `'rebuild'` — and an `INSERT OR REPLACE` is a delete plus an insert under a fresh rowid
whose implicit delete fires the trigger only under `recursive_triggers`; the DAO uses `@Upsert` and
`OR IGNORE`, and must keep to that. The index is external content, so it costs roughly a third to a half of
the body bytes plus the FTS shadow tables. `unicode61` knows no word boundary inside a run of ideographs,
so only a prefix of such a run matches (`京` finds nothing in `東京`). Search results are one-shot `suspend`
reads, never a `Flow`: the FTS table's invalidation is the content table's, so an observed search would
re-run on every write anywhere. What keeps this true: `KnitDatabaseMigrationTest`'s 11→12 case (the
backfill, the four triggers by name, insert/update/delete through them, `integrity-check`),
`MessageSearchDaoTest` (prefix, case and diacritic folding, Cyrillic and Hangul, kinds, blocked senders,
the allow-list, the moderation flag, ordering and the limit, the index following deletes and re-upserts),
`SearchQueryTest` + `SnippetsTest` (the operator strip, the keyword fold, the per-code-point fold, the
token-boundary rule, surrogate pairs), and `SqlCipherDriverUpgradeTest`, the one place FTS4 under
`SQLCipherDriver` is exercised rather than assumed.
