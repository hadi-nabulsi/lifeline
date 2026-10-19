package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.RangeHunt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which peers the dialing side hunts after a drop at range (ADR 2026-10.yvn6, the 2026-10-04 amendment). */
class RangeHuntTest {
    private val hunt = RangeHunt()

    private fun wanted(
        now: Long,
        inUse: Boolean = false,
    ) = hunt.wanted(now, inUse, HOLD, IN_USE_HOLD)

    @Test
    fun nobodyIsHuntedUntilALinkDrops() {
        assertTrue(wanted(now = 0).isEmpty())
    }

    @Test
    fun aPocketPhoneHuntsForTheHold() {
        hunt.onDrop("p", now = 1_000)
        assertEquals(setOf("p"), wanted(now = 1_000 + HOLD - 1))
        assertTrue(wanted(now = 1_000 + HOLD).isEmpty())
    }

    @Test
    fun knitInUseHuntsPastTheHoldUpToItsBound() {
        hunt.onDrop("p", now = 0)
        assertEquals(setOf("p"), wanted(now = HOLD + 60_000, inUse = true))
        assertTrue(wanted(now = IN_USE_HOLD, inUse = true).isEmpty())
    }

    @Test
    fun openingKnitResumesAHuntTheHoldHadEnded() {
        hunt.onDrop("p", now = 0)
        assertTrue("in a pocket the hold ran out", wanted(now = HOLD + 1).isEmpty())
        assertEquals("the drop time is kept, so opening Knit picks it up", setOf("p"), wanted(now = HOLD + 2, inUse = true))
        assertTrue("and closing it ends it at once", wanted(now = HOLD + 3).isEmpty())
    }

    @Test
    fun aDropPastTheLongerBoundIsForgotten() {
        hunt.onDrop("p", now = 0)
        assertTrue(wanted(now = IN_USE_HOLD).isEmpty())
        assertTrue("forgotten, not just outside the window", wanted(now = IN_USE_HOLD, inUse = true).isEmpty())
    }

    @Test
    fun thePeerLinkingAgainEndsItsHunt() {
        hunt.onDrop("p", now = 0)
        hunt.onDrop("q", now = 0)
        hunt.onLinkUp("p")
        assertEquals(setOf("q"), wanted(now = 1, inUse = true))
    }

    @Test
    fun aSecondDropRestartsThatPeersHold() {
        hunt.onDrop("p", now = 0)
        hunt.onDrop("p", now = 100_000)
        assertEquals(setOf("p"), wanted(now = HOLD + 1))
    }

    @Test
    fun clearForgetsEveryone() {
        hunt.onDrop("p", now = 0)
        hunt.clear()
        assertTrue(wanted(now = 1, inUse = true).isEmpty())
    }

    private companion object {
        const val HOLD = 180_000L
        const val IN_USE_HOLD = 1_800_000L
    }
}
