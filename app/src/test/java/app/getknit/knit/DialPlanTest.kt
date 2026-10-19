package app.getknit.knit

import app.getknit.knit.mesh.ConnectFailReason
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.DialPlan
import app.getknit.knit.mesh.bluetooth.LonelyDialPolicy
import app.getknit.knit.mesh.bluetooth.PromotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [DialPlan]: which peers the connection engine hands its policies, and how their answers combine. */
class DialPlanTest {
    private fun snap(
        nodeId: String,
        rssi: Double = -60.0,
        psm: Int = 100,
        dwell: Long = 15_000,
    ): BlePresenceTracker.Snapshot =
        BlePresenceTracker.Snapshot(
            nodeId = nodeId,
            protoVersion = 1,
            capabilities = 0L,
            psm = psm,
            digestCue = 0,
            smoothedRssi = rssi,
            dwellMs = dwell,
            lastSeenAgoMs = 0,
        )

    @Test
    fun theTieBreakDialsOnlySmallerIdsNotLinkedAndNotInFlight() {
        val snaps = listOf(snap("aaa"), snap("bbb"), snap("ccc"), snap("zzz"))
        val got = DialPlan.ordinaryCandidates("mmm", snaps, linked = setOf("bbb"), inFlight = setOf("ccc")).map { it.nodeId }
        assertEquals("a larger id is the other side's to dial", listOf("aaa"), got)
    }

    @Test
    fun aPeerIsScanWorkOnlyWhenWeDialItItIsCloseEnoughAndNotBackingOff() {
        val floor = -90.0

        fun promotable(
            nodeId: String,
            rssi: Double?,
            linked: Set<String> = emptySet(),
            backoff: Set<String> = emptySet(),
        ) = DialPlan.promotable("mmm", nodeId, rssi, linked, floor) { it in backoff }
        assertTrue(promotable("aaa", -70.0))
        assertTrue("the floor itself promotes", promotable("aaa", -90.0))
        assertFalse("below the floor", promotable("aaa", -91.0))
        assertFalse("never sighted, no RSSI", promotable("aaa", null))
        assertFalse("a larger id", promotable("zzz", -70.0))
        assertFalse("our own id", promotable("mmm", -70.0))
        assertFalse("linked", promotable("aaa", -70.0, linked = setOf("aaa")))
        assertFalse("backing off", promotable("aaa", -70.0, backoff = setOf("aaa")))
    }

    @Test
    fun theBackoffIsReadOnlyForAPeerThatPassesEveryOtherGate() {
        // It takes the transport's lock, on every sighting.
        var reads = 0
        DialPlan.promotable("mmm", "zzz", -70.0, emptySet(), -90.0) { reads++ == 0 && false }
        DialPlan.promotable("mmm", "aaa", -95.0, emptySet(), -90.0) { reads++ == 0 && false }
        assertEquals(0, reads)
        DialPlan.promotable("mmm", "aaa", -70.0, emptySet(), -90.0) { reads++ == 0 && false }
        assertEquals(1, reads)
    }

    @Test
    fun theScanCandidatesAreTheSightingsThatPassTheSameGate() {
        val snaps = listOf(snap("aaa", -70.0), snap("bbb", -95.0), snap("ccc", -70.0), snap("ddd", -70.0), snap("zzz", -50.0))
        val got = DialPlan.scanCandidates("mmm", snaps, linked = setOf("ccc"), backoff = setOf("ddd"), rssiFloorDbm = -90.0)
        assertEquals(setOf("aaa"), got)
    }

    @Test
    fun theLonelyDialChoosesAmongDialableUnlinkedPeersWithAPsmAndMarksTheBackedOff() {
        val snaps = listOf(snap("aaa"), snap("bbb", psm = 0), snap("ccc"), snap("ddd"), snap("zzz", rssi = -40.0, dwell = 9_000))
        val got =
            DialPlan.lonelyCandidates(snaps, linked = setOf("ccc"), backoff = setOf("zzz")) { it != "ddd" }
        assertEquals(
            listOf(
                LonelyDialPolicy.Candidate("aaa", -60.0, 15_000, backedOff = false),
                LonelyDialPolicy.Candidate("zzz", -40.0, 9_000, backedOff = true),
            ),
            got,
        )
    }

    @Test
    fun onlyADialToALargerIdIsALonelyOne() {
        assertFalse(DialPlan.lonelyDialInFlight("mmm", setOf("aaa", "bbb")))
        assertTrue(DialPlan.lonelyDialInFlight("mmm", setOf("aaa", "zzz")))
        assertFalse(DialPlan.lonelyDialInFlight("mmm", emptySet()))
    }

    @Test
    fun theLonelyDialRidesAfterThePromotionsWithNoCap() {
        val ordinary = PromotionPolicy.Decision(promote = listOf("aaa"), evict = listOf("bbb"))
        val lonely = LonelyDialPolicy.Candidate("zzz", -60.0, 15_000, backedOff = false)
        assertEquals(
            PromotionPolicy.Decision(listOf("aaa", "zzz"), listOf("bbb")),
            DialPlan.decision(ordinary, lonely, cap = null, linkCount = 7, inFlightCount = { 7 }),
        )
        assertEquals(ordinary, DialPlan.decision(ordinary, null, cap = null, linkCount = 7, inFlightCount = { 7 }))
    }

