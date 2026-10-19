package app.getknit.knit.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Hands each file in this flow to [handle], then to [taken] whatever [handle] did — the link counts a file as arriving
 * until it is taken (`MeshTransport.fileTaken`, #138). One file's failure is that file's: [onFailure] hears it and the
 * next file is handled. A throw that escaped here used to end the session's only file collector, and every photo, file
 * and avatar after it was dropped, re-asked on the tick and dropped again until the mesh restarted (#139).
 * Cancellation still ends it.
 */
internal suspend fun Flow<ReceivedFile>.handleEach(
    handle: suspend (ReceivedFile) -> Unit,
    taken: (ReceivedFile) -> Unit,
    onFailure: (ReceivedFile, Throwable) -> Unit,
) = collect { file ->
    try {
        runCatching { handle(file) }.onFailure {
            if (it is CancellationException) throw it
            onFailure(file, it)
        }
    } finally {
        taken(file)
    }
}
