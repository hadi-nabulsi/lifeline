package app.getknit.knit.mesh.bluetooth

/**
 * The peers this phone is hunting: each one a link of ours dropped at range, on the side that dials back
 * ([CodedPhyPolicy.huntAfterDrop]), held until it links again or its hold runs out (ADR 2026-10.yvn6, the 2026-10-04
 * amendment). It is the dialing side's twin of [CodedAdvertPace]: for the same drop, the far peer advertises fast and
 * this phone listens for it.
 *
 * The hold depends on whether Knit is in use. A phone in a pocket hunts for [PhyTuning.fastHoldMs], the same window the
 * far peer's advert stays fast. A phone with Knit open on a lit screen keeps hunting for [PhyTuning.huntInUseHoldMs]
 * from the drop, because the person is waiting on that peer. The drop time is kept, not a deadline, so a phone that
 * opens Knit two minutes after a drop picks the hunt up again, and one that closes it ten minutes in stops at once.
 *
 * Pure and thread-safe: the transport's link edges and its scan loop both touch it.
 */
internal class RangeHunt {
    private val droppedAt = HashMap<String, Long>()

    /** [peerNodeId]'s link just dropped at range at [now]. */
    @Synchronized
    fun onDrop(
        peerNodeId: String,
        now: Long,
    ) {
        droppedAt[peerNodeId] = now
    }

    /** [peerNodeId] linked again: it is no longer hunted. */
    @Synchronized
    fun onLinkUp(peerNodeId: String) {
        droppedAt.remove(peerNodeId)
    }

    /**
     * The peers hunted at [now]: each for [holdMs] after its drop, or for [inUseHoldMs] while [inUse]. A peer past the
     * longer of the two is forgotten, so a later [inUse] edge cannot bring back a drop that old.
     */
    @Synchronized
    fun wanted(
        now: Long,
        inUse: Boolean,
        holdMs: Long,
        inUseHoldMs: Long,
    ): Set<String> {
        val longest = maxOf(holdMs, inUseHoldMs)
        droppedAt.values.removeAll { now - it >= longest }
        val hold = if (inUse) longest else holdMs
        return droppedAt.filterValues { now - it < hold }.keys.toSet()
    }

    /** Forgets every peer: the transport stopped. */
    @Synchronized
    fun clear() {
        droppedAt.clear()
    }
}
