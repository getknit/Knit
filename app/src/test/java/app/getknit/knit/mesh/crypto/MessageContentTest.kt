package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.Mention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plaintext content schema: CBOR round-trip and the delivery-time version gate (unknown → dropped). */
class MessageContentTest {
    @Test
    fun encodeThenDecodeRoundTripsEveryField() {
        val original =
            MessageContent(
                body = "hi",
                mentions = listOf(Mention("bob00000", "Bob")),
                attachmentHash = "hash",
                attachmentMime = "image/jpeg",
                attachmentKey = "key",
            )
        val decoded = MessageContent.decode(original.encode())!!
        assertEquals("hi", decoded.body)
        assertEquals(listOf(Mention("bob00000", "Bob")), decoded.mentions)
        assertEquals("hash", decoded.attachmentHash)
        assertEquals("image/jpeg", decoded.attachmentMime)
        assertEquals("key", decoded.attachmentKey)
    }

    @Test
    fun theCurrentVersionIsSupported() {
        assertTrue(MessageContent(body = "hi").isSupported()) // v defaults to VERSION
    }

    @Test
    fun aFutureContentSchemaVersionIsNotSupported() {
        assertFalse(MessageContent(v = MessageContent.MAX_SUPPORTED + 1, body = "hi").isSupported())
    }

    @Test
    fun decodingGarbageReturnsNullRatherThanThrowing() {
        assertNull(MessageContent.decode(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun theControlMarkerRoundTripsAndDefaultsToNull() {
        // Additive field under the same schema version: a v1-era decoder ignores it, an ordinary
        // message omits it (encodeDefaults = false), and a reset frame carries it inside the ciphertext.
        val reset = MessageContent.decode(MessageContent(body = "", ctl = MessageContent.CTL_SESSION_RESET).encode())!!
        assertEquals(MessageContent.CTL_SESSION_RESET, reset.ctl)
        assertTrue(reset.isSupported())
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.ctl)
    }
}
