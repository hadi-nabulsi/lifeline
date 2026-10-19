package app.getknit.knit

import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.ScanPlan
import app.getknit.knit.mesh.power.PowerPolicy
import app.getknit.knit.mesh.power.PowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [ScanPlan]: far peers, when Coded-only windows run and why, and the reachable set. */
class ScanPlanTest {
    private fun snap(
        nodeId: String,
        codedLagMs: Long? = null,
        caps: Long = 0L,
    ): BlePresenceTracker.Snapshot =
        BlePresenceTracker.Snapshot(
            nodeId = nodeId,
            protoVersion = 1,
            capabilities = caps,
            psm = 100,
            digestCue = 0,
            smoothedRssi = -60.0,
            dwellMs = 15_000,
            lastSeenAgoMs = 0,
            codedLagMs = codedLagMs,
        )

    @Test
    fun aFarPeerIsUnlinkedAndHeardOnCodedAlone() {
        val stale = CodedPhyPolicy.ONE_M_FRESH_MS + 1
        val snaps =
            listOf(
                snap("near"), // 1M heard
                snap("fresh", codedLagMs = CodedPhyPolicy.ONE_M_FRESH_MS), // its 1M is still fresh
                snap("far", codedLagMs = stale),
                snap("linked", codedLagMs = stale),
            )
        assertEquals(listOf("far"), ScanPlan.far(snaps, linked = setOf("linked")))
    }

    @Test
    fun codedWindowsRunOnlyWithCodedOnAndAReasonForThem() {
        assertFalse(ScanPlan.codedWindows(on = false, alone = true, far = listOf("x"), hunt = true, chased = true))
        assertFalse("linked, nothing far, no hunt, no chase", ScanPlan.codedWindows(true, false, emptyList(), false, false))
        assertTrue(ScanPlan.codedWindows(true, alone = true, far = emptyList(), hunt = false, chased = false))
        assertTrue(ScanPlan.codedWindows(true, alone = false, far = listOf("x"), hunt = false, chased = false))
        assertTrue(ScanPlan.codedWindows(true, alone = false, far = emptyList(), hunt = true, chased = false))
        assertTrue(ScanPlan.codedWindows(true, alone = false, far = emptyList(), hunt = false, chased = true))
    }

    @Test
    fun theWindowsLineNamesTheFirstReason() {
        val chase = { listOf("c1") }
        assertEquals("alone", ScanPlan.codedWindowsWhy(alone = true, hunt = true, far = listOf("x"), chasedCoded = chase))
        assertEquals("hunt", ScanPlan.codedWindowsWhy(alone = false, hunt = true, far = listOf("x"), chasedCoded = chase))
        assertEquals("codedOnly=[x]", ScanPlan.codedWindowsWhy(alone = false, hunt = false, far = listOf("x"), chasedCoded = chase))
        assertEquals("chase=[c1]", ScanPlan.codedWindowsWhy(alone = false, hunt = false, far = emptyList(), chasedCoded = chase))
    }

    @Test
    fun theChaseIsReadOnlyWhenItIsTheReason() {
        var reads = 0
        ScanPlan.codedWindowsWhy(alone = true, hunt = false, far = emptyList()) { reads++.let { emptyList() } }
        assertEquals(0, reads)
    }

    @Test
    fun reachableIsEverySightingAndEveryLinkAndALinksPeerWins() {
        val got =
            ScanPlan.reachable(
                listOf(snap("seen", caps = 1L), snap("both", caps = 1L)),
                listOf(Peer("both", 2, 2L), Peer("unsighted", 2, 2L)),
            )
        assertEquals(setOf(Peer("seen", 1, 1L), Peer("both", 2, 2L), Peer("unsighted", 2, 2L)), got)
    }

    private fun before(
        adapterOn: Boolean = true,
        dialing: Boolean = false,
        busy: Boolean = false,
        nextWindowAt: Long = 0,
        now: Long = 1_000,
    ) = ScanPlan.beforeWindow(adapterOn, dialing, busy, nextWindowAt, now, connectPauseMs = 28_000, adapterOffWaitMs = 60_000)

    @Test
    fun theScanWaitsForTheEventThatEndsItsPause() {
        assertNull("free radio, no slot held: scan now", before())
        assertEquals("adapter off, whatever else", 60_000L, before(adapterOn = false, dialing = true, busy = true))
        assertEquals("a dial in flight", 28_000L, before(dialing = true))
        assertEquals("the arbiter held", 28_000L, before(busy = true))
    }

    @Test
    fun aWindowADialCutShortKeepsItsSlot() {
        assertEquals(400L, before(nextWindowAt = 1_400, now = 1_000))
        assertNull("its slot has come", before(nextWindowAt = 1_000, now = 1_000))
        assertEquals("a pause wins over the slot", 28_000L, before(dialing = true, nextWindowAt = 1_400, now = 1_000))
    }

    @Test
    fun aHuntingGapKeepsWallTimeAndNeverAsksForTheFloor() {
        var floorReads = 0
        val gap = ScanPlan.gapAfterWindow(PowerState(), hunt = true, floor = { floorReads++ > 0 }, links = 2, aloneForMs = 0)
        assertEquals(ScanPlan.Gap(PowerPolicy.HUNT_IDLE_MS, keepsWallTime = true), gap)
        assertEquals(0, floorReads)
    }

    @Test
    fun aSettledOrDutyGapIsAPowerBudgetThatStretches() {
        val lit = PowerState(interactive = true)
        assertEquals(
            ScanPlan.Gap(PowerPolicy.settledIdleAfterScan(lit, 2, 0), keepsWallTime = false),
            ScanPlan.gapAfterWindow(lit, hunt = false, floor = { true }, links = 2, aloneForMs = 0),
        )
        assertEquals(
            ScanPlan.Gap(PowerPolicy.idleAfterScan(lit, 2, 0), keepsWallTime = false),
            ScanPlan.gapAfterWindow(lit, hunt = false, floor = { false }, links = 2, aloneForMs = 0),
        )
    }

    @Test
    fun aLinklessPhoneThatHasNotRelaxedKeepsWallTimeOutsideAHunt() {
        val dark = PowerState(interactive = false)
        val gap = ScanPlan.gapAfterWindow(dark, hunt = false, floor = { false }, links = 0, aloneForMs = 0)
        assertEquals(PowerPolicy.hunting(dark, 0, 0), gap.keepsWallTime)
        assertTrue("alone and not yet relaxed", gap.keepsWallTime)
    }
}
