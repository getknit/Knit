package app.getknit.knit.data.emoji

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class EmojiCatalogLoaderTest {
    private val asset = "# c\n😀\t0\t0\tgrinning face\n🦄\t2\t0\tunicorn\n👍🏻\t1\t1\tthumbs up: light skin tone\n"

    @Test
    fun loadsOnceAndCachesTheCatalog() =
        runTest {
            var opens = 0
            val loader =
                EmojiCatalogLoader(
                    open = {
                        opens++
                        asset.byteInputStream()
                    },
                    canRender = { true },
                    io = UnconfinedTestDispatcher(testScheduler),
                )

            val first = loader.load()
            val second = loader.load()

            assertSame(first, second)
            assertEquals(1, opens)
            assertEquals(listOf("😀", "🦄", "👍🏻"), first.entries.map { it.emoji })
        }

    @Test
    fun dropsWhatTheDeviceCannotRenderFromBrowseAndSearchAlike() =
        runTest {
            val loader =
                EmojiCatalogLoader(
                    open = { asset.byteInputStream() },
                    canRender = { it != "🦄" },
                    io = UnconfinedTestDispatcher(testScheduler),
                )

            val catalog = loader.load()

            assertEquals(listOf("😀"), catalog.browse.map { it.emoji })
            assertTrue(catalog.search("unicorn").isEmpty())
            assertEquals("👍🏻", catalog.search("light skin").single().emoji)
        }

    @Test
    fun anUnreadableAssetDegradesToEmptyAndIsRetriedNextTime() =
        runTest {
            var fail = true
            val loader =
                EmojiCatalogLoader(
                    open = { if (fail) throw IOException("no asset") else asset.byteInputStream() },
                    canRender = { true },
                    io = UnconfinedTestDispatcher(testScheduler),
                )

            assertSame(EmojiCatalog.EMPTY, loader.load())
            fail = false
            assertEquals(3, loader.load().entries.size)
        }

    @Test
    fun aStreamThatFailsMidReadAlsoDegrades() =
        runTest {
            val broken =
                object : InputStream() {
                    override fun read(): Int = throw IOException("torn")
                }
            val loader = EmojiCatalogLoader(open = { broken }, canRender = { true }, io = UnconfinedTestDispatcher(testScheduler))

            assertSame(EmojiCatalog.EMPTY, loader.load())
        }
}
