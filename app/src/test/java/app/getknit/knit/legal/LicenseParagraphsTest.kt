package app.getknit.knit.legal

import org.junit.Assert.assertEquals
import org.junit.Test

class LicenseParagraphsTest {
    @Test
    fun blankLinesSeparateParagraphsAndHardWrapsBecomeSpaces() {
        val text = "  First line,\n   wrapped here.\n\nSecond paragraph.\n\n\n\nThird\nparagraph\n"
        assertEquals(listOf("First line, wrapped here.", "Second paragraph.", "Third paragraph"), licenseParagraphs(text))
    }

    @Test
    fun whitespaceOnlyLinesCountAsBlank() {
        assertEquals(listOf("A", "B"), licenseParagraphs("A\n   \nB"))
    }

    @Test
    fun windowsLineEndingsAreTolerated() {
        assertEquals(listOf("A B", "C"), licenseParagraphs("A\r\nB\r\n\r\nC"))
    }

    @Test
    fun emptyInputIsEmpty() {
        assertEquals(emptyList<String>(), licenseParagraphs(""))
        assertEquals(emptyList<String>(), licenseParagraphs("\n\n  \n"))
    }
}
