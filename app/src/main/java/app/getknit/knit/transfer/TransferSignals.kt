package app.getknit.knit.transfer

import app.getknit.knit.mesh.protocol.TransferPayload

/** The mesh seam of a direct transfer: one sealed `CTL_TRANSFER` DM to [peerId]. False when nothing could be sealed. */
fun interface TransferSignals {
    suspend fun sendTransferSignal(
        peerId: String,
        payload: TransferPayload,
    ): Boolean
}
