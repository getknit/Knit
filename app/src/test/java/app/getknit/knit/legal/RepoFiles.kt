package app.getknit.knit.legal

import java.io.File

/**
 * Locates a file by its path relative to the repository root, whether the test runs from `app/` (Gradle) or
 * the root (an IDE) — the `EmojiCatalogAssetTest` idiom. The legal tests read the notices file, the lockfile
 * and the license assets straight from disk: they are contract tests over committed text, not unit tests.
 */
internal fun repoFile(relativeToRoot: String): File =
    listOf("../$relativeToRoot", relativeToRoot)
        .map(::File)
        .firstOrNull { it.exists() }
        ?: error("$relativeToRoot not found (cwd=${File(".").absolutePath})")
