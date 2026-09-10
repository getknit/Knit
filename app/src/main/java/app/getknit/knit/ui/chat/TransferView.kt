package app.getknit.knit.ui.chat

import app.getknit.knit.R
import app.getknit.knit.data.FileTypes
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.transfer.TransferRefusal
import app.getknit.knit.transfer.TransferState

/**
 * A direct-transfer row as the card draws it: the persisted [TransferRecord]'s facts overlaid with the
 * manager's live state when there is one. [interrupted] is the one thing neither holds on its own — a record
 * that never reached a terminal phase and has no live state behind it, which is what a process death
 * mid-transfer leaves.
 */
data class TransferView(
    val id: String,
    val outgoing: Boolean,
    val name: String,
    val size: Long,
    val mime: String?,
    val phase: TransferPhase,
    val bytes: Long,
    val savedUri: String?,
    val reason: Int?,
    val interrupted: Boolean,
) {
    /** An archive or executable: nothing on the device can look inside it, and the card says so before Accept. */
    val risky: Boolean get() = FileTypes.isRisky(mime, name)

    /** An app package: never offered an "Open", so Knit is never the install source (the roadmap's rule). */
    val installable: Boolean get() = FileTypes.isInstallable(mime, name)
}

/** The card for a [MessageEntity.KIND_FILE_TRANSFER] row, or null when its body is not a record this build reads. */
fun transferViewFor(
    row: MessageEntity,
    live: Map<String, TransferState>,
): TransferView? {
    val record = TransferRecord.decode(row.body) ?: return null
    val state = live[record.id]
    return TransferView(
        id = record.id,
        outgoing = record.outgoing,
        name = record.name,
        size = record.size,
        mime = record.mime,
        phase = state?.phase ?: record.phase,
        bytes = state?.bytes ?: 0L,
        savedUri = state?.savedUri ?: record.savedUri,
        reason = state?.reason ?: record.reason,
        interrupted = state == null && !record.phase.terminal,
    )
}

/** The sentence for a refusal, as a string resource id. */
fun transferRefusalMessage(refusal: TransferRefusal): Int =
    when (refusal) {
        TransferRefusal.NoWifiDirect -> R.string.chat_transfer_no_wifi_direct
        TransferRefusal.WifiOff -> R.string.chat_transfer_wifi_off
        TransferRefusal.Permission -> R.string.chat_transfer_permission
        TransferRefusal.Busy -> R.string.chat_transfer_busy
        TransferRefusal.Unreadable -> R.string.chat_transfer_unreadable
        TransferRefusal.TooLarge -> R.string.chat_transfer_too_large
        TransferRefusal.NotNearby -> R.string.chat_transfer_peer_not_nearby
        TransferRefusal.NoSession -> R.string.chat_transfer_no_session
        TransferRefusal.NoSpace -> R.string.chat_transfer_no_space
        TransferRefusal.Hotspot -> R.string.chat_transfer_hotspot
        TransferRefusal.Background -> R.string.chat_transfer_background
        TransferRefusal.Gone -> R.string.chat_transfer_gone
    }
