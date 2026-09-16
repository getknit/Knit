package app.getknit.knit.legal

import android.content.res.AssetManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Reads [license]'s bundled text off the main thread; null when the asset is missing or unreadable, which
 * the screen shows as a plain "couldn't load" line rather than a crash. No cache — the largest text is
 * 35 KB and a license screen opens rarely.
 */
suspend fun readLicenseText(
    assets: AssetManager,
    license: License,
    io: CoroutineDispatcher = Dispatchers.IO,
): String? =
    withContext(io) {
        try {
            assets.open(license.asset).bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            null
        }
    }

/**
 * Reflows a license text for a phone screen: paragraphs are what blank lines separate, and the hard wraps
 * inside one (the GPL and Apache texts are wrapped at ~70 columns with leading indents) become single
 * spaces, so the proportional font wraps them itself instead of leaving ragged right edges. The asset is
 * never altered — only its rendering.
 */
fun licenseParagraphs(text: String): List<String> =
    text
        .replace("\r\n", "\n")
        .split(PARAGRAPH_BREAK)
        .map { paragraph ->
            paragraph
                .lines()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString(" ")
        }.filter(String::isNotEmpty)

private val PARAGRAPH_BREAK = Regex("\n[ \t]*\n")
