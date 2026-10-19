package app.getknit.knit.moderation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the shipped `moderation/slurs_en.txt` (ADR 2026-10.d62w): the identity-slur list every
 * conversation scope runs, DMs and groups included. Because it blocks sends in private chats too, the
 * exclusions are pinned as hard as the inclusions — an in-group or everyday word here blocks someone talking
 * to a friend.
 */
class SlurListTest {
    private val terms: List<String> by lazy { ShippedWordList.read(ShippedWordList.SLURS) }

    /** The DM/group chain as the DI module builds it, with no model: what a private chat still catches. */
    private val direct by lazy {
        HybridTextModerator(lexical = LexicalTextFilter(terms, category = TextVerdict.Category.SLUR))
    }

    @Test
    fun everyTermIsAMatchableSortedUniqueToken() {
        assertTrue("the slur list should parse to a non-empty list", terms.isNotEmpty())
        terms.forEach { assertTrue("dead or non-[a-z] term shipped: '$it'", it.matches(TOKEN)) }
        terms.forEach { assertTrue("term shorter than 3 chars over-blocks: '$it'", it.length >= 3) }
        assertEquals("no duplicates", terms.size, terms.toHashSet().size)
        assertEquals("sorted, so regenerations diff cleanly", terms.sorted(), terms)
    }

    @Test
    fun theSlursTheModelMissesAreListedWithTheirPlurals() {
        // Each scored below every enforced threshold on the bundled classifier; the whole-token matcher does
        // not stem, so the generator adds the plural.
        listOf("nigger", "niggers", "kike", "kikes", "spic", "spics", "wetback", "faggot", "faggots", "tranny")
            .forEach { assertTrue("'$it' should be on the slur list", it in terms) }
    }

    @Test
    fun inGroupEverydayAndSelfDescriptiveWordsAreNot() {
        // nigga: in-group use. coon: raccoon. fag: a cigarette in Britain. The rest describe people or name
        // something without insulting anyone. The room's profanity list may still carry some of these.
        listOf(
            "nigga",
            "niggas",
            "coon",
            "coons",
            "fag",
            "fagot",
            "dyke",
            "queer",
            "gay",
            "trans",
            "twink",
            "negro",
            "haji",
            "hajji",
            "swastika",
            "jap",
            "black",
            "jew",
            "jewish",
            "muslim",
            "mexican",
        ).forEach { assertFalse("'$it' must not be blocked in private chats", it in terms) }
    }

    @Test
    fun aSlurIsFlaggedInAPrivateChatWithoutTheModel() =
        runTest {
            for (text in listOf("you are a nigger", "N1GG3R", "k i k e", "dirty spics", "f.a.g.g.o.t")) {
                val verdict = direct.classify(text)
                assertTrue("'$text' should be flagged", verdict.flagged)
                assertEquals(TextVerdict.Category.SLUR, verdict.category)
            }
        }

    @Test
    fun ordinaryPrivateSpeechStillGoesThrough() =
        runTest {
            // Profanity stays room-only, and the exclusions above hold through the matcher.
            for (text in listOf("fuck off, mate", "this is shit", "nigga what's up", "went coon hunting", "got a fag?")) {
                assertFalse("'$text' should be allowed in a DM", direct.classify(text).flagged)
            }
        }

    private companion object {
        val TOKEN = Regex("^[a-z]+$")
    }
}
