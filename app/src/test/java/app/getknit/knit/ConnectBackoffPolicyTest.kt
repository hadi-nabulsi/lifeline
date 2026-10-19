package app.getknit.knit

import app.getknit.knit.mesh.ConnectFailReason
import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.bluetooth.ConnectBackoffEntry
import app.getknit.knit.mesh.bluetooth.ConnectBackoffPolicy
import app.getknit.knit.mesh.bluetooth.ConnectBackoffs
import app.getknit.knit.mesh.bluetooth.ConnectFailClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** Unit tests for [ConnectBackoffPolicy] — the pure escalating per-peer connect backoff curve. */
class ConnectBackoffPolicyTest {
    private val cfg = BackoffConfig(baseMs = 10_000, maxMs = 180_000, jitterFraction = 0.2)

    // rand()=0.5 → the offset is zero, so the delay is exactly the un-jittered value (easy to assert).
    private val noJitter = { 0.5 }

    @Test
    fun doublesPerStreak() {
        assertEquals(10_000, ConnectBackoffPolicy.nextDelayMs(1, cfg, noJitter))
        assertEquals(20_000, ConnectBackoffPolicy.nextDelayMs(2, cfg, noJitter))
        assertEquals(40_000, ConnectBackoffPolicy.nextDelayMs(3, cfg, noJitter))
        assertEquals(80_000, ConnectBackoffPolicy.nextDelayMs(4, cfg, noJitter))
        assertEquals(160_000, ConnectBackoffPolicy.nextDelayMs(5, cfg, noJitter))
    }

    @Test
    fun saturatesAtMax() {
        assertEquals(180_000, ConnectBackoffPolicy.nextDelayMs(6, cfg, noJitter))
        assertEquals(180_000, ConnectBackoffPolicy.nextDelayMs(7, cfg, noJitter))
        assertEquals(180_000, ConnectBackoffPolicy.nextDelayMs(50, cfg, noJitter)) // no Long overflow on a big streak
    }

    @Test
    fun streakBelowOneIsTreatedAsTheFirstFailure() {
        assertEquals(10_000, ConnectBackoffPolicy.nextDelayMs(0, cfg, noJitter))
        assertEquals(10_000, ConnectBackoffPolicy.nextDelayMs(-5, cfg, noJitter))
    }

    @Test
    fun jitterSpansPlusMinusTheConfiguredFraction() {
        // rand()=0 → −20 %, rand()→1 → +20 % of the un-jittered delay.
        assertEquals(8_000, ConnectBackoffPolicy.nextDelayMs(1, cfg) { 0.0 })
        assertEquals(12_000, ConnectBackoffPolicy.nextDelayMs(1, cfg) { 1.0 })
    }

    @Test
    fun theEngineSleepsToTheEarliestDeadlineWithinItsBounds() {
        // Nothing backed off → the ceiling (an event wakes it sooner); a deadline ahead → exactly that far;
        // one that just passed → the floor, never a spin; one far out → the ceiling again.
        assertEquals(60_000, ConnectBackoffPolicy.nextDueWaitMs(now = 100_000, nextDueAt = null, minMs = 1_000, maxMs = 60_000))
        assertEquals(7_500, ConnectBackoffPolicy.nextDueWaitMs(now = 100_000, nextDueAt = 107_500, minMs = 1_000, maxMs = 60_000))
        assertEquals(1_000, ConnectBackoffPolicy.nextDueWaitMs(now = 100_000, nextDueAt = 99_000, minMs = 1_000, maxMs = 60_000))
        assertEquals(60_000, ConnectBackoffPolicy.nextDueWaitMs(now = 100_000, nextDueAt = 400_000, minMs = 1_000, maxMs = 60_000))
    }

    @Test
    fun jitterStaysWithinBoundsAcrossTheRange() {
        for (r in listOf(0.0, 0.1, 0.5, 0.9, 0.999)) {
            val d = ConnectBackoffPolicy.nextDelayMs(3, cfg) { r }
            assertTrue("delay $d within ±20% of 40s", d in 32_000..48_000)
        }
    }

    @Test
    fun eachFailureExtendsThePeersStreakAndDeadlineFromNow() {
        val book = HashMap<String, ConnectBackoffEntry>()
        assertEquals(1 to 1_010_000L, ConnectBackoffs.bump(book, "a", now = 1_000_000, config = cfg, rand = noJitter))
        assertEquals(2 to 2_020_000L, ConnectBackoffs.bump(book, "a", now = 2_000_000, config = cfg, rand = noJitter))
        assertEquals("another peer has its own streak", 1 to 2_010_000L, ConnectBackoffs.bump(book, "b", 2_000_000, cfg, noJitter))
    }

