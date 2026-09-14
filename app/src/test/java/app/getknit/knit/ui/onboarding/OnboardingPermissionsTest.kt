package app.getknit.knit.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Open settings" decision. Android answers `shouldShowRequestPermissionRationale == false` both before
 * the first ask and after "don't ask again", so the pure rule has to lean on whether we asked at all — and
 * one un-askable grant in the set is enough, because the radio request is a single dialog sequence.
 */
class OnboardingPermissionsTest {
    private val missing = listOf("a", "b")

    @Test
    fun neverAskedIsNeverSettings_evenWhenAndroidWouldShowNoRationale() {
        assertFalse(needsSettings(asked = false, missing = missing) { false })
    }

    @Test
    fun askedAndStillAskableIsNotSettings() {
        assertFalse(needsSettings(asked = true, missing = missing) { true })
    }

    @Test
    fun askedWithOneUnaskableGrantAmongAskableOnesIsSettings() {
        assertTrue(needsSettings(asked = true, missing = missing) { it == "a" })
    }

    @Test
    fun nothingMissingIsNeverSettings() {
        assertFalse(needsSettings(asked = true, missing = emptyList()) { false })
    }
}
