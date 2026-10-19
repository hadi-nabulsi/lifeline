package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.hiddenByFilter
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A quote is the sender's claim, judged by each reader against the original it holds (ADR 2026-10.gpa3): a forged
 * snippet is replaced by the original's words, a reply that overtakes its original is judged when the original
 * lands, a quote reaching into another thread shows nothing, and a reply to words a reader's moderator flagged is
 * hidden on that reader alone. Every case ends in the oracle.
 */
@RunWith(RobolectricTestRunner::class)
class QuoteLabTest {
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

    /** Bob's client writes words under Alice's name; every reader that holds her post shows what she said. */
    @Test
    fun aForgedQuoteReadsAsTheOriginalOnEveryReaderThatHoldsIt() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            assertTrue(alice.sendRoom("Meet at the trailhead"))
            val post = alice.ownMessageId(Conversations.NEARBY, "Meet at the trailhead")
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }

            assertTrue(bob.sendRoom("lol", ReplyRef(post, alice.nodeId, "Alice", "I hate you all")))
            val reply = bob.ownMessageId(Conversations.NEARBY, "lol")
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2) { Conversations.NEARBY }

            for (reader in listOf(alice, carol)) {
                assertTrue(lab.tryAwait(1) { if (reader.row(reply)?.replyCheck == MessageEntity.REPLY_VERIFIED) 1 else 0 })
                assertEquals("${reader.name} shows Alice's words", "Meet at the trailhead", reader.row(reply)?.replyToSnippet)
            }
            assertEquals("the sender's own row keeps what it sent", "I hate you all", bob.row(reply)?.replyToSnippet)
        }

    /**
     * Carol hears Bob's reply before Alice's post (Bob relays both; the pipe to Carol is held and released newest
     * first). The reply is unverified until the post lands, then judged against it.
     */
    @Test
    fun aReplyThatOvertakesItsOriginalIsVerifiedWhenTheOriginalLands() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)
            // Every sender's seed out first, so the held batch is the two chat frames' business alone.
            assertTrue(alice.sendGroup(groupId, "hello"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            assertTrue(bob.sendGroup(groupId, "hi"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2) { groupId }

            bob.transport.hold(carol.transport)
            assertTrue(alice.sendGroup(groupId, "Meet at the trailhead"))
            val post = alice.ownMessageId(groupId, "Meet at the trailhead")
            // Bob's relay of the post sits in the held pipe before he replies, so the pipe holds both to reorder.
            assertTrue(lab.tryAwait(1) { bob.heldTo(carol).count { it == post } })
            assertTrue(bob.sendGroup(groupId, "on my way", ReplyRef(post, alice.nodeId, "Alice", "Meet at the trailhead")))
            val reply = bob.ownMessageId(groupId, "on my way")
            assertTrue(lab.tryAwait(1) { bob.heldTo(carol).count { it == reply } })
            // Ordered by id, not by position: a custody re-serve can add a second copy of each to the hold.
            val released =
                bob.transport.release(carol.transport) { batch ->
                    batch.sortedBy {
                        when (WireCodec.decodeEnvelope(it.signed)?.id) {
                            reply -> 0
                            post -> 1
                            else -> 2
                        }
                    }
                }
            val order = released.mapNotNull { WireCodec.decodeEnvelope(it.signed)?.id }
            assertTrue("the reply was handed over ahead of its original: $order", order.indexOf(reply) in 0 until order.indexOf(post))

            lab.assertConverged(listOf(alice, bob, carol), atLeast = 4) { groupId }
            assertTrue(lab.tryAwait(1) { if (carol.row(reply)?.replyCheck == MessageEntity.REPLY_VERIFIED) 1 else 0 })
            assertEquals("Meet at the trailhead", carol.row(reply)?.replyToSnippet)
        }

    /**
     * Bob quotes, in the group, a DM Alice sent him. Alice holds it — in another thread — so the quote shows nothing on
     * her phone; Carol never held it, so it stays the sender's word for it there.
     */
    @Test
    fun aQuoteReachingIntoAnotherThreadShowsNothingOnAReaderThatHoldsIt() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            assertTrue(alice.sendDm(bob, "just between us"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "just between us")
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "hello"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            assertTrue(bob.sendGroup(groupId, "look", ReplyRef(dm, alice.nodeId, "Alice", "just between us")))
            val reply = bob.ownMessageId(groupId, "look")
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2) { groupId }

            assertTrue(lab.tryAwait(1) { if (alice.row(reply)?.replyCheck == MessageEntity.REPLY_MISMATCH) 1 else 0 })
            assertEquals("", alice.row(reply)?.replyToSnippet)
            assertEquals(MessageEntity.REPLY_UNCHECKED, carol.row(reply)?.replyCheck)
        }

    /**
     * Carol's moderator flags Alice's group post; a group is never screened on the way out (ADR 2026-10.9rh6). Bob's
     * clean reply to it is hidden on Carol's phone too, and on nobody else's.
     */
    @Test
    fun aReplyToWordsAReaderFlaggedIsHiddenOnThatReaderAlone() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            carol.flaggedText = setOf("something vile")
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)

            assertTrue(alice.sendGroup(groupId, "something vile"))
            val post = alice.ownMessageId(groupId, "something vile")
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            assertTrue(bob.sendGroup(groupId, "not cool", ReplyRef(post, alice.nodeId, "Alice", "something vile")))
            val reply = bob.ownMessageId(groupId, "not cool")
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2) { groupId }

            assertTrue(lab.tryAwait(1) { if (carol.row(reply)?.replyToFlagged == true) 1 else 0 })
            assertTrue(carol.row(reply)!!.hiddenByFilter(filterOn = true))
            assertEquals("its own words were clean", MessageEntity.MODERATION_NONE, carol.row(reply)?.moderation)
            assertFalse("Alice's moderator minds nothing", alice.row(reply)!!.hiddenByFilter(filterOn = true))
        }

    /** The frame ids this node's pipe to [to] holds. */
    private fun LabNode.heldTo(to: LabNode): List<String> =
        transport.held(to.transport).mapNotNull { WireCodec.decodeEnvelope(it.signed)?.id }
}