    @Test
    fun aDebugCapCountsLinksAndDialsInFlightAndFreesASlotPerEviction() {
        val ordinary = PromotionPolicy.Decision(promote = listOf("aaa", "bbb", "ccc"), evict = emptyList())
        // Cap 4, two links, one dial in flight: one slot.
        assertEquals(listOf("aaa"), DialPlan.decision(ordinary, null, cap = 4, linkCount = 2, inFlightCount = { 1 }).promote)
        // An eviction frees one more.
        val evicting = ordinary.copy(evict = listOf("old"))
        assertEquals(listOf("aaa", "bbb"), DialPlan.decision(evicting, null, cap = 4, linkCount = 2, inFlightCount = { 1 }).promote)
        // Over the cap already: none, never a negative take.
        assertEquals(emptyList<String>(), DialPlan.decision(ordinary, null, cap = 2, linkCount = 3, inFlightCount = { 1 }).promote)
        // The lonely dial is held to the cap too, last in line.
        val lonely = LonelyDialPolicy.Candidate("zzz", -60.0, 15_000, backedOff = false)
        val two = PromotionPolicy.Decision(listOf("aaa"), emptyList())
        assertEquals(listOf("aaa"), DialPlan.decision(two, lonely, cap = 1, linkCount = 0, inFlightCount = { 0 }).promote)
        assertEquals(listOf("aaa", "zzz"), DialPlan.decision(two, lonely, cap = 2, linkCount = 0, inFlightCount = { 0 }).promote)
    }

    @Test
    fun aPeerHeardOnCodedAloneIsDialedAtItsCodedAddressOnly() {
        assertEquals("coded" to true, DialPlan.target(viaCoded = true, oneM = "1m", coded = "coded"))
        assertNull("its 1M address is not a fallback", DialPlan.target(viaCoded = true, oneM = "1m", coded = null))
    }

    @Test
    fun anyOtherPeerIsDialedAt1mElseAsACodedDialAtItsCodedAddress() {
        assertEquals("1m" to false, DialPlan.target(viaCoded = false, oneM = "1m", coded = "coded"))
        // #148: the flag follows the address, so the fallback gets Coded's watchdog, count and via=.
        assertEquals("coded" to true, DialPlan.target(viaCoded = false, oneM = null, coded = "coded"))
        assertEquals(
            CodedPhyPolicy.CODED_CONNECT_TIMEOUT_MS,
            CodedPhyPolicy.connectTimeoutMs(DialPlan.target(viaCoded = false, oneM = null, coded = "c")!!.second),
        )
        assertNull(DialPlan.target<String>(viaCoded = false, oneM = null, coded = null))
    }

    @Test
    fun theInitiatingLineNamesItsPhyOnlyWhileCodedIsOn() {
        assertEquals("", DialPlan.via(codedOn = false, viaCoded = false))
        assertEquals("", DialPlan.via(codedOn = false, viaCoded = true))
        assertEquals(" via=coded", DialPlan.via(codedOn = true, viaCoded = true))
        assertEquals(" via=1m", DialPlan.via(codedOn = true, viaCoded = false))
    }

    @Test
    fun theDialsInFlightAreCountedOnlyUnderACap() {
        // The count takes the transport's lock, on every promotion tick.
        var reads = 0
        val ordinary = PromotionPolicy.Decision(listOf("aaa"), emptyList())
        DialPlan.decision(ordinary, null, cap = null, linkCount = 0) { reads++ }
        assertEquals(0, reads)
        DialPlan.decision(ordinary, null, cap = 3, linkCount = 0) { reads++ }
        assertEquals(1, reads)
    }

    @Test
    fun onlyAPeerWeDialIsWorthAChase() {
        assertEquals(setOf("aaa"), DialPlan.initiable("mmm", setOf("aaa", "mmm", "zzz")))
        assertEquals(emptySet<String>(), DialPlan.initiable("mmm", emptySet()))
    }

    @Test
    fun aGattReadWaitsForTheRadioAndNeverTouchesALinkedAddress() {
        assertTrue(DialPlan.mayReadGatt("A", listOf("B"), arbiterBusy = false) { false })
        assertFalse("a link holds it", DialPlan.mayReadGatt("B", listOf("B"), arbiterBusy = false) { false })
        assertFalse("a dial is in flight", DialPlan.mayReadGatt("A", listOf("B"), arbiterBusy = false) { true })
        assertFalse("another connect holds the arbiter", DialPlan.mayReadGatt("A", listOf("B"), arbiterBusy = true) { false })
    }

    @Test
    fun theDialsInFlightAreNotReadForALinkedAddress() {
        var reads = 0
        DialPlan.mayReadGatt("B", listOf("B"), arbiterBusy = false) { reads++ > 0 }
        assertEquals(0, reads)
    }

    @Test
    fun onlyAHelloRefusalKeepsTheGattPayload() {
        assertFalse(DialPlan.forgetsGattPayload(ConnectFailReason.HANDSHAKE))
        ConnectFailReason.entries.filter { it != ConnectFailReason.HANDSHAKE }.forEach {
            assertTrue("$it may have used a stale PSM", DialPlan.forgetsGattPayload(it))
        }
    }

    @Test
    fun aGattReadYieldsOnlyToAPromotedPeerWithAnAddressToDial() {
        // #154: a promoted peer initiateTo cannot dial (no address) would hold every read off for its whole linger.
        val addressed = setOf("A")
        assertTrue(DialPlan.dialWaiting(listOf("B", "A")) { it in addressed })
        assertFalse("no address, no dial", DialPlan.dialWaiting(listOf("B")) { it in addressed })
        assertFalse("nothing promoted", DialPlan.dialWaiting(emptyList()) { true })
    }
}
