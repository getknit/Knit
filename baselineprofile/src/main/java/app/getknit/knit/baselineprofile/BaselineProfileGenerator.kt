package app.getknit.knit.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Produces the ART baseline profile that ships as `app/src/main/baseline-prof.txt`.
 *
 * Run it, then copy the result over that file (see `.agents/context/baseline-profile.md`); this module is
 * not in the build unless `-Pknit.baselineProfile=true` asks for it, and nothing it contains is packaged.
 *
 * The journey is deliberately the **cold-start-to-first-conversation** path and not an exhaustive tour.
 * A baseline profile buys ahead-of-time compilation for the code it names, and naming everything is the
 * same as naming nothing: the profile grows, the dex layout loses its locality, and install time goes up.
 * What is here is what a user hits before they have decided whether the app feels good — process start,
 * Koin's graph, the SQLCipher open, Compose's first frame, the chat list, a thread, and one navigation in
 * each direction, which is the transition that exposed all of this in the first place.
 *
 * Permissions are granted up front rather than driven through the onboarding gate. Nothing about the
 * permission screen is on the hot path — it is seen once, ever — and granting them is what lets the run
 * reach the screens that are.
 */
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndFirstConversation() {
        rule.collect(
            packageName = PACKAGE,
            // Cold start is measured across several iterations and merged: ART's profile is sampled, so a
            // single run under-reports methods that were interpreted rather than JIT-compiled that time.
            maxIterations = MAX_ITERATIONS,
            stableIterations = STABLE_ITERATIONS,
            // Emit a plain baseline profile, not the startup-profile flavour. Startup profiles are a
            // separate AGP feature (`src/main/startup-prof.txt`) that reorders dex to put startup code
            // together; worth having one day, but it is a second thing to verify against the F-Droid
            // byte-comparison and this change is not the place. The rules still carry ART's own S/P
            // startup markers either way — that comes from the runtime, not from this flag.
            includeInStartupProfile = false,
        ) {
            grantMeshPermissions()
            pressHome()
            startActivityAndWait()

            // The chat list. The Nearby room is always present, seeded or not, so it is the one row that
            // can be relied on with no data.
            device.wait(Until.hasObject(By.res(CHAT_ROW_NEARBY)), TIMEOUT_MS)

            // Into a thread and back out: the chat screen's first composition is the most expensive in the
            // app (the message list, the composer, the moderation seam), and the return trip exercises the
            // chat list's re-entry rather than its cold build.
            device.findObject(By.res(CHAT_ROW_NEARBY))?.click()
            device.wait(Until.hasObject(By.res(CHAT_INPUT)), TIMEOUT_MS)
            device.pressBack()
            device.wait(Until.hasObject(By.res(CHAT_ROW_NEARBY)), TIMEOUT_MS)
        }
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

        const val TIMEOUT_MS = 20_000L
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
