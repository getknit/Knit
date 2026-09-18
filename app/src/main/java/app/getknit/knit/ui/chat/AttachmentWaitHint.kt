package app.getknit.knit.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.getknit.knit.R
import app.getknit.knit.data.relay.AttachmentWait

/**
 * The second line under a missing attachment's spinner, or null when there is nothing to add. One
 * function for the photo, voice and file bubbles so the three never drift: the first line is each
 * kind's own ("Photo appears once…"), this one is the Internet plane's half of the answer
 * ([AttachmentWait], work item 50).
 */
@Composable
internal fun attachmentWaitHint(wait: AttachmentWait): String? =
    when (wait) {
        AttachmentWait.Nearby -> null
        AttachmentWait.Relay -> stringResource(R.string.chat_attachment_wait_relay)
        AttachmentWait.RelayFramesOnly -> stringResource(R.string.chat_attachment_wait_frames_only)
    }