    @Test
    fun aPeerBacksOffUntilItsDeadline() {
        val book = hashMapOf("a" to ConnectBackoffEntry(1, nextAt = 100), "b" to ConnectBackoffEntry(1, nextAt = 200))
        assertEquals(setOf("a", "b"), ConnectBackoffs.active(book, now = 99))
        assertEquals("due at its deadline", setOf("b"), ConnectBackoffs.active(book, now = 100))
        assertEquals(emptySet<String>(), ConnectBackoffs.active(book, now = 200))
    }

    @Test
    fun aPassedPeerThatLeftForgetsItsStreakAndOneStillHeldKeepsIt() {
        val book =
            hashMapOf(
                "gone" to ConnectBackoffEntry(5, nextAt = 100),
                "present" to ConnectBackoffEntry(5, nextAt = 100),
                "waiting" to ConnectBackoffEntry(5, nextAt = 500),
            )
        ConnectBackoffs.prune(book, now = 200) { it == "present" }
        assertEquals(setOf("present", "waiting"), book.keys)
    }

    @Test
    fun theNextDueDeadlineIsTheEarliestStillAhead() {
        val book = hashMapOf("a" to ConnectBackoffEntry(1, 100), "b" to ConnectBackoffEntry(1, 300), "c" to ConnectBackoffEntry(1, 200))
        assertEquals(200L, ConnectBackoffs.nextDueAt(book, now = 100))
        assertNull(ConnectBackoffs.nextDueAt(book, now = 300))
        assertNull(ConnectBackoffs.nextDueAt(emptyMap(), now = 0))
    }

    @Test
    fun aConnectExceptionIsBucketedByItsTypeOrMessage() {
        assertEquals(ConnectFailReason.TIMEOUT, ConnectFailClassifier.classify(SocketTimeoutException("read timed out")))
        assertEquals(ConnectFailReason.RADIO, ConnectFailClassifier.classify(IOException("Radio not ready")))
        assertEquals(ConnectFailReason.RADIO, ConnectFailClassifier.classify(IOException("ENOMEM")))
        assertEquals(ConnectFailReason.RADIO, ConnectFailClassifier.classify(IOException("No resources")))
        assertEquals(ConnectFailReason.RADIO, ConnectFailClassifier.classify(IOException("device busy")))
        assertEquals(ConnectFailReason.REFUSED, ConnectFailClassifier.classify(IOException("Connection refused")))
        assertEquals(ConnectFailReason.REFUSED, ConnectFailClassifier.classify(IOException("connection reset by peer")))
        assertEquals(ConnectFailReason.OTHER, ConnectFailClassifier.classify(IOException("read failed, socket might closed")))
        assertEquals(ConnectFailReason.OTHER, ConnectFailClassifier.classify(IOException()))
        assertEquals(ConnectFailReason.OTHER, ConnectFailClassifier.classify(null))
    }

    @Test
    fun aDialBeginsOnlyToAnUnlinkedPeerWithNoDialInFlightAndNoBackoff() {
        val book = hashMapOf("waiting" to ConnectBackoffEntry(1, nextAt = 500), "due" to ConnectBackoffEntry(3, nextAt = 100))
        assertTrue(ConnectBackoffs.mayBegin(book, "new", emptySet(), emptySet(), now = 200))
        assertTrue("its window has passed", ConnectBackoffs.mayBegin(book, "due", emptySet(), emptySet(), now = 100))
        assertFalse("backing off", ConnectBackoffs.mayBegin(book, "waiting", emptySet(), emptySet(), now = 499))
        assertFalse("linked", ConnectBackoffs.mayBegin(book, "new", setOf("new"), emptySet(), now = 200))
        assertFalse("a dial in flight", ConnectBackoffs.mayBegin(book, "new", emptySet(), setOf("new"), now = 200))
    }

    @Test
    fun aDialRefusedBecauseThePeersOwnDialLinkedFirstIsNoFailure() {
        val book = HashMap<String, ConnectBackoffEntry>()
        assertEquals(0 to 1_000L, ConnectBackoffs.onDialFailed(book, "a", linkedNow = true, now = 1_000, config = cfg, rand = noJitter))
        assertTrue("no streak recorded", book.isEmpty())
        assertEquals(1 to 11_000L, ConnectBackoffs.onDialFailed(book, "a", linkedNow = false, now = 1_000, config = cfg, rand = noJitter))
    }

    @Test
    fun aLinkThatEndedFlapsUnlessAFreshLinkFromThePeerReplacedIt() {
        assertTrue("its own link went", ConnectBackoffs.flapped(released = true, replaced = false))
        assertTrue("released, and the peer relinked since", ConnectBackoffs.flapped(released = true, replaced = true))
        assertTrue("already gone, nothing holds the slot", ConnectBackoffs.flapped(released = false, replaced = false))
        assertFalse("a fresh link holds the slot", ConnectBackoffs.flapped(released = false, replaced = true))
    }
}
