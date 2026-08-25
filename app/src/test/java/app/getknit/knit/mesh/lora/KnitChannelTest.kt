package app.getknit.knit.mesh.lora

import com.google.crypto.tink.subtle.Hkdf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [KnitChannel.PSK] to its documented HKDF-SHA256 derivation. This is the interop + reproducibility
 * guard: the pinned bytes are what boards get provisioned with, so a change to either the HKDF inputs or the
 * pinned bytes must be a deliberate, test-breaking act (it would strand every already-provisioned board on a
 * different channel hash).
 */
class KnitChannelTest {
    @Test
    fun pskIsTheDocumentedHkdfDerivation() {
        val derived =
            Hkdf.computeHkdf(
                "HMACSHA256",
                KnitChannel.IKM.toByteArray(),
                ByteArray(SALT_BYTES), // zero salt, matching the codebase's ZERO_SALT convention
                KnitChannel.INFO.toByteArray(),
                KnitChannel.PSK_BYTES,
            )
        assertEquals(KnitChannel.PSK_BYTES, derived.size)
        assertEquals(
            "KnitChannel.PSK must equal the HKDF derivation",
            derived.toHex(),
            KnitChannel.PSK.toHex(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val SALT_BYTES = 32
    }
}
