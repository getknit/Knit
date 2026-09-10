package app.getknit.knit.data.settings

import app.getknit.knit.mesh.lora.LoraPlaneSnapshot
import app.getknit.knit.mesh.lora.LoraPlaneState
import kotlinx.serialization.json.Json

/**
 * The LoRa plane's [LoraPlaneState] over the settings DataStore: one JSON blob under one key.
 *
 * DataStore rather than the encrypted database because none of it is user content — it is a ledger of how
 * much air this device has spent and when it last beaconed, the same class of thing as the board binding
 * beside it. One blob rather than a key per limiter because the snapshot is read and written whole, and a
 * half-applied one would be worse than none.
 *
 * A blob that fails to parse (a downgrade, a truncated write) reads as absent: the plane then starts on a
 * clean window, which is the pre-existing behaviour and costs one window at most.
 */
internal class LoraPlaneStateStore(
    private val settings: SettingsStore,
) : LoraPlaneState {
    override suspend fun load(): LoraPlaneSnapshot? =
        settings.loraPlaneState()?.let { blob ->
            runCatching { json.decodeFromString<LoraPlaneSnapshot>(blob) }.getOrNull()
        }

    override suspend fun save(snapshot: LoraPlaneSnapshot) {
        settings.setLoraPlaneState(json.encodeToString(snapshot))
    }

    private companion object {
        // Unknown keys are ignored so a snapshot written by a newer build downgrades to what this one knows
        // rather than being thrown away whole — the limiters it does understand are still owed their air.
        val json = Json { ignoreUnknownKeys = true }
    }
}
