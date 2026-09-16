package app.getknit.knit.ui.about

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.legal.License
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Null paragraphs is loading, an empty list is a failed read, anything else is the text. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LicenseTextScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun render(paragraphs: List<String>?) {
        compose.setContent {
            KnitTheme { LicenseTextScreenContent(license = License.MIT, paragraphs = paragraphs, onBack = {}) }
        }
    }

    @Test
    fun loadingShowsAProgressIndicatorAndNoBody() {
        render(paragraphs = null)
        compose.onNodeWithTag("license_text_loading").assertIsDisplayed()
        compose.onNodeWithTag("license_text_body").assertDoesNotExist()
    }

    @Test
    fun aFailedReadSaysSo() {
        render(paragraphs = emptyList())
        compose.onNodeWithTag("license_text_error").assertIsDisplayed()
        compose.onNodeWithTag("license_text_body").assertDoesNotExist()
    }

    @Test
    fun paragraphsRenderUnderTheLicensesName() {
        render(paragraphs = listOf("Copyright (c) 2020 The nsfw_model Developers", "Permission is hereby granted."))
        compose.onNodeWithText("Copyright (c) 2020 The nsfw_model Developers").assertIsDisplayed()
        compose.onNodeWithText("Permission is hereby granted.").assertIsDisplayed()
        compose.onNodeWithTag("license_text_body").assertIsDisplayed()
        compose.onNodeWithText(License.MIT.displayName).assertIsDisplayed()
    }
}
