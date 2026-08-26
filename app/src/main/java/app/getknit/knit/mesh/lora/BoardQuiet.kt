package app.getknit.knit.mesh.lora

/**
 * The board settings Knit rewrites, as they stood before it touched them — recorded at setup time so
 * restoring puts back *the user's* values rather than the firmware's.
 */
internal data class BoardSettings(
    val nodeInfoSecs: Int,
    val positionSecs: Int,
    val smartPosition: Boolean,
    val telemetrySecs: Int,
    /** `Config.DeviceConfig.RebroadcastMode`; 0 is the firmware default, ALL. */
    val rebroadcastMode: Int,
    /**
     * The board's own name before the setup renamed it ([BoardName]); null when no setup ever recorded one,
     * which a restore answers with the name the firmware itself would have given the board.
     */
    val owner: BoardOwner? = null,
)

/**
 * What a board set up for Knit stops spending air and battery on (ADR 045).
 *
 * Two costs, both invisible to the user. A stock node re-broadcasts its node-info, its position and its
 * device telemetry on a schedule, each a 3-hop flood costing seconds of air on the very band Knit is trying
 * to talk over; Knit needs none of them, since it identifies peers from its own signed frames, and the
 * battery row reads the board's *phone-only* telemetry, a separate firmware timer from
 * [MeshtasticProto.TELEMETRY_DEVICE_UPDATE_INTERVAL]'s mesh broadcast. And because Knit deliberately shares
 * the public frequency, a board left on the default `rebroadcast_mode = ALL` spends its battery repeating
 * **every** packet on the band — [REBROADCAST_LOCAL_ONLY] keeps it repeating its own channels, which is all
 * the pocket bridge needs, and nothing else.
 *
 * Pure policy over field numbers, so both directions are one table and a test can read them.
 */
internal object BoardQuiet {
    /** What every quieted interval is stretched to: six hours, comfortably past every firmware default. */
    const val QUIET_SECS = 21_600

    /**
     * `RebroadcastMode.LOCAL_ONLY` — "only rebroadcasts messages on the node's local primary / secondary
     * channels". Knit is one of those, so a board still relays Knit between pockets; what stops is paying the
     * battery to repeat strangers' traffic on the shared frequency.
     */
    const val REBROADCAST_LOCAL_ONLY = 2

    /** Firmware defaults, used by [restore] only when a setup never recorded the board's own values. */
    const val DEFAULT_NODE_INFO_SECS = 10_800
    const val DEFAULT_POSITION_SECS = 900
    const val DEFAULT_TELEMETRY_SECS = 1_800

    /** The board's current settings, read out of the three raw sub-configs and the `User` the admin reads returned. */
    fun recorded(
        configs: Map<BoardConfig, ByteArray>,
        owner: BoardOwner? = null,
    ): BoardSettings {
        val device = configs[BoardConfig.DEVICE]
        val position = configs[BoardConfig.POSITION]
        val telemetry = configs[BoardConfig.TELEMETRY]
        return BoardSettings(
            nodeInfoSecs = device.secs(MeshtasticProto.DEVICE_NODE_INFO_BROADCAST_SECS, DEFAULT_NODE_INFO_SECS),
            positionSecs = position.secs(MeshtasticProto.POSITION_BROADCAST_SECS, DEFAULT_POSITION_SECS),
            // A plain proto3 bool has no presence: absent really is false, not "apply the firmware default".
            smartPosition = position?.let { (readVarintField(it, MeshtasticProto.POSITION_BROADCAST_SMART) ?: 0L) != 0L } ?: false,
            telemetrySecs = telemetry.secs(MeshtasticProto.TELEMETRY_DEVICE_UPDATE_INTERVAL, DEFAULT_TELEMETRY_SECS),
            // 0 is a real value here (RebroadcastMode.ALL, the firmware default), not "unset".
            rebroadcastMode = device?.let { readVarintField(it, MeshtasticProto.DEVICE_REBROADCAST_MODE)?.toInt() } ?: 0,
            owner = owner,
        )
    }

    /** The fields to splice into [config] to quiet it. */
    fun quiet(config: BoardConfig): Map<Int, Long> =
        when (config) {
            BoardConfig.DEVICE -> {
                mapOf(
                    MeshtasticProto.DEVICE_NODE_INFO_BROADCAST_SECS to QUIET_SECS.toLong(),
                    MeshtasticProto.DEVICE_REBROADCAST_MODE to REBROADCAST_LOCAL_ONLY.toLong(),
                )
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
        previous: BoardSettings?,
    ): Map<Int, Long> =
        when (config) {
            BoardConfig.DEVICE -> {
                mapOf(
                    MeshtasticProto.DEVICE_NODE_INFO_BROADCAST_SECS to
                        (previous?.nodeInfoSecs ?: DEFAULT_NODE_INFO_SECS).toLong(),
                    MeshtasticProto.DEVICE_REBROADCAST_MODE to (previous?.rebroadcastMode ?: 0).toLong(),
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
