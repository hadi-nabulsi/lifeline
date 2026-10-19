package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.ArrivingFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The receive side's progress reports for one link: how much of the file streaming in it holds, and when by its own
 * clock since the header, to [report], which the owner sends back to the sender (ADR 2026-10.nsr7). The sender measures
 * its drain from them, and a windowed one is clocked by them (ADR 2026-10.5buf).
 *
 * - **Paced** ([windowed] false): every [EVERY_MS] of chunks, on the reader after a chunk is written. A stall sends
 *   nothing.
 * - **Windowed:** on every chunk that brings [WINDOWED_EVERY_BYTES] since the last report (about every chunk),
 *   every [WINDOWED_EVERY_MS] whatever it brought, and once more [WINDOWED_EVERY_MS] after chunks no later report
 *   carried. A windowed sender writes nothing more until
 *   it hears, so a fast pair's starting window lands inside one interval and would otherwise wait out its silence (the
 *   bluetooth-reviewer's finding). A stall still sends nothing: the trailing report goes only when the file grew.
 *
 * [arriving] is the file streaming in now, null with none or once the link is closed.
 */
internal class RxReports(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val windowed: () -> Boolean,
    private val arriving: () -> ArrivingFile?,
    private val report: (key: String, held: Long, ms: Long) -> Unit,
) {
    // When the file streaming in began, and when it was last reported, with how much it held then and the trailing
    // report armed for it. Guarded by [lock]: the trailing report runs off the reader.
    private var startedAt = 0L
    private var reportedAt = 0L
    private var reportedBytes = -1L
    private var trailing: Job? = null
    private val lock = Any()

    /** A file's header came at [at]. */
    fun started(at: Long) =
        synchronized(lock) {
            startedAt = at
            reportedAt = at
            reportedBytes = -1L
        }

    /** A chunk of the file streaming in was written. Called on the reader, outside every lock of the link's. */
    fun chunk() {
        val f = arriving() ?: return
        val windowed = windowed()
        val every = if (windowed) WINDOWED_EVERY_MS else EVERY_MS
        val t = now()
        val ms =
            synchronized(lock) {
                val wait = reportedAt + every - t
                // A windowed sender's window is freed only by what a report says landed. Between two dark phones nothing
                // else wakes either CPU, so the receiver answers its chunks themselves, on the reader, awake for them: a
                // sender that ran out of window then always has a report coming (device sweep 2026-10-07, P9→P8 dark: an
                // 8 KiB rule left a smaller window waiting out the sender's 3 s silence every few seconds).
                val grown = windowed && f.bytes - maxOf(reportedBytes, 0L) >= WINDOWED_EVERY_BYTES
                if (wait <= 0 || grown) {
                    reportedAt = t
                    reportedBytes = f.bytes
                    return@synchronized t - startedAt
                }
                if (windowed && trailing == null) trailing = scope.launch { trail(f.key, wait) }
                null
            }
        if (ms != null) report(f.key, f.bytes, ms)
    }

    /** The link closed: no trailing report goes. */
    fun close() =
        synchronized(lock) {
            trailing?.cancel()
            trailing = null
        }

    /**
     * The trailing report: while chunks have landed that no report covered, one every [WINDOWED_EVERY_MS]. It ends, and
     * clears itself, only under [lock] with nothing owed, so a chunk landing as it reports is covered by its next turn,
     * and one landing after it ends arms a new one.
     */
    private suspend fun trail(
        key: String,
        ms: Long,
    ) {
        var wait = ms
        while (true) {
            // stretches: a backstop only. A receiver that suspends before it fires reports at the sender's next chunk,
            // which comes after the sender's 3 s silence; the report on a chunk's worth of bytes ([chunk]) runs awake.
            delay(wait)
            val bytes = arriving()?.takeIf { it.key == key }?.bytes ?: -1L // -1: the file is over
            val t = now()
            var since = -1L
            val left =
                synchronized(lock) {
                    if (bytes < 0 || bytes <= reportedBytes) {
                        trailing = null // the file is over, or reported since with nothing more landed
                        return
                    }
                    (reportedAt + WINDOWED_EVERY_MS - t).also {
                        if (it <= 0) {
                            reportedAt = t
                            reportedBytes = bytes
                            since = t - startedAt
                        }
                    }
                }
            if (since >= 0) report(key, bytes, since)
            wait = if (left <= 0) WINDOWED_EVERY_MS else left
        }
    }

    companion object {
        // How often a paced sender is reported to: often enough that a stall shows inside FeedMeter.STALL_MS, rarely
        // enough that the report costs nothing beside the file. FeedMeter's settle and smoothing count reports at this.
        const val EVERY_MS = 2_000L

        // How often a windowed one is at the least, whatever landed; it hears far more often from the chunk rule below.
        const val WINDOWED_EVERY_MS = 500L

        // And on every chunk that brings this much since the last report: a sender's smallest chunk, so under every
        // window. A chunk is about a quarter second of the drain, so about four reports a second at any rate, ~500 B/s
        // of reverse air. A reader catching up a burst reports chunks ms apart; the sender takes no sample from those.
        const val WINDOWED_EVERY_BYTES = FeedWindow.MIN_CHUNK_BYTES.toLong()
    }
}
