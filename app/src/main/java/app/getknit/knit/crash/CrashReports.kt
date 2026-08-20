package app.getknit.knit.crash

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Cache subdirectory the share sheet reads from; matches `<cache-path name="crash" …>` in `res/xml/file_paths.xml`. */
private const val SHARE_DIR = "crash"

private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)

/**
 * Everything the UI is allowed to do with a stored crash report.
 *
 * This is where redaction's **phase 2** happens. The uncaught-exception handler could only apply
 * structural rules — the contact names live in the SQLCipher database and DataStore, and a dying
 * process can reach neither — so the name pass runs here, on the way to the screen, the clipboard, or
 * the share sheet. There is no method that returns an unredacted report.
 */
class CrashReports(
    private val context: Context,
    private val store: CrashStore,
    private val identity: Identity,
    private val peers: PeerRepository,
    private val settings: SettingsStore,
) {
    /** Stored reports, newest first. */
    suspend fun list(): List<CrashReportRef> = withContext(Dispatchers.IO) { store.list() }

    /** The newest stored report, or `null` if this device has never captured one. */
    suspend fun latest(): CrashReportRef? = withContext(Dispatchers.IO) { store.latest() }

    /** The full report, name pass applied, with a footer recording which passes actually ran. */
    suspend fun read(ref: CrashReportRef): String? = withContext(Dispatchers.IO) { render(ref) }

    /**
     * Stages the rendered report under `cacheDir/crash/` and returns a `content://` [Uri] for it.
     *
     * The store itself lives in `noBackupFilesDir`, which `FileProvider` cannot expose — so a share is
     * always an explicit copy of the one report the user picked, never a window onto the whole store.
     * The directory is emptied first so a stale copy, redacted against an older name list, can never be
     * re-shared. The staged file is deliberately not deleted afterwards: the receiving app reads the Uri
     * asynchronously and a delete would race it. `cacheDir` eviction cleans up.
     */
    suspend fun exportForShare(ref: CrashReportRef): Uri? =
        withContext(Dispatchers.IO) {
            val text = render(ref) ?: return@withContext null
            runCatching {
                val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val dest = File(dir, shareFileName(ref.appVersion, ref.at))
                dest.writeText(text)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
            }.getOrNull()
        }

    /** Removes every stored report from this device. */
    suspend fun clear() = withContext(Dispatchers.IO) { store.clear() }

    private suspend fun render(ref: CrashReportRef): String? {
        val secrets = knownSecrets()
        val text = store.readRedacted(ref, secrets ?: KnownSecrets.NONE) ?: return null
        return text.trimEnd() + "\n" + footer(namesApplied = secrets != null)
    }

    /**
     * Every name this device could recognise: stored peer names, the generated alias each peer is shown
     * under when it has no stored name (`displayNameFor` falls back to it, so on screen the alias *is*
     * the contact name), and our own.
     *
     * Returns `null` — not an empty set — when the database or DataStore cannot be read, so [footer] can
     * say the name pass did not run rather than silently claiming a guarantee it did not deliver.
     */
    private suspend fun knownSecrets(): KnownSecrets? =
        runCatching {
            val known = mutableSetOf<String>()
            peers.observePeers().first().forEach { peer ->
                known += peer.name
                known += Alias.aliasFor(peer.nodeId)
            }
            known += settings.displayName.first()
            known += Alias.aliasFor(identity.nodeId())
            KnownSecrets(known.filter { it.isNotBlank() }.toSet())
        }.getOrNull()

    private fun footer(namesApplied: Boolean): String =
        if (namesApplied) {
            "redaction: names known to this device were removed when this report was read.\n"
        } else {
            "redaction: structural only - the contact-name list could not be read for this report.\n"
        }
}

/**
 * The attachment name the share sheet shows, e.g. `knit-crash-2.3.0-20260820-1432.txt` — self-labelling
 * in an issue thread. Pure (the zone is a parameter) so it is JVM-testable; `Locale.ROOT` keeps the
 * digits Latin whatever the device locale is, and a version string carrying a path separator or spaces
 * is sanitised away.
 */
internal fun shareFileName(
    appVersion: String,
    at: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val stamp = Instant.ofEpochMilli(at).atZone(zone).format(FILE_STAMP)
    val version = appVersion.substringBefore(' ').replace(UNSAFE_NAME_CHARS, "-").ifBlank { "unknown" }
    return "knit-crash-$version-$stamp.txt"
}
