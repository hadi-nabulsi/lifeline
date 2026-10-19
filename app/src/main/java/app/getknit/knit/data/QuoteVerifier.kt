package app.getknit.knit.data

import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.QuoteLabels
import app.getknit.knit.data.message.settleQuote
import app.getknit.knit.data.message.settledWith

/**
 * Judges an inbound reply's quote against the original it names (ADR 2026-10.gpa3). A `ReplyRef` is the sender's
 * claim — an id anyone can mint, an author, and words — so a reply is only as good as the original this phone
 * holds: [app.getknit.knit.data.message.settleQuote] rebuilds a matching quote from it, refuses one that names the
 * wrong author or thread, and carries the original's moderation onto the reply.
 *
 * Originals routinely land after their replies (custody serves newest-first), so a quote is judged on whichever of
 * the two arrives second: [judge] before the reply's insert when its original is already held, else
 * [settleAfterInsert] when either row lands. No transaction is needed to close the race between them: each side
 * reads only after its own insert has committed, so whichever commits second sees the other, and the write is
 * guarded to happen once ([MessageRepository.applyQuoteVerdict]). [settleBacklog] is the net under both.
 *
 * Our own replies are never judged or rewritten: we quoted a row we held, and a re-seal re-ships the stored quote.
 *
 * [labels] is read per judgement so a quote is labelled in the reader's language at the time; [transact] wraps one
 * backlog batch, so an open chat refreshes once per batch rather than once per row.
 */
class QuoteVerifier(
    private val messages: MessageRepository,
    private val labels: () -> QuoteLabels = { QuoteLabels.ENGLISH },
    private val transact: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    /**
     * [reply] with its quote judged, when the original it names is already held; null when it is not (the caller
     * then screens the sender's snippet itself and leaves the row unchecked).
     */
    suspend fun judge(reply: MessageEntity): MessageEntity? {
        val original = reply.replyToId?.let { messages.find(it) } ?: return null
        return reply.settledWith(settleQuote(reply, original, labels()))
    }

    /**
     * Runs after the row [id] was persisted: judges it if it is somebody else's still-unchecked reply whose original
     * landed since [judge] looked, then settles every reply that was waiting on it as an original. Returns the row as
     * stored afterwards, or null when nothing was persisted (an exists-gated or failed commit).
     */
    suspend fun settleAfterInsert(
        id: String,
        me: String,
    ): MessageEntity? {
        val row = messages.find(id) ?: return null
        val quoted = row.replyToId
        val stored =
            if (quoted != null && row.senderId != me && row.replyCheck == MessageEntity.REPLY_UNCHECKED) {
                val original = messages.find(quoted)
                if (original != null) {
                    val settlement = settleQuote(row, original, labels())
                    if (messages.applyQuoteVerdict(id, settlement)) row.settledWith(settlement) else messages.find(id)
                } else {
                    row
                }
            } else {
                row
            }
        settleRepliesTo(row, me)
        return stored
    }

    /**
     * Judges the replies already held whose original is too — those stored before the columns existed, or whose
     * settle a crash cut short. Each batch leaves the set it was read from, so the loop ends; [maxBatches] bounds a
     * single start's share of a very large backlog, and the next start takes the rest.
     */
    suspend fun settleBacklog(
        me: String,
        batch: Int = BACKLOG_BATCH,
        maxBatches: Int = BACKLOG_MAX_BATCHES,
    ) {
        repeat(maxBatches) {
            val replies = messages.unsettledQuotes(me, batch)
            if (replies.isEmpty()) return
            transact {
                for (reply in replies) {
                    val original = reply.replyToId?.let { messages.find(it) } ?: continue
                    messages.applyQuoteVerdict(reply.id, settleQuote(reply, original, labels()))
                }
            }
            if (replies.size < batch) return
        }
    }

    private suspend fun settleRepliesTo(
        original: MessageEntity,
        me: String,
    ) {
        while (true) {
            val waiting = messages.repliesAwaiting(original.id, me, BACKLOG_BATCH)
            for (reply in waiting) messages.applyQuoteVerdict(reply.id, settleQuote(reply, original, labels()))
            if (waiting.size < BACKLOG_BATCH) return
        }
    }

    companion object {
        /** Replies read and judged per query. */
        const val BACKLOG_BATCH = 200

        /** Most batches one mesh start spends on the backlog — 10,000 replies, far past any real history's. */
        const val BACKLOG_MAX_BATCHES = 50
    }
}
