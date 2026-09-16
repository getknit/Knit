package app.getknit.knit.legal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The license texts are committed copies under `assets/legal/` — no Gradle task writes them, so the release
 * APK stays a function of the tree alone — and this is what keeps the copies honest: `COPYING` byte-equal to
 * the repository's, every [License] backed by a real file, no file without a [License].
 */
class LicenseAssetsTest {
    private val assetsDir: File by lazy { repoFile("app/src/main/assets/legal") }

    @Test
    fun theBundledCopyingIsTheRepositorysCopying() {
        assertArrayEquals(repoFile("COPYING").readBytes(), File(assetsDir, "COPYING").readBytes())
    }

    @Test
    fun everyLicenseHasATextAndEveryTextHasALicense() {
        License.entries.forEach { license ->
            val file = repoFile("app/src/main/assets/${license.asset}")
            assertTrue("${license.asset} looks truncated", file.length() > 1_000)
        }
        val claimed = License.entries.map { it.asset.removePrefix("legal/") }.toSet()
        val present =
            assetsDir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .toSet()
        assertEquals("assets/legal/ and License.entries disagree", claimed, present)
    }

    @Test
    fun everyTextReflowsIntoParagraphs() {
        License.entries.forEach { license ->
            val paragraphs = licenseParagraphs(repoFile("app/src/main/assets/${license.asset}").readText())
            assertTrue("${license.spdx} reflowed to nothing", paragraphs.size >= 3)
            paragraphs.forEach { assertTrue("'$it' keeps a hard wrap", '\n' !in it && it == it.trim()) }
        }
    }

    @Test
    fun routeIdsRoundTrip() {
        License.entries.forEach { assertEquals(it, License.fromRouteId(it.routeId)) }
        assertEquals(null, License.fromRouteId("wtfpl"))
    }
}
