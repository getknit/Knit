package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * Supplies this device's current signed cleartext `profile` frame, so a long-range plane
 * ([app.getknit.knit.mesh.lora.LoraMeshTransport]) can beacon it to bootstrap key exchange with a peer it
 * hears for the first time (over LoRa the far side has never seen our profile, and without the pinned key
 * it drops every frame we send). Implemented by [MeshManager] as `sign(currentProfileEnvelope())` — the
 * same self-certifying frame [MeshManager] floods on radio contact, with a stable id so re-hearing it is a
 * SeenSet no-op. Late-bound (the transport is constructed before [MeshManager]); resolved at call time.
 */
fun interface ProfileFrameSource {
    /** The signed profile frame, or null if the identity/profile isn't ready yet. */
    suspend fun signedProfile(): WireEnvelope?
}
