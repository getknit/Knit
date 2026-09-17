package app.getknit.knit.ui.onboarding

import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.MeshPermissionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/**
 * Which "Open settings" hint a row shows on an administered phone (ADR 2026-09.a8ud). A policy-denied grant
 * is indistinguishable from "don't ask again", so the parent is named only where Family Link can actually
 * hold that grant — the radio row on the tiers that put Location in it — while an administrator can hold
 * any of them.
 */
class SupervisedHintTest {
    @Test
    fun aPlainPhoneNamesNobody() {
        MeshPermissionTier.entries.forEach { tier ->
            GrantRow.entries.forEach { row ->
                assertNull(supervisedHint(DeviceSupervision.None, tier, row))
            }
        }
    }

    @Test
    fun familyLinkIsNamedOnTheRadioRowOnlyWhereLocationIsInIt() {
        assertEquals(
            DeviceSupervision.FamilyLink,
            supervisedHint(DeviceSupervision.FamilyLink, MeshPermissionTier.LOCATION, GrantRow.Radio),
        )
        assertEquals(
            DeviceSupervision.FamilyLink,
            supervisedHint(DeviceSupervision.FamilyLink, MeshPermissionTier.LOCATION_AND_BLUETOOTH, GrantRow.Radio),
        )
        // Nearby devices is not a group a parent can hold, so the generic line stays on 33+.
        assertNull(supervisedHint(DeviceSupervision.FamilyLink, MeshPermissionTier.NEARBY_DEVICES, GrantRow.Radio))
    }

    @Test
    fun familyLinkIsNeverNamedOnTheNotificationsRow() {
        MeshPermissionTier.entries.forEach { tier ->
            assertNull(supervisedHint(DeviceSupervision.FamilyLink, tier, GrantRow.Notifications))
        }
    }

    @Test
    fun aManagedPhoneIsNamedOnEveryRowAndTier() {
        MeshPermissionTier.entries.forEach { tier ->
            GrantRow.entries.forEach { row ->
                assertEquals(DeviceSupervision.Managed, supervisedHint(DeviceSupervision.Managed, tier, row))
            }
        }
    }
}
