package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.ArrivingFile
import java.util.concurrent.ConcurrentHashMap

/**
 * The files a transport's links have finished receiving that its consumer has not taken yet (#138, ADR 2026-09.4tx5's
 * 2026-10-05 amendment). Past `FILE_END` a file is moved into the cache ([FileIntake.finalize]), waits in the
 * transport's `incomingFiles` buffer, and waits again behind the files the consumer is still storing. None of that
 * is the link's, and all of it outlives the link, so the mark is the transport's, by peer and key: a link torn down
 * or replaced meanwhile leaves it standing while its file goes on to the store, and the consumer's take
 * (`MeshTransport.fileTaken`) is what ends it. A transport clears it when it stops, with the session that would
 * have taken them.
 */
class LandingFiles {
    private val files = ConcurrentHashMap<String, ArrivingFile>()

    /** [file] from [peer] has its end in: it lands until it is taken. */
    fun land(
        peer: String,
        file: ArrivingFile,
    ) {
        files[slot(peer, file.key)] = file
    }

    /** The consumer is done with [key] from [peer] — stored, refused or dropped. Idempotent. */
    fun take(
        peer: String,
        key: String,
    ) {
        files.remove(slot(peer, key))
    }

    /** Every file landing now. */
    val all: Collection<ArrivingFile> get() = files.values.toList()

    /** The transport stopped: nothing it handed on will be taken now. */
    fun clear() = files.clear()

    private fun slot(
        peer: String,
        key: String,
    ) = "$peer|$key"
}
