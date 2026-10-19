package app.getknit.knit.mesh.lora

/**
 * When this plane last put a DM on the air as a **probe** to each recipient it had not heard (ADR 2026-10.a4pm).
 *
 * A DM-form frame rides the fan-out only to a recipient in `reachable`; the rest stay in custody for the
 * re-offer that the recipient's first hearing runs. That alone would be a deadlock between two phones that
 * are both quiet — neither is heard, so neither speaks — so the first plain DM to an unheard recipient in
 * each [windowMs] goes out anyway. If the recipient is there, its sealed ✓✓ makes it heard here, and the DMs
 * held behind the probe follow through `reofferTo`. If it is not, the probe is the only air its absence costs
 * per window.
 *
 * A DM rather than a profile beacon, because a recipient that already hears us (from our room posts, say)
 * never fires a first-hearing beacon of its own back, and a beacon names nobody: the ✓✓ to a DM is the one
 * reply a present recipient is certain to send.
 *
 * A limiter, so it outlives the process ([stamps] / [restore], `LoraPlaneSnapshot.dmProbes`, ADR
 * 2026-09.7svb): a window that reopened on every launch would buy a probe per recipient per restart. Stamps
 * are on the plane's monotonic clock here and converted at the snapshot. Not cleared by `quiesce`, like the
 * auto-reply's memory and the ledger: the window is about the recipient and the air, not the board, and the
 * restore runs once per instance, so a clear on `stop()` would reopen every window for an in-process restart.
 * Every entry point is `@Synchronized` — the fan-out runs on whichever coroutine sent, and two DMs to one
 * recipient must not both win the window.
 */
internal class LoraDmProbe(
    private val windowMs: Long = LoraMeshTransport.DM_PROBE_WINDOW_MS,
) {
    /** A probe slot taken by [claim], handed back through [release] when the frame never made the queue. */
    class Claim internal constructor(
        val recipientId: String,
        internal val at: Long,
        internal val previous: Long?,
    )

    private val probedAt = HashMap<String, Long>()

    /**
     * Takes [recipientId]'s probe for the window that starts at [now], or null when one was already taken
     * within [windowMs]. The caller hands it back with [release] if the frame is not enqueued after all — a
     * frame that failed to encode, was deduplicated or was refused by the queue must not spend the window.
     */
    @Synchronized
    fun claim(
        recipientId: String,
        now: Long,
    ): Claim? {
        prune(now)
        val previous = probedAt[recipientId]
        if (previous != null && now - previous < windowMs) return null
        probedAt[recipientId] = now
        return Claim(recipientId, now, previous)
    }

    /** Gives [claim] back, unless a later claim has replaced it since. */
    @Synchronized
    fun release(claim: Claim) {
        if (probedAt[claim.recipientId] != claim.at) return
        if (claim.previous == null) probedAt.remove(claim.recipientId) else probedAt[claim.recipientId] = claim.previous
    }

    /** The live stamps, oldest first — what the persisted snapshot carries. Expired ones are open again anyway. */
    @Synchronized
    fun stamps(now: Long): List<Pair<String, Long>> =
        probedAt.entries
            .filter { now - it.value < windowMs }
            .sortedBy { it.value }
            .map { it.key to it.value }

    /** Re-applies [stamps] a previous process saved, skipping any its window has outlived. */
    @Synchronized
    fun restore(
        stamps: List<Pair<String, Long>>,
        now: Long,
    ) {
        stamps.forEach { (id, at) -> if (now - at < windowMs) probedAt[id] = at }
    }

    private fun prune(now: Long) {
        probedAt.entries.removeAll { now - it.value >= windowMs }
    }
}
