package app.getknit.knit.legal

import java.io.File

/**
 * Locates a file by its path relative to the repository root, whether the test runs from `app/` (Gradle) or
 * the root (an IDE) — the `EmojiCatalogAssetTest` idiom. A general seam for the tests that assert over
 * committed text or walk a source tree on disk rather than over the SUT.
 */
internal fun repoFile(relativeToRoot: String): File =
    listOf("../$relativeToRoot", relativeToRoot)
        .map(::File)
        .firstOrNull { it.exists() }
        ?: error("$relativeToRoot not found (cwd=${File(".").absolutePath})")
