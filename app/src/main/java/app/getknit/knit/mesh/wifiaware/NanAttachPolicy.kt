package app.getknit.knit.mesh.wifiaware

import kotlin.random.Random

/**
 * Pure, JVM-testable backoff for a **failed `WifiAwareManager.attach`**, the sibling of [NanConnectPolicy]'s
 * per-peer connect backoff one layer down: that one paces a data-path handshake to a single peer, this one
 * paces our attempts to get an Aware session at all.
 *
 * The discovery loop's detached branch retried `attach()` at a flat [WifiAwareTransport.ATTACH_RETRY_MS]
 * forever, which is right for the transient failures it was written for — the chipset needing a beat after a
 * reattach teardown, Wi-Fi mid-toggle — and wrong for a device where the attach can **never** succeed. On a
 * chipset whose vendor HAL publishes no STA+NAN interface combination the framework refuses to create the NAN
 * iface while the STA is up (`HalDevMgr: bestIfaceCreationProposal is null`), and `isAvailable` stays *true*
 * throughout — it reports whether Aware is enabled, not whether an iface can be had — so nothing upstream of
 * here ever says stop. Field evidence from getknit/Knit#9 (OnePlus 8 `IN2010`, LineageOS 23.2): the framework's
 * Aware client id and NAN iface id had both passed **51,000** while `wlan0` sat at 22, i.e. ~51 k binder round
 * trips into `system_server` and as many HAL `createIface`/`removeIface` pairs, ≈43 h at one per 3 s.
 *
 * So: geometric backoff from [BASE_BACKOFF_MS] (= the old flat cadence, so a *first* failure still retries
 * exactly as promptly as it always did) to a [MAX_BACKOFF_MS] cap of the discovery loop's own idle cadence — a
 * radio we cannot open is polled no more often than a radio we have opened and have nothing to say on.
 *
 * **What the cap costs, stated plainly.** The Aware availability broadcast covers the case this backoff would
 * otherwise slow down most — Wi-Fi off→on — because the transport clears the streak on that edge and attaches
 * at once. What it does not cover is *another app* releasing the NAN iface, for which there is no broadcast and
 * polling is the only recovery; that detection can now lag by up to one cap. Two minutes to notice a radio
 * freed by someone else is a fair price for not spending 43 h hammering one that will never be free.
 */
internal object NanAttachPolicy {
    /** Matches `WifiAwareTransport.ATTACH_RETRY_MS`, so the first retry after a lone failure is unchanged. */
    const val BASE_BACKOFF_MS = 3_000L

    /** Matches `WifiAwareTransport.REDISCOVER_IDLE_MS` — the loop's own idle cadence is the floor on usefulness. */
    const val MAX_BACKOFF_MS = 120_000L

    private const val JITTER_FRACTION = 0.2
    private const val MAX_SHIFT = 16 // BASE shl 16 already dwarfs the cap; bound the shift so the Long can't wrap

    /**
     * How long to wait before the [streak]-th consecutive failed attach is retried (1 = the first):
     * [BASE_BACKOFF_MS] doubling per failure, saturating at [MAX_BACKOFF_MS], then spread ±[JITTER_FRACTION] by
     * [rand] (a 0..1 source; 0.5 yields exactly the un-jittered delay). The curve is 3→6→12→24→48→96→120(cap) s,
     * so a device that can never attach settles inside ~3.5 min having spent seven attempts getting there.
     *
     * Jittered because the thing we are usually waiting on is *another* app's hold on the single NAN iface: an
     * un-jittered poll can beat against a periodic holder and sample the same phase of it every time.
     */
    fun backoffMs(
        streak: Int,
        rand: () -> Double = { Random.nextDouble() },
    ): Long {
        val shift = (streak.coerceAtLeast(1) - 1).coerceAtMost(MAX_SHIFT)
        val raw = (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        val delta = raw * JITTER_FRACTION
        val jittered = raw - delta + rand() * (delta + delta)
        return jittered.toLong().coerceAtLeast(0L)
    }
}
