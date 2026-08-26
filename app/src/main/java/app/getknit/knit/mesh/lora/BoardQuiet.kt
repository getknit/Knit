package app.getknit.knit.mesh.lora

/**
 * The board's own housekeeping broadcasts, as they stood before Knit touched them — recorded at dedicate
 * time so "Restore Meshtastic defaults" puts back *the user's* settings rather than the firmware's.
 */
internal data class BoardIntervals(
    val nodeInfoSecs: Int,
    val positionSecs: Int,
    val smartPosition: Boolean,
    val telemetrySecs: Int,
)

/**
 * What a dedicated board stops shouting about (ADR 045).
 *
 * A stock Meshtastic node re-broadcasts its node-info, its position and its device telemetry on a schedule.
 * Each of those is a 3-hop flood costing seconds of air, and on a dedicated board they ride the *Knit*
 * channel — the primary is Knit now — so they compete directly with chat that somebody is waiting for.
 * Knit needs none of them: it identifies peers from its own signed frames, and the battery row reads the
 * board's phone-only telemetry, which is a separate firmware timer from
 * [MeshtasticProto.TELEMETRY_DEVICE_UPDATE_INTERVAL]'s mesh broadcast.
 *
 * Pure policy over field numbers, so both directions are one table and a test can read them.
 */
internal object BoardQuiet {
    /** What every quieted interval is stretched to: six hours, comfortably past every firmware default. */
    const val QUIET_SECS = 21_600

    /** Firmware defaults, used by [restore] only when a dedicate never recorded the board's own values. */
    const val DEFAULT_NODE_INFO_SECS = 10_800
    const val DEFAULT_POSITION_SECS = 900
    const val DEFAULT_TELEMETRY_SECS = 1_800

    /** The board's current settings, read out of the three raw sub-configs the admin reads returned. */
    fun recorded(configs: Map<BoardConfig, ByteArray>): BoardIntervals {
        val device = configs[BoardConfig.DEVICE]
        val position = configs[BoardConfig.POSITION]
        val telemetry = configs[BoardConfig.TELEMETRY]
        return BoardIntervals(
            nodeInfoSecs = device.secs(MeshtasticProto.DEVICE_NODE_INFO_BROADCAST_SECS, DEFAULT_NODE_INFO_SECS),
            positionSecs = position.secs(MeshtasticProto.POSITION_BROADCAST_SECS, DEFAULT_POSITION_SECS),
            // A plain proto3 bool has no presence: absent really is false, not "apply the firmware default".
            smartPosition = position?.let { (readVarintField(it, MeshtasticProto.POSITION_BROADCAST_SMART) ?: 0L) != 0L } ?: false,
            telemetrySecs = telemetry.secs(MeshtasticProto.TELEMETRY_DEVICE_UPDATE_INTERVAL, DEFAULT_TELEMETRY_SECS),
        )
    }

    /** The fields to splice into [config] to quiet it. */
    fun quiet(config: BoardConfig): Map<Int, Long> =
        when (config) {
            BoardConfig.DEVICE -> {
                mapOf(MeshtasticProto.DEVICE_NODE_INFO_BROADCAST_SECS to QUIET_SECS.toLong())
            }

            BoardConfig.POSITION -> {
                mapOf(
                    MeshtasticProto.POSITION_BROADCAST_SECS to QUIET_SECS.toLong(),
                    MeshtasticProto.POSITION_BROADCAST_SMART to 0L,
                )
            }

            BoardConfig.TELEMETRY -> {
                mapOf(MeshtasticProto.TELEMETRY_DEVICE_UPDATE_INTERVAL to QUIET_SECS.toLong())
            }
        }

    /** The fields to splice into [config] to undo [quiet], back to [previous] or to the firmware defaults. */
    fun restore(
        config: BoardConfig,
        previous: BoardIntervals?,
    ): Map<Int, Long> =
        when (config) {
            BoardConfig.DEVICE -> {
                mapOf(
                    MeshtasticProto.DEVICE_NODE_INFO_BROADCAST_SECS to
                        (previous?.nodeInfoSecs ?: DEFAULT_NODE_INFO_SECS).toLong(),
                )
            }

            BoardConfig.POSITION -> {
                mapOf(
                    MeshtasticProto.POSITION_BROADCAST_SECS to (previous?.positionSecs ?: DEFAULT_POSITION_SECS).toLong(),
                    MeshtasticProto.POSITION_BROADCAST_SMART to if (previous?.smartPosition != false) 1L else 0L,
                )
            }

            BoardConfig.TELEMETRY -> {
                mapOf(
                    MeshtasticProto.TELEMETRY_DEVICE_UPDATE_INTERVAL to
                        (previous?.telemetrySecs ?: DEFAULT_TELEMETRY_SECS).toLong(),
                )
            }
        }

    /** A seconds field, treating both "absent" and an explicit 0 as "the firmware substitutes its default". */
    private fun ByteArray?.secs(
        field: Int,
        default: Int,
    ): Int = this?.let { readVarintField(it, field)?.toInt() }?.takeIf { it > 0 } ?: default
}
