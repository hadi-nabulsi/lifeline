package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.power.PowerPolicy
import app.getknit.knit.mesh.power.PowerState

/**
 * The presence scan's glue between what it heard and its policies ([CodedPhyPolicy], [ScanDemandPolicy]): which peers
 * count as far, when Coded-only windows run and why, and what the transport publishes as reachable. Pure, so
 * [BluetoothMeshTransport]'s choices are asserted on the JVM.
 */
internal object ScanPlan {
    /**
     * How long the scan waits before its next window, null to scan now: while the adapter is off ([adapterOn] false),
     * [adapterOffWaitMs], for the adapter's own event; while a dial is in flight ([dialing]) or another connect holds
     * the arbiter, [connectPauseMs], for the connect's end (a scan starves a connect); else until [nextWindowAt], the
     * slot of a window a dial cut short, when that is still ahead of [now].
     */
    @Suppress("LongParameterList") // the window's gates and its two waits, each from a different source
    fun beforeWindow(
        adapterOn: Boolean,
        dialing: Boolean,
        arbiterBusy: Boolean,
        nextWindowAt: Long,
        now: Long,
        connectPauseMs: Long,
        adapterOffWaitMs: Long,
    ): Long? =
        when {
            !adapterOn -> adapterOffWaitMs
            dialing || arbiterBusy -> connectPauseMs
            nextWindowAt > now -> nextWindowAt - now
            else -> null
        }

    /** The gap after a presence window: [ms] long, and whether it keeps wall time through sleep ([keepsWallTime]). */
    data class Gap(
        val ms: Long,
        val keepsWallTime: Boolean,
    )

    /**
     * The gap after a presence window: the hunting gap while it [hunt]s, else the settled [floor]'s or the duty cycle's.
     * Only a hunting gap keeps wall time (ADR 2026-10.pj9w): the settled, linked and relaxed gaps are power budgets
     * and stretch while the CPU sleeps.
     */
    fun gapAfterWindow(
        power: PowerState,
        hunt: Boolean,
        floor: () -> Boolean,
        links: Int,
        aloneForMs: Long,
    ): Gap {
        val ms =
            when {
                hunt -> PowerPolicy.HUNT_IDLE_MS
                floor() -> PowerPolicy.settledIdleAfterScan(power, links, aloneForMs)
                else -> PowerPolicy.idleAfterScan(power, links, aloneForMs)
            }
        return Gap(ms, keepsWallTime = hunt || PowerPolicy.hunting(power, links, aloneForMs))
    }

    /** The sighted peers not [linked] that are heard on Coded alone ([CodedPhyPolicy.codedOnly]): the far ones. */
    fun far(
        snaps: List<BlePresenceTracker.Snapshot>,
        linked: Set<String>,
    ): List<String> = snaps.filter { it.nodeId !in linked && CodedPhyPolicy.codedOnly(it.codedLagMs) }.map { it.nodeId }

    /**
     * Whether the scan runs Coded-only windows at all ([CodedPhyPolicy.scanPhys] alternates them): only with Coded [on],
     * and then while this phone is [alone], hears a [far] peer, [hunt]s, or [chased] a peer another plane sees.
     */
    fun codedWindows(
        on: Boolean,
        alone: Boolean,
        far: List<String>,
        hunt: Boolean,
        chased: Boolean,
    ): Boolean = on && (alone || far.isNotEmpty() || hunt || chased)

    /** Why Coded-only windows came on, for their edge's log line: the first of [codedWindows]' reasons, else the chase. */
    fun codedWindowsWhy(
        alone: Boolean,
        hunt: Boolean,
        far: List<String>,
        chasedCoded: () -> List<String>,
    ): String =
        when {
            alone -> "alone"
            hunt -> "hunt"
            far.isNotEmpty() -> "codedOnly=$far"
            else -> "chase=${chasedCoded()}"
        }

    /**
     * What the transport publishes as reachable: every sighted peer in [snaps], and every peer it holds a link to
     * ([linkPeers]), so a linked peer stays nearby while the throttled scan has not re-sighted it. A link's [Peer] wins
     * over a sighting's on the same node id.
     */
    fun reachable(
        snaps: List<BlePresenceTracker.Snapshot>,
        linkPeers: Collection<Peer>,
    ): Set<Peer> {
        val byId = HashMap<String, Peer>()
        snaps.forEach { byId[it.nodeId] = Peer(it.nodeId, it.protoVersion, it.capabilities) }
        linkPeers.forEach { byId[it.nodeId] = it }
        return byId.values.toSet()
    }
}
