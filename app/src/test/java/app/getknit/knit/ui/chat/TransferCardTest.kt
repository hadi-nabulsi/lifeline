package app.getknit.knit.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The card's Retry: drawn only where the view says so, and it answers. The rule itself is `TransferViewTest`'s. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransferCardTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun view(
        phase: TransferPhase,
        retryable: Boolean,
        interrupted: Boolean = false,
    ) = TransferView(
        id = "t1",
        outgoing = true,
        name = "clip.mp4",
        size = 2_000L,
        mime = "video/mp4",
        phase = phase,
        bytes = 0L,
        savedUri = null,
        reason = null,
        interrupted = interrupted,
        retryable = retryable,
    )

    private fun card(view: TransferView): () -> Int {
        var retries = 0
        compose.setContent {
            KnitTheme {
                TransferCard(view = view, mine = true, onAccept = {}, onDecline = {}, onCancel = {}, onRetry = { retries++ })
            }
        }
        return { retries }
    }

    @Test
    fun aSendThatEndedShortOffersRetryAndRetryAnswers() {
        val retries = card(view(TransferPhase.Failed, retryable = true))
        compose.onAllNodesWithTag("transfer_cancel").assertCountEquals(0)
        compose.onNodeWithTag("transfer_retry").performClick()
        assertEquals(1, retries())
    }

    @Test
    fun anInterruptedSendOffersRetryInsteadOfCancel() {
        card(view(TransferPhase.Transferring, retryable = true, interrupted = true))
        compose.onAllNodesWithTag("transfer_retry").assertCountEquals(1)
        compose.onAllNodesWithTag("transfer_cancel").assertCountEquals(0)
    }

    @Test
    fun aCardTheViewDoesNotMarkRetryableHasNoRetry() {
        card(view(TransferPhase.Failed, retryable = false))
        compose.onAllNodesWithTag("transfer_retry").assertCountEquals(0)
    }
}
