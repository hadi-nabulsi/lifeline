package app.getknit.knit.fieldlog

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FieldJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun line(t: Long) = """{"t":$t,"e":"mesh_off"}"""

    @Test
    fun linesComeBackOldestFirstAndOnlyInsideTheWindow() {
        val journal = FieldJournal(tmp.root, keepMs = 1_000)
        journal.append(listOf(line(100), line(900)))
        journal.append(listOf(line(1_500)))

        assertEquals(listOf(line(900), line(1_500)), journal.read(now = 1_800))
    }

    @Test
    fun aFullSegmentRollsAndTheOneBeforeItGoes() {
        val journal = FieldJournal(tmp.root, segmentBytes = 40)
        journal.append(listOf(line(1), line(2))) // past 40 bytes: rolls to prev
        journal.append(listOf(line(3), line(4))) // rolls again: the first pair goes
        journal.append(listOf(line(5)))

        assertEquals(listOf(line(3), line(4), line(5)), journal.read(now = 10))
        assertTrue(journal.size() < 3 * 2 * line(5).length)
    }

    @Test
    fun aTornTailIsSkippedAndDoesNotSpoilTheNextLine() {
        val journal = FieldJournal(tmp.root)
        journal.append(listOf(line(1)))
        File(tmp.root, "current.jsonl").appendText("""{"t":2,"e":"po""") // a process death mid-write
        journal.append(listOf(line(3)))

        assertEquals(listOf(line(1), line(3)), journal.read(now = 10))
    }

    @Test
    fun clearDeletesTheLinesAndMintsANewSalt() {
        val journal = FieldJournal(tmp.root)
        val salt = journal.salt()
        assertArrayEquals("the salt is kept between reads", salt, journal.salt())
        journal.append(listOf(line(1)))

        journal.clear()

        assertEquals(emptyList<String>(), journal.read(now = 10))
        assertFalse("a new log's peers read under new pseudonyms", salt.contentEquals(journal.salt()))
    }
}
