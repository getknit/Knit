package app.getknit.knit.crash

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ShareFileNameTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun `names the attachment for the version that crashed`() {
        assertEquals("knit-crash-2.3.0-20231114-2213.txt", shareFileName("2.3.0 (13) release", STAMP, zone))
    }

    @Test
    fun `sanitises a version that could not be a file name`() {
        assertEquals("knit-crash-1.4.2-beta-20231114-2213.txt", shareFileName("1.4.2/beta (9)", STAMP, zone))
    }

    @Test
    fun `falls back when the version is missing`() {
        assertEquals("knit-crash-unknown-20231114-2213.txt", shareFileName("", STAMP, zone))
    }

    private companion object {
        const val STAMP = 1_700_000_000_000L
    }
}
