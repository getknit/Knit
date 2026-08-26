package app.getknit.knit.mesh.lora

/**
 * A snapshot of the LoRa plane for the settings/diagnostics row — the board it is bound to, the live
 * link state, the last signal reading, and the running counters. Derived purely from the transport's
 * existing state, so reading it never perturbs routing.
 */
internal data class LoraStatus(
    val state: LinkState = LinkState.Idle,
    val boardName: String? = null,
    val boardAddress: String? = null,
    val boardNodeNum: UInt? = null,
    val lastSnr: Float? = null,
    val lastRssi: Int? = null,
    val queueFree: Int? = null,
    val heard: Int = 0,
    /** The board's own power reading, once its handshake or telemetry has reported one. */
    val battery: BoardBattery? = null,
    /** The airtime ledger: what the plane has spent this hour against what it allows itself (ADR 044). */
    val airtime: AirtimeSnapshot? = null,
    /** Whether this phone speaks for its pocket on the hop, or another board here does (ADR 044). */
    val role: LoraGatewayPolicy.Role = LoraGatewayPolicy.Role.ACTIVE,
)
