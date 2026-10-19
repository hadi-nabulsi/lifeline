package app.getknit.knit.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [handleEach], the loop `MeshManager` runs over every file the radios deliver (#139): a throw while handling one
 * file used to end the session's only collector, and every file after it was dropped until the mesh restarted.
 */
class IncomingFilesTest {
    private fun file(key: String) = ReceivedFile("peer", "/cache/$key", FileKind.ATTACHMENT, key, "image/jpeg")

    @Test
    fun aFileWhoseHandlingThrowsFailsAloneAndEveryFileIsTaken() =
        runTest {
            val handled = mutableListOf<String>()
            val taken = mutableListOf<String>()
            val failed = mutableListOf<String>()
            flowOf(file("a"), file("b"), file("c")).handleEach(
                handle = {
                    check(it.key != "a") { "New BMP version not implemented yet" } // the screen's decoder, #139
                    handled += it.key
                },
                taken = { taken += it.key },
                onFailure = { f, _ -> failed += f.key },
            )
            assertEquals(listOf("a"), failed)
            assertEquals("the files after it are still handled", listOf("b", "c"), handled)
            assertEquals("and every one is taken off its link (#138)", listOf("a", "b", "c"), taken)
        }

    @Test
    fun cancellationStillEndsTheLoopAndTakesTheFileInHand() =
        runTest {
            val taken = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val ended =
                runCatching {
                    flowOf(file("a"), file("b")).handleEach(
                        handle = { throw CancellationException("the session stopped") },
                        taken = { taken += it.key },
                        onFailure = { f, _ -> failed += f.key },
                    )
                }.exceptionOrNull()
            assertTrue("cancellation is not a file's failure: $ended", ended is CancellationException)
            assertTrue(failed.isEmpty())
            assertEquals(listOf("a"), taken)
        }
}
