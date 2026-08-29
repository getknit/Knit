package app.getknit.knit.demo

import android.os.Build
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.crash.CrashStore
import app.getknit.knit.crash.currentCrashEnvironment
import app.getknit.knit.data.settings.ModelLoadState
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.ModelLoadPolicy
import app.getknit.knit.moderation.modelGuardStamp
import org.koin.core.Koin

/**
 * Populates the database with a believable conversation history so the app renders fully on an
 * emulator — used only by the static demo-screenshot build (`-PseedDemo=true`; the field defaults false,
 * so this never runs in normal/release builds, and it is skipped when the animated `-PdemoDirector` is on,
 * which seeds its own baseline). The concrete content (cast, messages, group) comes from a [DemoScenario]
 * chosen by `-PdemoTheme` (see [demoScenarioFor]), so we can shoot multiple marketing themes from one code.
 *
 * The actual writes go through [DemoWriter], the shared primitives the animated [DemoDirector] also uses,
 * so both stay in lockstep. Paired with [app.getknit.knit.mesh.DemoTransport], which reports
 * [ONLINE_NODE_IDS] as connected so the "connected" header and contact "online" dots light up. All writes
 * are idempotent upserts keyed by stable ids, so a relaunch re-seeds deterministically.
 */
class DemoSeeder(
    private val koin: Koin,
) {
    suspend fun seed() {
        runCatching { seedInternal() }
            .onFailure { Log.e("DemoSeeder", "demo seeding failed", it) }
    }

    private suspend fun seedInternal() {
        val me = koin.get<Identity>().nodeId()
        val scenario = demoScenarioFor(BuildConfig.DEMO_THEME)
        val msgById =
            (scenario.nearby + scenario.dms.flatMap { it.messages } + scenario.groupMessages)
                .associateBy { it.id }
        val writer = DemoWriter(koin, scenario, me, msgById)
        val now = System.currentTimeMillis()

        writer.seedProfileAndPeers(now)
        writer.seedNearby(now)
        writer.seedDms(now)
        writer.seedGroup(now)

        // Pin one persistent "now typing" cue for the dm-sam marketing shot. A real cue is TTL'd (12s) and
        // would race a static capture; this bypasses the TTL. For a DM the conversationId is the peer's
        // nodeId (see seedDms), so both args are the same slot.
        koin.get<MeshManager>().seedDemoTyping(conversationId = SAM, senderId = SAM)

        seedCrashReport()
        seedLatchedModel()
    }

    /**
     * Plants one synthetic crash report so the "Last crash" row and the crash screen have something to
     * render. Without it the accessibility audit would only ever see the empty state, and the dense
     * monospace trace plus the error-tinted destructive action — exactly what the checks exist for —
     * would ship unaudited. Goes through the real [CrashStore.record], so what is audited is what a real
     * crash produces, redaction included.
     */
    private fun seedCrashReport() {
        val store = koin.get<CrashStore>()
        if (store.latest() != null) return
        store.record(
            environment = currentCrashEnvironment(),
            threadName = "DefaultDispatcher-worker-3",
            throwable =
                IllegalStateException("hello reply ${NodeId.derive(SAM)} != expected ${NodeId.derive(DANI)}"),
        )
    }

    /**
     * Latches the toxicity model off, so the Diagnostics "Problem reports" section renders its
     * poison-pill row (ADR 037) and the accessibility audit covers it instead of only its absence. Goes
     * through the real journal, so what is audited is the real state a latched phone reaches.
     *
     * Side effect, accepted knowingly: the seeded build then runs lexical-only, which makes a text send
     * *faster* (no cold tflite load — see `.agents/context/testing.md`). Nothing asserts on an ML verdict;
     * `ModerationRevealUiAutomatorTest` drives the synthetic `FLAGMSG` seam instead.
     */
    private suspend fun seedLatchedModel() {
        koin.get<SettingsStore>().setModelLoadState(
            model = ModelLoadGuard.TOXICITY,
            state =
                ModelLoadState(
                    stamp = modelGuardStamp(BuildConfig.VERSION_CODE, Build.FINGERPRINT.orEmpty()),
                    pendingSince = 0L,
                    fails = ModelLoadPolicy.MAX_FAILS,
                ),
        )
    }

    companion object {
        // Stable, illustrative demo node ids — short fixed slots (NOT the real 26-char base32 [NodeId]
        // format; demo peers are seeded straight into the DB and never advertised over a radio, so any
        // opaque string works). Names/avatars/messages vary by theme, but the id slots stay constant so
        // ONLINE_NODE_IDS and the fake transport are theme-independent.
        const val SAM = "samr1v00"
        const val DANI = "danich01"
        const val THEO = "theob123"
        const val PRIYA = "priyan07"
        const val JONAS = "jonasw88"
        const val LENA = "lenaf042"

        /** The second "Jonas W." of the hiking cast — its first six chars differ from [JONAS] so even the short-id fallback reads apart. */
        const val JONAS_TWO = "jonas2w9"

        /** The subset of demo peers reported as connected by [app.getknit.knit.mesh.DemoTransport]. */
        val ONLINE_NODE_IDS: Set<String> = setOf(SAM, DANI, PRIYA)
    }
}
