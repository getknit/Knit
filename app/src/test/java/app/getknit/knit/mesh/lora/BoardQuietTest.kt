package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Test

/** The quieting table (ADR 045), in both directions. */
class BoardQuietTest {
    private val device = ProtoWriter().varint(1, 2).varint(7, 900).toByteArray()
    private val position = ProtoWriter().varint(1, 600).varint(2, 1).toByteArray()
    private val telemetry = ProtoWriter().varint(1, 1_800).toByteArray()
    private val configs =
        mapOf(BoardConfig.DEVICE to device, BoardConfig.POSITION to position, BoardConfig.TELEMETRY to telemetry)

    @Test
    fun recordsWhatTheBoardWasSetTo() {
        assertEquals(
            BoardIntervals(nodeInfoSecs = 900, positionSecs = 600, smartPosition = true, telemetrySecs = 1_800),
            BoardQuiet.recorded(configs),
        )
    }

    @Test
    fun anUnsetIntervalIsRecordedAsTheFirmwareDefaultItStandsFor() {
        // A board that never wrote the field runs the firmware's own default, so that — not 0 — is what a
        // restore has to put back.
        val recorded = BoardQuiet.recorded(mapOf(BoardConfig.DEVICE to ByteArray(0)))
        assertEquals(BoardQuiet.DEFAULT_NODE_INFO_SECS, recorded.nodeInfoSecs)
        assertEquals(BoardQuiet.DEFAULT_POSITION_SECS, recorded.positionSecs)
        assertEquals(BoardQuiet.DEFAULT_TELEMETRY_SECS, recorded.telemetrySecs)
        assertEquals("absent really is false for a bool", false, recorded.smartPosition)
    }

    @Test
    fun quietingStretchesEveryIntervalAndClearsSmartBroadcast() {
        val quieted = configs.mapValues { (config, raw) -> spliceVarintFields(raw, BoardQuiet.quiet(config))!! }
        assertEquals(
            BoardIntervals(
                nodeInfoSecs = BoardQuiet.QUIET_SECS,
                positionSecs = BoardQuiet.QUIET_SECS,
                smartPosition = false,
                telemetrySecs = BoardQuiet.QUIET_SECS,
            ),
            BoardQuiet.recorded(quieted),
        )
    }

    @Test
    fun restoringAQuietedBoardReproducesWhatWasRecorded() {
        val before = BoardQuiet.recorded(configs)
        val quieted = configs.mapValues { (config, raw) -> spliceVarintFields(raw, BoardQuiet.quiet(config))!! }
        val restored = quieted.mapValues { (config, raw) -> spliceVarintFields(raw, BoardQuiet.restore(config, before))!! }
        assertEquals(before, BoardQuiet.recorded(restored))
    }

    @Test
    fun restoringWithoutARecordFallsBackToTheFirmwareDefaults() {
        val quieted = configs.mapValues { (config, raw) -> spliceVarintFields(raw, BoardQuiet.quiet(config))!! }
        val restored = quieted.mapValues { (config, raw) -> spliceVarintFields(raw, BoardQuiet.restore(config, null))!! }
        assertEquals(
            BoardIntervals(
                nodeInfoSecs = BoardQuiet.DEFAULT_NODE_INFO_SECS,
                positionSecs = BoardQuiet.DEFAULT_POSITION_SECS,
                smartPosition = true,
                telemetrySecs = BoardQuiet.DEFAULT_TELEMETRY_SECS,
            ),
            BoardQuiet.recorded(restored),
        )
    }
}
