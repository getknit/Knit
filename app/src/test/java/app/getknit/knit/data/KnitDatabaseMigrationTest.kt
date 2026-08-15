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
 * v1 forward every schema bump ships a tested [KnitMigrations] entry validated here. `KnitMigrations.ALL` is
 * empty at launch, so today this exercises the schema-export pipeline end-to-end: `createDatabase(version)`
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
    fun `the current schema (v2) creates and opens from the exported JSON`() {
        val version = 2 // KnitDatabase @Database(version = 2) — bump alongside the DB (its retention is CLASS,
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
}
