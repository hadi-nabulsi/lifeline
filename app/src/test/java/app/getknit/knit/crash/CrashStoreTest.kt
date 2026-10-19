package app.getknit.knit.crash

import app.getknit.knit.identity.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var clock = 1_700_000_000_000L

    private fun store(dir: File = temp.root.resolve("crashes")) = CrashStore(dir) { clock }

    private fun CrashStore.recordAt(
        millis: Long,
        message: String = "boom",
    ): File? {
        clock = millis
        return record(testEnvironment(), "main", throwableWith(message))
    }

    @Test
    fun `writes a report named for its timestamp`() {
        val file = requireNotNull(store().recordAt(1_700_000_000_123L))
        assertTrue(file.name.matches(Regex("""crash-\d{13}-[0-9a-f]{8}\.txt""")))
        assertTrue(file.name.startsWith("crash-1700000000123-"))
    }

    @Test
    fun `keeps only the five newest reports`() {
        val store = store()
        repeat(6) { index -> store.recordAt(1_700_000_000_000L + index * 1_000L, message = "boom $index") }
        val kept = store.list()
        assertEquals(5, kept.size)
        assertEquals(1_700_000_005_000L, kept.first().at)
        assertEquals(1_700_000_001_000L, kept.last().at)
    }

    @Test
    fun `lists newest first`() {
        val store = store()
        listOf(3L, 1L, 2L).forEach { store.recordAt(1_700_000_000_000L + it * 1_000L, message = "boom $it") }
        assertEquals(store.list().map { it.at }.sortedDescending(), store.list().map { it.at })
    }

    @Test
    fun `two crashes in the same millisecond both survive`() {
        val store = store()
        store.recordAt(1_700_000_000_000L, message = "first")
        store.recordAt(1_700_000_000_000L, message = "second")
        assertEquals(2, store.list().size)
    }

    @Test
    fun `a crash loop does not evict an unrelated older report`() {
        val store = store()
        store.recordAt(1_700_000_000_000L, message = "the rare one")
        repeat(4) { index -> store.recordAt(1_700_000_001_000L + index * 1_000L, message = "loop") }
        assertTrue(store.list().any { it.file.readText().contains("the rare one") })
    }

    @Test
    fun `truncates a deep stack but keeps its header and top frame`() {
        // A StackOverflowError arrives with thousands of frames; the per-message cap cannot help there,
        // so this is what exercises the report-level cap.
        clock = 1_700_000_000_000L
        val deep =
            StackOverflowError().apply {
                stackTrace =
                    Array(5_000) { index ->
                        StackTraceElement("app.getknit.knit.mesh.MeshRouter", "route$index", "MeshRouter.kt", index)
                    }
            }
        val file = requireNotNull(store().record(testEnvironment(), "main", deep))
        val text = file.readText()
        assertTrue(text.length < 70_000)
        assertTrue(text.startsWith("FATAL EXCEPTION: main"))
        assertTrue(text.contains("MeshRouter.route0(MeshRouter.kt:0)"))
        assertTrue(text.contains("[truncated,"))
    }

    @Test
    fun `a huge exception message is capped without truncating the report`() {
        val file = requireNotNull(store().recordAt(1_700_000_000_000L, message = "chatter ".repeat(40_000)))
        val text = file.readText()
        assertTrue(text.length < 2_000)
        assertTrue(text.contains("...(+"))
    }

    @Test
    fun `declines instead of throwing when the directory cannot be created`() {
        val blocker = temp.newFile("not-a-dir")
        assertNull(CrashStore(blocker.resolve("crashes")) { clock }.record(testEnvironment(), "main", throwableWith("x")))
    }

    @Test
    fun `sweeps interrupted temp files but leaves unrelated files alone`() {
        val dir = temp.newFolder("crashes")
        val stale = dir.resolve("crash-1700000000000-deadbeef.txt.tmp").apply { writeText("partial") }
        val unrelated = dir.resolve("README").apply { writeText("keep me") }
        store(dir).recordAt(1_700_000_009_000L)
        assertFalse(stale.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `stores a redacted trace, never the raw one`() {
        val node = NodeId.derive("peer")
        val file = requireNotNull(store().recordAt(1_700_000_000_000L, message = "hello reply $node"))
        val text = file.readText()
        assertFalse(text.contains(node))
        assertTrue(text.contains("[node]"))
    }

    @Test
    fun `parses the header back into the ref`() {
        val store = store()
        store.recordAt(1_700_000_000_000L)
        val ref = requireNotNull(store.latest())
        assertEquals("2.3.0 (13) debug", ref.appVersion)
        assertEquals("Google Pixel 8 (shiba)", ref.device)
        assertEquals("16 (SDK 36)", ref.androidVersion)
        assertEquals("IllegalStateException at BluetoothMeshTransport.kt:552", ref.summary)
    }

    @Test
    fun `applies the name pass only when reading`() {
        val store = store()
        store.recordAt(1_700_000_000_000L, message = "no peer named Ada Lovelace")
        val ref = requireNotNull(store.latest())
        assertTrue(ref.file.readText().contains("Ada Lovelace"))
        val read = requireNotNull(store.readRedacted(ref, KnownSecrets(setOf("Ada Lovelace"))))
        assertFalse(read.contains("Ada Lovelace"))
    }

    @Test
    fun `clear removes every report`() {
        val store = store()
        repeat(3) { store.recordAt(1_700_000_000_000L + it * 1_000L, message = "boom $it") }
        store.clear()
        assertTrue(store.list().isEmpty())
    }

    private fun anr(
        at: Long,
        predatesInstall: Boolean = false,
        onRead: () -> Unit = {},
    ) = AnrExit(at, "executing service app.getknit.knit/.mesh.MeshService", predatesInstall) {
        onRead()
        // Distinct per ANR, as real dumps are (memory lines, addresses): identical ones share one fingerprint slot.
        "RssKb: $at\n\"main\" prio=5 tid=1 Native\n  at android.os.MessageQueue.nativePollOnce(Native method)\n"
    }

    @Test
    fun `imports an ANR as a report stamped when it happened`() {
        val store = store()
        assertEquals(1, store.importAnrs(testEnvironment(), listOf(anr(1_699_000_000_000L))))
        val ref = requireNotNull(store.latest())
        assertEquals(1_699_000_000_000L, ref.at)
        assertEquals("ApplicationNotResponding at Native method", ref.summary)
        assertEquals("2.3.0 (13) debug", ref.appVersion)
        assertTrue(ref.file.readText().contains("Knit ANR report"))
        assertTrue(ref.file.readText().startsWith("FATAL EXCEPTION: main"))
    }

    @Test
    fun `never imports the same ANR twice, even after a delete all`() {
        val store = store()
        var reads = 0
        val exits = listOf(anr(1_699_000_002_000L) { reads++ }, anr(1_699_000_001_000L) { reads++ })
        assertEquals(2, store.importAnrs(testEnvironment(), exits))
        store.clear()
        assertEquals(0, store.importAnrs(testEnvironment(), exits))
        assertEquals("a seen ANR's dump is never read again", 2, reads)
        assertTrue(store.list().isEmpty())
        assertEquals(1, store.importAnrs(testEnvironment(), listOf(anr(1_699_000_003_000L)) + exits))
    }

    @Test
    fun `imports only the five newest of a long history`() {
        val store = store()
        val exits = (1..8).map { anr(1_699_000_000_000L + it * 1_000L) }.reversed()
        assertEquals(5, store.importAnrs(testEnvironment(), exits))
        assertEquals((8 downTo 4).map { 1_699_000_000_000L + it * 1_000L }, store.list().map { it.at })
    }

    @Test
    fun `an ANR from before this install names no version`() {
        val store = store()
        store.importAnrs(testEnvironment(obfuscated = true), listOf(anr(1_699_000_000_000L, predatesInstall = true)))
        val ref = requireNotNull(store.latest())
        assertEquals("unknown - recorded before Knit was updated to 2.3.0 (13) debug", ref.appVersion)
        assertFalse(ref.file.readText().contains("mapping-2.3.0"))
    }

    @Test
    fun `a dump that cannot be read still leaves a report`() {
        val store = store()
        val exit = AnrExit(1_699_000_000_000L, "executing service x", predatesInstall = false) { error("gone") }
        assertEquals(1, store.importAnrs(testEnvironment(), listOf(exit)))
        assertTrue(requireNotNull(store.latest()).file.readText().contains("ApplicationNotResponding: executing service x"))
    }
}
