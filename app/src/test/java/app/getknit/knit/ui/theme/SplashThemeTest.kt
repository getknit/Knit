package app.getknit.knit.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Contract test for the launch theme — the window the system paints *before* the first Compose frame
 * (the API 31+ splash screen, and the pre-31 starting window).
 *
 * [KnitTheme] tracks the system dark theme through `isSystemInDarkTheme()`, but `res/values/themes.xml`
 * is plain resource XML that nothing in the Kotlin build graph references, so nothing else would notice
 * the two drifting apart. That drift is exactly issue #2: the launch theme inherited
 * `Theme.Material.Light`'s near-white `windowBackground`, so a cold launch on a dark-themed device
 * flashed full-screen #FAFAFA before Compose drew the dark scheme.
 *
 * The invariants below pin the fix: `@color/splash_background` exists in both the default and the `night`
 * configuration, each equal to the [MaterialTheme][androidx.compose.material3.MaterialTheme] background
 * Compose is about to draw, and both window-background attributes resolve to it. The resources are read
 * straight off disk (module-dir cwd, like [app.getknit.knit.moderation.WordListTest]) — a fast,
 * deterministic JVM test with no Robolectric host.
 */
class SplashThemeTest {
    @Test
    fun lightSplashBackgroundMatchesTheComposeLightBackground() {
        assertEquals(
            "values/colors.xml splash_background must equal KnitTheme's BackgroundLight",
            BackgroundLight.hex(),
            colorValue("values"),
        )
    }

    @Test
    fun darkSplashBackgroundMatchesTheComposeDarkBackground() {
        // The one that actually fixes issue #2: without a night-qualified value the splash stays light.
        assertEquals(
            "values-night/colors.xml splash_background must equal KnitTheme's BackgroundDark",
            BackgroundDark.hex(),
            colorValue("values-night"),
        )
    }

    @Test
    fun launchThemePaintsBothWindowBackgroundsFromTheNightAwareColor() {
        // windowBackground covers minSdk 29-30 (no system splash screen there, just the starting window)
        // and is the API 31+ fallback; windowSplashScreenBackground is the explicit 31+ attribute. AAPT2
        // splits the latter into a -v31 config on its own, so declaring both here is safe at minSdk 29.
        val items = launchThemeItems()
        listOf("android:windowBackground", "android:windowSplashScreenBackground").forEach { attr ->
            assertEquals(
                "Theme.Knit must paint $attr from the night-aware colour",
                "@color/$SPLASH_COLOR",
                items[attr],
            )
        }
    }

    /** The `splash_background` literal declared in `res/<config>/colors.xml`, as `#AARRGGBB`. */
    private fun colorValue(config: String): String =
        elements("$config/colors.xml", "color")
            .firstOrNull { it.getAttribute("name") == SPLASH_COLOR }
            ?.textContent
            ?.trim()
            ?.uppercase()
            ?: error("no <color name=\"$SPLASH_COLOR\"> in res/$config/colors.xml")

    /** `Theme.Knit`'s own `<item>` overrides, keyed by attribute name. */
    private fun launchThemeItems(): Map<String, String> {
        val style =
            elements("values/themes.xml", "style").firstOrNull { it.getAttribute("name") == "Theme.Knit" }
                ?: error("no <style name=\"Theme.Knit\"> in res/values/themes.xml")
        return elements(style, "item").associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun elements(
        resPath: String,
        tag: String,
    ): List<Element> = elements(parse(resPath).documentElement, tag)

    private fun elements(
        parent: Element,
        tag: String,
    ): List<Element> =
        parent.getElementsByTagName(tag).let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }

    private fun parse(resPath: String) =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(resFile(resPath))

    private fun resFile(resPath: String): File =
        // Gradle runs unit tests with the module dir as the working dir.
        listOf("src/main/res/$resPath", "app/src/main/res/$resPath")
            .map(::File)
            .firstOrNull { it.exists() }
            ?: error("res/$resPath not found (cwd=${File(".").absolutePath})")

    private companion object {
        const val SPLASH_COLOR = "splash_background"

        fun Color.hex(): String = "#%08X".format(toArgb())
    }
}
