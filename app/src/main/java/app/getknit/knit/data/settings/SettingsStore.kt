package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User/device settings backed by a Preferences DataStore (replaces the legacy
 * SharedPreferences). Holds the profile/mesh toggles and per-conversation read state. (The node id is
 * no longer persisted here — it is derived from the E2E keypair; see [app.getknit.knit.identity.Identity].)
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
) : InboundSettings {
    override val displayName: Flow<String> = dataStore.data.map { it[KEY_NAME] ?: "" }
    val status: Flow<String> = dataStore.data.map { it[KEY_STATUS] ?: "" }

    /** Bumped whenever the avatar image changes, so profile re-broadcasts can be triggered. */
    val avatarUpdatedAt: Flow<Long> = dataStore.data.map { it[KEY_AVATAR_UPDATED_AT] ?: 0L }

    /**
     * Monotonic version of this device's own profile — the LWW key receivers order against, carried in
     * `ProfileContent.version`. Must be **stable across app restarts** (persisted here, not a launch
     * timestamp), so a relaunch does not look like an edit to every peer. Bumped only on a real profile edit
     * or a prekey rotation (see `MeshManager`); 0 until the first one.
     */
    val profileVersion: Flow<Long> = dataStore.data.map { it[KEY_PROFILE_VERSION] ?: 0L }

    /**
     * When this device last *published* its profile frame — the frame's `sentAt` and id, distinct from
     * [profileVersion]. Custody expiry is `sentAt + ttl`, so a frame stamped with the edit time is refused
     * as dead on arrival once that edit is a day old: the profile silently leaves custody, a late joiner
     * cannot pull it, and the Internet plane (which seals what custody holds) cannot carry it at all.
     * `MeshManager` re-publishes on a cadence inside the custody TTL and records the stamp here so the
     * cadence survives restarts. 0 until the first publish.
     */
    val profilePublishedAt: Flow<Long> = dataStore.data.map { it[KEY_PROFILE_PUBLISHED_AT] ?: 0L }

    /**
     * Content hash of the device's own avatar, or null if none is set. The avatar bytes live in the
     * encrypted `blobs` table keyed by this hash; the hash is what the profile frame advertises and
     * what the UI/notifications resolve against. (Pre-v6 this was derived from the avatar's filename.)
     */
    override val ownAvatarHash: Flow<String?> = dataStore.data.map { it[KEY_OWN_AVATAR_HASH] }

    /**
     * Per-conversation read watermarks: for each conversation id, the [MessageEntity.sentAt] of the
     * newest message the local user has seen there. The chat list counts messages newer than the
     * watermark (from other senders) as that conversation's unread badge. Stored under one dynamic
     * key per conversation (see [lastReadKey]); [lastReadAll] reads them back as a map for the list.
     */
    val lastReadAll: Flow<Map<String, Long>> =
        dataStore.data.map { prefs ->
            prefs
                .asMap()
                .filterKeys { it.name.startsWith(LAST_READ_PREFIX) }
                .entries
                .associate { (key, value) -> key.name.removePrefix(LAST_READ_PREFIX) to (value as? Long ?: 0L) }
        }

    /** Read watermark for a single conversation (0 until the user has read anything there). */
    fun lastReadAt(conversationId: String): Flow<Long> = dataStore.data.map { it[lastReadKey(conversationId)] ?: 0L }

    /**
     * Node ids the local user has blocked. Their messages/reactions are never stored, shown, or
     * notified, and they're hidden from the new-DM picker. Blocking is local-only and keyed by the
     * peer's node id; since a node id is now the hash of the peer's keypair, a blocked peer that
     * regenerates its identity key (e.g. a reinstall that drops `identity.key`) gets a fresh id and is
     * no longer matched — the cost of binding identity to the key rather than the device.
     */
    override val blockedNodeIds: Flow<Set<String>> = dataStore.data.map { it[KEY_BLOCKED] ?: emptySet() }

    /**
     * Device tags (see [app.getknit.knit.identity.DeviceTag]) the user has blocked. Because a nodeId is
     * the hash of a keypair, a blocked peer that regenerates its key returns under a new nodeId; the
     * device tag is key-independent, so `MeshManager.handleProfile` re-blocks the new id when the tag
     * matches. Maintained alongside [blockedNodeIds] by [block]/[unblock].
     */
    override val blockedDeviceTags: Flow<Set<String>> = dataStore.data.map { it[KEY_BLOCKED_TAGS] ?: emptySet() }

    /**
     * Conversation ids the user has explicitly **accepted** out of the message-request queue — a DM keyed by
     * the peer's node id, or a group keyed by its "g-…" id (see [app.getknit.knit.data.message.Conversations]).
     * `InboundPipeline` treats a DM/group as a stranger *request* — notifications suppressed, storage bounded —
     * unless it is accepted here, the DM peer is verified, or the user has already sent into it. Local-only and,
     * like [blockedNodeIds], keyed by node id for DMs, so a contact that regenerates its identity key returns as
     * a fresh request (a one-tap re-accept; the verified / own-message signals usually cover it anyway).
     */
    override val acceptedConversations: Flow<Set<String>> = dataStore.data.map { it[KEY_ACCEPTED] ?: emptySet() }

    /**
     * Whether to hide sensitive content received from others. Defaults to on. Gates receive-side hiding
     * only — the inbound toxic-text collapse, the inbound explicit-image blur, and the explicit-avatar
     * rejection (off → adopt anyway). It does **not** affect sending: the sender-side "good-citizen"
     * checks (block abusive text, confirm/hard-block explicit images) and the on-device screening always
     * run regardless, so toggling this flips already-received content's blur/collapse reactively without
     * re-scanning.
     */
    override val contentFilteringEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_CONTENT_FILTERING] ?: true }

    /**
     * Whether the mesh foreground service should be running — the persisted twin of "is the mesh on".
     * Defaults to on. Flipped to false when the user manually stops the service from its ongoing
     * notification, and back to true whenever the service (re)starts, so [app.getknit.knit.mesh.BootReceiver]
     * can restore the mesh after a device reboot **unless** the user had stopped it beforehand.
     */
    val meshEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_MESH_ENABLED] ?: true }

    /**
     * Local-clock time the first peer message was observed (0 until then) — the start of the
     * review-prompt engagement window (see [app.getknit.knit.review.ReviewPromptPolicy]). Deliberately a
     * locally-stamped watermark rather than anything derived from a message's `sentAt`, which is the
     * sender's skewable clock.
     */
    val reviewEngagementStartedAt: Flow<Long> = dataStore.data.map { it[KEY_REVIEW_ENGAGEMENT_STARTED_AT] ?: 0L }

    /** Local-clock time of the last rate-prompt shown (0 = never). See [recordReviewAttempt]. */
    val reviewLastAttemptAt: Flow<Long> = dataStore.data.map { it[KEY_REVIEW_LAST_ATTEMPT_AT] ?: 0L }

    /** Lifetime rate-prompts shown — we don't record the user's choice, so shown-count is all we keep. */
    val reviewAttemptCount: Flow<Long> = dataStore.data.map { it[KEY_REVIEW_ATTEMPT_COUNT] ?: 0L }

    /**
     * Whether the Internet (spool) plane may run — **default off**, deliberately. Uploading a
     * conversation's sealed history to third-party machines is a real threat-model change, so it is a
     * choice the user makes rather than one they inherit (docs/SPOOL_PROTOCOL.md §10). With it off, or
     * with [spoolUrls] empty, `ScopeSync` opens no socket at all.
     */
    val spoolEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_SPOOL_ENABLED] ?: false }

    /**
     * The spools to sync every scope against — full `wss://host/spool/v1[?k=token]` URLs. A set, since a
     * scope's members converge through the union of whatever every spool holds; the spools themselves
     * never talk to each other. Release builds refuse a non-`wss://` entry at dial time.
     *
     * Until the signed scope-config ctl ships (spec §5), this list is per-device rather than
     * per-conversation: every scope is synced against every configured spool.
     */
    val spoolUrls: Flow<Set<String>> = dataStore.data.map { it[KEY_SPOOL_URLS] ?: emptySet() }

    /**
     * Whether the user has been shown, and accepted, the disclosure behind [spoolEnabled] — what a spool
     * can observe (IP, timing, volume) and cannot (content, roster), that the choice is global rather
     * than per-conversation, and that switching off stops new uploads while sealed copies already at a
     * spool age out on the scope TTL.
     *
     * Kept separate from [spoolEnabled] rather than inferred from it so that turning the plane off and on
     * again does not re-prompt: consent is about having read the disclosure once, not about the current
     * switch position.
     */
    val spoolConsented: Flow<Boolean> = dataStore.data.map { it[KEY_SPOOL_CONSENTED] ?: false }

    suspend fun setDisplayName(value: String) = dataStore.edit { it[KEY_NAME] = value }

    suspend fun setStatus(value: String) = dataStore.edit { it[KEY_STATUS] = value }

    /** Persists display name + status in a single transaction so the profile watcher broadcasts once. */
    suspend fun setProfile(
        name: String,
        status: String,
    ) = dataStore.edit {
        it[KEY_NAME] = name
        it[KEY_STATUS] = status
    }

    suspend fun setAvatarUpdatedAt(value: Long) = dataStore.edit { it[KEY_AVATAR_UPDATED_AT] = value }

    suspend fun setProfileVersion(value: Long) = dataStore.edit { it[KEY_PROFILE_VERSION] = value }

    suspend fun setProfilePublishedAt(value: Long) = dataStore.edit { it[KEY_PROFILE_PUBLISHED_AT] = value }

    suspend fun setOwnAvatarHash(value: String) = dataStore.edit { it[KEY_OWN_AVATAR_HASH] = value }

    /** Removes the stored own-avatar hash so [ownAvatarHash] emits null again (the user cleared their photo). */
    suspend fun clearOwnAvatarHash() = dataStore.edit { it.remove(KEY_OWN_AVATAR_HASH) }

    suspend fun setLastReadAt(
        conversationId: String,
        value: Long,
    ) = dataStore.edit { it[lastReadKey(conversationId)] = value }

    /** Blocks [nodeId]; also records the peer's [deviceTag] (when known) so the block survives a key reset. */
    override suspend fun block(
        nodeId: String,
        deviceTag: String?,
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_BLOCKED] = (prefs[KEY_BLOCKED] ?: emptySet()) + nodeId
            if (deviceTag != null) {
                prefs[KEY_BLOCKED_TAGS] = (prefs[KEY_BLOCKED_TAGS] ?: emptySet()) + deviceTag
            }
        }
    }

    /** Unblocks [nodeId]; also clears its [deviceTag] (when known) so the device is no longer re-blocked. */
    suspend fun unblock(
        nodeId: String,
        deviceTag: String? = null,
    ) = dataStore.edit { prefs ->
        prefs[KEY_BLOCKED] = (prefs[KEY_BLOCKED] ?: emptySet()) - nodeId
        if (deviceTag != null) {
            prefs[KEY_BLOCKED_TAGS] = (prefs[KEY_BLOCKED_TAGS] ?: emptySet()) - deviceTag
        }
    }

    /** Accepts [conversationId] out of the message-request queue (a DM peer id or a "g-…" group id). */
    suspend fun accept(conversationId: String) = dataStore.edit { it[KEY_ACCEPTED] = (it[KEY_ACCEPTED] ?: emptySet()) + conversationId }

    suspend fun setContentFilteringEnabled(value: Boolean) = dataStore.edit { it[KEY_CONTENT_FILTERING] = value }

    suspend fun setMeshEnabled(value: Boolean) = dataStore.edit { it[KEY_MESH_ENABLED] = value }

    suspend fun setReviewEngagementStartedAt(value: Long) = dataStore.edit { it[KEY_REVIEW_ENGAGEMENT_STARTED_AT] = value }

    /** Stamps the attempt time and bumps the lifetime count in one transaction (mirrors [setProfile]). */
    suspend fun recordReviewAttempt(now: Long) =
        dataStore.edit {
            it[KEY_REVIEW_LAST_ATTEMPT_AT] = now
            it[KEY_REVIEW_ATTEMPT_COUNT] = (it[KEY_REVIEW_ATTEMPT_COUNT] ?: 0L) + 1
        }

    /** Clears all review-prompt state (debug bridge reset). */
    suspend fun clearReviewState() =
        dataStore.edit {
            it.remove(KEY_REVIEW_ENGAGEMENT_STARTED_AT)
            it.remove(KEY_REVIEW_LAST_ATTEMPT_AT)
            it.remove(KEY_REVIEW_ATTEMPT_COUNT)
        }

    suspend fun setSpoolEnabled(value: Boolean) = dataStore.edit { it[KEY_SPOOL_ENABLED] = value }

    /**
     * Records consent and enables the plane in **one** write, so the two can never disagree: a crash
     * between two edits would otherwise leave a device either consented-but-off (harmless) or, if the
     * order were reversed, relaying without having recorded that the disclosure was accepted.
     */
    suspend fun acceptSpoolConsent() =
        dataStore.edit {
            it[KEY_SPOOL_CONSENTED] = true
            it[KEY_SPOOL_ENABLED] = true
        }

    /**
     * Seeds the shipped default spools (`res/values/spools.xml`) into [spoolUrls] exactly once, marking
     * the install as seeded so a **removal sticks**. A default the app kept re-adding would not be a
     * default, it would be a policy — and this list decides which third parties see a conversation's
     * traffic pattern, so the user's edit has to be the last word.
     *
     * Idempotent and safe to call on every start. Seeding a URL does not use it: the plane stays off
     * until [spoolEnabled] is set, so a fresh install still opens no socket.
     */
    suspend fun seedDefaultSpools(defaults: List<String>) =
        dataStore.edit { prefs ->
            if (prefs[KEY_SPOOL_SEEDED] == true) return@edit
            prefs[KEY_SPOOL_SEEDED] = true
            if (defaults.isNotEmpty()) prefs[KEY_SPOOL_URLS] = (prefs[KEY_SPOOL_URLS] ?: emptySet()) + defaults
        }

    /** Adds a spool URL to sync against (idempotent — the setting is a set, not a list). */
    suspend fun addSpoolUrl(url: String) = dataStore.edit { it[KEY_SPOOL_URLS] = (it[KEY_SPOOL_URLS] ?: emptySet()) + url }

    suspend fun removeSpoolUrl(url: String) = dataStore.edit { it[KEY_SPOOL_URLS] = (it[KEY_SPOOL_URLS] ?: emptySet()) - url }

    /** Dynamic per-conversation read-watermark key, e.g. "last_read_nearby" / "last_read_<nodeId>". */
    private fun lastReadKey(conversationId: String) = longPreferencesKey(LAST_READ_PREFIX + conversationId)

    private companion object {
        const val LAST_READ_PREFIX = "last_read_"

        val KEY_NAME = stringPreferencesKey("display_name")
        val KEY_STATUS = stringPreferencesKey("status")
        val KEY_AVATAR_UPDATED_AT = longPreferencesKey("avatar_updated_at")
        val KEY_PROFILE_VERSION = longPreferencesKey("profile_version")
        val KEY_PROFILE_PUBLISHED_AT = longPreferencesKey("profile_published_at")
        val KEY_OWN_AVATAR_HASH = stringPreferencesKey("own_avatar_hash")
        val KEY_BLOCKED = stringSetPreferencesKey("blocked_node_ids")
        val KEY_BLOCKED_TAGS = stringSetPreferencesKey("blocked_device_tags")
        val KEY_ACCEPTED = stringSetPreferencesKey("accepted_conversations")
        val KEY_CONTENT_FILTERING = booleanPreferencesKey("content_filtering_enabled")
        val KEY_MESH_ENABLED = booleanPreferencesKey("mesh_enabled")
        val KEY_REVIEW_ENGAGEMENT_STARTED_AT = longPreferencesKey("review_engagement_started_at")
        val KEY_REVIEW_LAST_ATTEMPT_AT = longPreferencesKey("review_last_attempt_at")
        val KEY_REVIEW_ATTEMPT_COUNT = longPreferencesKey("review_attempt_count")
        val KEY_SPOOL_ENABLED = booleanPreferencesKey("spool_enabled")
        val KEY_SPOOL_URLS = stringSetPreferencesKey("spool_urls")
        val KEY_SPOOL_SEEDED = booleanPreferencesKey("spool_defaults_seeded")
        val KEY_SPOOL_CONSENTED = booleanPreferencesKey("spool_consented")
    }
}
