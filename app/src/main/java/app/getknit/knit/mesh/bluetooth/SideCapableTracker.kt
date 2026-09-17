package app.getknit.knit.mesh.bluetooth

/**
 * Which nearby peers can hear the BLE side channel — the sender's gate for spending airtime on it and the
 * receiver's gate for spending battery listening. Fed from every presence sighting's
 * [BleAdvertPayload.Parsed.sideChannel] flag.
 *
 * Presence alone would be the wrong source: [BlePresenceTracker] prunes a peer 90 s after its last sighting,
 * and a settled clique's scan is floored for minutes at a time, so a linked, flagged peer is routinely absent
 * from it. A flag therefore counts while its sighting is inside [lingerMs] **or** while that peer is
 * currently linked (the link keeps the flag it was sighted with for its lifetime). A sighting *without* the
 * flag clears it — a build downgrade or a controller that lost the feature must stop us sending to it.
 * Pure; JVM-tested.
 */
internal class SideCapableTracker(
    private val lingerMs: Long = CAPABLE_LINGER_MS,
) {
    // nodeId → elapsed-clock instant of its last flagged sighting.
    private val flagged = HashMap<String, Long>()

    @Synchronized
    fun note(
        nodeId: String,
        capable: Boolean,
        now: Long,
    ) {
        if (capable) flagged[nodeId] = now else flagged.remove(nodeId)
    }

    /** Whether any peer sighted with the flag inside the linger, or linked and once flagged, is around. */
    @Synchronized
    fun anyCapable(
        now: Long,
        linked: Set<String>,
    ): Boolean {
        prune(now, linked)
        return flagged.isNotEmpty()
    }

    @Synchronized
    fun forget(nodeId: String) {
        flagged.remove(nodeId)
    }

    @Synchronized
    fun clear() = flagged.clear()

    private fun prune(
        now: Long,
        linked: Set<String>,
    ) {
        val it = flagged.entries.iterator()
        while (it.hasNext()) {
            val (id, at) = it.next()
            if (now - at >= lingerMs && id !in linked) it.remove()
        }
    }

    companion object {
        /** How long a flagged sighting counts without a link — well past the presence scan's settled floor. */
        const val CAPABLE_LINGER_MS = 10 * 60_000L
    }
}
