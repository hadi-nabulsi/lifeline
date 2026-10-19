package app.getknit.knit.crash

import app.getknit.knit.data.crypto.writeBytesAtomically
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** One stored crash report, described by its header alone — the trace is read on demand. */
data class CrashReportRef(
    /** When the crash was captured (epoch millis), taken from the file name. */
    val at: Long,
    /** A one-line "`IllegalStateException` at `Foo.kt:552`" label for a list row. */
    val summary: String,
    /** The `app:` header line — the version that **crashed**, which may predate the one running now. */
    val appVersion: String,
    /** The `device:` header line. */
    val device: String,
    /** The `android:` header line. */
    val androidVersion: String,
    val file: File,
)

/**
 * Captured crash reports on disk: at most [MAX_FILES], each capped at [MAX_REPORT_CHARS], written
 * already-redacted.
 *
 * **The directory is `noBackupFilesDir/crashes/` and moving it to `filesDir` would be a privacy
 * regression, not a simplification.** Backup is allow-by-default across three sections of
 * `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`; under `filesDir` a report is
 * cloud-backed-up and device-transferred unless all three exclude entries exist and stay correct.
 * `noBackupFilesDir` is excluded by construction. See [crashStore].
 *
 * Takes a plain [File] and an injectable [now] clock and touches no Android type, so the capture path
 * is exercised on the JVM with a temp directory rather than under Robolectric.
 */
