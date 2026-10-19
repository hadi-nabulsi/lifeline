package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What stamps `peers.lastSeenAt` for the name-clash fade (ADR 2026-10.k8ge), and when. */
class HeardFromTest {
    private val signed = WireEnvelope(sig = byteArrayOf(1), signed = byteArrayOf(2))
    private val unsigned = WireEnvelope(sig = byteArrayOf(), signed = byteArrayOf(2))

    private fun env(
        type: String,
        sentAt: Long = 100L,
    ) = RelayEnvelope(id = "f", type = type, senderId = "s", sentAt = sentAt, payload = byteArrayOf())

    @Test
    fun aSignedFrameOfAnyTypeButABlobAskIsEvidence() {
        assertTrue(isHeardEvidence(signed, env(FrameType.CHAT)))
        assertTrue(isHeardEvidence(signed, env(FrameType.PROFILE)))
        // verifyInbound lets these two through without authenticating the sender.
        assertFalse(isHeardEvidence(signed, env(FrameType.BLOB_REQ)))
        // …and so does the holder query and its answer (ADR 2026-10.6e5t), whatever their signature bytes say.
        assertFalse(isHeardEvidence(signed, env(FrameType.BLOB_HAVE)))
        assertFalse(isHeardEvidence(signed, env(FrameType.BLOB_HAS)))
        assertFalse(isHeardEvidence(unsigned, env(FrameType.RECEIPT)))
    }

    @Test
    fun theStampIsTheFramesOwnTimeCappedAtOurClock() {
        assertEquals("a replayed old frame keeps its age", 100L, heardAt(env(FrameType.CHAT, sentAt = 100L), now = 5_000L))
        assertEquals("a skewed future stamp stops at now", 5_000L, heardAt(env(FrameType.CHAT, sentAt = 9_000L), now = 5_000L))
    }
}
