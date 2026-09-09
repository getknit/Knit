package app.getknit.knit.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GeoUriTest {
    private val point = GeoPoint(37.421998, -122.084, 12)

    @Test
    fun formatWritesSixDecimalsAndTheAccuracy() {
        assertEquals("geo:37.421998,-122.084000;u=12", GeoUri.format(point))
        assertEquals("geo:37.421998,-122.084000", GeoUri.format(point.copy(accuracyM = null)))
        assertEquals("a zero radius is no radius", "geo:37.421998,-122.084000", GeoUri.format(point.copy(accuracyM = 0)))
    }

    @Test
    fun formatIgnoresTheDefaultLocale() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("geo:37.421998,-122.084000;u=12", GeoUri.format(point))
            assertEquals("37.421998, -122.084000", GeoUri.coordinates(point))
            assertEquals("37.421998,-122.084000", GeoUri.mapsQuery(point))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun formatClampsIntoRange() {
        assertEquals("geo:90.000000,-180.000000", GeoUri.format(GeoPoint(91.0, -181.0)))
    }

    @Test
    fun parseRoundTripsWhatFormatWrote() {
        assertEquals(point, GeoUri.parse(GeoUri.format(point)))
        assertEquals(point.copy(accuracyM = null), GeoUri.parse(GeoUri.format(point.copy(accuracyM = null))))
    }

    @Test
    fun parseAcceptsTheGrammarsOptionalParts() {
        assertEquals(GeoPoint(48.2, 16.37, null), GeoUri.parse("GEO:48.2,16.37"))
        assertEquals(GeoPoint(48.2, 16.37, null), GeoUri.parse("geo:48.2,16.37,183"))
        assertEquals(GeoPoint(48.2, 16.37, 5), GeoUri.parse("geo:48.2,16.37;crs=wgs84;u=5"))
        assertEquals("a fractional radius rounds up", GeoPoint(48.2, 16.37, 3), GeoUri.parse("geo:48.2,16.37;u=2.1"))
        assertEquals(
            "Android's query suffix is tolerated",
            GeoPoint(48.2, 16.37, null),
            GeoUri.parse("geo:48.2,16.37?q=48.2,16.37(Home)&z=17"),
        )
        assertEquals(GeoPoint(-33.8688, 151.2093, null), GeoUri.parse("geo:-33.8688,151.2093"))
    }

    @Test
    fun parseRefusesWhatIsNotAPosition() {
        listOf(
            "",
            "geo:",
            "geo:abc",
            "geo:1",
            "geo:91,0",
            "geo:0,181",
            "geo:-90.1,0",
            "geo:1,2;u=x",
            "geo:1,2;foo=bar",
            "geo:1,2;u=",
            "geo:1,2 ",
            "geo:1.2.3,4",
            "geo:1,2,3,4",
            "https://example.com/geo:1,2",
            "geo:" + "1".repeat(200),
        ).forEach { assertNull(it, GeoUri.parse(it)) }
    }

    @Test
    fun findTakesTheFirstTokenThatParsesAndLeavesProsePunctuationOutside() {
        assertEquals(point, GeoUri.find("meet at geo:37.421998,-122.084000;u=12."))
        assertEquals(point, GeoUri.find("here:\ngeo:37.421998,-122.084000;u=12"))
        assertEquals(GeoPoint(1.0, 2.0), GeoUri.find("(geo:1,2) then geo:3,4"))
        assertNull(GeoUri.find("no position here"))
        assertNull("a token glued to prose is not a position", GeoUri.find("xgeo:1,2"))
        assertNull("a token that fails the grammar is plain text", GeoUri.find("geo:91,0 is not a place"))
        assertFalse(GeoUri.contains("geo: 1,2"))
        assertTrue(GeoUri.contains("geo:1,2"))
    }

    @Test
    fun stripRemovesThePositionAndTheLineItStoodOn() {
        assertEquals("See you at the gate", GeoUri.strip("See you at the gate\ngeo:37.421998,-122.084000;u=12"))
        assertEquals("", GeoUri.strip("geo:37.421998,-122.084000;u=12"))
        assertEquals("meet at now", GeoUri.strip("meet at geo:1,2 now"))
        assertEquals("first\n\nsecond", GeoUri.strip("first\n\nsecond\ngeo:1,2"))
        assertEquals("untouched text", GeoUri.strip("untouched text"))
        assertEquals("geo:91,0 stays", GeoUri.strip("geo:91,0 stays"))
    }

    @Test
    fun describeNamesThePositionOnOneLine() {
        assertEquals(
            "See you at the gate 📍 Location",
            GeoUri.describe("See you at the gate\ngeo:37.421998,-122.084000;u=12", GeoUri.LABEL),
        )
        assertEquals("📍 Location", GeoUri.describe("geo:1,2", GeoUri.LABEL))
        assertEquals("Location, then Location.", GeoUri.describe("geo:1,2, then geo:3,4.", "Location"))
        assertEquals("plain", GeoUri.describe("plain", GeoUri.LABEL))
        assertEquals("", GeoUri.describe("", GeoUri.LABEL))
    }

    @Test
    fun theReserveCoversTheLongestTokenFormatCanWrite() {
        val longest = GeoUri.format(GeoPoint(-90.0, -180.0, 9_999_999))
        assertTrue(longest, longest.length <= GeoUri.RESERVE_BYTES)
        assertTrue(longest.all { it.code < 128 })
    }
}