class CrashStore(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Renders [throwable] with [environment]'s header, redacts it, and writes it atomically. Returns
     * the file, or `null` if anything made that impossible.
     *
     * **Never throws.** This runs on a dying thread with the platform's handler waiting behind it, so
     * there is nobody to catch an exception and no UI to show it — unlike `ApkMerger`, which throws
     * because a user is watching a share. Declining silently and letting the chain run is the whole
     * contract.
     */
    fun record(
        environment: CrashEnvironment,
        threadName: String,
        throwable: Throwable,
    ): File? {
        val stamp = now()
        return write(stamp) {
            val raw = throwable.stackTraceToString()
            val body = runCatching { CrashRedactor.redact(raw) }.getOrElse { CrashRedactor.framesOnly(raw) }
            cap(header(environment, threadName, stamp, Kind.CRASH) + body, MAX_REPORT_CHARS)
        }
    }

    /**
     * Writes a report for each ANR in [exits] (newest first, as the platform lists them) that no earlier call
     * imported, oldest first so [prune] keeps the newest, and returns how many were written. At most [MAX_FILES]
     * are read — older ones would be pruned on arrival. A watermark file beside the reports records the newest
     * ANR seen, so a restart, a Delete all or a pruned report never brings one back; it is advanced even when a
     * write fails (a full disk is not worth re-reading traces for on every start).
     *
     * Never throws, for the same reason [record] does not: it runs at every process start.
     */
    fun importAnrs(
        environment: CrashEnvironment,
        exits: List<AnrExit>,
    ): Int =
        runCatching {
            val seen = anrWatermark()
            val fresh =
                exits
                    .filter { it.at > seen }
                    .sortedByDescending { it.at }
                    .take(MAX_FILES)
                    .sortedBy { it.at }
            if (fresh.isEmpty()) return 0
            val written = fresh.count { recordAnr(environment, it) != null }
            if (dir.isDirectory || dir.mkdirs()) {
                File(dir, ANR_WATERMARK).writeBytesAtomically(
                    fresh
                        .last()
                        .at
                        .toString()
                        .encodeToByteArray(),
                )
            }
            written
        }.getOrDefault(0)

    private fun recordAnr(
        environment: CrashEnvironment,
        exit: AnrExit,
    ): File? =
        write(exit.at) {
            val condensed = AnrTrace.condense(runCatching { exit.trace() }.getOrNull(), exit.description)
            val body = runCatching { CrashRedactor.redact(condensed) }.getOrElse { CrashRedactor.framesOnly(condensed) }
            val kind = if (exit.predatesInstall) Kind.EARLIER_ANR else Kind.ANR
            cap(header(environment, "main", exit.at, kind) + body, MAX_ANR_CHARS)
        }

    /** The newest ANR an earlier [importAnrs] saw, or 0 if none ever was (or the mark cannot be read). */
    private fun anrWatermark(): Long = runCatching { File(dir, ANR_WATERMARK).readText().trim().toLong() }.getOrDefault(0L)

    /**
     * The one write path: checks the directory and the free space, renders, writes atomically, prunes. A
     * render that throws writes nothing — **no path here writes an unredacted stack trace to disk.**
     */
    private fun write(
        stamp: Long,
        render: () -> String,
    ): File? {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        if (dir.usableSpace < MIN_FREE_BYTES) return null
        val body = runCatching(render).getOrNull() ?: return null
        val target = nextFile(stamp, fingerprintOf(body))
        target.writeBytesAtomically(body.encodeToByteArray())
        prune(keep = target)
        return target
    }

    /** Stored reports, newest first. Reads each file's header only. */
    fun list(): List<CrashReportRef> = crashFiles().sortedByDescending { it.name }.mapNotNull { refFor(it) }

    /** The newest stored report, or `null` if this device has never captured one. */
    fun latest(): CrashReportRef? = list().firstOrNull()

    /**
     * The full report text with [secrets] applied — the phase-2 pass the dying handler could not run.
     * There is deliberately **no** raw-read API: every path out of this class is redacted.
     */
    fun readRedacted(
        ref: CrashReportRef,
        secrets: KnownSecrets,
    ): String? = runCatching { CrashRedactor.redact(ref.file.readText(), secrets) }.getOrNull()

    /** Deletes every stored report. */
    fun clear() {
        dir.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) }?.forEach { it.delete() }
    }

    /**
     * The report header. **ASCII only, deliberately**: [CrashRedactor]'s non-ASCII rule is what catches
     * typed message text, and phase 2 re-runs over this header, so an em dash here would redact itself.
     *
     * Every line answers something `.github/workflows/needs-info.yml` asks a crash reporter for: the
     * `FATAL EXCEPTION` line matches its trace regex (and mirrors logcat, so the paste looks familiar),
     * `device:` carries the model its device regex looks for, and `app:` names the release whose
     * published `mapping.txt` deobfuscates the frames.
     */
    private fun header(
        environment: CrashEnvironment,
        threadName: String,
        stamp: Long,
        kind: Kind,
    ): String =
        buildString {
            appendLine("FATAL EXCEPTION: $threadName")
            when (kind) {
                Kind.CRASH -> appendLine("Lifeline crash report - redacted on device. Nothing was uploaded.")
                else -> appendLine("Lifeline ANR report (the app stopped responding) - redacted on device. Nothing was uploaded.")
            }
            // An ANR from before the running build was installed: naming this build here would point a reader,
            // and crash-report.yml, at the wrong release's mapping. The value is deliberately not `x (n) type`.
            if (kind == Kind.EARLIER_ANR) {
                appendLine("app: unknown - recorded before Lifeline was updated to ${environment.appLine()}")
            } else {
                appendLine("app: ${environment.appLine()}")
            }
            if (environment.obfuscated) {
                val mapping =
                    if (kind == Kind.EARLIER_ANR) {
                        "the mapping of the release installed at the time"
                    } else {
                        "mapping-${environment.versionName}.txt.gz from the matching GitHub release"
                    }
                appendLine("obfuscated: yes - frames are R8-mangled; deobfuscate with $mapping")
            }
            appendLine("time: ${Instant.ofEpochMilli(stamp)} ($stamp)")
            appendLine("device: ${environment.deviceLine()}")
            appendLine("board: ${environment.board}  hardware: ${environment.hardware}  soc: ${environment.soc}")
            appendLine("android: ${environment.androidLine()}")
            appendLine("abis: ${environment.abis}")
            appendLine("fingerprint: ${environment.fingerprint}")
            appendLine("redaction: structural (ids, urls, paths, non-ascii); names applied when this is read")
            appendLine(SEPARATOR)
        }

    /** Truncates the **tail** — the header and top frames are the diagnosis. Chars, not bytes: a byte cut splits UTF-8. */
    private fun cap(
        text: String,
        max: Int,
    ): String =
        if (text.length <= max) {
            text
        } else {
            text.take(max) + "\n... [truncated, ${text.length - max} more chars]"
        }

    /**
     * A short digest of the first [FINGERPRINT_LINES] lines of the redacted body — the exception header
     * (class *and* message) plus the top frames — so [prune] can tell a startup crash loop, which would
     * otherwise fill all five slots in seconds, from a genuinely different failure.
     *
     * Including the message makes this deliberately conservative: two crashes are "the same" only if
     * they read the same. Erring that way costs some loop detection and never costs a distinct report,
     * which is the right side to be wrong on. Redaction has already collapsed the ids that would
     * otherwise make every repetition look unique.
     */
    private fun fingerprintOf(body: String): String {
        val signature =
            body
                .lineSequence()
                .dropWhile { it != SEPARATOR }
                .drop(1)
                .take(FINGERPRINT_LINES)
                .joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.encodeToByteArray())
        return digest.take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    /** Resolves a free name at [stamp], stepping the clock forward on the (test-visible) same-millis collision. */
    private fun nextFile(
        stamp: Long,
        fingerprint: String,
    ): File {
        var at = stamp
        var candidate = File(dir, fileName(at, fingerprint))
        while (candidate.exists()) {
            candidate = File(dir, fileName(++at, fingerprint))
        }
        return candidate
    }

    /**
     * Enforces the per-fingerprint and total caps, newest first, and sweeps `.tmp` siblings left by an
     * interrupted atomic write. Runs **after** the write, so a failed write never costs an existing
     * report, and only ever deletes files it named itself.
     */
    private fun prune(keep: File) {
        dir
            .listFiles { file -> file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(TMP_SUFFIX) }
            ?.forEach { it.delete() }
        val seen = mutableMapOf<String, Int>()
        var kept = 0
        for (file in crashFiles().sortedByDescending { it.name }) {
            val fingerprint = fingerprintIn(file.name)
            val forThisCrash = seen.getOrDefault(fingerprint, 0)
            if (file != keep && (kept >= MAX_FILES || forThisCrash >= MAX_PER_FINGERPRINT)) {
                file.delete()
                continue
            }
            seen[fingerprint] = forThisCrash + 1
            kept++
        }
    }

    private fun crashFiles(): List<File> = dir.listFiles { file -> file.isFile && NAME.matches(file.name) }.orEmpty().toList()

    private fun refFor(file: File): CrashReportRef? {
        val stamp = stampIn(file.name) ?: return null
        val head = runCatching { readHead(file) }.getOrNull() ?: return null
        return CrashReportRef(
            at = stamp,
            summary = head.summary,
            appVersion = head.fields["app"].orEmpty(),
            device = head.fields["device"].orEmpty(),
            androidVersion = head.fields["android"].orEmpty(),
            file = file,
        )
    }

    /** Reads the `key: value` header block plus enough of the body to build a one-line summary. */
    private fun readHead(file: File): Head {
        val fields = mutableMapOf<String, String>()
        var exception = ""
        var frame = ""
        file.useLines { lines ->
            var inBody = false
            for (line in lines) {
                when {
                    line == SEPARATOR -> inBody = true
                    !inBody -> line.indexOf(": ").takeIf { it > 0 }?.let { fields[line.take(it)] = line.substring(it + 2) }
                    exception.isEmpty() && line.isNotBlank() -> exception = line
                    frame.isEmpty() && line.trimStart().startsWith("at ") -> frame = line
                }
                if (exception.isNotEmpty() && frame.isNotEmpty()) break
            }
        }
        return Head(fields, summarize(exception, frame))
    }

    private fun summarize(
        exceptionLine: String,
        frameLine: String,
    ): String {
        val cut = exceptionLine.indexOf(": ")
        val qualified = if (cut > 0) exceptionLine.take(cut) else exceptionLine
        val simple = qualified.substringAfterLast('.').ifBlank { qualified }
        val where = frameLine.substringAfterLast('(', "").substringBefore(')')
        return if (where.isBlank()) simple else "$simple at $where"
    }

    /** What a report records: a crash, an ANR of this build, or an ANR from before this build was installed. */
    private enum class Kind { CRASH, ANR, EARLIER_ANR }

    private data class Head(
        val fields: Map<String, String>,
        val summary: String,
    )

    private companion object {
        /** The issue's "keep the last five". */
        const val MAX_FILES = 5

        /**
         * A crash loop at startup — issue #9's exact shape — must not evict four unrelated older
         * reports before the user ever opens Diagnostics.
         */
        const val MAX_PER_FINGERPRINT = 2

        /** ~60 KB of ASCII per report, so the directory ceiling is ~300 KB. */
        const val MAX_REPORT_CHARS = 60_000

        /**
         * An ANR's every thread is the diagnosis when `main` is idle (ADR 2026-10.fzsy), and a busy process dumps
         * ~100 KB of them, so an ANR may take twice a crash's room: ~600 KB for a directory of nothing but ANRs.
         */
        const val MAX_ANR_CHARS = 120_000

        /** The newest imported ANR's timestamp; not a `crash-` file, so [list], [prune] and [clear] never see it. */
        const val ANR_WATERMARK = "anr-seen"

        const val MIN_FREE_BYTES = 512L * 1024L
        const val FINGERPRINT_BYTES = 4
        const val FINGERPRINT_LINES = 5
        const val SEPARATOR = "----"
        const val PREFIX = "crash-"
        const val TMP_SUFFIX = ".tmp"

        /**
         * `crash-<13-digit epoch millis>-<8 hex>.txt`. Zero-padding makes lexicographic order
         * chronological until the year 2286, so listing is one `listFiles()` + a name sort — no
         * `lastModified()` stat per file and no dependence on a clock the user can move.
         */
        val NAME = Regex("""crash-(\d{13})-([0-9a-f]{8})\.txt""")

        /**
         * `Locale.ROOT` is mandatory, not stylistic: `"%013d".format(x)` uses the default locale, and
         * `ar`/`fa`/`ne` render Arabic-Indic digits — silently breaking both the sort and [NAME] on a
         * user's device.
         */
        fun fileName(
            stamp: Long,
            fingerprint: String,
        ): String = String.format(Locale.ROOT, "crash-%013d-%s.txt", stamp, fingerprint)

        fun stampIn(name: String): Long? =
            NAME
                .matchEntire(name)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()

        fun fingerprintIn(name: String): String =
            NAME
                .matchEntire(name)
                ?.groupValues
                ?.get(2)
                .orEmpty()
    }
}
