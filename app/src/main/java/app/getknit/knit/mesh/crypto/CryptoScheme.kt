package app.getknit.knit.mesh.crypto

import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.Protocol

/**
 * Which DM crypto scheme a sealed frame toward a peer should use — the one send-time decision every DM-form
 * seal site makes, so it is made in one place (ADR 059). Both bits ride the same signed profile frame as
 * the prekey, so there is no window in which a peer claims v3 without being able to open it; a peer whose
 * pinned profile predates the bit simply keeps receiving v2. Read from the **pinned** profile
 * ([PeerEntity.capabilities]), never from a transport's advert copy.
 */
object CryptoScheme {
    /** [EncEnvelope.VERSION_DM_V3] when [capabilities] carries both ratchet bits, else [EncEnvelope.VERSION_RATCHET]. */
    fun forCapabilities(capabilities: Long?): Int {
        val caps = capabilities ?: 0L
        val v3 = caps and Protocol.CAP_RATCHET != 0L && caps and Protocol.CAP_CRYPTO_V3 != 0L
        return if (v3) EncEnvelope.VERSION_DM_V3 else EncEnvelope.VERSION_RATCHET
    }
}

/** Whether this pinned peer opens crypto scheme v3 (see [CryptoScheme]); false for an unpinned peer. */
fun PeerEntity?.readsCryptoV3(): Boolean = CryptoScheme.forCapabilities(this?.capabilities) == EncEnvelope.VERSION_DM_V3
