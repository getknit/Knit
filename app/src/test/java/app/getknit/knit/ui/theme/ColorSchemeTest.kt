package app.getknit.knit.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Contract tests for the two static [androidx.compose.material3.ColorScheme]s in [KnitTheme].
 *
 * `lightColorScheme()`/`darkColorScheme()` default every role the caller omits, and the defaults are
 * Material's **baseline** palette — a purple-tinted neutral ramp (`surfaceContainer` = #F3EDF7, hue 276°).
 * Knit set 24 of ~45 roles and let the rest fall through, so every `Card`, `AlertDialog`, `DropdownMenu`,
 * `ModalBottomSheet` and scrolled `TopAppBar` drew a purple-grey container against warm coral surfaces.
 * Nothing in the build would ever have noticed: an omitted role is valid Kotlin and renders fine, just in
 * the wrong hue family.
 *
 * Two independent guards, because they catch opposite mistakes. [everyRoleTheDynamicSchemeSetsIsSetHere]
 * catches a **missing** role, by reading `Theme.kt` and diffing its named arguments against the role set
 * Material's own dynamic scheme fills. [neutralRolesCarryTheBrandHue] catches a **wrong** role, by
 * measuring hue: a value that fell back to baseline lands near 270°, nowhere near the brand's ~40°.
 *
 * Plain JVM, no Robolectric host — [SplashThemeTest] already establishes that [Color] and `toArgb()` work
 * here. The colour arithmetic is hand-rolled for a reason: `androidx.core.graphics.ColorUtils` delegates
 * to `android.graphics.Color`, and this module sets `unitTests.isReturnDefaultValues = true`, so every
 * channel would read back 0 and the assertions would pass on garbage.
 */
class ColorSchemeTest {
    @Test
    fun everyRoleTheDynamicSchemeSetsIsSetHere() {
        val source = themeSource()
        listOf("lightColorScheme" to "Light", "darkColorScheme" to "Dark").forEach { (factory, which) ->
            val set = namedArgumentsOf(source, factory)
            assertEquals(
                "$which scheme omits roles the dynamic scheme fills, so they fall through to Material's " +
                    "baseline purple neutrals. Toggling Material You would reveal exactly which ones.",
                emptySet<String>(),
                DYNAMIC_ROLES - set,
            )
        }
    }

    @Test
    fun neutralRolesCarryTheBrandHue() {
        (neutralRoles(light = true) + neutralRoles(light = false)).forEach { (name, color) ->
            // Near-greys have no meaningful hue, and white/black legitimately have none at all.
            if (chromaProxy(color) < CHROMA_FLOOR) return@forEach
            val hue = hueOf(color)
            assertTrue(
                "$name is ${color.hex()} (hue ${hue.toInt()}°). Material's baseline neutrals sit near 270° " +
                    "— set this role explicitly in Theme.kt rather than letting it default.",
                hueDistance(hue, BRAND_HUE) <= HUE_TOLERANCE,
            )
        }
    }

    /** Material You landed in API 31; minSdk here is 29, so two supported releases have no wallpaper palette. */
    @Test
    fun dynamicColourIsOfferedOnlyFromApi31() {
        assertFalse("API 29 has no wallpaper palette", dynamicColorAvailable(29))
        assertFalse("API 30 has no wallpaper palette", dynamicColorAvailable(30))
        assertTrue("API 31 is where Material You landed", dynamicColorAvailable(31))
        assertTrue(dynamicColorAvailable(34))
    }

    @Test
    fun positiveClearsContrastAgainstItsOwnSurface() {
        // KnitSemanticColors pins this green across every scheme, so it has to hold up against a dynamic
        // surface too. That is safe because Material fixes the dynamic surface *tone* (98 light, 6 dark)
        // and varies only its hue, which means the static surfaces below are a fair stand-in.
        listOf(
            "light" to (LightSemanticColors.positive to LightColorScheme.surface),
            "dark" to (DarkSemanticColors.positive to DarkColorScheme.surface),
        ).forEach { (which, pair) ->
            val (positive, surface) = pair
            val ratio = contrastRatio(positive, surface)
            assertTrue(
                "positive on the $which surface is %.2f:1, under the %.1f:1 floor for a meaningful graphic"
                    .format(ratio, MIN_CONTRAST),
                ratio >= MIN_CONTRAST,
            )
        }
    }

    /** Every role whose value is a neutral or surface — the ones a baseline fallthrough would taint. */
    private fun neutralRoles(light: Boolean): List<Pair<String, Color>> {
        val s = if (light) LightColorScheme else DarkColorScheme
        val suffix = if (light) "(light)" else "(dark)"
        return listOf(
            "background $suffix" to s.background,
            "onBackground $suffix" to s.onBackground,
            "surface $suffix" to s.surface,
            "onSurface $suffix" to s.onSurface,
            "surfaceVariant $suffix" to s.surfaceVariant,
            "onSurfaceVariant $suffix" to s.onSurfaceVariant,
            "surfaceBright $suffix" to s.surfaceBright,
            "surfaceDim $suffix" to s.surfaceDim,
            "surfaceContainerLowest $suffix" to s.surfaceContainerLowest,
            "surfaceContainerLow $suffix" to s.surfaceContainerLow,
            "surfaceContainer $suffix" to s.surfaceContainer,
            "surfaceContainerHigh $suffix" to s.surfaceContainerHigh,
            "surfaceContainerHighest $suffix" to s.surfaceContainerHighest,
            "outline $suffix" to s.outline,
            "outlineVariant $suffix" to s.outlineVariant,
            "inverseSurface $suffix" to s.inverseSurface,
            "inverseOnSurface $suffix" to s.inverseOnSurface,
        )
    }

