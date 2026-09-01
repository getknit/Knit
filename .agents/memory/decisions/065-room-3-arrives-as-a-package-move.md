---
id: "065"
slug: room-3-arrives-as-a-package-move
title: "Room 3 arrives as a package move, and takes SQLCipher's driver with it"
date: 2026-08-31
topics: [data, room, build]
---

# ADR 065 — Room 3 arrives as a package move, and takes SQLCipher's driver with it

Room 3.0.2 is `androidx.room3:room3-*` — a new group and a new package (`androidx.room3`), not a version
bump of `androidx.room`. It deletes the SupportSQLite layer from the core API, and with it
`RoomDatabase.Builder.openHelperFactory`, which was our **only** seam for SQLCipher. That is why this
upgrade waited: it was impossible until `net.zetetic:sqlcipher-android` **4.18.0** (2026-08-18) shipped
`SQLCipherDriver implements androidx.sqlite.SQLiteDriver`. Room 3 and SQLCipher now move as a pair;
neither can be bumped past the other alone, and the version catalog says so at both entries.

**It landed in two commits on purpose, because only one of them can break a user's database.** The first
kept Room at 2.8.4 — which already has `setDriver` — and changed just the two things that alter runtime
behavior: `openHelperFactory(SupportOpenHelperFactory)` → `setDriver(SQLCipherDriver)`, and every
transactional write off `androidx.room.withTransaction`. Those are inseparable: setting a driver takes
Room out of compatibility mode, and `withTransaction` runs through `beginTransaction()` → `getOpenHelper()`,
which then throws. The second commit is the package rename, which changes no behavior at all. Splitting
them means the risky half was verifiable while *both* SQLCipher seams still existed — a test opened a
database through the old factory and reopened it through the driver — a comparison Room 3 makes
unexpressible, since the factory is gone.

**The identity hash does not move, and that was the gate.** ADR 008 forbids a destructive fallback, so a
hash that changed under the new compiler would mean every installed database fails its integrity check at
open with no recovery path. Comparing `vo/Database.class` between room-compiler 2.8.4 and room3-compiler
3.0.2 shows an identical hash input; only the digest helper changed (`commons-codec md5Hex` →
`androidx.room3.util.md5Hex`, both lowercase-hex MD5). Re-exporting confirmed it: `7.json` came back
byte-identical, all seven committed hashes unchanged. The re-export also surfaced a trap the docs had
wrong — clearing `app/schemas/` regenerates only the *current* version, so 1..6 must be restored from git.

**`withTransaction` → `withWriteTransaction`, and the reentrancy ADR 019's amended lock order rests on survives.**
Room 3 carries the write connection in the coroutine context (`ConnectionElement`), so a nested
`withWriteTransaction` joins the outer transaction rather than opening a second — the property
`SessionTransactor` and `MessageReceiptRepository.record` depend on, both still pinned by their tests. The
transaction type moves from the framework's `BEGIN EXCLUSIVE` to `BEGIN IMMEDIATE`, which is what Room 3
itself emits and which under WAL takes the same write lock without blocking readers. `SQLCipherDriver`
reports `hasConnectionPool() = true`, so Room holds exactly one connection through it and lets SQLCipher
pool underneath — `driver.open()` is called once, not per transaction, so there is no repeated PBKDF2.

**What it cost elsewhere:** `Migration.migrate` and the `MigrationTestHelper` methods are now `suspend`;
`room3-ktx` does not exist (the transaction helpers are in `room3-runtime`); the Gradle plugin id and
extension are both `room3`; the compile/runtime `androidx.sqlite` skew in the lockfile (2.6.2 vs 2.7.0)
collapses to 2.7.0 now that Room and SQLCipher agree; and `baseline-prof.txt` had 469 `androidx/room/`
rules that no longer resolve, so it was regenerated. No `@Database` bump, no wire change, no new native
dependency — the F-Droid reproducibility posture is untouched beyond the lockfile and the profile, both
committed.
