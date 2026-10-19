package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.CodedPhyMode
import app.getknit.knit.mesh.bluetooth.LinkPhy
import app.getknit.knit.mesh.bluetooth.PhyStepper
import app.getknit.knit.mesh.bluetooth.PhyStepper.Action
import app.getknit.knit.mesh.bluetooth.PhyTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhyStepper]'s step-up backoff (#169, ADR 2026-10.yvn6's 2026-10-14 amendment): a link that steps back down to Coded
 * soon after a step-up waits longer on Coded each time before AUTO steps it up again; the step-down never waits.
 */
class PhyStepUpBackoffTest {
    private val tuning = PhyTuning()

    private fun stepper(
        phy: LinkPhy,
        vararg reads: Int,
    ): PhyStepper =
        PhyStepper { tuning }.also { s ->
            s.onPhy(phy, succeeded = true, now = 0)
            reads.forEachIndexed { i, r -> s.onRssi(r, now = i * 1_000L) }
        }

    /** A step the controller reported: when, and the PHY the link moved to. */
    private data class Step(
        val at: Long,
        val to: LinkPhy,
    )

    /**
     * Drives a stepper in AUTO as `BlePhyControl` does: a link-RSSI read at [PhyStepper.nextReadMs]' cadence from t=0
     * (5 s, 2 s at the edge), one decision per read, and each request answered [ANSWER_MS] later (a step-up with 2M).
     * [link] is the link RSSI at a time; the link opened on 2M.
     */
    private fun drive(
        tuning: PhyTuning,
        untilMs: Long,
        link: (Long) -> Int,
    ): Pair<PhyStepper, List<Step>> {
        val s = PhyStepper { tuning }.also { it.onPhy(LinkPhy.TWO_M, succeeded = true, now = 0) }
        val steps = mutableListOf<Step>()
        var t = 0L
        while (t < untilMs) {
            s.onRssi(link(t), now = t)
            val to =
                when (s.decide(CodedPhyMode.AUTO, now = t)) {
                    Action.REQUEST_CODED -> LinkPhy.CODED
                    Action.REQUEST_FAST -> LinkPhy.TWO_M
                    else -> null
                }
            if (to != null && s.onPhy(to, succeeded = true, now = t + ANSWER_MS)) steps += Step(t + ANSWER_MS, to)
            t += s.nextReadMs()
        }
        return s to steps
    }

    /** A link at −58 that reads each segment's RSSI from the segment's start (ms) until the next segment's. */
    private fun walk(vararg segments: Pair<Long, Int>): (Long) -> Int =
        { t -> segments.lastOrNull { (from, _) -> t >= from }?.second ?: -58 }

    /** Today's rule: no step-down is ever inside the flap window, so the backoff never leaves the minimum gap. */
    private val noBackoff = PhyTuning(flapWindowMs = 0)

    @Test
    fun aLinkThatFlapsIsHeldOnCodedLongerEachTimeAndStepsHalfAsOften() {
        // #169's shape: a link at −58 that dips to −90 for 10 s every 2 min, as a phone swung between a clear and a
        // body-blocked path. Every other dip trips the three weak reads, so today's rule steps every 2 min each way.
        val dips: (Long) -> Int = { t -> if (t >= 60_000 && (t - 60_000) % 120_000 < 10_000) -90 else -58 }
        val thirtyMin = 30 * 60_000L
        val (_, today) = drive(noBackoff, thirtyMin, dips)
        assertEquals(16, today.size)
        val (s, now) = drive(tuning, thirtyMin, dips)
        // Each reversal held the link on Coded twice as long — 40 s, 80 s, 160 s, then the 5 min cap — before it
        // could step up, so it skips the dips that trip in between. At the cap a cycle is at least the 5 min on Coded
        // and the 20 s gap on 2M: no more than 2 steps in 5 min 20 s, where the minimum gap alone allowed 2 in 40 s.
        assertEquals(11, now.size)
        assertEquals(tuning.stepUpBackoffMaxMs, s.stepUpBackoffMs)
        // Every step-down still came on the read today's rule would have taken at that point of the series.
        now.filter { it.to == LinkPhy.CODED }.forEach { step -> assertTrue(step in today) }
        // Over an hour the gap widens: 30 steps today, 19 with the backoff.
        assertEquals(30, drive(noBackoff, 2 * thirtyMin, dips).second.size)
        assertEquals(19, drive(tuning, 2 * thirtyMin, dips).second.size)
    }

    @Test
    fun aSingleWalkAwayAndReturnStepsOnceEachWayAsToday() {
        val away = walk(120_000L to -95, 420_000L to -58)
        val (s, steps) = drive(tuning, 900_000, away)
        assertEquals(listOf(Step(126_300, LinkPhy.CODED), Step(442_300, LinkPhy.TWO_M)), steps)
        assertEquals(drive(noBackoff, 900_000, away).second, steps)
        assertEquals(0, s.reversals)
    }

