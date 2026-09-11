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
import kotlin.math.sign

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

    /**
     * The overscroll glow is the one colour Compose does not take from a theme at all:
     * `AndroidOverscroll.android.kt` compiles in `DefaultGlowColor = Color(0xFF666666)` and hands it to
     * `EdgeEffect.setColor`, so a list that runs out of content glows flat grey under warm coral surfaces.
     * `KnitTheme` overrides it through `LocalOverscrollFactory`. Only ever visible on API 29-30 — from 31
     * the effect is the stretch, which ignores the colour — which is exactly why nothing else would catch
     * this going missing: the lab Pixels can't show it.
     */
    @Test
    fun overscrollGlowComesFromTheThemeNotComposesGrey() {
        val match =
            GLOW_COLOR.find(themeSource())
                ?: error(
                    "KnitTheme no longer provides LocalOverscrollFactory, so every list falls back to " +
                        "Compose's hardcoded #666666 glow on API 29-30.",
                )
        assertTrue(
            "The glow is ${match.groupValues[1]}, which is not a role off the scheme KnitTheme just built " +
                "— so it stops tracking dark mode and the Material You switch.",
            match.groupValues[1].startsWith("colorScheme."),
        )
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

    /**
     * The identity-keyed avatar palette (ADR 2026-09.j8c7) is generated at Material's container tones, so
     * the initial should clear text contrast by construction — this is the measurement behind that claim,
     * against AA's 4.5:1 text floor rather than the 3:1 graphic floor, because the initial *is* text.
     */
    @Test
    fun everyAvatarTintClearsTextContrast() {
        listOf("light" to AvatarTintsLight, "dark" to AvatarTintsDark).forEach { (which, tints) ->
            assertEquals(
                "the $which palette has a different size, so slots no longer line up across modes",
                AvatarTintsLight.size,
                tints.size,
            )
            tints.forEachIndexed { i, tint ->
                val ratio = contrastRatio(tint.onContainer, tint.container)
                assertTrue(
                    "$which avatar tint $i: ${tint.onContainer.hex()} on ${tint.container.hex()} is %.2f:1, under AA's %.1f:1 for text"
                        .format(ratio, MIN_TEXT_CONTRAST),
                    ratio >= MIN_TEXT_CONTRAST,
                )
            }
        }
    }

    /**
     * The palette walks the wheel once, in order, and a slot is the same hue in both modes — so a person is
     * "the green one" whether the phone is light or dark, and a hand edit that swapped or duplicated a row
     * is caught. Measured in HSL hue, which is not the generator's HCT and bunches around orange and cyan;
     * that is why this checks order and per-slot agreement rather than a fixed gap between neighbours.
     */
    @Test
    fun avatarTintsWalkTheWheelOnceAndAgreeAcrossModes() {
        listOf("light" to AvatarTintsLight, "dark" to AvatarTintsDark).forEach { (which, tints) ->
            val hues = tints.map { hueOf(it.container) }
            val steps = hues.indices.map { i -> (hues[(i + 1) % hues.size] - hues[i] + 360f) % 360f }
            steps.forEachIndexed { i, step ->
                assertTrue(
                    "$which avatar tints $i -> ${(i + 1) % hues.size} step ${step.toInt()}° — the table is out of order or has a repeat",
                    step > 0f && step < 180f,
                )
            }
            assertEquals("$which palette does not lap the wheel exactly once", 360f, steps.sum(), 1f)
        }
        AvatarTintsLight.zip(AvatarTintsDark).forEachIndexed { i, (light, dark) ->
            val gap = hueDistance(hueOf(light.container), hueOf(dark.container))
            assertTrue(
                "avatar slot $i is hue ${hueOf(light.container).toInt()}° in light but ${hueOf(dark.container).toInt()}° in dark",
                gap <= MAX_AVATAR_MODE_HUE_GAP,
            )
        }
    }

    /**
     * The slot is the contract: the same node id must land on the same tint on every phone and after every
     * update, or Ada changes colour between your list and Bob's, and between the list and the notification
     * shade. Pinned vectors, so a "better" hash or a resized palette shows up here rather than on devices.
     */
    @Test
    fun avatarTintSlotIsStableAcrossPhonesAndVersions() {
        assertEquals(12, AvatarTintsLight.size)
        // A real-shaped node id (26 base32 chars), a short label, a display name, and the empty string.
        assertEquals(0, avatarTintIndex("mfrggzdfmztwq2lknnwg23tpobyxe43uov3ho6dz"))
        assertEquals(6, avatarTintIndex("ada"))
        assertEquals(7, avatarTintIndex("Ada Lovelace"))
        assertEquals(5, avatarTintIndex("!a1b2c3d4"))
        assertEquals(0, avatarTintIndex(""))
        // Integer.MIN_VALUE is the one hash a `%` would have sent negative; floorMod keeps it in range.
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        assertEquals(4, avatarTintIndex("polygenelubricants"))
        // What AvatarPalettePreview relies on to show the twelve slots in order.
        assertEquals((0 until 12).toList(), ('l'..'w').map { avatarTintIndex(it.toString()) })
    }

    /** The colour maths under harmonize: a colour must come back from Oklch as itself, or every tint drifts. */
    @Test
    fun oklchRoundTripsWithinAStep() {
        (AvatarTintsLight + AvatarTintsDark).flatMap { listOf(it.container, it.onContainer) }.plus(ACCENTS).forEach { color ->
            val back = Oklch.of(color).toColor()
            val (r1, g1, b1) = color.rgb()
            val (r2, g2, b2) = back.rgb()
            assertTrue(
                "${color.hex()} came back from Oklch as ${back.hex()}",
                abs(r1 - r2) <= 1 && abs(g1 - g2) <= 1 && abs(b1 - b2) <= 1,
            )
        }
    }

    /**
     * Material's harmonize rule, as `AvatarTint.harmonizedToward` applies it: the hue turns toward the
     * accent by half the difference and never past 15°, and lightness stays put — which is what keeps a
     * green contact green under a blue wallpaper and keeps the initial's contrast intact.
     */
    @Test
    fun harmonizeTurnsTheHueTowardTheAccentByAtMostFifteenDegreesAndMovesNothingElse() {
        (AvatarTintsLight + AvatarTintsDark).flatMap { listOf(it.container, it.onContainer) }.forEach { color ->
            ACCENTS.forEach { accent ->
                val from = Oklch.of(color)
                val to = Oklch.of(accent)
                val difference = signedHue(to.hue - from.hue)
                val turn = difference.sign * min(abs(difference) / 2.0, MAX_HARMONIZE_DEGREES)
                // Compared as 8-bit colours rather than as angles: at a pastel's chroma one quantisation step
                // is worth a degree or more of hue, so the honest oracle is the colour the rule's own turn
                // produces, and it must match to the step.
                val expected = from.copy(hue = from.hue + turn).toColor()
                val got = harmonize(color, accent)
                val (r1, g1, b1) = expected.rgb()
                val (r2, g2, b2) = got.rgb()
                assertTrue(
                    "${color.hex()} toward ${accent.hex()} (difference ${"%.1f".format(difference)}°, turn ${"%.1f".format(turn)}°) " +
                        "should be ${expected.hex()} but is ${got.hex()}",
                    abs(r1 - r2) <= 1 && abs(g1 - g2) <= 1 && abs(b1 - b2) <= 1,
                )
                val moved = Oklch.of(got)
                assertEquals("${color.hex()} toward ${accent.hex()} changed lightness", from.lightness, moved.lightness, OKLCH_TOLERANCE)
                // Chroma may only ever come *down*, and only when the turned hue left the gamut (a light
                // saturated yellow turned toward orange); it never goes up.
                assertTrue(
                    "${color.hex()} toward ${accent.hex()} gained chroma: ${from.chroma} -> ${moved.chroma}",
                    moved.chroma <= from.chroma + OKLCH_TOLERANCE,
                )
            }
        }
    }

    /** A monochrome wallpaper theme has a grey primary, which has no hue to steer by: the palette stays as drawn. */
    @Test
    fun harmonizeTowardAGreyIsANoOp() {
        (AvatarTintsLight + AvatarTintsDark).forEach { tint ->
            assertEquals(tint, tint.harmonizedToward(Color(0xFF777777)))
        }
    }

    /** Harmonizing keeps tone, so the AA floor the palette was generated to should survive any wallpaper. */
    @Test
    fun harmonizedAvatarTintsStillClearTextContrast() {
        listOf("light" to AvatarTintsLight, "dark" to AvatarTintsDark).forEach { (which, tints) ->
            tints.forEachIndexed { i, tint ->
                ACCENTS.forEach { accent ->
                    val moved = tint.harmonizedToward(accent)
                    val ratio = contrastRatio(moved.onContainer, moved.container)
                    assertTrue(
                        "$which avatar tint $i harmonized toward ${accent.hex()} is %.2f:1, under AA's %.1f:1 for text".format(
                            ratio,
                            MIN_TEXT_CONTRAST,
                        ),
                        ratio >= MIN_TEXT_CONTRAST,
                    )
                }
            }
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

        /** WCAG AA's floor for text at any size. */
        const val MIN_TEXT_CONTRAST = 4.5

        /** How far one avatar slot's hue may drift between the light and dark tables. */
        const val MAX_AVATAR_MODE_HUE_GAP = 20f

        /** Slack for Oklch lightness and chroma after an 8-bit round trip. */
        const val OKLCH_TOLERANCE = 0.006

        /**
         * Wallpaper primaries to harmonize toward: Material's baseline purple, then a blue, green, amber and
         * red of the kind `dynamicLightColorScheme` produces (tone 40), plus one that sits opposite the
         * palette's coral so the 15° cap is exercised.
         */
        val ACCENTS =
            listOf(
                Color(0xFF6750A4),
                Color(0xFF0B57D0),
                Color(0xFF386A20),
                Color(0xFF7A5900),
                Color(0xFFB3261E),
                Color(0xFF006A6A),
            )

        fun signedHue(degrees: Double): Double {
            val d = ((degrees % 360.0) + 360.0) % 360.0
            return if (d > 180.0) d - 360.0 else d
        }

        val NAMED_ARG = Regex("""(\w+)\s*=""")

        /** The `glowColor` KnitTheme hands to `rememberPlatformOverscrollFactory`. */
        val GLOW_COLOR = Regex("""rememberPlatformOverscrollFactory\(glowColor = ([\w.]+)\)""")

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
