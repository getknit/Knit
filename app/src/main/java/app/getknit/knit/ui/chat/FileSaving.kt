package app.getknit.knit.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import app.getknit.knit.mesh.transferExtForMime

/** Everything a save needs about the attachment the user tapped, carried from the bubble to the picker. */
data class PendingSave(
    val hash: String,
    val key: String?,
    val name: String?,
    val mime: String?,
)

/** The MIME filter that means "any file", for both the document picker and a save with no better type. */
const val ANY_MIME = "*/*"

/**
 * `ACTION_CREATE_DOCUMENT` with the type **and** the suggested filename chosen per call.
 *
 * `ActivityResultContracts.CreateDocument` fixes its MIME type at construction and takes only a name as its
 * input, which is the wrong shape here: every received file has its own type, and a launcher remembered once
 * per screen would have to pick one for all of them. So this is its two-argument sibling and nothing more.
 *
 * The name is the sender's, already normalized at the decode boundary
 * ([app.getknit.knit.mesh.protocol.AttachmentName]) — it cannot be a path, and the picker treats it as a
 * suggestion the user is free to change either way.
 */
class CreateNamedDocument : ActivityResultContract<PendingSave, Uri?>() {
    override fun createIntent(
        context: Context,
        input: PendingSave,
    ): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime ?: ANY_MIME)
            .putExtra(Intent.EXTRA_TITLE, input.name ?: fallbackName(input))

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = intent.takeIf { resultCode == Activity.RESULT_OK }?.data

    /**
     * A name for a file whose sender gave it none — an attachment from a build that predates the sealed
     * name, or one whose name did not survive normalization. Content-addressed, so it is at least stable and
     * unique, with an extension derived from the type rather than guessed.
     */
    private fun fallbackName(input: PendingSave): String =
        "knit-${input.hash.take(HASH_PREFIX)}.${transferExtForMime(input.mime ?: ANY_MIME)}"

    private companion object {
        const val HASH_PREFIX = 8
    }
}
