package app.getknit.knit.data

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.hiddenByFilter
import app.getknit.knit.data.message.withReply
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.ui.msg
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuoteVerifier] over the real `messages` SQL (ADR 2026-10.gpa3): a quote is judged on whichever of reply and
 * original lands second, exactly once, touching nothing but the quote columns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuoteVerifierTest : RoomDbTest() {
    private val me = "me"
    private val repo by lazy { MessageRepository(db.messageDao()) }
    private val quotes by lazy { QuoteVerifier(repo) }

    private val original = msg("ada", 1, "peer-1", body = "Meet at the trailhead", id = "o1")

    private fun reply(
        id: String = "r1",
        from: String = "bo",
        authorId: String = "ada",
        snippet: String = "forged words",
        quotedId: String = "o1",
    ): MessageEntity =
        msg(from, 2, "peer-1", body = "On my way", id = id)
            .withReply(ReplyRef(messageId = quotedId, authorId = authorId, author = "Ada", snippet = snippet))

    @Test
    fun `judge rebuilds a reply whose original is already held, before it is stored`() =
        runTest {
            repo.save(original)

            val judged = quotes.judge(reply())!!

            assertEquals(MessageEntity.REPLY_VERIFIED, judged.replyCheck)
            assertEquals("Meet at the trailhead", judged.replyToSnippet)
        }

    @Test
    fun `judge answers null when the original is not held`() =
        runTest {
            assertNull(quotes.judge(reply()))
        }

    @Test
    fun `a reply that landed first is settled when its original lands`() =
        runTest {
            repo.saveIfAbsent(reply())
            assertEquals(MessageEntity.REPLY_UNCHECKED, quotes.settleAfterInsert("r1", me)!!.replyCheck)

            repo.saveIfAbsent(original)
            quotes.settleAfterInsert("o1", me)

            val settled = repo.find("r1")!!
            assertEquals(MessageEntity.REPLY_VERIFIED, settled.replyCheck)
            assertEquals("Meet at the trailhead", settled.replyToSnippet)
        }

    @Test
    fun `a reply settles itself when its original raced in after the check`() =
        runTest {
            // The judge saw nothing; the original then committed before the reply did.
            repo.saveIfAbsent(original)
            repo.saveIfAbsent(reply())

            val stored = quotes.settleAfterInsert("r1", me)!!

            assertEquals(MessageEntity.REPLY_VERIFIED, stored.replyCheck)
            assertEquals(MessageEntity.REPLY_VERIFIED, repo.find("r1")!!.replyCheck)
        }

    @Test
    fun `a flagged original hides its waiting replies, and a forged author is a mismatch`() =
        runTest {
            repo.saveIfAbsent(reply(id = "r1"))
            repo.saveIfAbsent(reply(id = "r2", authorId = "mallory"))
            repo.saveIfAbsent(original.copy(moderation = MessageEntity.MODERATION_TEXT_FLAGGED))

            quotes.settleAfterInsert("o1", me)

            val honest = repo.find("r1")!!
            assertEquals(MessageEntity.REPLY_VERIFIED, honest.replyCheck)
            assertTrue(honest.replyToFlagged)
            assertTrue(honest.hiddenByFilter(filterOn = true))
            val forged = repo.find("r2")!!
            assertEquals(MessageEntity.REPLY_MISMATCH, forged.replyCheck)
            assertEquals("", forged.replyToSnippet)
            assertFalse(forged.replyToFlagged)
        }

    @Test
    fun `a judged quote is written once, and touches nothing else on the row`() =
        runTest {
            repo.saveIfAbsent(reply().copy(attachmentHash = "h", attachmentMime = "audio/aac"))
            // Written between the reply's insert and its original's arrival: the tick and a voice note's metadata.
            repo.markReceived("r1", DeliveryPlane.Bluetooth)
            db.messageDao().setVoiceMeta("h", 1_000, "AAEC")
            repo.saveIfAbsent(original)
            quotes.settleAfterInsert("o1", me)
            val first = repo.find("r1")!!

            // A second original under the same id cannot exist, but a second settle of the same pair must not rewrite.
            quotes.settleAfterInsert("o1", me)
            quotes.settleBacklog(me)

            assertEquals(first, repo.find("r1"))
            assertEquals(MessageEntity.REPLY_VERIFIED, first.replyCheck)
            assertTrue(first.received)
            assertEquals(1_000, first.voiceDurationMs)
        }

    @Test
    fun `our own replies are never judged or rewritten`() =
        runTest {
            repo.saveIfAbsent(reply(from = me, snippet = "what I quoted"))
            repo.saveIfAbsent(original)

            quotes.settleAfterInsert("o1", me)
            quotes.settleBacklog(me)

            val mine = repo.find("r1")!!
            assertEquals(MessageEntity.REPLY_UNCHECKED, mine.replyCheck)
            assertEquals("what I quoted", mine.replyToSnippet)
        }

    @Test
    fun `the backlog judges every held pair, in batches, and leaves an absent original's reply unchecked`() =
        runTest {
            repo.saveIfAbsent(original)
            for (i in 1..5) repo.saveIfAbsent(reply(id = "r$i"))
            repo.saveIfAbsent(reply(id = "orphan", quotedId = "never-held"))

            quotes.settleBacklog(me, batch = 2)

            for (i in 1..5) assertEquals(MessageEntity.REPLY_VERIFIED, repo.find("r$i")!!.replyCheck)
            assertEquals(MessageEntity.REPLY_UNCHECKED, repo.find("orphan")!!.replyCheck)
        }
}
