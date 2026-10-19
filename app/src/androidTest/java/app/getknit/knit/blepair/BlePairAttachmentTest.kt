package app.getknit.knit.blepair

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.AttachmentStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.random.Random

/** Pictures and files over a real BLE link: the bulk plane, the holder's serve and the receiver's screening. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BlePairAttachmentTest : BlePairTest() {
    @Test
    fun a_aPhotoCrossesBothWays() =
        runBlocking<Unit> {
            awaitLink()
            val ingested = ingested(attachments.ingest(Uri.fromFile(noisePhoto(PHOTO_SIDE, "$run-$self"))))
            note("sending a ${ingested.mime} photo ${ingested.hash}")
            assertTrue(
                "sendChat refused the photo",
                mesh.sendChat(body("photo from $self"), attachment = ingested, recipientId = peer),
            )
            val started = System.currentTimeMillis()
            val theirs =
                awaitRow(peer, ATTACHMENT_TIMEOUT_MS, "the peer's photo message") {
                    it.senderId == peer && it.body == body("photo from $peer") && it.attachmentHash != null
                }
            awaitHeld(checkNotNull(theirs.attachmentHash))
            note("held the peer's photo ${System.currentTimeMillis() - started} ms after sending ours")
        }

    /** A non-image through the file picker's door keeps its name, and lands as bytes the receiver holds. */
    @Test
    fun b_aFileCrossesBothWaysUnderItsName() =
        runBlocking<Unit> {
            awaitLink()
            val file = File(context.cacheDir, fileName(self))
            val random = Random("$run-$self".hashCode())
            file.writeText(List(FILE_LINES) { "line $it ${random.nextLong().toULong().toString(36)}" }.joinToString("\n"))
            // A file:// Uri offers no display name, so the file's own stands in, as a document provider would give it.
            val picked = ingested(attachments.ingestFile(Uri.fromFile(file)))
            val ingested = picked.takeIf { it.name != null } ?: picked.copy(name = file.name)
            note("sending ${ingested.name} (${ingested.mime}, ${file.length()} B) ${ingested.hash}")
            assertTrue(
                "sendChat refused the file",
                mesh.sendChat(body("file from $self"), attachment = ingested, recipientId = peer),
            )
            val theirs =
                awaitRow(peer, ATTACHMENT_TIMEOUT_MS, "the peer's file message") {
                    it.senderId == peer && it.body == body("file from $peer") && it.attachmentHash != null
                }
            assertEquals("the file arrived under another name", fileName(peer), theirs.attachmentName)
            awaitHeld(checkNotNull(theirs.attachmentHash))
        }

    private fun fileName(author: String) = "pair-notes-${author.take(8)}.txt"

    private fun ingested(result: AttachmentStore.IngestResult): AttachmentStore.Ingested {
        assertTrue("ingest refused it: $result", result is AttachmentStore.IngestResult.Success)
        return (result as AttachmentStore.IngestResult.Success).ingested
    }

    private companion object {
        const val PHOTO_SIDE = 160
        const val FILE_LINES = 800 // ~20 KB
    }
}
