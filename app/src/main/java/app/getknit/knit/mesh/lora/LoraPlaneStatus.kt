package app.getknit.knit.mesh.lora

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The LoRa transport's read-only face for the UI — the settings screen and the connection header's
 * [LoraStatusRepository]: its live [status] plus the one action the settings screen drives. A seam rather
 * than the concrete [LoraMeshTransport] for two reasons: a build with the plane dark binds [Dark] and so
 * never instantiates the GATT/session singletons (`MeshModule` promises release never does, and the
 * chat-header repository is resolved by every open chat), and a ViewModel test hands in a
 * `MutableStateFlow<LoraStatus>` instead of a transport over a fake air.
 */
internal interface LoraPlaneStatus {
    /** A snapshot of the plane: link state, bound board, last signal reading, peers heard. */
    val status: StateFlow<LoraStatus>

    /** Writes the well-known Knit channel onto the connected board (`LoraMeshTransport.provisionKnitChannel`). */
    suspend fun provisionKnitChannel(): ProvisionResult

    /** The plane in a build that does not ship it: idle forever, and never provisions. */
    object Dark : LoraPlaneStatus {
        override val status: StateFlow<LoraStatus> = MutableStateFlow(LoraStatus())

        override suspend fun provisionKnitChannel(): ProvisionResult = ProvisionResult.NotReady(LinkState.Idle)
    }
}
