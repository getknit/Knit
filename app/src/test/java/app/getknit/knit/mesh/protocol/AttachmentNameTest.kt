package app.getknit.knit.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The filename rules for an arbitrary-file attachment (ADR 2026-09.qq2r). Every case here is a name a peer
 * could put on the wire, so the contract is that none of them throws, none of them survives as a path, and
 * anything with something left in it comes back usable rather than being dropped.
 */
class AttachmentNameTest {
    @Test
    fun anOrdinaryNameIsUntouched() {
        assertEquals("quarterly-report.pdf", AttachmentName.sanitize("quarterly-report.pdf"))
    }

    @Test
    fun pathSeparatorsAreStrippedSoANameCanNeverReadAsAPath() {
        assertEquals("etcpasswd", AttachmentName.sanitize("/etc/passwd"))
        assertEquals("WindowsSystem32evil.dll", AttachmentName.sanitize("\\Windows\\System32\\evil.dll"))
    }

    @Test
    fun traversalThatLeavesNothingUsableIsRejected() {
        assertNull(AttachmentName.sanitize(".."))
        assertNull(AttachmentName.sanitize("/"))
        assertNull(AttachmentName.sanitize("   "))
        assertNull(AttachmentName.sanitize(""))
        assertNull(AttachmentName.sanitize(null))
    }

    /** A newline can blank the line the name is drawn on. An ordinary space is just part of the name. */
    @Test
    fun controlCharactersAreStrippedAndSpacesAreNot() {
        assertEquals("re port.pdf", AttachmentName.sanitize("re port\n.pdf"))
        assertEquals("Q3 board deck.pdf", AttachmentName.sanitize("  Q3 board deck.pdf  "))
    }

    /**
     * The classic filename spoof: a right-to-left override before the extension makes a name ending
     * `\u202Efdp.exe` render as `exe.pdf` to whoever is deciding whether to save it. Written as an escape
     * rather than a literal so the character cannot flip this test's own source.
     */
    @Test
    fun aBidiOverrideIsStripped() {
        assertEquals("evilfdp.exe", AttachmentName.sanitize("evil\u202Efdp.exe"))
        assertEquals("report.pdf", AttachmentName.sanitize("\uFEFFreport.pdf"))
    }

    @Test
    fun aLongNameIsTruncatedThroughTheStemAndKeepsItsExtension() {
        val long = "a".repeat(400) + ".pdf"
        val out = checkNotNull(AttachmentName.sanitize(long))
        assertEquals(AttachmentName.MAX_LENGTH, out.length)
        assertEquals(true, out.endsWith(".pdf"))
    }

    /** A long tail after the last dot is not an extension, so preserving it would drop the whole name. */
    @Test
    fun aLongTailIsNotTreatedAsAnExtension() {
        val long = "report." + "b".repeat(400)
        val out = checkNotNull(AttachmentName.sanitize(long))
        assertEquals(AttachmentName.MAX_LENGTH, out.length)
        assertEquals(true, out.startsWith("report."))
    }

    @Test
    fun aDotfileKeepsItsLeadingDot() {
        assertEquals(".gitignore", AttachmentName.sanitize(".gitignore"))
    }
}
