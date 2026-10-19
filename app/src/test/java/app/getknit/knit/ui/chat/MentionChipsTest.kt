package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.protocol.Mention
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MentionChips] through a real text field, so the edits are the ones the field's own key handling makes: an
 * autocompleted mention is one chip — a backspace or a forward delete takes the whole "@name", and the arrow
 * keys step over it rather than into it. The pure rules are pinned in `MentionTextTest`.
 */
@RunWith(AndroidJUnit4::class)
class MentionChipsTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun field(state: TextFieldState) {
        val mentions = listOf(Mention("n1", "Walter"))
        compose.setContent {
            BasicTextField(
                state = state,
                inputTransformation = MentionChips { mentions },
                modifier = Modifier.testTag("field"),
            )
        }
        compose.onNodeWithTag("field").requestFocus()
    }

    private fun fieldWith(
        state: TextFieldState,
        mentions: List<Mention>,
    ) {
        compose.setContent {
            BasicTextField(
                state = state,
                inputTransformation = MentionChips { mentions },
                modifier = Modifier.testTag("field"),
            )
        }
        compose.onNodeWithTag("field").requestFocus()
    }

    private fun press(key: Key) = compose.onNodeWithTag("field").performKeyInput { pressKey(key) }

    @Test
    fun backspaceAtAChipsEndDeletesTheWholeChip() {
        val state = TextFieldState("hi @Walter", initialSelection = TextRange(10))
        field(state)

        press(Key.Backspace)

        assertEquals("hi ", state.text.toString())
        assertEquals(TextRange(3), state.selection)
    }

    @Test
    fun forwardDeleteAtAChipsStartDeletesTheWholeChip() {
        val state = TextFieldState("hi @Walter ok", initialSelection = TextRange(3))
        field(state)

        press(Key.Delete)

        assertEquals("hi  ok", state.text.toString())
        assertEquals(TextRange(3), state.selection)
    }

    @Test
    fun arrowKeysStepOverAChip() {
        val state = TextFieldState("hi @Walter", initialSelection = TextRange(10))
        field(state)

        press(Key.DirectionLeft)
        assertEquals(TextRange(3), state.selection)
        press(Key.DirectionRight)
        assertEquals(TextRange(10), state.selection)
    }

    @Test
    fun textAroundAChipEditsNormally() {
        val state = TextFieldState("hi @Walter ok", initialSelection = TextRange(13))
        field(state)

        press(Key.Backspace)

        assertEquals("hi @Walter o", state.text.toString())
    }

    @Test
    fun sevenEntriesNamedEveryoneAreOneChip() {
        val everyone = (1..7).map { Mention("n$it", "everyone") }
        val state = TextFieldState("hi @everyone ok", initialSelection = TextRange(12))
        fieldWith(state, everyone)

        assertEquals(1, mentionSpans(state.text, everyone).size)
        press(Key.Backspace)

        assertEquals("hi  ok", state.text.toString())
    }
}
