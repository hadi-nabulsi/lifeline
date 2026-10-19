package app.getknit.knit.crash

/**
 * One ANR the platform recorded against an earlier process of this app, as [CrashStore.importAnrs] takes it.
 *
 * @param at when the process died for it (epoch millis) — `ApplicationExitInfo.timestamp`
 * @param description the platform's one-line reason ("executing service app.getknit.knit/.mesh.MeshService");
 *   the fallback subject when the trace is gone or carries no `Subject:` line
 * @param predatesInstall the ANR happened before the build running now was installed, so the header must not
 *   name this build's version (and with it the wrong `mapping.txt`)
 * @param trace reads the system's trace dump; called only for an ANR not imported before, and may return null
 *   (the platform keeps traces for a few days only)
 */
class AnrExit(
    val at: Long,
    val description: String?,
    val predatesInstall: Boolean,
    val trace: () -> String?,
)

/**
 * Turns the trace dump `ApplicationExitInfo.getTraceInputStream()` returns for an ANR into a report body that
 * reads like a crash report: an `ApplicationNotResponding: <subject>` line in the exception's place (so
 * [CrashStore]'s summary, the issue form and `crash-report.yml`'s signature all work unchanged), then every
 * thread with **`main` first**, its top frame being what the summary and the signature key on.
 *
 * What is kept is an allow-list, not the dump minus a deny-list. The preamble before the first thread is the
 * ANR's subject, the process's memory lines and any line that says whether it was frozen — then ART's runtime
 * statistics, which are noise, and on Android 14+ a `CriticalEventLog` naming *other* apps' recent ANRs, which is
 * not ours to copy. The `Waiting Channels` section is kept: it names each thread's kernel wait, which is how a
 * process frozen by the system (or an OEM's freezer) shows itself when every Java stack looks idle — the one
 * question a `nativePollOnce` ANR leaves open.
 *
 * Native frames are kept as frames (see [CrashRedactor]), with the per-install APK directory collapsed to
 * `[app]` and any other on-device path to `[path]`, so a frame carries nothing but code locations. Pure Kotlin;
 * ADR 2026-10.fzsy.
 */
object AnrTrace {
    /** The class-like word the body opens with, standing where a crash report's exception class stands. */
    const val EXCEPTION = "ApplicationNotResponding"

    fun condense(
        raw: String?,
        description: String?,
    ): String {
        val lines =
            raw
                ?.lineSequence()
                ?.map { it.trimEnd() }
                ?.toList()
                .orEmpty()
        val subject =
            lines
                .firstOrNull { it.startsWith(SUBJECT) }
                ?.removePrefix(SUBJECT)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: description?.trim()?.takeIf { it.isNotEmpty() }
                ?: "reason not recorded"
        return buildString {
            appendLine("$EXCEPTION: $subject")
            if (lines.isEmpty()) {
                appendLine("(the system kept no thread dump for this ANR)")
                return@buildString
            }
            val (preamble, threads, channels) = split(lines)
            preamble.forEach { appendLine(it) }
            val (main, others) = threads.partition { it.first().startsWith(MAIN_THREAD) }
            (main + others).forEach { block ->
                appendLine()
                block.forEach { appendLine(clean(it)) }
            }
            if (channels.isNotEmpty()) {
                appendLine()
                channels.forEach { appendLine(it) }
            }
        }.trimEnd() + "\n"
    }

    /**
     * Splits the dump into the kept preamble lines and the thread blocks of the first process section (a thread
     * block opens with its quoted name and runs to the next blank line), and the first `Waiting Channels`
     * section. The process section ends at the first section boundary: a process whose Java threads could not be
     * dumped — one held frozen, device-observed — has no section of its own and goes straight to its waiting
     * channels. A second process section, which an app's own dump should not carry, is dropped.
     */
    private fun split(lines: List<String>): Triple<List<String>, List<List<String>>, List<String>> {
        val processEnd =
            lines.indexOfFirst { it.startsWith(END) || it.startsWith(CHANNELS_START) }.let { if (it < 0) lines.size else it }
        val (preamble, threads) = processSection(lines.subList(0, processEnd))
        return Triple(preamble, threads, waitingChannels(lines))
    }

    private fun processSection(lines: List<String>): Pair<List<String>, List<List<String>>> {
        val preamble = mutableListOf<String>()
        val threads = mutableListOf<MutableList<String>>()
        var inThread = false
        for (line in lines) {
            when {
                line.startsWith("\"") -> {
                    threads.add(mutableListOf(line))
                    inThread = true
                }

                inThread && line.isBlank() -> {
                    inThread = false
                }

                inThread -> {
                    threads.last().add(line)
                }

                line.startsWith(PROCESS_START) || keptPreamble(line) -> {
                    preamble.add(line)
                }
            }
        }
        return preamble to threads
    }

    /** The first `Waiting Channels` section, its own `Cmd line` and blank lines dropped, through its end line. */
    private fun waitingChannels(lines: List<String>): List<String> {
        val start = lines.indexOfFirst { it.startsWith(CHANNELS_START) }
        if (start < 0) return emptyList()
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith(END) }
        val body = if (end < 0) rest else rest.take(end + 1)
        return listOf(lines[start]) + body.filter { it.isNotBlank() && !it.startsWith(CMD_LINE) }
    }

    private fun keptPreamble(line: String): Boolean =
        KEPT_PREFIXES.any { line.startsWith(it) } || MEMORY.matches(line) || line.contains("frozen", ignoreCase = true)

    /** A native frame keeps its code location and loses the install directory and any other on-device path. */
    private fun clean(line: String): String {
        if (!line.trimStart().startsWith(NATIVE_FRAME)) return line
        return OTHER_PATH.replace(APP_DIR.replace(line, "[app]/"), "[path]")
    }

    private const val SUBJECT = "Subject:"
    private const val PROCESS_START = "----- pid "
    private const val CHANNELS_START = "----- Waiting Channels: pid "
    private const val END = "----- end "
    private const val CMD_LINE = "Cmd line:"
    private const val MAIN_THREAD = "\"main\" "
    private const val NATIVE_FRAME = "native: #"

    /** `libdebuggerd_client:` is the line that says the Java threads could not be dumped (and why). */
    private val KEPT_PREFIXES = listOf(CMD_LINE, "ABI:", "Build type:", "DALVIK THREADS", "----- dumping pid", "libdebuggerd_client:")

    /** `RssHwmKb: 123456`, `VmSwapKb: 0` — what the process held when it stopped answering. */
    private val MEMORY = Regex("""^(?:Rss|VmSwap)\w*Kb:\s*\d+$""")

    /** `/data/app/~~<random>==/app.getknit.knit-<random>==/` (API 30+) or `/data/app/app.getknit.knit-<random>==/`. */
    private val APP_DIR = Regex("""/data/app/(?:~~[^/\s]+/)?[^/\s]+/""")

    private val OTHER_PATH = Regex("""(?<![\w/\]])/(?:data|storage|sdcard|mnt)/\S*""")
}
