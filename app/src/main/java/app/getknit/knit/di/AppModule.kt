package app.getknit.knit.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.getknit.knit.crash.CrashReports
import app.getknit.knit.crash.crashStore
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.ratchet.GroupRootRepository
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.demo.DemoComposer
import app.getknit.knit.identity.AndroidDeviceIdSource
import app.getknit.knit.identity.DeviceIdSource
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetStore
import app.getknit.knit.mesh.crypto.ratchet.RatchetStore
import app.getknit.knit.mesh.spool.GroupRootStore
import app.getknit.knit.notifications.MessageNotifier
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.review.ReviewPrompter
import app.getknit.knit.ui.RouteInbox
import app.getknit.knit.ui.review.ReviewPromptInbox
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.voice.VoicePlayer
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule =
    module {
        single<DataStore<Preferences>> {
            PreferenceDataStoreFactory.create {
                androidContext().preferencesDataStoreFile("knit_settings")
            }
        }
        single { SettingsStore(get()) }
        // Stable per-device id (ANDROID_ID) — seeds the soft block-continuity DeviceTag, not the nodeId.
        single<DeviceIdSource> { AndroidDeviceIdSource(androidContext()) }
        // E2E identity keypair, wrapped under a hardware AndroidKeyStore key in filesDir (outside the DB).
        single { IdentityKeyStore(KeystoreSecret(androidContext(), "knit_identity_key", "identity.key")) }
        // nodeId is derived from the keypair's public bundle; the device id only feeds the block tag.
        single { Identity(get(), get()) }
        single { AvatarStore(androidContext(), get()) }
        single { AttachmentStore(androidContext(), get(), get()) }
        single { GallerySaver(androidContext()) }
        // One voice player for the whole app: any number of voice-note bubbles can be on screen, and
        // starting one note has to stop whichever was playing. Owns its own scope (see VoicePlayer).
        single { VoicePlayer(androidContext(), get()) }
        single<Notifier> { MessageNotifier(androidContext()) }
        // Single-shot handoff for content arriving via the system share sheet (ACTION_SEND).
        single { ShareInbox() }
        // Debug trailer seam driving the real Nearby composer (see DemoComposer). Inert in every build
        // unless the debug DemoDirector emits into it; R8 strips it from release.
        single { DemoComposer() }
        // Single-shot handoff for a notification-tap deep-link route (drained by KnitApp).
        single { RouteInbox() }
        // Single-shot signal that the rate/review prompt should show (drained by KnitApp).
        single { ReviewPromptInbox() }
        // Decides when to ask for an app rating and where to route it (installer-aware); no-op in demo builds.
        single { ReviewPrompter(androidContext(), get(), get(), get(), get()) }

        single { DatabaseKey(androidContext()) }
        single { KnitDatabase.build(androidContext(), get<DatabaseKey>().getOrCreate()) }
        single { get<KnitDatabase>().messageDao() }
        single { get<KnitDatabase>().peerDao() }
        single { get<KnitDatabase>().reactionDao() }
        single { get<KnitDatabase>().blobDao() }
        single { get<KnitDatabase>().groupDao() }
        single { get<KnitDatabase>().blobVerdictDao() }
        single { get<KnitDatabase>().forwardDao() }
        single { get<KnitDatabase>().ratchetDao() }
        single { get<KnitDatabase>().groupRatchetDao() }
        single { get<KnitDatabase>().groupRootDao() }
        single { MessageRepository(get()) }
        single { PeerRepository(get()) }
        // Crash reports. The capture-side CrashStore is built by hand in KnitApplication.onCreate BEFORE
        // startKoin, so a crash inside startup itself is still captured; this is the reader side over the
        // same fixed directory (crashStore() is the single definition of the path, so the two can't drift).
        // Two instances is deliberate and harmless — CrashStore holds no state beyond that File.
        single { crashStore(androidContext()) }
        // Applies the known-contact-name redaction pass the dying handler couldn't run (the names live in
        // the encrypted DB and DataStore) and stages the share copy under cacheDir/crash.
        single { CrashReports(androidContext(), get(), get(), get(), get()) }
        single { ReactionRepository(get(), get()) }
        // BlobRepository: blobDao, messageDao, peerDao, settings, blobVerdictDao, groupDao, forwardDao, db.
        single { BlobRepository(get(), get(), get(), get(), get(), get(), get(), get()) }
        single { GroupRepository(get(), get(), get(), get(), get()) }
        // Store-and-forward custody for DMs, backed by the encrypted forward_store table. Takes the shared
        // StoreDigest (from meshModule) so every carry-store mutation keeps the cue-plane content digest in sync,
        // plus the KnitDatabase so store/remove/sweep run their DB writes in a transaction under the repo mutex.
        single<ForwardStore> { ForwardRepository(get(), get(), get()) }
        // DM epoch-ratchet session state (docs/FORWARD_SECRECY_RATCHET.md), in the encrypted DB so the
        // ratchet advance commits in the same transaction as the message row it decrypted/sealed.
        single<RatchetStore> { RatchetRepository(get()) }
        // Group sender-key ratchet state (docs/GROUP_FORWARD_SECRECY.md), same transactional posture.
        single<GroupRatchetStore> { GroupRatchetRepository(get()) }
        // The spool plane's shared group roots (docs/SPOOL_PROTOCOL.md §3.2). Deliberately NOT scoped to the
        // Internet plane's own lifetime: a device with the plane off still adopts and re-gossips roots, which
        // is what carries one across a plane-off member sitting between two plane-on ones.
        single<GroupRootStore> { GroupRootRepository(get()) }
    }
