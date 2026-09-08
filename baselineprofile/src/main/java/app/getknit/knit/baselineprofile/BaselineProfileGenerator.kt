package app.getknit.knit.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Produces the ART baseline profile that ships as `app/src/main/baseline-prof.txt`, and the startup
 * profile that ships beside it as `app/src/main/startup-prof.txt`.
 *
 * Run it, then copy both results over those files (see `.agents/context/baseline-profile.md`); this module
 * is not in the build unless `-Pknit.baselineProfile=true` asks for it, and nothing it contains is packaged.
 *
 * The journey is deliberately the **cold-start-to-first-conversation** path and not an exhaustive tour.
 * A baseline profile buys ahead-of-time compilation for the code it names, and naming everything is the
 * same as naming nothing: the profile grows, the dex layout loses its locality, and install time goes up.
 * What is here is what a user hits before they have decided whether the app feels good — process start,
 * Koin's graph, the SQLCipher open, Compose's first frame, the chat list, a thread, and one navigation in
 * each direction, which is the transition that exposed all of this in the first place.
 *
 * **The thread it opens has messages in it, and that is the point.** The room is empty on a fresh install,
 * so a journey that only opened it profiled `EmptyState` and `BubbleSkeleton` and never once compiled the
 * code that draws a message — `MessageBubble`, the reaction row, the receipt ticks and the emoji path were
 * all absent from the shipped profile. The run therefore *sends* before it reads, through the ordinary
 * composer, and scrolls what comes back.
 *
 * Sending rather than seeding is deliberate. `-PseedDemo=true` cannot reach this variant — `release` (and
 * so `nonMinifiedRelease`) hard-codes `SEED_DEMO=false` and the seeder lives only in `src/debug` — but even
 * if it could, `BuildConfig.SEED_DEMO` gates real startup branches (`KnitApp`'s onboarding check, the boot
 * receiver, the review prompter). A seeded run would faithfully profile a path the shipped app never takes,
 * which is the same error as collecting against a minified build.
 *
 * Permissions are granted up front rather than driven through the onboarding gate. Nothing about the
 * permission screen is on the hot path — it is seen once, ever — and granting them is what lets the run
 * reach the screens that are.
 */
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * The baseline profile — `app/src/main/baseline-prof.txt`, the rules ART compiles ahead of time.
     */
    @Test
    fun startupAndFirstConversation() = collectJourney(startupFlavour = false)

    /**
     * The startup profile — `app/src/main/startup-prof.txt`. A *separate* AGP feature from the baseline
     * profile: R8 reorders dex so startup classes sit together, for page locality rather than
     * ahead-of-time compilation.
     *
     * It needs its own run because `includeInStartupProfile` is a **flavour switch, not an additive flag**
     * — a collection emits `-baseline-prof.txt` or `-startup-prof.txt`, never both, and the output is named
     * after the test method. Two methods is what gets two files out of one journey.
     *
     * This is also why the journey must stay narrow in *screens* even as it gains content: marking a tour
     * of the app as "startup" tells R8 nothing about what to put next to what.
     */
    @Test
    fun startupProfileForDexLayout() = collectJourney(startupFlavour = true)

    private fun collectJourney(startupFlavour: Boolean) {
        rule.collect(
            packageName = PACKAGE,
            // Cold start is measured across several iterations and merged: ART's profile is sampled, so a
            // single run under-reports methods that were interpreted rather than JIT-compiled that time.
            maxIterations = MAX_ITERATIONS,
            stableIterations = STABLE_ITERATIONS,
            includeInStartupProfile = startupFlavour,
        ) {
            grantMeshPermissions()
            pressHome()
            startActivityAndWait()

            // The chat list. The Nearby room is always present, seeded or not, so it is the one row that
            // can be relied on with no data.
            device.wait(Until.hasObject(By.res(CHAT_ROW_NEARBY)), TIMEOUT_MS)

            // Into a thread: the chat screen's first composition is the most expensive in the app (the
            // message list, the composer, the moderation seam).
            device.findObject(By.res(CHAT_ROW_NEARBY))?.click()
            device.wait(Until.hasObject(By.res(CHAT_INPUT)), TIMEOUT_MS)

            postAMessage()
            scrollTheThread()

            // The return trip exercises the chat list's re-entry rather than its cold build — and now its
            // rows have a last-message preview to draw, which an empty room never gave them.
            device.pressBack()
            device.wait(Until.hasObject(By.res(CHAT_ROW_NEARBY)), TIMEOUT_MS)
        }
    }

    /**
     * Puts one message in the room through the composer, so the list has a bubble to draw.
     *
     * The echo wait is long because `sendChat` runs the on-device text moderator first and its tflite model
     * cold-loads on every fresh process — the same reason the instrumented suites allow a minute for it.
     * Data survives between iterations, so only the first pass starts from an empty room; the rest cold-start
     * onto a populated one, which is the case a returning user actually meets.
     */
    private fun MacrobenchmarkScope.postAMessage() {
        val input = device.findObject(By.res(CHAT_INPUT)) ?: return
        input.click()
        input.text = MESSAGE
        device.findObject(By.res(CHAT_SEND))?.click()
        device.wait(Until.hasObject(By.textContains(MESSAGE)), ECHO_TIMEOUT_MS)
    }

    /**
     * Flings the message list both ways. Item composition is only half of what a list costs; the other half
     * is the recycle-and-rebind path, which never runs unless something scrolls.
     */
    private fun MacrobenchmarkScope.scrollTheThread() {
        val thread = device.findObject(By.res(CHAT_THREAD)) ?: return
        thread.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
        thread.fling(Direction.UP)
        thread.fling(Direction.DOWN)
        device.waitForIdle()
    }

    /**
     * Grants every runtime permission the mesh asks for, so the app starts past the onboarding gate.
     * `pm grant` fails for a permission the manifest does not declare on this API level (older devices do
     * not know NEARBY_WIFI_DEVICES at all), and that is not a reason to fail the run — the app only needs
     * enough of them to get past `hasAllMeshPermissions`.
     */
    private fun grantMeshPermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        MESH_PERMISSIONS.forEach { permission ->
            runCatching { automation.executeShellCommand("pm grant $PACKAGE $permission").close() }
        }
    }

    private companion object {
        const val PACKAGE = "app.getknit.knit"

        // Compose testTags surface as uiautomator resource-ids app-wide (KnitApp sets
        // testTagsAsResourceId), so the profile journey addresses the same anchors the UIAutomator suite
        // does — if one of these is ever renamed, both break together rather than this drifting silently.
        const val CHAT_ROW_NEARBY = "chat_row_nearby"
        const val CHAT_INPUT = "chat_input"
        const val CHAT_SEND = "chat_send"
        const val CHAT_THREAD = "chat_thread"

        // Deliberately dull, and deliberately not a test marker. With a real radio attached this goes out
        // on the Nearby room as an ordinary broadcast, so anyone in range reads it.
        const val MESSAGE = "Morning"

        const val TIMEOUT_MS = 20_000L

        /** The first send cold-loads the tflite moderator; the instrumented suites allow the same minute. */
        const val ECHO_TIMEOUT_MS = 90_000L

        /** Keeps a fling off the gesture-navigation edges, which would go to the system instead of the list. */
        const val GESTURE_MARGIN_DIVISOR = 5

        const val MAX_ITERATIONS = 12
        const val STABLE_ITERATIONS = 3

        val MESH_PERMISSIONS =
            listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.POST_NOTIFICATIONS",
            )
    }
}
