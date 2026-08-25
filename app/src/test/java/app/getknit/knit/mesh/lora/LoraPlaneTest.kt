package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Test

/** The header's coarse view of the board: the pure fold from settings + link state. */
class LoraPlaneTest {
    private val ready =
        LinkState.Ready(
            board = BoardInfo(myNodeNum = 1u, pioEnv = "heltec-v4", firmwareVersion = "2.5.0"),
            channels = emptyList(),
            mtu = 512,
        )

    @Test
    fun `off outranks the link`() {
        // The user switched it off (or forgot the board) while the session was still up: Off is what they asked for.
        assertEquals(LoraPlane.Off, loraPlaneFor(enabled = false, bound = true, state = ready))
        assertEquals(LoraPlane.Off, loraPlaneFor(enabled = true, bound = false, state = ready))
    }

    @Test
    fun `a ready session is live`() {
        assertEquals(LoraPlane.Live, loraPlaneFor(enabled = true, bound = true, state = ready))
    }

    @Test
    fun `every other link state is down`() {
        val states =
            listOf(
                LinkState.Idle,
                LinkState.Connecting,
                LinkState.Bonding,
                LinkState.Handshaking(board = null),
                LinkState.Disconnected(reason = "gatt", retryAtMs = 5_000L, streak = 1),
                LinkState.Unavailable,
                LinkState.NeedsPairing(address = "AA:BB"),
                LinkState.StaleBond(address = "AA:BB"),
            )
        for (state in states) {
            assertEquals(state.toString(), LoraPlane.Down, loraPlaneFor(enabled = true, bound = true, state = state))
        }
    }
}
