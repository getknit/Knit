package app.getknit.knit.mesh.wifiaware

import kotlin.random.Random

/**
 * Pure, JVM-testable retry budget for a **failed `WifiAwareManager.attach`**. Unlike [NanConnectPolicy] — the
 * sibling one layer down, which paces a data-path handshake to one peer so a busy responder gets a turn — this
 * one is not about politeness. **A failed attach leaks two binder objects into `system_server`, and enough of
 * them get the app killed.**
 *
 * `WifiAwareManager.attach` mints a fresh `Binder` token *and* an `IWifiAwareEventCallback.Stub` and passes
 * both to `IWifiAwareManager.connect`. The success path has a release: `onConnectSuccess` hands the token to
 * the new `WifiAwareSession`, and `session.close()` calls `disconnect(clientId, binder)`. The failure path has
 * none — `onConnectFail` clears a `WeakReference` and invokes `onAttachFailed`, and there is no client id to
 * disconnect with, so nothing ever tells `system_server` to let go. Two objects per failure, retained against
 * our uid until the process dies (AOSP `WifiAwareManager`, verified against the android-36.1 sources).
 *
 * `ActivityManagerService` watches that count per uid and kills every process of a uid that crosses its high
 * watermark, with `REASON_EXCESSIVE_RESOURCE_USAGE` / `SUBREASON_EXCESSIVE_BINDER_OBJECTS` and the description
 * "Too many Binders sent to SYSTEM". It is not a crash: no exception, no trace, nothing in the log. The live
 * count is readable with `adb shell dumpsys activity binder-proxies` (a healthy Knit install sits at ~20).
 *
 * This matters because nothing upstream stops the retries. `WifiAwareManager.isAvailable` reports whether Aware
 * is *enabled*, not whether an interface can be had, so on a chipset whose vendor HAL publishes no STA+NAN
 * interface combination it stays `true` while every attach fails at `HalDevMgr: bestIfaceCreationProposal is
 * null`. The discovery loop's detached branch then retried at a flat 3 s **forever**: ~28.8 k failed attaches a
 * day, ~57.6 k leaked binder objects, and an AMS kill within hours — getknit/Knit#9 (OnePlus 8 `IN2010`,
 * LineageOS 23.2), where the framework's Aware client id had passed 51 k while `wlan0` sat at 22.
 *
 * So the curve is set by a **leak budget**, not by a retry cadence anyone would otherwise choose:
 *
 * - [BASE_BACKOFF_MS] equals the old flat cadence, so a *lone* failure — the chipset needing a beat after a
 *   `reattach()` teardown, Wi-Fi mid-toggle — retries exactly as promptly as it always did.
 * - It doubles to a [MAX_BACKOFF_MS] of half an hour. That is far longer than any retry cadence is *useful*;
 *   the point is that the only failure that survives it is a permanent one, and 48 attempts a day costs 96
 *   binder objects a day against a watermark in the thousands.
 * - After [MAX_FAILURES] consecutive failures we stop attaching altogether — ~26 h of trying for ~120 leaked
 *   objects, a couple of per cent of the budget. A radio that has refused for a day is not coming back on its
 *   own, and the caller re-arms on any genuinely new fact about it (see the transport's `clearAttachBackoff`).
 *
 * **What the cap costs, stated plainly.** There is no broadcast for *another app* releasing the NAN interface,
 * so polling is the only recovery there and it now lags by up to half an hour — and after a day of refusals it
 * stops entirely until Aware availability changes. That is the trade: the alternative is a mesh app that
 * silently kills itself on any device whose chipset won't give it an interface.
 */
internal object NanAttachPolicy {
    /** Matches `WifiAwareTransport.ATTACH_RETRY_MS`, so the first retry after a lone failure is unchanged. */
    const val BASE_BACKOFF_MS = 3_000L

    /** Half an hour: long enough that a permanently-refusing radio costs ~96 binder objects a day. */
    const val MAX_BACKOFF_MS = 1_800_000L

    /** Consecutive failures before we stop attaching until a new fact arrives — ~26 h, ~120 leaked objects. */
    const val MAX_FAILURES = 60

    private const val JITTER_FRACTION = 0.2
    private const val MAX_SHIFT = 16 // BASE shl 16 already dwarfs the cap; bound the shift so the Long can't wrap

    /** Whether a streak of [streak] consecutive failures has spent the budget: stop attaching, don't back off. */
    fun giveUp(streak: Int): Boolean = streak >= MAX_FAILURES

    /**
     * How long to wait before the [streak]-th consecutive failed attach is retried (1 = the first):
     * [BASE_BACKOFF_MS] doubling per failure, saturating at [MAX_BACKOFF_MS], then spread ±[JITTER_FRACTION] by
     * [rand] (a 0..1 source; 0.5 yields exactly the un-jittered delay). The curve is
     * 3→6→12→24→48→96→192→384→768→1536→1800(cap) s, so the cap is reached on the 11th attempt, ~51 min in.
     *
     * Jittered because the thing we are usually waiting on is *another* app's hold on the single NAN interface:
     * an un-jittered poll can beat against a periodic holder and sample the same phase of it every time.
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
