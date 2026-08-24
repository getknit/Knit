package app.getknit.knit.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * What survives a process death about one bundled on-device model's first load.
 *
 * @param stamp the app version + OS build this record was written under; a change discards the whole
 *   record, because a new app version may ship a new model or a new LiteRT and a new ROM may fix the
 *   driver that faulted
 * @param pendingSince when a load was started and not yet finished (epoch millis; 0 = nothing in flight).
 *   Finding this non-zero on a later launch is the *entire* signal: the completion write runs in a
 *   `finally`, so a load that merely failed, was cancelled, or found no asset clears it too — only a
 *   process death leaves it set.
 * @param fails consecutive process deaths inside this model's load that the platform could not explain
 */
data class ModelLoadState(
    val stamp: String,
    val pendingSince: Long,
    val fails: Int,
) {
    companion object {
        /** A model this device has never tried to load. */
        val NONE = ModelLoadState(stamp = "", pendingSince = 0L, fails = 0)
    }
}

/**
 * The three-method slice of [SettingsStore] the model poison-pill needs (see
 * `moderation/ModelLoadGuard`, ADR 037). Extracted as a seam — the [InboundSettings] precedent — so the
 * guard's state machine is exercised in a plain JVM test instead of standing up a Preferences DataStore.
 *
 * [modelLoadState] is a `suspend` read, not a `Flow`: the guard reads once, immediately before a load it
 * may never return from, and needs the current value rather than a stream. [observeModelLoad] is the
 * stream, and exists for Diagnostics — where the latch *can* change under the user, because the reset
 * button is right there.
 *
 * [setModelLoadState] is the durability barrier the whole mechanism rests on. DataStore's `edit {}`
 * writes a scratch sibling, `fsync`s it and renames it, and does not resume until that completes
 * (`datastore-core` 1.2.1 `FileStorage.kt`), so a native crash microseconds later still finds the marker
 * on disk. (Its own `TODO(b/151635324)` notes the *directory* is not fsynced — that would matter for a
 * power cut, not for a process death, which is the only thing this guards against.)
 */
interface ModelLoadJournal {
    fun observeModelLoad(model: String): Flow<ModelLoadState>

    suspend fun modelLoadState(model: String): ModelLoadState

    suspend fun setModelLoadState(
        model: String,
        state: ModelLoadState,
    )
}
