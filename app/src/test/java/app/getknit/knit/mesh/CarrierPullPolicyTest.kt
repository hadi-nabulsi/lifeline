package app.getknit.knit.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 2026-10.g24x: a carrier holds back a DM's picture while its recipient is linked, and a group's while its author is,
 * for a bounded while.
 */
class CarrierPullPolicyTest {
    private val linked = setOf("bob")

    private fun dm(
        to: String?,
        at: Long = 0L,
    ) = CarriedAttachment("h", to, at)

    @Test
    fun aDmToALinkedRecipientWaits() {
        assertTrue(CarrierPullPolicy.defers(listOf(dm("bob")), linked, now = 0L))
        assertTrue(CarrierPullPolicy.defers(listOf(dm("bob"), dm("bob", at = 5L)), linked, now = 10L))
    }

    @Test
    fun aRecipientNotLinkedOrARoomFramePullsAtOnce() {
        assertFalse(CarrierPullPolicy.defers(listOf(dm("carol")), linked, now = 0L))
        assertFalse(CarrierPullPolicy.defers(listOf(dm(null)), linked, now = 0L))
        assertFalse("one frame that is not held back is enough", CarrierPullPolicy.defers(listOf(dm("bob"), dm(null)), linked, now = 0L))
        assertFalse(CarrierPullPolicy.defers(emptyList(), linked, now = 0L))
    }

    @Test
    fun aGroupsPictureWaitsWhileItsAuthorIsLinked() {
        // The 2026-10-07 big-file runs: a carrier outside the group asked the author first, and both members waited.
        val group = CarriedAttachment("h", null, 0L, senderId = "bob", groupId = "g-1")
        assertTrue(CarrierPullPolicy.defers(listOf(group), linked, now = 0L))
        assertFalse("its author is not linked", CarrierPullPolicy.defers(listOf(group.copy(senderId = "carol")), linked, now = 0L))
        assertFalse("a room frame: no group", CarrierPullPolicy.defers(listOf(group.copy(groupId = null)), linked, now = 0L))
        val ours = group.copy(member = true)
        assertFalse("we are in the group: a member pulls its own (#132)", CarrierPullPolicy.defers(listOf(ours), linked, now = 0L))
        assertFalse(CarrierPullPolicy.defers(listOf(group), linked, now = CarrierPullPolicy.DEFER_MS))
    }

    @Test
    fun theHoldEndsOnceTheWindowHasPassed() {
        val window = CarrierPullPolicy.DEFER_MS
        assertTrue(CarrierPullPolicy.defers(listOf(dm("bob", at = 1_000L)), linked, now = 1_000L + window - 1))
        assertFalse(CarrierPullPolicy.defers(listOf(dm("bob", at = 1_000L)), linked, now = 1_000L + window))
    }
}
