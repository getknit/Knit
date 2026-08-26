package app.getknit.knit.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The animation-scale rule behind [LocalReduceMotion]. Kept a pure function precisely so it can be asserted
 * on the JVM, with no device and no Robolectric.
 */
class MotionTest {
    @Test
    fun `a zero animator scale means reduce motion`() {
        assertTrue(reduceMotionFor(0f))
    }

    @Test
    fun `the platform default does not reduce motion`() {
        assertFalse(reduceMotionFor(1f))
    }

    @Test
    fun `a merely faster scale is a pace preference, not a request for stillness`() {
        assertFalse(reduceMotionFor(0.5f))
        assertFalse(reduceMotionFor(10f))
    }
}
