package app.getknit.knit.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hop the split introduced: Settings renders its header row, and tapping it opens the profile editor.
 *
 * Deep-linking to `settings` makes it the nav root, so Profile stacks above it and the tap is the whole
 * journey — no Back needed. Asserting the header row (not the Scaffold tag) also proves the row's own
 * state arrived: it reads the display name off DataStore continuously, unlike the editor behind it.
 */
@RunWith(AndroidJUnit4::class)
class SettingsInstrumentedTest : SeededUiTest() {
    @Test
    fun theProfileRowOpensTheProfileEditor() {
        launch("settings")

        awaitTag("settings_profile_row")
        compose.onNodeWithTag("settings_profile_row").performClick()
        awaitTag("profile_name")
    }
}