    @Test
    fun aSecondWalkAwaySoonAfterAReturnIsNeverDelayed() {
        // Back at 300 s, away again at 340 s for 20 s: the second step-down is a reversal.
        val twice = walk(120_000L to -95, 300_000L to -58, 340_000L to -95, 360_000L to -58)
        val today = drive(noBackoff, 900_000, twice).second
        val (s, steps) = drive(tuning, 900_000, twice)
        assertEquals(1, s.reversals)
        // The step-downs land on the same reads as today's rule's: the minimum gap after the step-up, nothing more.
        assertEquals(today.filter { it.to == LinkPhy.CODED }, steps.filter { it.to == LinkPhy.CODED })
        assertEquals(Step(348_300, LinkPhy.CODED), steps[2])
        // Only the step-up after it waits: 40 s on Coded (to 388.3 s), met on the next read, where today's came at 382.3 s.
        assertEquals(Step(382_300, LinkPhy.TWO_M), today[3])
        assertEquals(Step(392_300, LinkPhy.TWO_M), steps[3])
    }

    @Test
    fun theBackoffDoublesPerReversalStopsAtTheCapAndResetsAfterAFastStretch() {
        val s = PhyStepper { tuning }.also { it.onPhy(LinkPhy.TWO_M, succeeded = true, now = 0) }
        var t = 0L

        // Down, then up `onCoded` later, then down again `fast` after that.
        fun cycle(
            onCoded: Long,
            fast: Long,
        ) {
            t += fast
            s.onPhy(LinkPhy.CODED, succeeded = true, now = t)
            t += onCoded
            s.onPhy(LinkPhy.TWO_M, succeeded = true, now = t)
        }
        cycle(onCoded = 400_000, fast = 60_000) // the first step-down follows no step-up
        assertEquals(20_000, s.stepUpBackoffMs)
        val backoffs =
            (1..6).map {
                cycle(onCoded = 400_000, fast = 60_000)
                s.stepUpBackoffMs
            }
        assertEquals(listOf(40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L), backoffs)
        assertEquals(6, s.reversals)
        // A step-down a whole flap window after the last step-up: the link held fast, and the backoff starts over.
        cycle(onCoded = 400_000, fast = tuning.flapWindowMs)
        assertEquals(20_000, s.stepUpBackoffMs)
        assertEquals(6, s.reversals)
    }

    @Test
    fun aStrongCodedLinkWaitsOutTheBackoffThenStepsUp() {
        val s = PhyStepper { tuning }.also { it.onPhy(LinkPhy.TWO_M, succeeded = true, now = 0) }
        s.onPhy(LinkPhy.CODED, succeeded = true, now = 10_000)
        s.onPhy(LinkPhy.TWO_M, succeeded = true, now = 40_000)
        s.onPhy(LinkPhy.CODED, succeeded = true, now = 100_000) // a reversal: 40 s on Coded first
        (101_000L..140_000L step 1_000L).forEach { s.onRssi(-50, now = it) }
        // The fast hold is met at 111 s and the minimum gap at 120 s; the backoff runs to 140 s.
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 139_999))
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 140_000))
    }

    @Test
    fun aLinkThatOpenedOnCodedHasNoBackoff() {
        val s = stepper(LinkPhy.CODED, -55)
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 10_000))
    }

    @Test
    fun pinnedModesIgnoreTheBackoff() {
        val s = PhyStepper { tuning }.also { it.onPhy(LinkPhy.TWO_M, succeeded = true, now = 0) }
        s.onPhy(LinkPhy.CODED, succeeded = true, now = 10_000)
        s.onPhy(LinkPhy.TWO_M, succeeded = true, now = 40_000)
        s.onPhy(LinkPhy.CODED, succeeded = true, now = 100_000)
        assertEquals(40_000, s.stepUpBackoffMs)
        s.onRssi(-50, now = 101_000)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.CODED, now = 101_000))
        assertEquals(Action.REQUEST_ONE_M, s.decide(CodedPhyMode.ONE_M, now = 101_000))
        assertTrue(s.onPhy(LinkPhy.ONE_M, succeeded = true, now = 101_300))
        // Straight back down under CODED, inside the flap window and the minimum gap alike.
        assertEquals(Action.REQUEST_CODED, s.decide(CodedPhyMode.CODED, now = 102_000))
    }

    private companion object {
        /** How long [drive]'s controller takes to answer a PHY request (the spike measured 5–700 ms). */
        const val ANSWER_MS = 300L
    }
}
