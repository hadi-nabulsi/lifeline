package app.getknit.knit.mesh.lab

import app.getknit.knit.data.LabelFade
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.PeerLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The name-clash fade end to end (ADR 2026-10.k8ge), through the real pipeline and the real `peers` table: a
 * stranger who takes a contact's name suffixes the contact while heard from, stops a week after the last signed
 * frame of theirs arrived, starts again with their next post, and stays labelled apart throughout.
 *
 * A line, so every frame of Eve's reaches Obs over the one link. Moving the lab clock moves nobody's stamp: nothing
 * republishes a profile on the clock by itself (only a heal or a start does), and a frame re-served after the jump
 * — Obs's dedup window lapses with it — is stamped at its own `sentAt`, never at the time it arrived.
 */
@RunWith(RobolectricTestRunner::class)
class NameClashFadeLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    // lab-lint: allow missing-oracle — the subject is one node's labels, and Eve is retired before the end.
    @Test
    fun aStrangersClashFadesAWeekAfterItWasLastHeardAndComesBackWithItsNextPost() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val obs = lab.node("obs").apply { setDisplayName("Obs") }
            val eve = lab.node("eve").apply { setDisplayName("Alice") }
            lab.linkAll(alice to obs, obs to eve)
            lab.awaitAcquainted(alice, obs, eve)
            obs.settings.accept(alice.nodeId) // a contact: a card imported, a request accepted

            // Both heard from at acquaintance: a live clash, both marked.
            assertEquals(Alias.aliasFor(alice.nodeId), label(obs, alice).discriminator)
            assertEquals(Alias.aliasFor(eve.nodeId), label(obs, eve).discriminator)

            // A week and an hour with nothing from Eve: the contact reads plain, and Eve still reads apart.
            lab.clock.advance(LabelFade.LABEL_FADE_MS + HOUR)
            assertNull(label(obs, alice).discriminator)
            assertEquals("Alice", label(obs, alice).text)
            assertEquals(Alias.aliasFor(eve.nodeId), label(obs, eve).discriminator)

            // Eve speaks: her post is stamped as it is accepted and banks on Obs's next metrics tick, run here.
            assertTrue(eve.sendRoom("back again"))
            lab.await(1) { if ("back again" in obs.roomPosts()[eve.nodeId].orEmpty()) 1 else 0 }
            obs.peers.flushSeen()
            assertEquals(Alias.aliasFor(alice.nodeId), label(obs, alice).discriminator)

            // Eve leaves for good; a week on, the contact reads plain again.
            lab.retire(eve)
            lab.clock.advance(LabelFade.LABEL_FADE_MS + HOUR)
            assertNull(label(obs, alice).discriminator)
            assertEquals(Alias.aliasFor(eve.nodeId), label(obs, eve).discriminator)
        }

    /** [of]'s label on [reader]'s screens, read afresh — the window is judged at the read. */
    private suspend fun label(
        reader: LabNode,
        of: LabNode,
    ): PeerLabel =
        reader.peers
            .observeDirectory()
            .first()
            .label(of.nodeId)

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
