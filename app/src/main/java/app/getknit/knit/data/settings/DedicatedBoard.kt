package app.getknit.knit.data.settings

/**
 * A Meshtastic board handed over to Knit (ADR 045): Knit is its primary channel — which is what moves the
 * radio onto a Knit-derived RF slot — and its housekeeping broadcasts are stretched.
 *
 * The four intervals are the board's own values as they stood *before* the hand-over, kept so "Restore
 * Meshtastic defaults" can put those back instead of guessing at the firmware's. A zero means the value was
 * never recorded, which the restore writes as "let the firmware substitute its default".
 *
 * Primitive by design: [SettingsStore] is a data-layer class and stays free of `mesh/` types; the LoRa
 * ViewModel maps this onto `mesh.lora.BoardIntervals` at the seam.
 */
data class DedicatedBoard(
    val address: String,
    val nodeInfoSecs: Int,
    val positionSecs: Int,
    val smartPosition: Boolean,
    val telemetrySecs: Int,
)
