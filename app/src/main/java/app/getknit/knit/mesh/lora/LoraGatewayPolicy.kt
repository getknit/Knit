package app.getknit.knit.mesh.lora

/**
 * Decides whether this phone is the one that speaks for its pocket on the LoRa hop.
 *
 * A "pocket" is a BLE/NAN clique — a group of phones that mesh with each other but are out of radio range
 * of the next group. When more than one of them has a board, **each** of them re-fans every frame the clique
 * sees onto LoRa (`InboundPipeline.onDeliver` re-calls the fan-out on relay, and the sig dedup is per phone),
 * so the pocket pays for the same frame twice. The board's own `(from, id)` dedup does not help: the two
 * boards are different origins. ADR 038 shipped with "one board per clique" as an accepted residual; a bridge
 * makes it expensive, because now every pocket has a board by construction.
 *
 * The signal is free. [MeshTransport.onForeignReachable] hands the LoRa child the union of its **short-range**
 * siblings' reachable sets ([CompositeMeshTransport] filters on `shortRange`), which is exactly "who is in my
 * BLE/NAN pocket". And anything that publishes a [LoraCtl] OFFER is, by definition, a board-holder. So:
 *
 * - a publisher that **is** in `foreignReachable` is a **co-pocket** gateway — a rival for the same job;
 * - a publisher that is **not** is a **far-pocket** gateway — the bridge peer, and never a rival.
 *
 * Among co-pocket gateways the lowest **publisher key** wins — the 64-bit hash of the node id an OFFER
 * carries, since that is all the packet has room for. Same lowest-decides convention the mesh already uses
 * for NAN initiator roles, just over a uniformly distributed key rather than the id's own ordering, so no
 * node is structurally favoured. Everyone else goes [Role.PASSIVE] and transmits nothing at all; they still
 * *receive*, so a passive board keeps feeding its pocket and is a warm spare.
 *
 * Recovery needs no special timer. A gateway that walks away drops out of `foreignReachable`; one whose board
 * dies stops publishing and ages past [staleMs]. Either way the next-lowest id promotes itself on the next
 * evaluation. [staleMs] is therefore the whole blind spot, which is why the gossip interval is capped well
 * below it — an active gateway's OFFER doubles as its liveness beacon.
 *
 * Pure and clock-driven by the caller, like [LoraPacePolicy] and [LoraAirtime].
 */
internal class LoraGatewayPolicy(
    private val staleMs: Long = STALE_MS,
) {
    enum class Role {
        /** We speak for this pocket: fan-out, beacons, offers and backfill all transmit. */
        ACTIVE,

        /** Another board in this pocket has it; we listen only, so the pocket pays for one board's airtime. */
        PASSIVE,
    }

    /** publisher key -> when we last heard an OFFER from it. Pocket-sized; swept on every evaluation. */
    private val gatewaysHeardAt = HashMap<Long, Long>()

    /** Records that the gateway keyed [publisher] published an OFFER at [now]. */
    fun onOffer(
        publisher: Long,
        now: Long,
    ) {
        gatewaysHeardAt[publisher] = now
    }

    fun forget() = gatewaysHeardAt.clear()

    /**
     * Our role, given our own [selfKey], the keys of the peers our short-range siblings can see
     * ([pocketKeys]) and [now]. Note a peer we have never heard an OFFER from is not a gateway however
     * reachable it is — being in the pocket is not the qualification, having a board is.
     */
    fun roleFor(
        selfKey: Long,
        pocketKeys: Set<Long>,
        now: Long,
    ): Role {
        gatewaysHeardAt.entries.removeAll { now - it.value > staleMs }
        val rival = gatewaysHeardAt.keys.any { it in pocketKeys && it < selfKey }
        return if (rival) Role.PASSIVE else Role.ACTIVE
    }

    /** Whether [publisher] is a gateway in another pocket — the bridge peer whose OFFER we should serve. */
    fun isFarGateway(
        publisher: Long,
        pocketKeys: Set<Long>,
    ): Boolean = publisher in gatewaysHeardAt && publisher !in pocketKeys

    companion object {
        /**
         * How long a heard gateway still counts. Comfortably above the gossip policy's maximum interval — an
         * active gateway's OFFER is also its liveness beacon, and a couple of them may be lost to the air — so
         * a healthy one never lapses. It is also the whole blind spot when an active gateway dies *without*
         * leaving `foreignReachable` (a crashed phone rather than one that walked away), which is why it is
         * not longer.
         */
        const val STALE_MS = 45 * 60_000L
    }
}
