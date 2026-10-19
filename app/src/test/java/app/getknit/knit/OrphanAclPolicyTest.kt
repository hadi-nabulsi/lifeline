package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.OrphanAclPolicy
import app.getknit.knit.mesh.bluetooth.OrphanAclPolicy.ArmGate
import app.getknit.knit.mesh.bluetooth.OrphanAclPolicy.Release
import app.getknit.knit.mesh.bluetooth.OrphanAclPolicy.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OrphanAclPolicy]: which LE connections a closed link left up, and how each is let go and reported (#142). */
class OrphanAclPolicyTest {
    private val policy = OrphanAclPolicy<String>()
    private val grace = OrphanAclPolicy.GRACE_MS
    private val addr = "AA:BB:CC:DD:EE:01"
    private val node = "n1"

    private fun check(
        now: Long,
        busy: Set<String> = emptySet(),
        armOn: Boolean = true,
    ) = policy.check(now, busy, armOn)

    /** A link on [address] whose ACL came up at 0 and ended at [endedAt]. */
    private fun linkEnded(
        endedAt: Long = 1_000,
        address: String = addr,
        armed: Boolean? = true,
    ) {
        policy.onAcl(address, up = true, now = 0)
        policy.onLinkUp(address)
        armed?.let { policy.onArmed(policy.onArming(address, now = 0), it, now = 0) }
        policy.onLinkEnded(address, node, "device-$address", endedAt)
    }

    @Test
    fun anAclThatDropsWithItsLinkIsNeverAnOrphan() {
        linkEnded(endedAt = 1_000)
        policy.onAcl(addr, up = false, now = 3_000) // the arm's 1–4 s

        assertEquals(emptyList<Any>(), check(1_000 + grace + 60_000))
        assertEquals(0, policy.watching)
        assertEquals(0, policy.reported)
    }

    @Test
    fun aDisconnectBeforeTheLinkEndsLeavesNothingToWatch() {
        policy.onAcl(addr, up = true, now = 0)
        policy.onAcl(addr, up = false, now = 500) // the ACL went first: a supervision timeout, the link's EOF after
        policy.onLinkEnded(addr, node, "device", 1_000)

        assertEquals(0, policy.watching)
        assertEquals(emptyList<Any>(), check(1_000 + grace * 10))
    }

    @Test
    fun anAddressWhoseConnectEdgeWasNeverSeenIsNeverFlagged() {
        policy.onLinkEnded(addr, node, "device", 1_000)

        assertEquals(0, policy.watching)
        assertEquals(emptyList<Any>(), check(1_000 + grace * 10))
    }

    @Test
    fun anAclStillUpPastTheGraceIsReleased() {
        linkEnded(endedAt = 1_000)

        assertEquals(emptyList<Any>(), check(1_000 + grace - 1))
        val actions = check(1_000 + grace)

        // Token 1 was the link-up arm's; the release is the next flight.
        assertEquals(listOf(Release(addr, node, "device-$addr", tries = 1, endedAgoMs = grace, token = 2)), actions)
        assertTrue(policy.aclUp(addr))
    }

    @Test
    fun aReleaseThatClearsTheAclIsReportedOnceAsCleared() {
        linkEnded(endedAt = 1_000)
        check(61_000) // release 1
        policy.onAcl(addr, up = false, now = 63_000)

        assertEquals(emptyList<Any>(), check(63_000 + grace - 1)) // down, but not yet for a grace: a re-dial may follow
        val actions = check(121_000)

        assertEquals(listOf(Report(addr, node, linked = true, armed = true, tries = 1, cleared = true, ageMs = 62_000)), actions)
        assertEquals(0, policy.watching)
        assertEquals(emptyList<Any>(), check(181_000))
    }

    @Test
    fun twoReleasesThenExactlyOneReport() {
        linkEnded(endedAt = 1_000, armed = false)

        assertEquals(1, (check(61_000).single() as Release<*>).tries)
        assertEquals(2, (check(121_000).single() as Release<*>).tries)
        assertEquals(listOf(Report(addr, node, linked = true, armed = false, tries = 2, cleared = false, ageMs = 180_000)), check(181_000))
        assertEquals(emptyList<Any>(), check(241_000))
        assertEquals(emptyList<Any>(), check(3_600_000))

        // It finally goes: no second report.
        policy.onAcl(addr, up = false, now = 3_700_000)
        assertEquals(emptyList<Any>(), check(3_700_000 + grace))
        assertEquals(2, policy.released)
        assertEquals(1, policy.reported)
        assertEquals(0, policy.watching)
    }

    @Test
    fun androidTwelvesAcceptListRedialEndsTheReleasesAfterTheOneItUndid() {
        linkEnded(endedAt = 1_000)
        check(61_000) // release 1
        policy.onAcl(addr, up = false, now = 62_000)
        policy.onAcl(addr, up = true, now = 64_500) // the stack re-dials the dropped peer: no channel on it

        assertEquals(emptyList<Any>(), check(64_500 + grace - 1))
        // A second release would only invite the next re-dial: it is reported as kept instead.
        val report = check(121_000).single() as Report
        assertEquals(1, report.tries)
        assertFalse(report.cleared)
        assertEquals(emptyList<Any>(), check(181_000))
        assertEquals(1, policy.released)
    }

    @Test
    fun anAclThatDroppedAndCameBackWithinTheGraceGetsOneTry() {
        linkEnded(endedAt = 1_000)
        policy.onAcl(addr, up = false, now = 3_000)
        policy.onAcl(addr, up = true, now = 5_000) // re-made before any round could forget it

        assertEquals(1, (check(5_000 + grace).single() as Release<*>).tries)
        assertEquals(1, (check(80_000).single() as Report).tries)
    }

    @Test
    fun anAclThatCameBackAfterTheGraceStillGetsTwoTries() {
        linkEnded(endedAt = 1_000)
        check(61_000) // release 1
        policy.onAcl(addr, up = false, now = 62_000)
        policy.onAcl(addr, up = true, now = 62_000 + grace) // not the accept list's 2–3 s re-dial

        assertEquals(2, (check(140_000).single() as Release<*>).tries)
    }

    @Test
    fun aChannelThatNeverBecameALinkIsWatchedAndReportedAsSuch() {
        policy.onAcl(addr, up = true, now = 0)
        policy.onLinkEnded(addr, node, "device", 1_000, linked = false) // a refused dialer
        policy.onArmed(policy.onArming(addr, now = 2_000), attached = false, now = 2_500)

        assertEquals(
            listOf(Report(addr, node, linked = false, armed = false, tries = 0, cleared = false, ageMs = 60_000)),
            check(61_000, armOn = false),
        )
    }

    @Test
    fun atStopAnUpAclWhoseArmNeverAttachedIsHandedBackOnce() {
        val pending = "AA:BB:CC:DD:EE:02"
        val attached = "AA:BB:CC:DD:EE:03"
        val down = "AA:BB:CC:DD:EE:04"
        linkEnded(endedAt = 1_000, armed = false)
        linkEnded(endedAt = 1_000, address = attached, armed = true)
        linkEnded(endedAt = 1_000, address = down, armed = false)
        policy.onAcl(down, up = false, now = 2_000)
        policy.onAcl(pending, up = true, now = 0)
        policy.onLinkUp(pending)
        policy.onArming(pending, now = 500) // a link-up arm still running: it does its own job
        policy.onLinkEnded(pending, node, "device-$pending", 1_000)

        val unarmed = policy.stop(5_000)

        assertEquals(listOf(OrphanAclPolicy.Unarmed(addr, node, "device-$addr", linked = true, endedAgoMs = 4_000)), unarmed)
        assertEquals(1, policy.reported)
        assertEquals(0, policy.watching)
        assertFalse(policy.aclUp(addr))
    }

    @Test
    fun aDoorbellAttachMarksTheLinkArmed() {
        linkEnded(endedAt = 1_000, armed = null)
        policy.onDoorbellAttached(addr, now = 2_000) // the doorbell lookup's attach, reported after the link's end

        assertTrue((check(61_000, armOn = false).single() as Report).armed)
        assertEquals(emptyList<OrphanAclPolicy.Unarmed<String>>(), policy.stop(62_000))
    }

    @Test
    fun aRelinkForgetsTheEntry() {
        linkEnded(endedAt = 1_000)
        policy.onLinkUp(addr)

        assertEquals(0, policy.watching)
        assertEquals(emptyList<Any>(), check(1_000 + grace * 10))
    }

    @Test
    fun aBusyAddressIsLeftAloneThatRound() {
        linkEnded(endedAt = 1_000)

        assertEquals(emptyList<Any>(), check(61_000, busy = setOf(addr))) // a dial in flight on it
        assertEquals(1, (check(121_000).single() as Release<*>).tries)
    }

    @Test
    fun withTheArmOffNothingIsReleasedAndTheOrphanIsReportedWithNoTries() {
        linkEnded(endedAt = 1_000, armed = null)

        assertEquals(
            listOf(Report(addr, node, linked = true, armed = false, tries = 0, cleared = false, ageMs = 60_000)),
            check(61_000, armOn = false),
        )
        assertEquals(emptyList<Any>(), check(121_000, armOn = false))
        assertEquals(0, policy.released)
    }

    @Test
    fun anOrphanThatClearsOnItsOwnAfterTheGraceIsReportedWithNoTries() {
        linkEnded(endedAt = 1_000)
        policy.onAcl(addr, up = false, now = 40_000) // the peer let it go, after the grace, before any round

        assertEquals(listOf(Report(addr, node, linked = true, armed = true, tries = 0, cleared = true, ageMs = 39_000)), check(61_000))
    }

    @Test
    fun anArmThatEndsAfterTheLinkStillMarksIt() {
        policy.onAcl(addr, up = true, now = 0)
        policy.onLinkUp(addr)
        val arm = policy.onArming(addr, now = 0)
        policy.onLinkEnded(addr, node, "device-$addr", 1_000)
        policy.onArmed(arm, attached = true, now = 2_000)

        val report = check(61_000, armOn = false).single() as Report
        assertTrue(report.armed)
    }

    @Test
    fun atMostSixteenAddressesAreWatchedAndTheOldestGoesFirst() {
        val addresses = (0 until OrphanAclPolicy.MAX_ENTRIES + 1).map { "AA:BB:CC:DD:EE:%02X".format(it) }
        addresses.forEachIndexed { i, a -> linkEnded(endedAt = 1_000L + i, address = a) }

        assertEquals(OrphanAclPolicy.MAX_ENTRIES, policy.watching)
        val released = check(1_000_000).map { it.address } // as many as the slots take, oldest first
        assertEquals(addresses.drop(1).take(OrphanAclPolicy.MAX_ARMS_IN_FLIGHT), released)
    }

    @Test
    fun clearForgetsEveryEdgeAndEveryEntry() {
        linkEnded(endedAt = 1_000)
        policy.clear()

        assertFalse(policy.aclUp(addr))
        assertEquals(0, policy.watching)
        assertEquals(emptyList<Any>(), check(1_000_000))
    }

    // #150: the arms are bounded, one per address and MAX_ARMS_IN_FLIGHT at once.

    private fun addr(i: Int) = "AA:BB:CC:DD:EF:%02X".format(i)

    private fun open(
        address: String,
        now: Long,
    ): Long = (policy.tryArm(address, now) as ArmGate.Open).token

    @Test
    fun aSecondUnlinkedArmWhileOneIsPendingIsRefusedAndCounted() {
        policy.onAcl(addr, up = true, now = 0)

        open(addr, now = 2_000)
        assertEquals(ArmGate.Pending, policy.tryArm(addr, now = 2_100))
        assertEquals(ArmGate.Pending, policy.tryArm(addr, now = 2_200))
        assertEquals(2, policy.armsSkipped)
    }

    @Test
    fun anAttachedArmCoversItsAclUntilTheDisconnectEdge() {
        policy.onAcl(addr, up = true, now = 0)
        policy.onArmed(open(addr, now = 2_000), attached = true, now = 2_050)

        assertEquals(ArmGate.Attached, policy.tryArm(addr, now = 60_000)) // the idle timeout lives on this ACL
        policy.onAcl(addr, up = false, now = 61_000)
        policy.onAcl(addr, up = true, now = 63_000) // Android 12's accept-list re-dial: a new ACL

        open(addr, now = 65_000)
        assertEquals(1, policy.armsSkipped)
    }

    @Test
    fun aFailedArmRefusesTheAddressForTheRetryWindow() {
        policy.onAcl(addr, up = true, now = 0)
        policy.onArmed(open(addr, now = 2_000), attached = false, now = 4_000)
        val reopens = 4_000 + OrphanAclPolicy.ARM_RETRY_MS

        assertEquals(ArmGate.Retry, policy.tryArm(addr, now = reopens - 1))
        policy.onAcl(addr, up = false, now = 5_000) // a down edge keeps a failed arm's row: its registration may live
        assertEquals(ArmGate.Retry, policy.tryArm(addr, now = reopens - 1))
        assertEquals(1L, policy.msUntilOpen(addr, now = reopens - 1))
        open(addr, now = reopens)
    }

    @Test
    fun atMostThreeArmsAreInFlightAndAnAttachFreesItsSlotAtOnce() {
        val tokens = (0 until OrphanAclPolicy.MAX_ARMS_IN_FLIGHT).map { open(addr(it), now = 1_000) }

        assertEquals(ArmGate.Full, policy.tryArm(addr(9), now = 1_500))
        policy.onArmed(tokens[0], attached = true, now = 1_600)
        open(addr(9), now = 1_600)
    }

    @Test
    fun anUnattachedArmHoldsItsSlotUntilItsSecondClose() {
        val tokens = (0 until OrphanAclPolicy.MAX_ARMS_IN_FLIGHT).map { open(addr(it), now = 1_000) }
        policy.onArmed(tokens[0], attached = false, now = 3_000)
        val frees = 3_000 + OrphanAclPolicy.SECOND_CLOSE_MS

        assertEquals(ArmGate.Full, policy.tryArm(addr(9), now = frees - 1))
        open(addr(9), now = frees)
    }

    @Test
    fun msUntilOpenNamesTheSoonestSlotAndNothingForACoveredAddress() {
        val tokens = (0 until OrphanAclPolicy.MAX_ARMS_IN_FLIGHT).map { open(addr(it), now = 1_000) }
        policy.onArmed(tokens[0], attached = false, now = 1_500) // its slot frees at 6 500
        policy.onArmed(tokens[1], attached = false, now = 1_400) // 6 400

        assertEquals(400L, policy.msUntilOpen(addr(9), now = 6_000)) // the pending third is guessed done by 3 000
        assertEquals(null, policy.msUntilOpen(addr(2), now = 6_000)) // pending: its own arm covers the ACL
        assertEquals(0L, policy.msUntilOpen(addr(9), now = 6_500))
    }

    @Test
    fun msUntilOpenGuessesAPendingFlightDoneAtItsAttachTimeout() {
        (0 until OrphanAclPolicy.MAX_ARMS_IN_FLIGHT).forEach { open(addr(it), now = 1_000) }

        assertEquals(1_500L, policy.msUntilOpen(addr(9), now = 1_500)) // the 2 s attach from 1 000
        assertEquals(500L, policy.msUntilOpen(addr(9), now = 2_900)) // never sooner than half a second
    }

    @Test
    fun aLinkUpArmIsNeverRefusedAndTakesASlot() {
        val tokens = (0 until OrphanAclPolicy.MAX_ARMS_IN_FLIGHT).map { open(addr(it), now = 1_000) }
        val linkUp = policy.onArming(addr(8), now = 1_100) // past the cap: every link is armed
        policy.onArmed(tokens[0], attached = true, now = 1_200)

        assertEquals(ArmGate.Full, policy.tryArm(addr(9), now = 1_300)) // the link-up arm holds the freed slot
        assertEquals(ArmGate.Pending, policy.tryArm(addr(8), now = 1_300))
        policy.onArmed(linkUp, attached = true, now = 1_400)
        open(addr(9), now = 1_400)
    }

    @Test
    fun anArmThatNeverReportsBackLapsesAndFreesItsSlot() {
        (0 until OrphanAclPolicy.MAX_ARMS_IN_FLIGHT).forEach { open(addr(it), now = 1_000) }
        val lapsed = 1_000 + OrphanAclPolicy.ARM_LAPSE_MS

        assertEquals(ArmGate.Full, policy.tryArm(addr(9), now = lapsed - 1))
        assertEquals(ArmGate.Retry, policy.tryArm(addr(0), now = lapsed)) // counted failed from the lapse
        assertEquals(ArmGate.Full, policy.tryArm(addr(9), now = lapsed + OrphanAclPolicy.SECOND_CLOSE_MS - 1))
        open(addr(9), now = lapsed + OrphanAclPolicy.SECOND_CLOSE_MS)
    }

    @Test
    fun aSupersededArmsFailureNeverDowngradesTheRowAndStillHoldsItsSlot() {
        policy.onAcl(addr, up = true, now = 0)
        val unlinked = open(addr, now = 1_000)
        val linkUp = policy.onArming(addr, now = 1_100) // a cross-dial's link came up on the same ACL
        policy.onLinkUp(addr)
        policy.onArmed(linkUp, attached = true, now = 1_200)
        policy.onArmed(unlinked, attached = false, now = 1_300) // the unlinked tuner found the link and stood down

        // The superseded arm's registration still holds a slot until its second close.
        open(addr(1), now = 1_400)
        open(addr(2), now = 1_400)
        assertEquals(ArmGate.Full, policy.tryArm(addr(3), now = 1_400))

        policy.onLinkEnded(addr, node, "device", 5_000)
        assertEquals(ArmGate.Attached, policy.tryArm(addr, now = 5_100))
        assertTrue((check(5_000 + grace, armOn = false).single() as Report).armed)
    }

    @Test
    fun aStaleArmEndAfterClearLeavesNoRow() {
        policy.onAcl(addr, up = true, now = 0)
        val arm = open(addr, now = 1_000)
        policy.clear() // the adapter went off
        policy.onArmed(arm, attached = true, now = 1_050) // the tuner ended a moment later
        policy.onAcl(addr, up = true, now = 5_000)

        open(addr, now = 5_000)
    }

    @Test
    fun aConnectEdgeClearsAnAttachOlderThanItsTrail() {
        policy.onDoorbellAttached(addr, now = 1_000) // its down edge was missed, or came before the attach
        policy.onAcl(addr, up = true, now = 1_000 + OrphanAclPolicy.EDGE_TRAIL_MS) // may trail that very attach
        assertEquals(ArmGate.Attached, policy.tryArm(addr, now = 4_000))

        policy.onAcl(addr, up = true, now = 10_000) // a new ACL
        open(addr, now = 10_000)
    }

    @Test
    fun aChannelsEndKeepsTheArmAndItsEntryInheritsTheAttach() {
        policy.onAcl(addr, up = true, now = 0)
        policy.onLinkUp(addr)
        policy.onArmed(policy.onArming(addr, now = 100), attached = true, now = 150)
        policy.onLinkEnded(addr, node, "device", 1_000)
        policy.onLinkUp(addr) // a relink over the same ACL
        policy.onLinkEnded(addr, node, "device", 2_000, linked = false) // then a refused channel on it

        assertEquals(ArmGate.Attached, policy.tryArm(addr, now = 4_000))
        val report = check(2_000 + grace, armOn = false).single() as Report
        assertTrue(report.armed)
    }

    @Test
    fun aHelloLessWatchNeverReplacesANamedOne() {
        linkEnded(endedAt = 1_000) // a peer's link
        policy.onLinkEnded(addr, "(no hello)", "flood", 5_000, linked = false, named = false)

        val release = check(1_000 + grace).single() as Release<*>
        assertEquals(node, release.nodeId)
        assertEquals(grace, release.endedAgoMs)
    }

    @Test
    fun aHelloLessFloodNeverEvictsANamedOrphan() {
        linkEnded(endedAt = 1_000)
        val flood = (0 until OrphanAclPolicy.MAX_ENTRIES * 2).map { addr(it) }
        flood.forEach { a ->
            policy.onAcl(a, up = true, now = 2_000)
            policy.onLinkEnded(a, "(no hello)", "flood", 2_000, linked = false, named = false)
        }

        assertEquals(1 + OrphanAclPolicy.MAX_UNNAMED, policy.watching)
        val released = check(1_000_000).filterIsInstance<Release<*>>().map { it.address }
        assertTrue(addr in released)
    }

    @Test
    fun releasesTakeSlotsAndThoseThatFindNoneWaitTheirTurnWithTriesIntact() {
        val orphans = (0 until 5).map { addr(it) }
        orphans.forEach { linkEnded(endedAt = 1_000, address = it) }

        val first = check(61_000).filterIsInstance<Release<*>>()
        assertEquals(OrphanAclPolicy.MAX_ARMS_IN_FLIGHT, first.size)
        assertEquals(ArmGate.Full, policy.tryArm(addr(9), now = 61_000))

        first.forEach { policy.onArmed(it.token, attached = true, now = 61_100) }
        val second = check(121_000).filterIsInstance<Release<*>>()
        // The two the slots deferred go first, at their first try; the third slot takes a second try.
        assertEquals(orphans.drop(3) + orphans[0], second.map { it.address })
        assertEquals(listOf(1, 1, 2), second.map { it.tries })
        assertEquals(6, policy.released)
    }

    @Test
    fun clearAndStopForgetTheArms() {
        policy.onAcl(addr, up = true, now = 0)
        open(addr, now = 1_000)
        policy.clear()
        open(addr, now = 1_100)

        policy.stop(1_200)
        open(addr, now = 1_300)
        assertEquals(0, policy.armsSkipped)
    }

    @Test
    fun anArmThatNeverMadeAClientFreesItsSlotAtOnceAndLeavesNoRetry() {
        val tokens = (0 until OrphanAclPolicy.MAX_ARMS_IN_FLIGHT).map { open(addr(it), now = 1_000) }
        policy.onNeverRegistered(tokens[0]) // its ACL was gone at the attach: no connectGatt

        open(addr(9), now = 1_100)
        policy.onNeverRegistered(tokens[1])
        open(addr(0), now = 1_200) // its row says what it said before: no arm, so no retry window
    }

    @Test
    fun aNeverRegisteredArmPutsBackTheRowItFound() {
        policy.onAcl(addr, up = true, now = 0)
        policy.onArmed(open(addr, now = 1_000), attached = false, now = 2_000)
        val linkUp = policy.onArming(addr, now = 3_000)
        policy.onNeverRegistered(linkUp)

        assertEquals(ArmGate.Retry, policy.tryArm(addr, now = 4_000)) // the failure at 2 000 still stands
        open(addr, now = 2_000 + OrphanAclPolicy.ARM_RETRY_MS)
    }

    @Test
    fun helloLessArmsLeaveOneSlotForReleasesAndNamedArms() {
        repeat(OrphanAclPolicy.MAX_UNNAMED_ARMS) { (policy.tryArm(addr(it), now = 1_000, named = false) as ArmGate.Open) }

        assertEquals(ArmGate.Full, policy.tryArm(addr(7), now = 1_100, named = false))
        assertEquals(1_900L, policy.msUntilOpen(addr(7), now = 1_100, named = false)) // the 2 s attach from 1 000
        assertEquals(0L, policy.msUntilOpen(addr(8), now = 1_100))
        open(addr(8), now = 1_100) // a named refusal takes the kept slot
    }

    @Test
    fun aReleaseTakesTheSlotHelloLessArmsCannot() {
        linkEnded(endedAt = 1_000)
        repeat(OrphanAclPolicy.MAX_UNNAMED_ARMS) { policy.tryArm(addr(it), now = 61_000, named = false) }

        assertEquals(1, check(61_000).filterIsInstance<Release<*>>().size)
    }

    @Test
    fun aNeverRegisteredArmOverAPendingOneHandsTheRowBackToIt() {
        policy.onAcl(addr, up = true, now = 0)
        val unlinked = open(addr, now = 1_000)
        val linkUp = policy.onArming(addr, now = 1_100) // a cross-dial's link came up, then went at once
        policy.onNeverRegistered(linkUp)
        policy.onArmed(unlinked, attached = false, now = 1_500) // the unlinked arm's own end still settles the row

        assertEquals(ArmGate.Retry, policy.tryArm(addr, now = 2_000))
        open(addr, now = 1_500 + OrphanAclPolicy.ARM_RETRY_MS)
    }

    @Test
    fun aNeverRegisteredArmOverAPendingOneThatLapsesLeavesTheRowFailed() {
        policy.onAcl(addr, up = true, now = 0)
        open(addr, now = 1_000)
        policy.onNeverRegistered(policy.onArming(addr, now = 1_100))
        val lapsed = 1_000 + OrphanAclPolicy.ARM_LAPSE_MS

        assertEquals(ArmGate.Pending, policy.tryArm(addr, now = lapsed - 1))
        assertEquals(ArmGate.Retry, policy.tryArm(addr, now = lapsed))
        open(addr, now = lapsed + OrphanAclPolicy.ARM_RETRY_MS)
    }

    @Test
    fun aNeverRegisteredArmOverAPendingOneThatFailedMeanwhileTakesItsFailure() {
        policy.onAcl(addr, up = true, now = 0)
        val unlinked = open(addr, now = 1_000)
        val linkUp = policy.onArming(addr, now = 1_100)
        policy.onArmed(unlinked, attached = false, now = 1_500) // superseded: the row ignores it for now
        policy.onNeverRegistered(linkUp)

        assertEquals(ArmGate.Retry, policy.tryArm(addr, now = 2_000))
        open(addr, now = 1_500 + OrphanAclPolicy.ARM_RETRY_MS)
    }

    @Test
    fun aNeverRegisteredArmOverAPendingOneLongGoneLeavesNoArm() {
        policy.onAcl(addr, up = true, now = 0)
        val unlinked = open(addr, now = 1_000)
        val linkUp = policy.onArming(addr, now = 1_100)
        policy.onArmed(unlinked, attached = false, now = 1_200)
        open(addr(9), now = 1_200 + OrphanAclPolicy.SECOND_CLOSE_MS) // a sweep: the failed flight's slot is gone
        policy.onNeverRegistered(linkUp)

        open(addr, now = 1_200 + OrphanAclPolicy.SECOND_CLOSE_MS) // never pinned Pending
    }
}
