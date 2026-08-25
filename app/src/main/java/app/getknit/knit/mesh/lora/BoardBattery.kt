package app.getknit.knit.mesh.lora

/**
 * The connected board's own power reading, as Meshtastic's `DeviceMetrics` reports it — read from the
 * board's `NodeInfo` in the config handshake and refreshed by the device telemetry the firmware sends the
 * phone (about once a minute while the phone is connected). Presentation-ready: the firmware's raw
 * `battery_level` conventions are folded into [percent] / [powered] in [of], the one place they live.
 */
data class BoardBattery(
    /** Charge, 0–100. Null while [powered] — the firmware stops estimating once on external power. */
    val percent: Int?,
    /** Battery voltage in volts, when the board measures one. */
    val voltage: Float?,
    /** Running on external (USB / solar) power: the firmware reports a level above 100. */
    val powered: Boolean,
) {
    /** Worth warning about: on the battery and at or under [LOW_PERCENT]. */
    val low: Boolean get() = !powered && percent != null && percent <= LOW_PERCENT

    companion object {
        /** At or below this the settings row paints the reading in the error colour. */
        const val LOW_PERCENT = 20

        /** The firmware's "on external power" sentinel: any `battery_level` above 100. */
        private const val POWERED_ABOVE = 100

        /**
         * Folds a raw `DeviceMetrics` pair into a reading, or null when the board reported nothing usable:
         * no `battery_level` at all (or the firmware's -1 "unknown" cast through a uint32), or a level of 0
         * with no voltage — a board without battery sense, not an empty cell (which still shows a voltage).
         */
        fun of(
            level: Int?,
            voltage: Float?,
        ): BoardBattery? {
            if (level == null || level < 0) return null
            val volts = voltage?.takeIf { it > 0f }
            return when {
                level > POWERED_ABOVE -> BoardBattery(percent = null, voltage = volts, powered = true)
                level == 0 && volts == null -> null
                else -> BoardBattery(percent = level, voltage = volts, powered = false)
            }
        }
    }
}
