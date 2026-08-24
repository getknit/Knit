package app.getknit.knit.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Migration-testing harness. **v1 is the frozen launch baseline:** there is no destructive fallback, and from
 * v1 forward every schema bump ships a tested [KnitMigrations] entry validated here — one per bump, plus the
 * current-schema smoke test that exercises the schema-export pipeline end-to-end: `createDatabase(version)`
 * rebuilds the DB from the checked-in `app/schemas/app.getknit.knit.data.KnitDatabase/<version>.json`, proving
 * `exportSchema`, the Room Gradle plugin's `schemaDirectory` export, the unit-test asset wiring (Robolectric
 * serves `sourceSets["test"]` assets), and the `MigrationTestHelper` harness all line up. The version is read from the
 * `@Database` annotation, so this always targets the current schema and fails loudly if its exported JSON is
 * missing.
 *
 * It uses the driver-based [MigrationTestHelper] constructor with [AndroidSQLiteDriver] — the connection API
 * (`createDatabase`/`runMigrationsAndValidate` returning a `SQLiteConnection`) requires a `SQLiteDriver`, and
 * the framework driver runs on Robolectric's shadowed SQLite (the same engine the DAO tests use;
 * `BundledSQLiteDriver` can't load its Android native lib on the host JVM). When the first post-v1 schema
 * change lands, add a [KnitMigrations] entry and fill in the template below — `runMigrationsAndValidate` then
 * validates both the migrated schema and the carried data.
 */
