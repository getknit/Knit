package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.StoreDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraGatewayPolicyTest {
    private fun key(node: String) = StoreDigest.hash64(node)

    private fun pocket(vararg nodes: String) = nodes.mapTo(HashSet()) { key(it) }

    /** Two node names whose publisher keys are ordered, so "lowest key wins" is testable by name. */
    private val lowerKeyNode = generateSequence(0) { it + 1 }.map { "n$it" }.first { key(it) < key("alice") }

    private val higherKeyNode = generateSequence(0) { it + 1 }.map { "n$it" }.first { key(it) > key("alice") }

    @Test
    fun aLoneBoardIsActiveFromTheStart() {
        val policy = LoraGatewayPolicy()
        assertEquals(LoraGatewayPolicy.Role.ACTIVE, policy.roleFor(key("alice"), emptySet(), 0))
    }

    @Test
    fun aCoPocketGatewayWithALowerKeyTakesOver() {
        val policy = LoraGatewayPolicy()
        policy.onOffer(key(lowerKeyNode), 0)
        assertEquals(
            LoraGatewayPolicy.Role.PASSIVE,
            policy.roleFor(key("alice"), pocket(lowerKeyNode), 0),
        )
    }

    @Test
    fun aCoPocketGatewayWithAHigherKeyDoesNot() {
        val policy = LoraGatewayPolicy()
        policy.onOffer(key(higherKeyNode), 0)
        assertEquals(
            LoraGatewayPolicy.Role.ACTIVE,
            policy.roleFor(key("alice"), pocket(higherKeyNode), 0),
        )
    }

    @Test
    fun aFarGatewayNeverSuppressesUsHoweverLowItsKey() {
        // The whole point of the bridge: the peer on the other side of the hop is not a rival for our job.
        val policy = LoraGatewayPolicy()
        policy.onOffer(key(lowerKeyNode), 0)
        assertEquals(
            "not in our BLE/NAN pocket, so not a rival",
            LoraGatewayPolicy.Role.ACTIVE,
            policy.roleFor(key("alice"), emptySet(), 0),
        )
        assertTrue(policy.isFarGateway(key(lowerKeyNode), emptySet()))
        assertFalse(policy.isFarGateway(key(lowerKeyNode), pocket(lowerKeyNode)))
    }

    @Test
    fun aReachablePeerWithNoBoardIsNotAGateway() {
        // Being in the pocket is not the qualification — publishing an OFFER is.
        val policy = LoraGatewayPolicy()
        assertEquals(
            LoraGatewayPolicy.Role.ACTIVE,
            policy.roleFor(key("alice"), pocket(lowerKeyNode), 0),
        )
        assertFalse("and a node we never heard is not a bridge peer either", policy.isFarGateway(key("stranger"), emptySet()))
    }

    @Test
    fun aGatewayLeavingThePocketPromotesUsWithNoTimer() {
        val policy = LoraGatewayPolicy()
        policy.onOffer(key(lowerKeyNode), 0)
        assertEquals(LoraGatewayPolicy.Role.PASSIVE, policy.roleFor(key("alice"), pocket(lowerKeyNode), 0))
        // It walked out of BLE/NAN range: the short-range sighting lapses long before the staleness window.
        assertEquals(LoraGatewayPolicy.Role.ACTIVE, policy.roleFor(key("alice"), emptySet(), 60_000))
    }

    @Test
    fun aSilentGatewayAgesOutAndWePromote() {
        val policy = LoraGatewayPolicy(staleMs = 10_000)
        policy.onOffer(key(lowerKeyNode), 0)
        assertEquals(LoraGatewayPolicy.Role.PASSIVE, policy.roleFor(key("alice"), pocket(lowerKeyNode), 9_999))
        assertEquals(
            "its board died, so it stopped publishing",
            LoraGatewayPolicy.Role.ACTIVE,
            policy.roleFor(key("alice"), pocket(lowerKeyNode), 10_001),
        )
    }

    @Test
    fun aRefreshedOfferKeepsItInCharge() {
        val policy = LoraGatewayPolicy(staleMs = 10_000)
        policy.onOffer(key(lowerKeyNode), 0)
        policy.onOffer(key(lowerKeyNode), 9_000)
        assertEquals(LoraGatewayPolicy.Role.PASSIVE, policy.roleFor(key("alice"), pocket(lowerKeyNode), 18_000))
    }

    @Test
    fun theStalenessWindowOutlastsTheGossipCeilingSoAHealthyGatewayNeverLapses() {
        assertTrue(
            "an active gateway's OFFER is also its liveness beacon",
            LoraGatewayPolicy.STALE_MS > 2 * LoraGossipPolicy.MAX_INTERVAL_MS,
        )
    }

    @Test
    fun forgettingResetsTheElection() {
        val policy = LoraGatewayPolicy()
        policy.onOffer(key(lowerKeyNode), 0)
        policy.forget()
        assertEquals(LoraGatewayPolicy.Role.ACTIVE, policy.roleFor(key("alice"), pocket(lowerKeyNode), 0))
    }
}
