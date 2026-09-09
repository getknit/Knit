package app.getknit.knit.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFixPolicyTest {
    private fun fix(
        accuracy: Float?,
        at: Long,
    ) = LocationFix(lat = 1.0, lon = 2.0, accuracyM = accuracy, timeMs = at, elapsedRealtimeMs = at)

    @Test
    fun theFirstReadingIsKeptWhateverItIs() {
        val first = fix(accuracy = null, at = 0)
        assertSame(first, LocationFixPolicy.better(null, first))
    }

    @Test
    fun withinOneMomentTheTighterRadiusWinsAndAKnownRadiusBeatsNone() {
        val wide = fix(accuracy = 40f, at = 1_000)
        val tight = fix(accuracy = 6f, at = 2_000)
        assertSame(tight, LocationFixPolicy.better(wide, tight))
        assertSame("a wider newcomer is not an improvement", tight, LocationFixPolicy.better(tight, fix(accuracy = 30f, at = 3_000)))
        assertSame(tight, LocationFixPolicy.better(fix(accuracy = null, at = 1_500), tight))
        assertSame(tight, LocationFixPolicy.better(tight, fix(accuracy = null, at = 2_500)))
        val same = fix(accuracy = 6f, at = 2_500)
        assertSame("a tie goes to the newer reading", same, LocationFixPolicy.better(tight, same))
    }

    @Test
    fun aClearlyNewerReadingWinsOnAgeAloneAndAClearlyOlderOneLoses() {
        val tightButOld = fix(accuracy = 3f, at = 0)
        val looseButNow = fix(accuracy = 25f, at = LocationFixPolicy.STALE_AFTER_MS + 1)
        assertSame(looseButNow, LocationFixPolicy.better(tightButOld, looseButNow))
        assertSame(
            "a late delivery of an old reading never replaces the current one",
            looseButNow,
            LocationFixPolicy.better(looseButNow, tightButOld),
        )
    }

    @Test
    fun goodEnoughNeedsAKnownRadiusAtOrUnderTheTarget() {
        assertTrue(LocationFixPolicy.isGoodEnough(fix(accuracy = LocationFixPolicy.GOOD_ENOUGH_M, at = 0)))
        assertFalse(LocationFixPolicy.isGoodEnough(fix(accuracy = LocationFixPolicy.GOOD_ENOUGH_M + 0.5f, at = 0)))
        assertFalse(LocationFixPolicy.isGoodEnough(fix(accuracy = null, at = 0)))
    }

    @Test
    fun aCachedReadingIsUsableOnlyWhileFresh() {
        val cached = fix(accuracy = 10f, at = 10_000)
        assertSame(cached, LocationFixPolicy.usableLastKnown(cached, nowElapsedMs = 10_000 + LocationFixPolicy.MAX_LAST_KNOWN_AGE_MS))
        assertNull(LocationFixPolicy.usableLastKnown(cached, nowElapsedMs = 10_000 + LocationFixPolicy.MAX_LAST_KNOWN_AGE_MS + 1))
        assertNull(
            "a reading from the future is a clock fault, not a position",
            LocationFixPolicy.usableLastKnown(cached, nowElapsedMs = 9_000),
        )
        assertNull(LocationFixPolicy.usableLastKnown(null, nowElapsedMs = 0))
    }

    @Test
    fun theMessageFormRoundsTheRadiusUp() {
        assertEquals(GeoPoint(1.0, 2.0, 13), fix(accuracy = 12.2f, at = 0).toPoint())
        assertEquals(GeoPoint(1.0, 2.0, 1), fix(accuracy = 0.1f, at = 0).toPoint())
        assertEquals(GeoPoint(1.0, 2.0, null), fix(accuracy = null, at = 0).toPoint())
    }
}
