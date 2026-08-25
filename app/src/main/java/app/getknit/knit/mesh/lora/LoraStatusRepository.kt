package app.getknit.knit.mesh.lora

import app.getknit.knit.BuildConfig
import app.getknit.knit.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * The LoRa plane's state as the UI needs it: the settings that arm it folded with the transport's live
 * status. The sibling of `RelayStatusRepository`, but **pushed** — [LoraPlaneStatus.status] is already a
 * `StateFlow`, so there is no ticker and nothing to poll.
 *
 * In a build where the plane is dark (`BuildConfig.LORA_PLANE` false) the flow is a single `Off`: the
 * settings already read false there and the status is [LoraPlaneStatus.Dark], so combining them would only
 * keep three collectors alive per open chat for a value that cannot change.
 */
internal class LoraStatusRepository(
    private val settings: SettingsStore,
    private val lora: LoraPlaneStatus,
) {
    /** The flattened facts the chat header, the DM notice and the composer hint read. */
    val facts: Flow<LoraFacts> =
        if (!BuildConfig.LORA_PLANE) {
            flowOf(LoraFacts())
        } else {
            combine(
                settings.loraEnabled,
                settings.loraDeviceAddress,
                settings.loraDmEnabled,
                lora.status,
            ) { enabled, address, dms, status ->
                val plane = loraPlaneFor(enabled, bound = address != null, state = status.state)
                LoraFacts(
                    plane = plane,
                    dms = enabled && dms,
                    battery = status.battery.takeIf { plane == LoraPlane.Live },
                )
            }.distinctUntilChanged()
        }
}
