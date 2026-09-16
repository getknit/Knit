package app.getknit.knit.ui

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The About journey on a real Compose host: Settings' overflow opens About and the licenses list, and both
 * paths end at a license text read off `assets/legal/` — the part a Robolectric content test cannot cover.
 *
 * Deep-linking to `settings` makes it the nav root, so each hop stacks above it and no Back is needed. The
 * overflow's items are driven by text: tags inside a `DropdownMenu` popup do not surface to the finder the
 * way the Scaffold's do.
 */
@RunWith(AndroidJUnit4::class)
class AboutInstrumentedTest : SeededUiTest() {
    @Test
    fun theOverflowOpensAboutAndTheLicenseTextLoads() {
        launch("settings")

        awaitTag("settings_profile_row")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("About Knit").performClick()
        awaitTag("about_build_device")

        compose.onNodeWithTag("about_license").performScrollTo().performClick()
        awaitTag("license_text_body")
    }

    @Test
    fun theOverflowOpensTheLicensesListAndARowOpensItsText() {
        launch("settings")

        awaitTag("settings_profile_row")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Open-source licenses").performClick()
        awaitTag("licenses_row_knit")

        compose.onNodeWithTag("licenses_row_androidx").performScrollTo().performClick()
        awaitTag("license_text_body")
    }
}
