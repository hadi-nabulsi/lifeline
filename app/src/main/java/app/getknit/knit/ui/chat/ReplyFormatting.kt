package app.getknit.knit.ui.chat

import android.content.Context
import app.getknit.knit.R
import app.getknit.knit.data.message.QuoteLabels
import app.getknit.knit.mesh.protocol.ReplyRef

/**
 * The author label the composer's reply banner shows: the [selfLabel] ("You") when the quoted message is the
 * viewer's own ([ReplyRef.authorId] == [myNodeId]), else the snapshotted [ReplyRef.author]. The swap is
 * viewer-relative, which is why it's resolved here at render time from [myNodeId] rather than baked into the stored
 * snapshot. Only for a quote this phone is composing from a row it holds: a received quote's author is the reader's
 * own label for its id, never the sender's string (`ChatViewModel`'s `QuoteView`, ADR 2026-10.gpa3).
 */
fun quoteAuthorLabel(
    replyTo: ReplyRef,
    myNodeId: String,
    selfLabel: String,
): String = if (replyTo.authorId == myNodeId) selfLabel else replyTo.author

/** The labels a quote names a positional or body-less original by, in the reader's language (see [QuoteLabels]). */
fun quoteLabels(context: Context): QuoteLabels =
    QuoteLabels(
        location = context.getString(R.string.chat_list_preview_location),
        voice = context.getString(R.string.chat_reply_voice),
        fileFormat = context.getString(R.string.chat_list_preview_file),
        gif = context.getString(R.string.chat_list_preview_gif),
    )
