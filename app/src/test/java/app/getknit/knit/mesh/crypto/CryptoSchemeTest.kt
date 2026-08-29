package app.getknit.knit.mesh.crypto

import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one v3 gate (ADR 059): both ratchet bits on the pinned profile, or v2. */
class CryptoSchemeTest {
    @Test
    fun v3NeedsBothBitsAndNothingElse() {
        val both = Protocol.CAP_RATCHET or Protocol.CAP_CRYPTO_V3
        assertEquals(EncEnvelope.VERSION_DM_V3, CryptoScheme.forCapabilities(both))
        assertEquals(EncEnvelope.VERSION_DM_V3, CryptoScheme.forCapabilities(Protocol.LOCAL_CAPABILITIES))
        val v3Alone = CryptoScheme.forCapabilities(Protocol.CAP_CRYPTO_V3)
        assertEquals("a v3 claim without the ratchet is no scheme at all", EncEnvelope.VERSION_RATCHET, v3Alone)
        assertEquals(EncEnvelope.VERSION_RATCHET, CryptoScheme.forCapabilities(Protocol.CAP_RATCHET))
        val ratchetOnly = Protocol.LOCAL_CAPABILITIES and Protocol.CAP_CRYPTO_V3.inv()
        assertEquals(EncEnvelope.VERSION_RATCHET, CryptoScheme.forCapabilities(ratchetOnly))
        assertEquals(EncEnvelope.VERSION_RATCHET, CryptoScheme.forCapabilities(0L))
        assertEquals(EncEnvelope.VERSION_RATCHET, CryptoScheme.forCapabilities(null))
    }

    @Test
    fun theProfileReadIsTheOnlySource() {
        val v3 = PeerEntity(nodeId = "a", capabilities = Protocol.LOCAL_CAPABILITIES, updatedAt = 1L)
        val v2 = PeerEntity(nodeId = "b", capabilities = Protocol.CAP_RATCHET, updatedAt = 1L)
        val unknown = PeerEntity(nodeId = "c", updatedAt = 1L)
        assertTrue(v3.readsCryptoV3())
        assertFalse(v2.readsCryptoV3())
        assertFalse(unknown.readsCryptoV3())
        assertFalse((null as PeerEntity?).readsCryptoV3())
    }
}
