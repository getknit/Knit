package app.getknit.knit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.ratchet.GroupKeySendEntity
import app.getknit.knit.data.ratchet.GroupRatchetDao
import app.getknit.knit.data.ratchet.GroupRecvChainEntity
import app.getknit.knit.data.ratchet.GroupSendChainEntity
import app.getknit.knit.data.ratchet.GroupSkippedKeyEntity
import app.getknit.knit.data.ratchet.RatchetDao
import app.getknit.knit.data.ratchet.RatchetLocalEpochEntity
import app.getknit.knit.data.ratchet.RatchetRecvEpochEntity
import app.getknit.knit.data.ratchet.RatchetSessionEntity
import app.getknit.knit.data.ratchet.RatchetSkippedKeyEntity
import app.getknit.knit.data.reaction.ReactionDao
import app.getknit.knit.data.reaction.ReactionEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        MessageEntity::class, PeerEntity::class, ReactionEntity::class, BlobEntity::class,
        GroupEntity::class, BlobVerdictEntity::class, ForwardEntity::class,
        RatchetSessionEntity::class, RatchetLocalEpochEntity::class,
        RatchetRecvEpochEntity::class, RatchetSkippedKeyEntity::class,
        GroupSendChainEntity::class, GroupRecvChainEntity::class,
        GroupSkippedKeyEntity::class, GroupKeySendEntity::class,
    ],
    // v1: frozen launch baseline. The pre-1.0 alpha schema churn (the old destructive v2…v22 bumps that
    //     rode the wire/crypto breaks) is collapsed; docs/WIRE_COMPAT.md keeps the historical break record.
    //     From v1 on, every @Database bump ships a tested KnitMigrations entry — a missing one throws at open
    //     time (caught by KnitDatabaseMigrationTest), never a silent wipe of a user's messages/custody/pins.
    // v2: the ratchet schemes, one never-released bump — DM epoch-ratchet state (4 ratchet_* tables),
    //     group sender-key state (4 group_* tables: send/recv chains, skipped keys, the seed outbox),
    //     and the peers prekey columns (docs/FORWARD_SECRECY_RATCHET.md +
    //     docs/GROUP_FORWARD_SECRECY.md); migrated by KnitMigrations.MIGRATION_1_2.
    version = 2,
    // Export the schema JSON to app/schemas/ (location set by the androidx.room Gradle plugin's
    // room { schemaDirectory(...) } in app/build.gradle.kts). Keeps the schema diffable in review and feeds
    // the migration test's MigrationTestHelper. Room also errors at compile time if an entity changes without
    // a version bump.
    exportSchema = true,
)
abstract class KnitDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun peerDao(): PeerDao

    abstract fun reactionDao(): ReactionDao

    abstract fun blobDao(): BlobDao

    abstract fun groupDao(): GroupDao

    abstract fun blobVerdictDao(): BlobVerdictDao

    abstract fun forwardDao(): ForwardDao

    abstract fun ratchetDao(): RatchetDao

    abstract fun groupRatchetDao(): GroupRatchetDao

    companion object {
        /**
         * Builds the encrypted database. [passphrase] is the SQLCipher key (see
         * [app.getknit.knit.data.crypto.DatabaseKey]); SQLCipher zeroes it once the DB is opened.
         * The native `libsqlcipher.so` must be loaded explicitly before the factory is created.
         */
        @Suppress("SpreadOperator") // vararg Room migrations API; a one-time DB-init copy
        fun build(
            context: Context,
            passphrase: ByteArray,
        ): KnitDatabase {
            System.loadLibrary("sqlcipher")
            return Room
                .databaseBuilder(context, KnitDatabase::class.java, "knit.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                // Production migration posture: v1 is the frozen launch baseline, with NO destructive fallback.
                // Every schema change from here ships a tested KnitMigrations entry; a version bump with no
                // matching migration makes Room throw at open time (caught by KnitDatabaseMigrationTest) — a loud
                // failure in CI, never a silent wipe of a user's messages/custody/pins in production.
                .addMigrations(*KnitMigrations.ALL)
                .build()
        }
    }
}