    /** `Theme.kt`'s own text: a role left unset is *absent* from the source, which a ColorScheme
     *  instance cannot show — it reports the default. So completeness is checked against the source. */
    private fun themeSource(): String =
        listOf(
            "src/main/java/app/getknit/knit/ui/theme/Theme.kt",
            "app/src/main/java/app/getknit/knit/ui/theme/Theme.kt",
        ).map(::File).firstOrNull { it.exists() }?.readText()
            ?: error("Theme.kt not found (cwd=${File(".").absolutePath})")

    /** The `name =` arguments inside `factory(...)`, matched across its balanced parentheses. */
    private fun namedArgumentsOf(
        source: String,
        factory: String,
    ): Set<String> {
        val start = source.indexOf("$factory(").takeIf { it >= 0 } ?: error("no $factory( in Theme.kt")
        var depth = 0
        var end = start + factory.length
        while (end < source.length) {
            when (source[end]) {
                '(' -> depth++
                ')' -> if (--depth == 0) break
            }
            end++
        }
        return NAMED_ARG.findAll(source.substring(start, end)).map { it.groupValues[1] }.toSet()
    }

    private companion object {
        /** The warm hue every neutral in the brand ramp is tinted with (neutral-variant hue 41.3°). */
        const val BRAND_HUE = 40f
        const val HUE_TOLERANCE = 35f

        /** Below this, a colour is a grey and its hue is numerically meaningless. */
        const val CHROMA_FLOOR = 0.012f

        /** WCAG's floor for a graphical object that carries meaning. */
        const val MIN_CONTRAST = 3.0

        val NAMED_ARG = Regex("""(\w+)\s*=""")

        /**
         * Every role `dynamicLightColorScheme31`/`dynamicDarkColorScheme31` fills (material3 1.4.0).
         * The four `error*` roles are **deliberately absent**: the dynamic schemes never set them either,
         * so they stay on Material's wallpaper-independent red in both modes, which is what keeps
         * `RadioWarningBanner` identical whether Material You is on or off. Re-check this list when the
         * Compose BOM moves.
         */
        val DYNAMIC_ROLES =
            setOf(
                "primary",
                "onPrimary",
                "primaryContainer",
                "onPrimaryContainer",
                "inversePrimary",
                "secondary",
                "onSecondary",
                "secondaryContainer",
                "onSecondaryContainer",
                "tertiary",
                "onTertiary",
                "tertiaryContainer",
                "onTertiaryContainer",
                "background",
                "onBackground",
                "surface",
                "onSurface",
                "surfaceVariant",
                "onSurfaceVariant",
                "surfaceTint",
                "inverseSurface",
                "inverseOnSurface",
                "outline",
                "outlineVariant",
                "scrim",
                "surfaceBright",
                "surfaceDim",
                "surfaceContainerLowest",
                "surfaceContainerLow",
                "surfaceContainer",
                "surfaceContainerHigh",
                "surfaceContainerHighest",
                "primaryFixed",
                "primaryFixedDim",
                "onPrimaryFixed",
                "onPrimaryFixedVariant",
                "secondaryFixed",
                "secondaryFixedDim",
                "onSecondaryFixed",
                "onSecondaryFixedVariant",
                "tertiaryFixed",
                "tertiaryFixedDim",
                "onTertiaryFixed",
                "onTertiaryFixedVariant",
            )

        fun Color.rgb(): Triple<Int, Int, Int> {
            val argb = toArgb()
            return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        }

        fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

        /** Saturation in the HSL sense: enough to tell a tinted neutral from a true grey. */
        fun chromaProxy(color: Color): Float {
            val (r, g, b) = color.rgb()
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            return if (hi == 0) 0f else (hi - lo) / 255f
        }

        fun hueOf(color: Color): Float {
            val (r, g, b) = color.rgb()
            val rf = r / 255f
            val gf = g / 255f
            val bf = b / 255f
            val hi = max(rf, max(gf, bf))
            val lo = min(rf, min(gf, bf))
            val d = hi - lo
            if (d == 0f) return 0f
            val h =
                when (hi) {
                    rf -> 60f * (((gf - bf) / d) % 6f)
                    gf -> 60f * (((bf - rf) / d) + 2f)
                    else -> 60f * (((rf - gf) / d) + 4f)
                }
            return (h + 360f) % 360f
        }

        fun hueDistance(
            a: Float,
            b: Float,
        ): Float {
            val d = abs(a - b) % 360f
            return if (d > 180f) 360f - d else d
        }

        fun relativeLuminance(color: Color): Double {
            val (r, g, b) = color.rgb()

            fun channel(c: Int): Double {
                val n = c / 255.0
                return if (n <= 0.03928) n / 12.92 else ((n + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        }

        fun contrastRatio(
            a: Color,
            b: Color,
        ): Double {
            val la = relativeLuminance(a)
            val lb = relativeLuminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }
    }
}
