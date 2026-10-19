package app.getknit.knit.fieldlog

import app.getknit.knit.data.crypto.writeBytesAtomically
import java.io.File
import java.io.RandomAccessFile

/**
 * The field log on disk: two append-only JSON Lines segments in a directory under `noBackupFilesDir`
 * (never in a backup, by construction — ADR 028's reasoning), plus the [Pseudonyms] salt.
 *
 * `current.jsonl` takes every [append]; once it passes [segmentBytes] it becomes `prev.jsonl` (the old `prev`
 * goes), so the log never holds more than about two segments. [read] drops what is older than [keepMs] and any
 * line that is not one of ours — a tail torn by a process death mid-append is skipped, never fatal.
 *
 * Not thread-safe on its own: [FieldLog] is the one caller and serialises every call.
 */
class FieldJournal(
    private val dir: File,
    private val segmentBytes: Long = SEGMENT_BYTES,
    private val keepMs: Long = KEEP_MS,
) {
    private val current get() = File(dir, CURRENT)
    private val prev get() = File(dir, PREV)
    private val saltFile get() = File(dir, SALT)

    /** This log's salt, minted on first use and on every [clear]. */
    fun salt(): ByteArray {
        val held = runCatching { saltFile.readBytes() }.getOrNull()
        if (held != null && held.size == Pseudonyms.SALT_BYTES) return held
        dir.mkdirs()
        return Pseudonyms.newSalt().also { saltFile.writeBytesAtomically(it) }
    }

    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        dir.mkdirs()
        // A torn tail (a process death mid-append) is closed off first, so it stays one bad line on its own
        // rather than gluing itself to the next good one.
        val lead = if (endsTorn()) "\n" else ""
        current.appendText(lines.joinToString(separator = "\n", prefix = lead, postfix = "\n"))
        if (current.length() > segmentBytes) {
            prev.delete()
            current.renameTo(prev)
        }
    }

    private fun endsTorn(): Boolean {
        val file = current
        return file.length() != 0L &&
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(raf.length() - 1)
                raf.read() != '\n'.code
            }
    }

    /** Every kept line, oldest first, no older than [keepMs] before [now]. */
    fun read(now: Long): List<String> {
        val floor = now - keepMs
        return listOf(prev, current)
            .filter { it.exists() }
            .flatMap { segment -> runCatching { segment.readLines() }.getOrDefault(emptyList()) }
            .filter { line -> FieldLine.timeOf(line)?.let { it >= floor } == true }
    }

    /** Bytes on disk, for the Diagnostics row. */
    fun size(): Long = prev.length() + current.length()

    /** Everything goes, the salt with it: the next log's peers read under new pseudonyms. */
    fun clear() {
        prev.delete()
        current.delete()
        saltFile.delete()
    }

    companion object {
        const val DIR = "fieldlog"
        private const val CURRENT = "current.jsonl"
        private const val PREV = "prev.jsonl"
        private const val SALT = "salt"

        /** Two mebibytes a segment, so about four on disk at most: three days of samples and edges, with room. */
        const val SEGMENT_BYTES = 2L * 1024 * 1024

        /** Three days: long enough to span a walk, a night and the day after, short enough to mean "lately". */
        const val KEEP_MS = 72 * 60 * 60_000L
    }
}
