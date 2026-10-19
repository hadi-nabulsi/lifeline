package app.getknit.knit.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * What lets a stranger's name clash fade (ADR 2026-10.k8ge): two peers named "Alice" both wear their alias while
 * both are around, and once the stranger has gone [LABEL_FADE_MS] without a signed frame reaching this phone, the
 * contact reads plain again. The stranger stays labelled apart — see `PeerLabels.index`'s `stale`.
 *
 * [anchors] are never stale; [clock] judges the window; [ticks] re-judge it with no write to wake the peers flow
 * (a stranger crosses the line on the calendar, not on an event); [transact] banks a batch of stamps in one write.
 */
class LabelFade(
    val anchors: LabelAnchors,
    val clock: () -> Long,
    val ticks: Flow<Unit>,
    val transact: suspend (suspend () -> Unit) -> Unit,
) {
    /** Whether [nodeId]'s clash has faded: neither anchored nor verified, and not heard from within the window. */
    fun fades(
        nodeId: String,
        verified: Boolean,
        lastSeenAt: Long,
        anchors: Set<String>,
        now: Long,
    ): Boolean = !verified && nodeId !in anchors && lastSeenAt < now - LABEL_FADE_MS

    companion object {
        private const val HOUR_MS = 60 * 60_000L

        /**
         * A week: a stranger met at an event keeps a same-named contact suffixed while they come and go over its days,
         * so the contact's label does not flicker, and an event's crowd has stopped suffixing anyone by the next week.
         */
        const val LABEL_FADE_MS = 7 * 24 * HOUR_MS

        /**
         * How far `peers.lastSeenAt` may lag: a stamp is written only once it is this much newer than the stored one.
         * Half the 12 h profile republish, so a live peer's republish is never the stamp the gate skips.
         */
        const val SEEN_GATE_MS = 6 * HOUR_MS

        /** How often [hourly] re-judges the window; against a week, an hour late is nothing. */
        const val TICK_MS = HOUR_MS

        /**
         * A tick now and every [TICK_MS] after. Collected only while a screen shows a directory, so a wait that
         * stretches while the CPU sleeps costs nothing anyone sees.
         */
        fun hourly(): Flow<Unit> =
            flow {
                while (true) {
                    emit(Unit)
                    delay(TICK_MS)
                }
            }
    }
}
