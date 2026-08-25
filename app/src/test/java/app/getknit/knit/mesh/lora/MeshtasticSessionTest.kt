package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.bluetooth.BackoffConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [MeshtasticSession] against [FakeGattChannel] under virtual time. `now` is bound to the test
 * scheduler and every GATT op is instantaneous in the fake, so `runCurrent()` settles the handshake at
 * t=0 while the 180 s heartbeat stays scheduled in the future (never use `advanceUntilIdle` — the
 * heartbeat ticker would spin forever).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshtasticSessionTest {
    private val nonce = 0x11u

    private fun scriptHandshake(ch: FakeGattChannel) {
        ch.onWrite = { bytes ->
            if (BoardBytes.isWantConfig(bytes)) {
                ch.enqueueRead(BoardBytes.myInfo(0xABCDu, "heltec-v4"))
                ch.enqueueRead(BoardBytes.channel(1, "knit", 2))
                ch.enqueueRead(BoardBytes.configComplete(nonce))
            }
        }
    }

    private fun session(
        dialer: FakeGattDialer,
        scope: kotlinx.coroutines.CoroutineScope,
        now: () -> Long,
    ) = MeshtasticSession(
        dialer = dialer,
        scope = scope,
        backoff = BackoffConfig(baseMs = 5_000, maxMs = 180_000, jitterFraction = 0.0),
        now = now,
        rand = { 0.5 },
        nonce = { nonce },
        ids = PacketIdSource(1000L),
    )

    @Test
    fun handshakeReachesReadyWithBoardAndChannels() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA:BB")
            runCurrent()

            val st = session.state.value
            assertTrue("state is Ready but was $st", st is LinkState.Ready)
            st as LinkState.Ready
            assertEquals(0xABCDu, st.board.myNodeNum)
            assertEquals("heltec-v4", st.board.pioEnv)
            assertEquals(listOf(ChannelInfo(1, "knit", 2)), st.channels)
            assertEquals(512, st.mtu)
            session.stop()
        }

    @Test
    fun aWrongNonceConfigCompleteIsIgnoredUntilTheRightOne() =
        runTest {
            val ch = FakeGattChannel()
            ch.onWrite = { bytes ->
                if (BoardBytes.isWantConfig(bytes)) {
                    ch.enqueueRead(BoardBytes.configComplete(0x99u)) // stale — from a previous handshake
                    ch.enqueueRead(BoardBytes.myInfo(0x1u, "heltec-v4"))
                    ch.enqueueRead(BoardBytes.configComplete(nonce)) // ours
                }
            }
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)
            session.stop()
        }

    @Test
    fun sendReturnsQueuedWhenTheBoardAcksTheId() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            ch.onWrite = { bytes ->
                if (BoardBytes.isWantConfig(bytes)) {
                    ch.enqueueRead(BoardBytes.myInfo(0xABCDu, "heltec-v4"))
                    ch.enqueueRead(BoardBytes.configComplete(nonce))
                } else if (BoardBytes.isPacket(bytes)) {
                    ch.enqueueRead(BoardBytes.queueStatus(free = 15, maxlen = 16, meshPacketId = BoardBytes.packetId(bytes)))
                }
            }
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val send = async { session.send(byteArrayOf(1, 2, 3), channelIndex = 1) }
            runCurrent()
            val result = send.await()
            assertTrue("got $result", result is SendResult.Queued)
            result as SendResult.Queued
            assertEquals(1000u, result.id)
            assertEquals(15, result.queue.free)
            session.stop()
        }

    @Test
    fun sendFoldsInAnImmediateNak() =
        runTest {
            val ch = FakeGattChannel()
            ch.onWrite = { bytes ->
                when {
                    BoardBytes.isWantConfig(bytes) -> {
                        ch.enqueueRead(BoardBytes.myInfo(0xABCDu, "heltec-v4"))
                        ch.enqueueRead(BoardBytes.configComplete(nonce))
                    }

                    BoardBytes.isPacket(bytes) -> {
                        ch.enqueueRead(
                            BoardBytes.nak(from = 0xABCDu, requestId = BoardBytes.packetId(bytes), reason = RoutingError.NO_CHANNEL),
                        )
                    }
                }
            }
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            val send = async { session.send(byteArrayOf(1, 2, 3), channelIndex = 9) }
            runCurrent()
            val result = send.await()
            assertEquals(SendResult.Nak(1000u, RoutingError.NO_CHANNEL), result)
            session.stop()
        }

    @Test
    fun sendRefusesLocallyWhenOverThePayloadCap() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertEquals(SendResult.TooLarge, session.send(ByteArray(MeshtasticProto.MAX_PAYLOAD + 1), channelIndex = 1))
            session.stop()
        }

    @Test
    fun sendIsBusyWhenTheBoardHasNoQueueHeadroom() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            // Deliver a queueStatus with free=0 over the notify path so the session's queue view is full.
            ch.enqueueRead(BoardBytes.queueStatus(free = 0, maxlen = 16, meshPacketId = 0u))
            ch.notify()
            runCurrent()
            assertEquals(SendResult.Busy, session.send(byteArrayOf(1), channelIndex = 1))
            session.stop()
        }

    @Test
    fun sendBeforeReadyIsNotReady() =
        runTest {
            val ch = FakeGattChannel()
            val dialer = FakeGattDialer(ch)
            dialer.adapterOn.value = false // never reaches Ready
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            val result = session.send(byteArrayOf(1), channelIndex = 1)
            assertTrue(result is SendResult.NotReady)
            session.stop()
        }

    @Test
    fun heartbeatIsWrittenAfterTheInterval() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            val before = ch.writes.count { BoardBytes.isHeartbeat(it) }
            advanceTimeBy(180_001)
            runCurrent()
            assertEquals(before + 1, ch.writes.count { BoardBytes.isHeartbeat(it) })
            session.stop()
        }

    @Test
    fun aDialFailureBacksOffThenConnects() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val dialer = FakeGattDialer(ch)
            dialer.dialResults.addLast(DialResult.Failed(status = 133, phase = "connect"))
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            val st = session.state.value
            assertTrue("first attempt backs off but was $st", st is LinkState.Disconnected)
            assertEquals(1, (st as LinkState.Disconnected).streak)

            advanceTimeBy(5_001) // the base backoff
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)
            assertEquals(2, dialer.dials)
            session.stop()
        }

    @Test
    fun adapterOffParksUnavailableThenConnectsWhenItReturns() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val dialer = FakeGattDialer(ch)
            dialer.adapterOn.value = false
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertEquals(LinkState.Unavailable, session.state.value)
            assertEquals(0, dialer.dials)

            dialer.adapterOn.value = true
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)
            session.stop()
        }

    @Test
    fun aStaleBondIsTerminalAndStopsRetrying() =
        runTest {
            val ch = FakeGattChannel()
            ch.subscribeResult = GattResult.Failed(status = 137) // GATT_AUTH_FAIL
            val dialer = FakeGattDialer(ch).apply { bond = BondState.BONDED }
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertEquals(LinkState.StaleBond("AA"), session.state.value)
            val dialsAfterTerminal = dialer.dials
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals("terminal state must not keep dialling", dialsAfterTerminal, dialer.dials)
            session.stop()
        }

    @Test
    fun anUnbondedAuthFailureAsksForPairing() =
        runTest {
            val ch = FakeGattChannel()
            ch.subscribeResult = GattResult.Failed(status = 5) // insufficient auth, not yet bonded
            val dialer = FakeGattDialer(ch).apply { bond = BondState.NONE }
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertEquals(LinkState.NeedsPairing("AA"), session.state.value)
            session.stop()
        }

    @Test
    fun aDisconnectEndsTheSessionAndReconnects() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)

            ch.disconnect(status = 19)
            runCurrent()
            assertTrue("a disconnect backs off", session.state.value is LinkState.Disconnected)
            advanceTimeBy(5_001)
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)
            session.stop()
        }

    @Test
    fun aRebootTriggersAFreshHandshake() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)

            ch.enqueueRead(BoardBytes.rebooted())
            ch.notify()
            runCurrent()
            advanceTimeBy(5_001) // reboot ends the session; it reconnects on the base backoff
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)
            session.stop()
        }

    @Test
    fun stopClosesTheChannelAndGoesIdle() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            val closesBefore = ch.closes
            session.stop()
            runCurrent()
            assertEquals(LinkState.Idle, session.state.value)
            assertTrue("stop closes the channel", ch.closes > closesBefore)
        }

    @Test
    fun inboundPacketsSurfaceOnTheNotifyPath() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            val received = mutableListOf<ReceivedPacket>()
            val collector = backgroundScope.launch { session.packets.collect { received += it } }
            session.start("AA")
            runCurrent()
            ch.enqueueRead(
                BoardBytes.packet(
                    from = 0x1234u,
                    channel = 1,
                    portnum = MeshtasticProto.PORT_PRIVATE_APP,
                    payload = byteArrayOf(9, 9),
                    id = 7u,
                ),
            )
            ch.notify()
            runCurrent()
            assertEquals(1, received.size)
            assertEquals(0x1234u, received.first().from)
            assertEquals(MeshtasticProto.PORT_PRIVATE_APP, received.first().portnum)
            collector.cancel()
            session.stop()
        }
}
