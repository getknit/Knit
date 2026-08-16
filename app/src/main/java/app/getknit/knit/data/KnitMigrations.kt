package app.getknit.knit.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The registry of tested schema migrations applied in [KnitDatabase.build].
 *
 * **v1 is the frozen launch baseline.** There is no destructive fallback: from v1 onward every `@Database`
 * version bump MUST add a [Migration] here — a missing one makes Room throw at open time (caught by
 * `KnitDatabaseMigrationTest`) instead of silently wiping user data. So this is the single place production
 * migrations live: keep it in lockstep with `@Database(version = …)` and the checked-in
 * `app/schemas/**/<version>.json`, using the driver-based `migrate(SQLiteConnection)` override (matching
 * the `KnitDatabaseMigrationTest` harness), and fill in a migration-test case per bump.
 */
object KnitMigrations {
    /**
     * v2 — the ratchet schemes (docs/FORWARD_SECRECY_RATCHET.md + docs/GROUP_FORWARD_SECRECY.md, one
     * never-released bump): four `ratchet_*` DM-session tables, four `group_*` sender-key tables
     * (send/recv chains, skipped keys, the seed outbox), and the peer's published-prekey columns.
     * Additive only; the SQL must stay byte-equivalent to what Room generates for
     * `app/schemas/**/2.json` (validated by `runMigrationsAndValidate`).
     */
    val MIGRATION_1_2 =
        object : Migration(1, 2) {
            @Suppress("LongMethod") // a flat list of CREATE TABLE/INDEX statements; splitting would obscure the schema
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ratchet_sessions` (" +
                        "`peerId` TEXT NOT NULL, `confirmed` INTEGER NOT NULL, `weAreInitiator` INTEGER NOT NULL, " +
                        "`root` BLOB NOT NULL, `prevRoot` BLOB, `prevRootWeAreInitiator` INTEGER NOT NULL, " +
                        "`prevRootExpiresAt` INTEGER NOT NULL, `establishedAt` INTEGER NOT NULL, `initEphPub` BLOB, " +
                        "`initPkid` INTEGER NOT NULL, `peerInitEphPub` BLOB, `peerBasePub` BLOB, " +
                        "`peerBaseEpoch` INTEGER NOT NULL, `sendEpoch` INTEGER NOT NULL, `sendEpochPub` BLOB, " +
                        "`sendChainKey` BLOB, `sendCount` INTEGER NOT NULL, `sendEpochStartedAt` INTEGER NOT NULL, " +
                        "`sendEpochBaseEpoch` INTEGER NOT NULL, `sendEpochExport` BLOB, `highestPeAcked` INTEGER NOT NULL, " +
                        "`lastResetSentAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`))",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ratchet_local_epochs` (" +
                        "`peerId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `priv` BLOB NOT NULL, `pub` BLOB NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`, `epoch`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ratchet_local_epochs_createdAt` ON `ratchet_local_epochs` (`createdAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ratchet_recv_epochs` (" +
                        "`peerId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `chainKey` BLOB NOT NULL, `next` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`, `epoch`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ratchet_recv_epochs_lastUsedAt` ON `ratchet_recv_epochs` (`lastUsedAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ratchet_skipped_keys` (" +
                        "`peerId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `msgKey` BLOB NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`, `epoch`, `idx`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ratchet_skipped_keys_createdAt` ON `ratchet_skipped_keys` (`createdAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_send_chains` (" +
                        "`groupId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `seed` BLOB NOT NULL, " +
                        "`chainKey` BLOB NOT NULL, `count` INTEGER NOT NULL, `mintedAt` INTEGER NOT NULL, " +
                        "`export` BLOB NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `epoch`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_send_chains_mintedAt` ON `group_send_chains` (`mintedAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_recv_chains` (" +
                        "`groupId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, " +
                        "`mintedAt` INTEGER NOT NULL, `chainKey` BLOB NOT NULL, `next` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `senderId`, `epoch`, `mintedAt`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_recv_chains_lastUsedAt` ON `group_recv_chains` (`lastUsedAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_skipped_keys` (" +
                        "`groupId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, " +
                        "`mintedAt` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `msgKey` BLOB NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `senderId`, `epoch`, `mintedAt`, `idx`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_skipped_keys_createdAt` ON `group_skipped_keys` (`createdAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_key_sends` (" +
                        "`groupId` TEXT NOT NULL, `memberId` TEXT NOT NULL, `sentEpoch` INTEGER NOT NULL, " +
                        "`sentAt` INTEGER NOT NULL, `ackedEpoch` INTEGER NOT NULL, `ackedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`groupId`, `memberId`))",
                )
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `prekeyId` INTEGER")
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `prekeyPub` TEXT")
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `prekeySig` TEXT")
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `prekeyProfileAt` INTEGER")
            }
        }

    /**
     * v3 — the spool plane's group scopes (docs/SPOOL_PROTOCOL.md §3.2): one `group_roots` table holding
     * the shared group root the group scope id and seal keys derive from, the retiring lineage's drain
     * window, and the two idempotent stamps (mint grace, re-mint due). Purely local state — the wire
     * change that accompanies it (`GroupKeyPayload.gr`) is additive and breaks nothing. Additive only;
     * the SQL must stay byte-equivalent to what Room generates for `app/schemas/**/3.json`.
     */
    val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_roots` (" +
                        "`groupId` TEXT NOT NULL, `root` BLOB, `version` INTEGER NOT NULL, `minter` TEXT NOT NULL, " +
                        "`prevRoot` BLOB, `prevVersion` INTEGER NOT NULL, `prevExpiresAt` INTEGER NOT NULL, " +
                        "`firstEligibleAt` INTEGER NOT NULL, `remintDueAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`))",
                )
            }
        }

    /** All migrations, applied by Room in order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
