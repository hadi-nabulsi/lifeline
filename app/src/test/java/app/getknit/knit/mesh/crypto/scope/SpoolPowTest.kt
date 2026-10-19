package app.getknit.knit.mesh.crypto.scope

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Behavior anchors for [SpoolPow]: the stamp digest is recomputed independently, difficulty counting
 * is pinned bit-by-bit, and mining is proven deterministic (smallest counter first — what the spec
 * vector in [ScopeVectorTest] relies on). docs/SPOOL_PROTOCOL.md §8 is the normative spec.
 */
class SpoolPowTest {
    @Test
    fun digestMatchesAnIndependentRecompute() {
        val scopeId = ByteArray(32) { ((it * 7 + 9) and 0xFF).toByte() }
        val day = 20_680L
        val n = 42L

        val expected =
            MessageDigest.getInstance("SHA-256").digest(
                "knit/spool/v1/pow".toByteArray() + scopeId +
                    byteArrayOf(0, 0, 0, 0, 0, 0, 0x50, 0xC8.toByte()) +
                    byteArrayOf(0, 0, 0, 0, 0, 0, 0, 42),
            )
        assertArrayEquals(expected, SpoolPow.digest(scopeId, day, n))
    }

    @Test
    fun leadingZeroBitsCountsBitLexicographically() {
        assertEquals(0, SpoolPow.leadingZeroBits(byteArrayOf(0x80.toByte(), 0)))
        assertEquals(7, SpoolPow.leadingZeroBits(byteArrayOf(0x01, 0xFF.toByte())))
        assertEquals(8, SpoolPow.leadingZeroBits(byteArrayOf(0x00, 0xFF.toByte())))
        assertEquals(17, SpoolPow.leadingZeroBits(byteArrayOf(0, 0, 0x40, 0)))
        assertEquals(32, SpoolPow.leadingZeroBits(ByteArray(4)))
    }

    @Test
    fun miningFindsTheSmallestValidCounterAndItVerifies() {
        val scopeId = ByteArray(32) { ((it * 7 + 9) and 0xFF).toByte() }
        val day = 20_680L

        val n = SpoolPow.stamp(scopeId, day, bits = 8, maxAttempts = 1_000_000)

        assertNotNull(n)
        assertTrue(SpoolPow.verify(scopeId, day, n!!, bits = 8))
        for (earlier in 0 until n) {
            assertFalse(SpoolPow.verify(scopeId, day, earlier, bits = 8))
        }
    }

    @Test
    fun exhaustedBudgetReturnsNullAndZeroBitsAcceptsEverything() {
        val scopeId = ByteArray(32) { 1 }

        assertNull(SpoolPow.stamp(scopeId, day = 1L, bits = 256, maxAttempts = 64))
        assertTrue(SpoolPow.verify(scopeId, day = 1L, n = 0L, bits = 0))
        assertTrue(SpoolPow.verify(scopeId, day = 1L, n = 0L, bits = -1))
    }

    @Test
    fun utcDayFloorsTheEpochMillis() {
        assertEquals(0L, SpoolPow.utcDay(0L))
        assertEquals(0L, SpoolPow.utcDay(SpoolPow.DAY_MS - 1))
        assertEquals(1L, SpoolPow.utcDay(SpoolPow.DAY_MS))
        assertEquals(-1L, SpoolPow.utcDay(-1L))
    }

    @Test
    fun theMinerCountsWhatTheReferenceDigestCounts() {
        val random = Random(7)
        // Prefix lengths around every block edge the counter can straddle, §8's 57 bytes among them.
        for (scopeLength in listOf(0, 1, 7, 8, 32, 38, 39, 40, 46, 47, 48, 100, 111, 112)) {
            val scopeId = random.nextBytes(scopeLength)
            val day = random.nextLong(0, 100_000)
            val miner = PowMiner("knit/spool/v1/pow".toByteArray() + scopeId + u64be(day))
            // Runs across the 256- and 65 536-counter edges where the cached state turns over, then far counters.
            val counters = (0L..600L) + (65_000L..66_000L) + listOf(Long.MAX_VALUE, -1L, 1L shl 40) + List(200) { random.nextLong() }
            for (n in counters) {
                assertEquals(
                    "scope ${scopeId.size} B, n=$n",
                    SpoolPow.leadingZeroBits(SpoolPow.digest(scopeId, day, n)),
                    miner.leadingZeroBits(n),
                )
            }
        }
    }

    @Test
    fun miningMatchesTheReferenceLoop() {
        val random = Random(11)
        repeat(8) {
            val scopeId = random.nextBytes(32)
            val day = random.nextLong(0, 100_000)
            val bits = 6 + it % 5
            val startN = random.nextLong(0, 1_000)
            var expected = startN
            while (!SpoolPow.verify(scopeId, day, expected, bits)) expected++
            assertEquals(expected, SpoolPow.stamp(scopeId, day, bits, maxAttempts = 1_000_000, startN = startN))
        }
        assertEquals(5L, SpoolPow.stamp(ByteArray(32), day = 1L, bits = 0, maxAttempts = 1, startN = 5L))
        assertNull(SpoolPow.stamp(ByteArray(32), day = 1L, bits = 0, maxAttempts = 0))
    }

    @Test
    fun miningAllocatesNothingPerAttempt() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val scopeId = ByteArray(32) { 3 }
        SpoolPow.stamp(scopeId, day = 1L, bits = 256, maxAttempts = 10_000) // warm up
        val before = threads.currentThreadAllocatedBytes
        assertNull(SpoolPow.stamp(scopeId, day = 1L, bits = 256, maxAttempts = 200_000))
        val allocated = threads.currentThreadAllocatedBytes - before
        // The miner's own buffers are well under 1 KiB; one allocation per attempt would be megabytes.
        assertTrue("allocated $allocated bytes over 200 000 attempts", allocated < 64 * 1024)
    }

    @Test
    fun aStampIsReusedThroughTheSpoolsWindowLessTheMargin() {
        val day = 20_000L
        val start = day * SpoolPow.DAY_MS
        assertTrue("the day it was minted", SpoolPow.reusable(day, start))
        assertTrue("all of the next day, which a spool still takes", SpoolPow.reusable(day, start + SpoolPow.DAY_MS + 3_600_000L))
        val closes = (day + 2) * SpoolPow.DAY_MS - SpoolPow.REUSE_MARGIN_MS
        assertTrue(SpoolPow.reusable(day, closes - 1))
        assertFalse("inside the margin before the window closes", SpoolPow.reusable(day, closes))
        assertFalse("two days on", SpoolPow.reusable(day, start + 2 * SpoolPow.DAY_MS))
        assertTrue("a day ahead is still in a spool's window", SpoolPow.reusable(day + 1, start))
        assertFalse("further ahead is not (a clock set back)", SpoolPow.reusable(day + 2, start))
    }

    private fun u64be(value: Long) = ByteArray(8) { (value ushr ((7 - it) * 8)).toByte() }
}
