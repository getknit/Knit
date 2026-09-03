package app.getknit.knit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What bytes actually are, as opposed to what a provider says. The load-bearing case is
 * [FileTypes.imageMimeOf] recognising an image whose MIME says otherwise — screening skips by MIME, so that
 * recognition is the whole reason a renamed JPEG cannot walk past the NSFW classifier (ADR 2026-09.qq2r).
 */
class FileTypesTest {
    private fun withHeader(
        header: ByteArray,
        size: Int = 64,
    ): ByteArray = ByteArray(size).also { header.copyInto(it) }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun everyBundledImageSignatureIsRecognised() {
        assertEquals("image/jpeg", FileTypes.imageMimeOf(withHeader(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))))
        assertEquals(
            "image/png",
            FileTypes.imageMimeOf(withHeader(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))),
        )
        assertEquals("image/gif", FileTypes.imageMimeOf(withHeader(ascii("GIF89a"))))
        assertEquals("image/gif", FileTypes.imageMimeOf(withHeader(ascii("GIF87a"))))
        assertEquals("image/bmp", FileTypes.imageMimeOf(withHeader(ascii("BM"), size = 8)))
    }

    @Test
    fun aWebpNeedsBothItsRiffHeaderAndItsBrand() {
        assertEquals("image/webp", FileTypes.imageMimeOf(withHeader(ascii("RIFF") + ascii("size") + ascii("WEBP"))))
        // RIFF alone is a container: a WAV is not an image and must not be routed as one.
        assertNull(FileTypes.imageMimeOf(withHeader(ascii("RIFF") + ascii("size") + ascii("WAVE"))))
    }

    @Test
    fun anIsoBaseMediaFileIsTypedByItsBrand() {
        assertEquals("image/heif", FileTypes.imageMimeOf(withHeader(ByteArray(4) + ascii("ftyp") + ascii("heic"))))
        assertEquals("image/avif", FileTypes.imageMimeOf(withHeader(ByteArray(4) + ascii("ftyp") + ascii("avif"))))
        // An MP4 shares the ftyp box and is emphatically not an image.
        assertNull(FileTypes.imageMimeOf(withHeader(ByteArray(4) + ascii("ftyp") + ascii("isom"))))
    }

    @Test
    fun ordinaryFilesAreNotImages() {
        assertNull(FileTypes.imageMimeOf(withHeader(ascii("%PDF-1.7"))))
        assertNull(FileTypes.imageMimeOf(ByteArray(0)))
        assertNull(FileTypes.imageMimeOf(byteArrayOf(1, 2)))
    }

    @Test
    fun anInstallableIsRecognisedByEitherItsMimeOrItsName() {
        assertTrue(FileTypes.isInstallable("application/vnd.android.package-archive", "anything"))
        assertTrue(FileTypes.isInstallable("application/octet-stream", "Signal.apk"))
        assertTrue(FileTypes.isInstallable(null, "bundle.XAPK"))
        assertFalse(FileTypes.isInstallable("application/pdf", "report.pdf"))
    }

    @Test
    fun archivesAndExecutablesAreRiskyAndDocumentsAreNot() {
        assertTrue(FileTypes.isRisky(null, "backup.zip"))
        assertTrue(FileTypes.isRisky(null, "setup.exe"))
        assertTrue(FileTypes.isRisky("application/vnd.android.package-archive", "app.apk"))
        assertFalse(FileTypes.isRisky("application/pdf", "report.pdf"))
        assertFalse(FileTypes.isRisky("text/plain", "notes.txt"))
    }

    @Test
    fun theExtensionIsLowercasedAndAbsentWhereThereIsNone() {
        assertEquals("pdf", FileTypes.extensionOf("Report.PDF"))
        assertEquals("gz", FileTypes.extensionOf("archive.tar.gz"))
        assertEquals("", FileTypes.extensionOf("README"))
        assertEquals("", FileTypes.extensionOf(".gitignore"))
        assertEquals("", FileTypes.extensionOf(null))
    }
}
