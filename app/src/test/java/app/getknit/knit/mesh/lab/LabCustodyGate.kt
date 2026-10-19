package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.protocol.RelayEnvelope
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/**
 * A node's custody writes, holdable: from [hold] on, every `store` of a frame [hold]'s predicate matches waits until
 * [release] — a phone whose database is slow to commit (a busy pool, a core taken away mid-write) while its radio is
 * not. Only the stack's own writes go through it (`LabNode.boot` hands the manager [over]); a scenario's reads of the
 * store do not. Kept across restarts, like the rest of the node's persistent state.
 */
class LabCustodyGate {
    @Volatile
    private var matches: ((RelayEnvelope) -> Boolean)? = null

    @Volatile
    private var opened = CompletableDeferred<Unit>()

    private val waiting = AtomicInteger()

    /**
     * How many writes are waiting at the gate right now — the event a scenario holds its next step on. A write leaves
     * the count as it leaves the gate, before it is stored: await the send that made it, not this, to know it landed.
     */
    val parked: Int get() = waiting.get()

    /** From now on, a custody write of a frame [which] answers true for waits for [release]. */
    fun hold(which: (RelayEnvelope) -> Boolean) {
        check(matches == null) { "the gate is already holding: release it first" }
        opened = CompletableDeferred()
        matches = which
    }

    /** Lets every waiting write through and stops holding. */
    fun release() {
        matches = null
        opened.complete(Unit)
    }

    /** [store] with its writes going through this gate. */
    fun over(store: ForwardStore): ForwardStore =
        object : ForwardStore by store {
            override suspend fun store(
                frame: CarriedFrame,
                origin: Int,
                now: Long,
            ): Boolean {
                // The predicate before the latch: [hold] sets them in the other order, so a match reads its own latch.
                if (matches?.invoke(frame.envelope) == true) {
                    waiting.incrementAndGet()
                    try {
                        opened.await()
                    } finally {
                        waiting.decrementAndGet()
                    }
                }
                return store.store(frame, origin, now)
            }
        }
}
