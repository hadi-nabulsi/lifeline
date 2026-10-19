package app.getknit.knit.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Copies the ANRs the platform recorded against earlier processes of this app into [store], with their thread
 * dumps, so Diagnostics can show them like a crash. Returns how many were added.
 *
 * Play shows an ANR whose `main` thread was idle when sampled (`MessageQueue.nativePollOnce`) with no trace at
 * all, which leaves nothing to act on; the platform keeps the whole dump in `ApplicationExitInfo` (API 30+) for
 * a few days, and this is the only way to get at it. A background ANR kills the process, so the record is read on
 * the next start. Below API 30 there is nothing to read. ADR 2026-10.fzsy.
 *
 * Call off the main thread: it is a binder call and, for a new ANR, a read of a dump that can run to megabytes.
 */
fun importAnrs(
    context: Context,
    store: CrashStore,
    environment: CrashEnvironment,
): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) importAnrsR(context, store, environment) else 0

@RequiresApi(Build.VERSION_CODES.R)
private fun importAnrsR(
    context: Context,
    store: CrashStore,
    environment: CrashEnvironment,
): Int {
    val exits =
        runCatching {
            context
                .getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 0)
                .orEmpty()
        }.getOrDefault(emptyList())
    val anrs = exits.filter { it.reason == ApplicationExitInfo.REASON_ANR }
    if (anrs.isEmpty()) return 0
    // An ANR older than this install ran an earlier build; the report must not name this one (see AnrExit).
    val installedAt =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
    return store.importAnrs(
        environment,
        anrs.map { info ->
            AnrExit(
                at = info.timestamp,
                description = info.description,
                predatesInstall = info.timestamp < installedAt,
                trace = { readTrace(info) },
            )
        },
    )
}

/** The dump, bounded: a pathological one is cut rather than read whole into memory. */
@RequiresApi(Build.VERSION_CODES.R)
private fun readTrace(info: ApplicationExitInfo): String? =
    info.traceInputStream?.bufferedReader()?.use { reader ->
        val out = StringBuilder()
        val chunk = CharArray(TRACE_CHUNK_CHARS)
        while (out.length < MAX_TRACE_CHARS) {
            val read = reader.read(chunk, 0, minOf(chunk.size, MAX_TRACE_CHARS - out.length))
            if (read < 0) break
            out.appendRange(chunk, 0, read)
        }
        out.toString()
    }

/** A busy process's dump is a few hundred KB; [AnrTrace] keeps a fraction of it. */
private const val MAX_TRACE_CHARS = 4 * 1024 * 1024

private const val TRACE_CHUNK_CHARS = 16 * 1024
