package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.LinkBoostPolicy
import app.getknit.knit.mesh.bluetooth.LinkBoostPolicy.Ask
import app.getknit.knit.mesh.bluetooth.LinkBoostPolicy.LINGER_MS
import app.getknit.knit.mesh.bluetooth.LinkBoostPolicy.SETTLE_MS
import app.getknit.knit.mesh.bluetooth.LinkBoostPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [LinkBoostPolicy] — when a Bluetooth link asks for a short interval, and when it has earned it (#120). */
class LinkBoostPolicyTest {
    private val high = Step(Ask.HIGH)
    private val highWithMtu = Step(Ask.HIGH, mtu = true)
    private val release = Step(Ask.BALANCED)

    private fun feeding(
        boost: LinkBoostPolicy.Boost,
        now: Long,
        receiving: Boolean = false,
    ) = boost.onState(feeding = true, receiving = receiving, eligible = true, now = now)

    private fun idle(
        boost: LinkBoostPolicy.Boost,
        now: Long,
        eligible: Boolean = true,
    ) = boost.onState(feeding = false, receiving = false, eligible = eligible, now = now)

    @Test
    fun aDarkScreenStillListensButNeverAsks() {
        // A sender in a pocket earns the receiver's ask only if its client is there to hear it.
        assertTrue(LinkBoostPolicy.observes(enabled = true, doorbell = false, coded = false))
        assertFalse(LinkBoostPolicy.observes(enabled = false, doorbell = false, coded = false))
        assertFalse(LinkBoostPolicy.observes(enabled = true, doorbell = true, coded = false))
        assertFalse(LinkBoostPolicy.observes(enabled = true, doorbell = false, coded = true))
    }

    @Test
    fun aLinkIsEligibleOnlyWithTheBuildOnTheScreenOnAndNeitherAnIphoneNorCoded() {
        assertTrue(LinkBoostPolicy.eligible(enabled = true, doorbell = false, coded = false, interactive = true))
        assertFalse("the flag off", LinkBoostPolicy.eligible(enabled = false, doorbell = false, coded = false, interactive = true))
        assertFalse(
            "an iPhone: its doorbell owns the parameters",
            LinkBoostPolicy.eligible(true, doorbell = true, coded = false, interactive = true),
        )
        assertFalse("Coded keeps its own pace", LinkBoostPolicy.eligible(true, doorbell = false, coded = true, interactive = true))
        assertFalse(
            "a dark screen: the linger would sleep",
            LinkBoostPolicy.eligible(true, doorbell = false, coded = false, interactive = false),
        )
        assertFalse(
            "Bluetooth audio streaming: A2DP takes the air",
            LinkBoostPolicy.eligible(true, doorbell = false, coded = false, interactive = true, audio = true),
        )
    }

    @Test
    fun aRisingFeedAsksForHighOnceWithTheMtuTheFirstTimeOnly() {
        val boost = LinkBoostPolicy.Boost()
        assertEquals(highWithMtu, feeding(boost, 0))
        assertTrue(boost.boosting)
        assertEquals("still feeding: asked already", Step.NONE, feeding(boost, 100))
        idle(boost, 1_000)
        assertEquals(release, idle(boost, 1_000 + LINGER_MS))
        assertEquals("the next episode asks again, without the MTU", high, feeding(boost, 30_000))
    }

    @Test
    fun aReceiverWithItsScreenOnAsksTooAndADarkOneDoesNot() {
        // Whoever is looking at the phone speeds up the file: a receiver watching the chat boosts a sender in a pocket.
        val lit = LinkBoostPolicy.Boost()
        assertEquals(highWithMtu, lit.onState(feeding = false, receiving = true, eligible = true, now = 0))
        assertTrue(lit.boosting)
        val dark = LinkBoostPolicy.Boost()
        assertEquals(Step.NONE, dark.onState(feeding = false, receiving = true, eligible = false, now = 0))
        assertFalse(dark.boosting)
        assertNull(dark.dueAt())
    }

    @Test
    fun aFlapInsideTheLingerNeitherReleasesNorAsksAgain() {
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        boost.onParams(15_000, ok = true, now = 300)
        assertEquals(Step.NONE, idle(boost, 5_000))
        assertEquals(5_000 + LINGER_MS, boost.dueAt())
        assertEquals("busy again before the linger runs out", Step.NONE, feeding(boost, 5_000 + LINGER_MS - 1))
        assertNull("nothing due while it streams and has earned", boost.dueAt())
        assertTrue(boost.boosting)
        assertTrue(boost.earned)
    }

    @Test
    fun idlePastTheLingerReleasesOnceAndClearsEarnedAtOnce() {
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        boost.onParams(15_000, ok = true, now = 300)
        idle(boost, 10_000)
        assertEquals(Step.NONE, boost.onTick(10_000 + LINGER_MS - 1))
        assertEquals(release, boost.onTick(10_000 + LINGER_MS))
        assertFalse(boost.boosting)
        assertFalse("the pace drops at its next chunk, ahead of the update", boost.earned)
        boost.onParams(45_000, ok = true, now = 10_000 + LINGER_MS + 300)
        assertNull("the update answered the release", boost.dueAt())
        assertEquals(Step.NONE, boost.onTick(60_000))
    }

    @Test
    fun receivingHoldsTheReleaseOfABoostThisSideAskedFor() {
        // The peer feeds back while this side's own feed has ended: letting go would cut the peer's feed.
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        boost.onParams(15_000, ok = true, now = 300)
        assertEquals(Step.NONE, boost.onState(feeding = false, receiving = true, eligible = true, now = 5_000))
        assertEquals(Step.NONE, boost.onState(feeding = false, receiving = true, eligible = true, now = 5_000 + 2 * LINGER_MS))
        assertTrue(boost.boosting)
        idle(boost, 60_000)
        assertEquals(release, boost.onTick(60_000 + LINGER_MS))
    }

    @Test
    fun aDarkScreenOrACodedStepReleasesAtOnce() {
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        boost.onParams(15_000, ok = true, now = 300)
        assertEquals(release, boost.onState(feeding = true, receiving = false, eligible = false, now = 1_000))
        assertFalse(boost.earned)
        assertEquals("ineligible: no new ask while it feeds", Step.NONE, boost.onState(true, false, eligible = false, now = 2_000))
        assertEquals("eligible again mid-feed: asks again", high, feeding(boost, 3_000))
    }

    @Test
    fun aShortIntervalEarnsWhoeverAskedForIt() {
        // A sender with its screen off asks nothing, and hears the receiver's update on its own client: it feeds faster.
        val boost = LinkBoostPolicy.Boost()
        assertEquals(Step.NONE, boost.onState(feeding = true, receiving = false, eligible = false, now = 0))
        assertEquals(Step.NONE, boost.onParams(15_000, ok = true, now = 300))
        assertTrue(boost.earned)
        assertFalse(boost.boosting)
        boost.onParams(45_000, ok = true, now = 20_000)
        assertFalse("the receiver let go", boost.earned)
        assertTrue("7.5 ms (Android 12's HIGH) earns too", LinkBoostPolicy.Boost().apply { onParams(7_500, ok = true, now = 0) }.earned)
        assertFalse(LinkBoostPolicy.Boost().apply { onParams(30_000, ok = true, now = 0) }.earned)
    }

    @Test
    fun aSlowerReportWhileStreamingUnearnsAndAsksAgainAtMostTwice() {
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        boost.onParams(15_000, ok = true, now = 300)
        assertEquals("a put-back", high, boost.onParams(45_000, ok = true, now = 1_000))
        assertFalse(boost.earned)
        assertEquals(high, boost.onParams(45_000, ok = true, now = 2_000))
        assertEquals("spent: the peer is not fought", Step.NONE, boost.onParams(45_000, ok = true, now = 3_000))
        assertEquals(Step.NONE, boost.onTick(3_000 + SETTLE_MS))
        assertTrue("still boosting: the release comes after the linger", boost.boosting)
    }

    @Test
    fun aFailedUpdateCountsForNothing() {
        // 0x2A: two asks collided, the link did not move.
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        boost.onParams(15_000, ok = true, now = 300)
        assertEquals(Step.NONE, boost.onParams(45_000, ok = false, now = 400))
        assertTrue(boost.earned)
        assertNull(boost.dueAt())
    }

    @Test
    fun theSettleCheckAsksAgainWhenNoReportCame() {
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        assertEquals(SETTLE_MS, boost.dueAt())
        assertEquals(Step.NONE, boost.onTick(SETTLE_MS - 1))
        assertEquals(high, boost.onTick(SETTLE_MS))
        assertEquals(2 * SETTLE_MS, boost.dueAt())
        assertEquals(high, boost.onTick(2 * SETTLE_MS))
        assertEquals(Step.NONE, boost.onTick(3 * SETTLE_MS))
        assertNull("spent, and still streaming: nothing due", boost.dueAt())
        assertFalse(boost.earned)
    }

    @Test
    fun aSilentReleaseIsAskedAgainAndAnAnsweredOneIsNot() {
        val silent = LinkBoostPolicy.Boost()
        feeding(silent, 0)
        silent.onParams(15_000, ok = true, now = 300)
        idle(silent, 1_000)
        val releasedAt = 1_000 + LINGER_MS
        assertEquals(release, silent.onTick(releasedAt))
        assertEquals(releasedAt + SETTLE_MS, silent.dueAt())
        assertEquals(release, silent.onTick(releasedAt + SETTLE_MS))
        assertEquals(release, silent.onTick(releasedAt + 2 * SETTLE_MS))
        assertEquals(Step.NONE, silent.onTick(releasedAt + 3 * SETTLE_MS))
        assertNull(silent.dueAt())

        val busyAgain = LinkBoostPolicy.Boost()
        feeding(busyAgain, 0)
        idle(busyAgain, 1_000)
        busyAgain.onTick(releasedAt)
        assertEquals(
            "the peer's file is coming in: boosted again, not released",
            high,
            busyAgain.onState(feeding = false, receiving = true, eligible = true, now = releasedAt + 100),
        )
        assertEquals(Step.NONE, busyAgain.onTick(releasedAt + SETTLE_MS))
    }

    @Test
    fun aReleaseThePeersAskAnsweredIsNeverAskedAgainBetweenItsFiles() {
        // The release collided with the peer's HIGH (the link reports fast), then the peer feeds a burst of files.
        // Between two files nothing streams, and a release asked again there would cut the peer's 15 ms each time.
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        idle(boost, 1_000, eligible = false)
        assertEquals(Step.NONE, boost.onParams(15_000, ok = true, now = 1_300))
        assertEquals(Step.NONE, boost.onState(feeding = false, receiving = true, eligible = false, now = 2_000))
        assertEquals("a gap between the peer's files", Step.NONE, idle(boost, 5_000, eligible = false))
        assertEquals(Step.NONE, boost.onTick(5_000 + SETTLE_MS))
        assertNull(boost.dueAt())
    }

    @Test
    fun anUnansweredReleaseIsNotAskedAgainOnceAFileHasStartedSince() {
        // Released on an idle link with no report at all (it was already slow), then the peer's next file, then a gap:
        // the release is over, so it never cuts a feed that began after it.
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        idle(boost, 1_000, eligible = false)
        boost.onState(feeding = false, receiving = true, eligible = false, now = 1_500)
        assertEquals(Step.NONE, idle(boost, 10_000, eligible = false))
        assertNull(boost.dueAt())
    }

    @Test
    fun aReleaseMadeUnderThePeersFeedIsRetriedWhenThatFeedEnds() {
        // The screen goes off while the peer still feeds, and the release fails (0x2A, or a central's refusal). The
        // pocketed peer never asks, so this retry is the only thing that puts the link back.
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        assertEquals(release, boost.onState(feeding = false, receiving = true, eligible = false, now = 1_000))
        assertEquals(Step.NONE, boost.onParams(15_000, ok = false, now = 1_200))
        assertEquals("still receiving: no retry under the feed", Step.NONE, boost.onState(false, true, eligible = false, now = 1_500))
        assertEquals("the feed ends: retried", release, idle(boost, 5_000, eligible = false))
    }

    @Test
    fun aDarkScreenLetsGoEvenUnderThePeersFeed() {
        // A dark phone cannot be trusted to let go later; a peer still streaming with its screen on asks again.
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        boost.onParams(15_000, ok = true, now = 300)
        assertEquals(release, boost.onState(feeding = false, receiving = true, eligible = false, now = 5_000))
        assertFalse(boost.earned)
    }

    @Test
    fun losingTheClientThatHeardTheLinkUnearnsIt() {
        // Nothing would hear the next update: a link put back at 45 ms would go on being fed at the boosted budget.
        val boost = LinkBoostPolicy.Boost()
        boost.onParams(15_000, ok = true, now = 0)
        assertTrue(boost.earned)
        boost.forget()
        assertFalse(boost.earned)
    }

    @Test
    fun aDarkScreenStillGetsItsReleaseAskedAgain() {
        // A release on the screen-off edge is exactly the one that must land: the linger cannot wake the CPU later.
        val boost = LinkBoostPolicy.Boost()
        feeding(boost, 0)
        assertEquals(release, idle(boost, 1_000, eligible = false))
        assertEquals(release, idle(boost, 1_000 + SETTLE_MS, eligible = false))
    }
}
