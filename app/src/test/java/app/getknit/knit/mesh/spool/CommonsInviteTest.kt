package app.getknit.knit.mesh.spool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invite codec against the daemon's: the pinned string below is what `knit-spool commons-invite`
 * prints for the §13 fixture secret, so a drift on either side shows up here before it shows up as a
 * member subscribed to a scope nobody else can find.
 */
class CommonsInviteTest {
    private val secret = ByteArray(32) { ((it * 7 + 10) and 0xFF).toByte() }

    @Test
    fun encodeMatchesTheDaemonsForm() {
        assertEquals(PINNED, CommonsInvite.encode(secret))
    }

    @Test
    fun decodeRoundTripsAndTolerateWhitespace() {
        assertArrayEquals(secret, CommonsInvite.decode(PINNED))
        assertArrayEquals(secret, CommonsInvite.decode("  $PINNED\n"))
        assertTrue(CommonsInvite.looksLikeInvite(PINNED))
    }

    @Test
    fun anythingThatIsNotAnInviteIsNull() {
        assertNull(CommonsInvite.decode(""))
        assertNull(CommonsInvite.decode("wss://spool.example/spool/v1"))
        assertNull(CommonsInvite.decode(PINNED.removePrefix("knit-commons:v1:")))
        assertNull(CommonsInvite.decode("knit-commons:v2:" + PINNED.removePrefix("knit-commons:v1:")))
        assertNull(CommonsInvite.decode(PINNED.dropLast(4)))
        assertNull(CommonsInvite.decode(PINNED + "AAAA"))
        assertNull(CommonsInvite.decode("knit-commons:v1:not*base64*at*all"))
        assertFalse(CommonsInvite.looksLikeInvite("knit-commons:v1:"))
    }

    @Test
    fun aPaddedBodyIsStillTheSameSecret() {
        // The daemon never emits padding, but the JDK decoder accepts it — a member who pads by hand
        // still lands in the right room rather than on a typo.
        assertArrayEquals(secret, CommonsInvite.decode("$PINNED="))
    }

    @Test
    fun encodeRefusesTheWrongLength() {
        assertTrue(runCatching { CommonsInvite.encode(ByteArray(31)) }.isFailure)
    }

    private companion object {
        const val PINNED = "knit-commons:v1:ChEYHyYtNDtCSVBXXmVsc3qBiI-WnaSrsrnAx87V3OM"
    }
}
