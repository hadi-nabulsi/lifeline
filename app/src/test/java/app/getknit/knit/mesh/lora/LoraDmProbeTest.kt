package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** ADR 2026-10.a4pm: one probe per recipient per window, handed back when its frame never queued. */
class LoraDmProbeTest {
    private val window = 1_000L

    @Test
    fun oneProbePerRecipientPerWindow() {
        val probes = LoraDmProbe(window)
        assertNotNull(probes.claim("bob", now = 0))
        assertNull("bob's window is spent", probes.claim("bob", now = window - 1))
        assertNotNull("someone else's is not", probes.claim("dave", now = 10))
        assertNotNull("the window ends", probes.claim("bob", now = window))
    }

    @Test
    fun aReleasedClaimGivesTheWindowBack() {
        val probes = LoraDmProbe(window)
        val first = probes.claim("bob", now = 0)!!
        probes.release(first)
        val second = probes.claim("bob", now = 10)
        assertNotNull("a frame that never queued spent nothing", second)
        assertNull(probes.claim("bob", now = 20))
    }

    @Test
    fun aReleaseRestoresTheWindowBeforeItRatherThanOpeningIt() {
        val probes = LoraDmProbe(window)
        probes.claim("bob", now = 0)!!
        val later = probes.claim("bob", now = window + 10)!!
        probes.release(later)
        assertNotNull("the old stamp has expired, so the window is open", probes.claim("bob", now = window + 20))
    }

    @Test
    fun aStaleReleaseDoesNotUndoANewerClaim() {
        val probes = LoraDmProbe(window)
        val old = probes.claim("bob", now = 0)!!
        probes.claim("bob", now = window + 10)!!
        probes.release(old)
        assertNull("the newer claim still holds the window", probes.claim("bob", now = window + 20))
    }

    @Test
    fun stampsRoundTripAndSkipWhatTheWindowOutlived() {
        val probes = LoraDmProbe(window)
        probes.claim("bob", now = 0)
        probes.claim("dave", now = 500)
        assertEquals(listOf("bob" to 0L, "dave" to 500L), probes.stamps(now = 600))
        assertEquals("bob's expired", listOf("dave" to 500L), probes.stamps(now = window + 1))

        val restored = LoraDmProbe(window)
        restored.restore(listOf("bob" to 0L, "dave" to 500L), now = window + 1)
        assertNotNull("an expired stamp is not restored", restored.claim("bob", now = window + 2))
        assertNull("a live one is", restored.claim("dave", now = window + 2))
    }

    @Test
    fun twoSendersRacingForOneWindowGetOneProbe() {
        val probes = LoraDmProbe(window)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val won = AtomicInteger()
        repeat(64) {
            pool.execute {
                start.await()
                if (probes.claim("bob", now = 5) != null) won.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals(1, won.get())
    }
}
