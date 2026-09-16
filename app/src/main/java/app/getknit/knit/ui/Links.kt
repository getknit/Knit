package app.getknit.knit.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri
import app.getknit.knit.location.GeoPoint
import app.getknit.knit.location.GeoUri

/** Knit's Play Store listing — the "Share Knit" link, and the rate target when Play installed the app. */
const val PLAY_LISTING_URL = "https://play.google.com/store/apps/details?id=app.getknit.knit"

/** The public source repository — the rate target for non-Play installs (F-Droid / sideload: a GitHub star). */
const val REPO_URL = "https://github.com/getknit/knit"

/** The issue tracker — where the review prompt's "not really" branch sends private feedback. */
const val ISSUES_URL = "https://github.com/getknit/knit/issues"

/** The project site — the About screen's Website row. */
const val WEBSITE_URL = "https://getknit.app"

/** Opens [url] in the user's browser. Swallows ActivityNotFoundException if nothing can handle it. */
fun openUrl(
    context: Context,
    url: String,
) {
    val intent =
        Intent(Intent.ACTION_VIEW, url.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Opens the system share sheet with [text] (e.g. a link), titled [chooserTitle], so the user can send
 * it to any app. Swallows ActivityNotFoundException if nothing can handle it.
 */
fun shareText(
    context: Context,
    text: String,
    chooserTitle: String,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    val chooser =
        Intent
            .createChooser(send, chooserTitle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}

/**
 * Hands [point] to whatever maps app the user has, pinned under [label] — the `geo:` intent every maps app
 * answers, offline ones included. Returns false when nothing could take it, unlike [openUrl], because the
 * caller has a fallback worth offering (the coordinates on the clipboard) and silence would read as a dead
 * tap. No `<queries>` entry is needed: package visibility never filters an implicit `startActivity`.
 */
fun openLocation(
    context: Context,
    point: GeoPoint,
    label: String,
): Boolean {
    val query = GeoUri.mapsQuery(point)
    val intent =
        Intent(Intent.ACTION_VIEW, "geo:$query?q=$query(${Uri.encode(label)})".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

/** Opens the system's location toggle — the only way out of a staged tile that says location is off. */
fun openLocationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
