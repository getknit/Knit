package app.getknit.knit.ui.chat

import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.message.MessageEntity

/**
 * What an attachment is, for every surface that has to name one in a line of text.
 *
 * The order the cases are tested in is the whole content of this type. A **file** is identified by its own
 * [MessageEntity.attachmentName] rather than by its MIME, because the name is the more specific fact and the
 * only one worth showing; a **voice note** by its MIME; and everything else is a **photo**, which is what
 * every attachment was before voice notes and files existed and what every Nearby-room attachment still is.
 * The last arm also catches a non-image with no name — a shape no shipped build originates, but one a future
 * or hostile peer could — so it is checked explicitly rather than left to fall through as a photo.
 */
enum class AttachmentKind { Photo, Voice, File }

/** The [AttachmentKind] of an attachment with this [mime] and [name]. */
fun attachmentKindOf(
    mime: String?,
    name: String?,
): AttachmentKind =
    when {
        name != null -> AttachmentKind.File
        VoiceAudio.isVoice(mime) -> AttachmentKind.Voice
        mime == null || mime.startsWith("image/") -> AttachmentKind.Photo
        else -> AttachmentKind.File
    }
