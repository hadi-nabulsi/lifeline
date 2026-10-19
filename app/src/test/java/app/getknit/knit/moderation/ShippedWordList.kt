package app.getknit.knit.moderation

import java.io.File

/**
 * A shipped `assets/moderation/` word list read straight from disk and parsed with the same rules as
 * [WordList.load] (trim, drop blank lines and `#` comments) — so the contract tests run on the plain JVM,
 * no Robolectric host. Gradle runs unit tests with the module dir as the working dir.
 */
object ShippedWordList {
    const val PROFANITY = "profanity_en.txt"
    const val SLURS = "slurs_en.txt"

    fun read(name: String): List<String> {
        val file =
            listOf("src/main/assets/moderation/$name", "app/src/main/assets/moderation/$name")
                .map(::File)
                .firstOrNull { it.exists() }
                ?: error("$name not found (cwd=${File(".").absolutePath})")
        return file
            .readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
