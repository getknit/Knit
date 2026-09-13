package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope

/**
 * A per-**link** token bucket over the one frame anyone in radio range can write: the cleartext
 * broadcast-room post.
 *
 * Every other bound on a room post is keyed on a node id, and a node id is free — it is the hash of a key
 * pair the sender minted itself — so a flood from one device can wear a fresh identity per frame and slip
 * every per-sender quota (`ForwardRepository.maxPerSender`, the room's per-sender retention cap). A link is
 * not free: it is one radio peer — one NDP, one L2CAP socket — and a device holds one at a time, so metering
 * on the link a frame arrived over gives one attacking device one link's budget however many identities it
 * claims. It is the mesh's per-IP limit.
 *
 * It runs in [MeshRouter] ahead of everything a frame costs — the Ed25519 verify, custody, the moderation
 * model, the relay to every other neighbor — so a refused frame costs one map probe and goes no further: the
 * flood stops at the first honest hop instead of being relayed, re-verified and re-classified across the
 * whole mesh. A refused frame is **not** marked seen, so the custody re-offer (`ForwardSync`) serves it again
 * later through the same meter: a burst over budget is delayed, never vetoed, and the carried sets still
 * converge — at the metered pace — once the flood stops. Duplicates are deduped *before* the meter and cost
 * nothing, so a dense mesh where every post arrives over every link pays for each post once.
 *
 * Sized to the largest honest burst a link carries: a link-up custody re-serve hands over at most the peer's
 * broadcast quota (`ForwardRepository.DEFAULT_MAX_BROADCAST`, 200) of room posts at once, so [burst] covers
 * that with headroom, and [perMinute] is the sustained rate of a busy room's first copies through one link.
 */
class IngressBudget(
    private val burst: Int = DEFAULT_BURST,
    private val perMinute: Int = DEFAULT_PER_MINUTE,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class Bucket(
        var tokens: Double,
        var refilledAt: Long,
    )

    // Bounded LRU keyed by the transport's attribution of the link. The live neighbor set is small, but links
    // come and go and nothing here may grow with history. An evicted bucket comes back full, which is the
    // same gain as re-linking — a cost the radio already charges in seconds of setup.
    private val buckets =
        object : LinkedHashMap<String, Bucket>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>): Boolean = size > MAX_LINKS
        }

    /** Whether [env] is a frame this budget meters: a cleartext broadcast-room post. */
    fun meters(env: RelayEnvelope): Boolean = env.type == FrameType.CHAT && env.recipientId == null && env.group == null

    /** Takes one token from [link]'s bucket, or returns false when it is empty (the frame is refused). */
    @Synchronized
    fun admit(link: String): Boolean {
        val now = clock()
        val bucket = buckets.getOrPut(link) { Bucket(burst.toDouble(), now) }
        val elapsed = (now - bucket.refilledAt).coerceAtLeast(0L)
        bucket.tokens = minOf(burst.toDouble(), bucket.tokens + elapsed * perMinute / MS_PER_MINUTE)
        bucket.refilledAt = now
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    companion object {
        /** Room posts one link may hand over at once — a full custody re-serve of the broadcast quota, plus headroom. */
        const val DEFAULT_BURST = 240

        /** Room posts per minute one link may sustain — first copies only, a busy room's worth through one neighbor. */
        const val DEFAULT_PER_MINUTE = 30

        private const val MAX_LINKS = 64
        private const val MS_PER_MINUTE = 60_000.0
    }
}