@RunWith(AndroidJUnit4::class)
class KnitDatabaseMigrationTest {
    private val dbFile = File.createTempFile("knit-migration", ".db").apply { delete() } // path must be free

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = dbFile,
            driver = AndroidSQLiteDriver(),
            databaseClass = KnitDatabase::class,
        )

    @Test
    fun `the current schema (v6) creates and opens from the exported JSON`() {
        val version = 6 // KnitDatabase @Database(version = 6) — bump alongside the DB (its retention is CLASS,
        // so the version can't be read reflectively). A missing schemas/<db>/<version>.json fails here.
        helper.createDatabase(version).close()
    }

    @Test
    fun `migrate 1 to 2 preserves existing rows and adds the ratchet and group-ratchet schemas`() {
        // Seed a v1 database with the rows a real device would carry into the upgrade: a pinned peer and
        // a message. runMigrationsAndValidate then applies MIGRATION_1_2 and validates the result against
        // the exported v2 schema JSON (so the hand-written SQL can't drift from what Room generates).
        helper.createDatabase(1).use { c ->
            c.execSQL(
                "INSERT INTO peers (nodeId, name, status, verified, updatedAt) VALUES ('n1','Ann','around',1,7)",
            )
            c.execSQL(
                "INSERT INTO messages (id, senderId, conversationId, body, sentAt, received, mentions, " +
                    "replyToHasAttachment, moderation, pendingKey, kind) " +
                    "VALUES ('m1','n1','c1','hello',1,1,'[]',0,0,0,0)",
            )
        }
        helper.runMigrationsAndValidate(2, listOf(KnitMigrations.MIGRATION_1_2)).use { c ->
            c.prepare("SELECT name, verified FROM peers WHERE nodeId = 'n1'").use { s ->
                assertTrue(s.step())
                assertEquals("Ann", s.getText(0))
                assertEquals(1L, s.getLong(1))
            }
            c.prepare("SELECT body FROM messages WHERE id = 'm1'").use { s ->
                assertTrue(s.step())
                assertEquals("hello", s.getText(0))
            }
            // The new prekey columns exist and default to null for a pre-upgrade peer.
            c.prepare("SELECT prekeyId, prekeyPub FROM peers WHERE nodeId = 'n1'").use { s ->
                assertTrue(s.step())
                assertTrue(s.isNull(0))
                assertTrue(s.isNull(1))
            }
            // The ratchet + group-ratchet tables are present and empty (one never-released bump holds both).
            for (table in listOf(
                "ratchet_sessions",
                "ratchet_skipped_keys",
                "group_send_chains",
                "group_recv_chains",
                "group_skipped_keys",
                "group_key_sends",
            )) {
                c.prepare("SELECT COUNT(*) FROM $table").use { s ->
                    assertTrue(s.step())
                    assertEquals(0L, s.getLong(0))
                }
            }
        }
    }

    @Test
    fun `migrate 2 to 3 preserves existing rows and adds the group-roots schema`() {
        // Seed a v2 database carrying group-ratchet state, since group roots ride beside it and a real
        // upgrade happens on a device that already has groups.
        helper.createDatabase(2).use { c ->
            c.execSQL(
                "INSERT INTO peers (nodeId, name, status, verified, updatedAt) VALUES ('n1','Ann','around',1,7)",
            )
            c.execSQL(
                "INSERT INTO group_key_sends (groupId, memberId, sentEpoch, sentAt, ackedEpoch, ackedAt) " +
                    "VALUES ('g-1','n1',3,10,3,11)",
            )
        }
        helper.runMigrationsAndValidate(3, listOf(KnitMigrations.MIGRATION_2_3)).use { c ->
            c.prepare("SELECT sentEpoch FROM group_key_sends WHERE groupId = 'g-1' AND memberId = 'n1'").use { s ->
                assertTrue(s.step())
                assertEquals(3L, s.getLong(0))
            }
            c.prepare("SELECT COUNT(*) FROM group_roots").use { s ->
                assertTrue(s.step())
                assertEquals(0L, s.getLong(0))
            }
            // The nullable root/prevRoot columns are what let a row exist purely to hold the mint-grace
            // stamp, so pin that a partially-populated row is actually insertable.
            c.execSQL(
                "INSERT INTO group_roots (groupId, root, version, minter, prevRoot, prevVersion, prevExpiresAt, " +
                    "firstEligibleAt, remintDueAt) VALUES ('g-1', NULL, 0, '', NULL, 0, 0, 42, 0)",
            )
            c.prepare("SELECT root, firstEligibleAt FROM group_roots WHERE groupId = 'g-1'").use { s ->
                assertTrue(s.step())
                assertTrue(s.isNull(0))
                assertEquals(42L, s.getLong(1))
            }
        }
    }

    @Test
    fun `migrate 3 to 4 keeps messages and leaves their delivery plane unknown`() {
        // The upgrade case that matters: a message already ticked ✓✓ on an older build has no record of
        // which plane acked it, so it reads as DeliveryPlane.Unknown (code 0) — not a globe, not a lie.
        helper.createDatabase(3).use { c ->
            c.execSQL(
                "INSERT INTO messages (id, senderId, conversationId, body, sentAt, received, mentions, " +
                    "replyToHasAttachment, moderation, pendingKey, kind) " +
                    "VALUES ('m1','me','bob','hello',1,1,'[]',0,0,0,0)",
            )
        }
        helper.runMigrationsAndValidate(4, listOf(KnitMigrations.MIGRATION_3_4)).use { c ->
            c.prepare("SELECT body, received, receivedVia FROM messages WHERE id = 'm1'").use { s ->
                assertTrue(s.step())
                assertEquals("hello", s.getText(0))
                assertEquals(1L, s.getLong(1))
                assertEquals(0L, s.getLong(2))
            }
        }
    }

    @Test
    fun `migrate 4 to 5 keeps messages and leaves their voice columns null`() {
        // Voice-note duration and waveform are derived locally from the audio, never carried on the wire, so
        // there is nothing to backfill: an existing row has no voice attachment and both columns read null.
        // The row seeded here carries an image attachment precisely to pin that — an attachment alone does
        // not make a message a voice note, and the migration must not invent metadata for one.
        helper.createDatabase(4).use { c ->
            c.execSQL(
                "INSERT INTO messages (id, senderId, conversationId, body, sentAt, received, receivedVia, " +
                    "mentions, attachmentHash, attachmentMime, replyToHasAttachment, moderation, pendingKey, kind) " +
                    "VALUES ('m1','me','bob','hello',1,1,0,'[]','abcd','image/jpeg',0,0,0,0)",
            )
        }
        helper.runMigrationsAndValidate(5, listOf(KnitMigrations.MIGRATION_4_5)).use { c ->
            c.prepare("SELECT body, attachmentMime, voiceDurationMs, voicePeaks FROM messages WHERE id = 'm1'").use { s ->
                assertTrue(s.step())
                assertEquals("hello", s.getText(0))
                assertEquals("image/jpeg", s.getText(1))
                assertTrue(s.isNull(2))
                assertTrue(s.isNull(3))
            }
        }
    }

    @Test
    fun `migrate 5 to 6 adds an empty message_receipts table and keeps an already-ticked message`() {
        // There is nothing to backfill and backfilling would be a lie: an already-received message was
        // acked before this device recorded ackers, so we know somebody got it and cannot say who. The
        // message-details screen reads exactly that — ticked with no rows means "predates the table", and
        // it shows no roster rather than accusing every member of missing it.
        helper.createDatabase(5).use { c ->
            c.execSQL(
                "INSERT INTO messages (id, senderId, conversationId, body, sentAt, received, receivedVia, " +
                    "mentions, replyToHasAttachment, moderation, pendingKey, kind) " +
                    "VALUES ('m1','me','g-1','hello',1,1,1,'[]',0,0,0,0)",
            )
        }
        helper.runMigrationsAndValidate(6, listOf(KnitMigrations.MIGRATION_5_6)).use { c ->
            c.prepare("SELECT body, received FROM messages WHERE id = 'm1'").use { s ->
                assertTrue(s.step())
                assertEquals("hello", s.getText(0))
                assertEquals(1L, s.getLong(1))
            }
            c.prepare("SELECT COUNT(*) FROM message_receipts").use { s ->
                assertTrue(s.step())
                assertEquals(0L, s.getLong(0))
            }
            // The table is usable straight away, and its composite key absorbs a duplicate receipt.
            c.execSQL("INSERT INTO message_receipts (messageId, ackerNodeId, notedAt, via) VALUES ('m1','sam',9,1)")
            c.execSQL("INSERT OR IGNORE INTO message_receipts (messageId, ackerNodeId, notedAt, via) VALUES ('m1','sam',99,2)")
            c.prepare("SELECT notedAt, via FROM message_receipts WHERE messageId = 'm1' AND ackerNodeId = 'sam'").use { s ->
                assertTrue(s.step())
                assertEquals(9L, s.getLong(0))
                assertEquals(1L, s.getLong(1))
            }
        }
    }
}
