package app.getknit.knit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.contentcapture.ContentCaptureManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.core.content.IntentCompat
import app.getknit.knit.ui.KnitApp
import app.getknit.knit.ui.RouteInbox
import app.getknit.knit.ui.addcontact.ContactCardInbox
import app.getknit.knit.ui.addcontact.contactLinkFrom
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.share.SharedContent
import app.getknit.knit.ui.theme.KnitTheme
import org.koin.android.ext.android.inject
import androidx.compose.ui.contentcapture.ContentCaptureManager as ComposeContentCaptureManager

class MainActivity : ComponentActivity() {
    // Single-shot holder for content arriving via the system share sheet; KnitApp/ChatScreen drain it.
    private val shareInbox: ShareInbox by inject()

    // Single-shot holder for a notification-tap deep-link route (e.g. "chat/<id>"); KnitApp drains it.
    private val routeInbox: RouteInbox by inject()

    // Single-shot holder for a contact link (a tapped getknit.app/c link, or a shared text carrying one).
    private val contactCardInbox: ContactCardInbox by inject()

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Opt the whole app out of content capture (the on-device "app content" feed to Android System
        // Intelligence): an offline, end-to-end-encrypted messenger has no business streaming its screen text
        // to another process. Two switches, because the platform one only silences the events: Compose keeps
        // its own manager and still re-walks every on-screen semantics node whenever the tree changes (measured:
        // ~2 ms per frame across a 120-cell emoji grid fling, ~260 ms per fling), and only its flag stops that.
        getSystemService(ContentCaptureManager::class.java)?.isContentCaptureEnabled = false
        ComposeContentCaptureManager.isEnabled = false
        // A cold-start share: stage the payload before composition so KnitApp opens the picker.
        handleShareIntent(intent)
        // A cold-start notification tap: stage its deep-link route so KnitApp navigates to that thread.
        handleRouteIntent(intent)
        // A cold-start contact link: stage it so KnitApp opens the Add-contact screen.
        handleContactLinkIntent(intent)
        // Debug builds honor a deep-link route extra so screenshots (demo builds) and automation agents
        // (any debug build, over the real mesh) can jump straight to a screen, e.g.
        // `adb shell am start -n app.getknit.knit/.MainActivity --es demo_route chat/nearby`. Gated to
        // debug so release never reads it. (Demo builds still swap in DemoTransport via SEED_DEMO.)
        val startRoute =
            if (BuildConfig.SEED_DEMO || BuildConfig.DEBUG) {
                intent?.getStringExtra(EXTRA_DEMO_ROUTE)
            } else {
                null
            }
        setContent {
            KnitTheme {
                KnitApp(startRoute = startRoute)
            }
        }
    }

    // Share into an already-running instance (launchMode=singleTask). Re-stage into the inbox; KnitApp
    // observes it and routes to the share-target picker.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        // A notification tap on an already-running instance: stage the deep-link route; KnitApp navigates.
        handleRouteIntent(intent)
        handleContactLinkIntent(intent)
    }

    /** Stage a contact link (a VIEW of a card link, or a SEND whose text carries one) into the [ContactCardInbox]. */
    private fun handleContactLinkIntent(intent: Intent?) {
        val link = contactLinkFrom(intent?.action, intent?.dataString, intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        if (link != null) contactCardInbox.offer(link)
    }

    /** Stage a notification deep-link route ([EXTRA_ROUTE], e.g. "chat/<id>") into the [RouteInbox]. */
    private fun handleRouteIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ROUTE)?.let { routeInbox.offer(it) }
    }

    /** Parse an ACTION_SEND intent into the [ShareInbox]. Other intents (incl. the launcher) are ignored. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        // A shared text that IS a contact link is an import, not a message draft — the Android-idiomatic
        // route for a link on 12+, where an unverified https link opens in the browser rather than here.
        if (contactLinkFrom(intent.action, null, text) != null) return
        // EXTRA_STREAM is only meaningful (and read-granted) for the image/* filter we declare.
        val imageUri =
            if (intent.type?.startsWith("image/") == true) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString()
            } else {
                null
            }
        shareInbox.offer(SharedContent(text = text, imageUri = imageUri))
    }

    companion object {
        /** Deep-link route extra set by [app.getknit.knit.notifications.MessageNotifier] on a notification tap. */
        const val EXTRA_ROUTE = "app.getknit.knit.NOTIF_ROUTE"
        private const val EXTRA_DEMO_ROUTE = "demo_route"
    }
}
