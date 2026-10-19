package app.getknit.knit.data

import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.sha256Hex
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One plaintext, one identity (ADR 2026-10.mptm), against the real SQL: which identity a pick finds, which sealed
 * copy a room want is made from, and when a sealed row learns its plaintext name.
 */
class AttachmentIdentitiesTest : RoomDbTest() {
    private val messages by lazy { MessageRepository(db.messageDao()) }
    private val blobs by lazy {
        BlobRepository(
            blobs = db.blobDao(),
            messages = db.messageDao(),
            peers = db.peerDao(),
            settings = mockk<SettingsStore>(relaxed = true),
            verdicts = db.blobVerdictDao(),
            groups = db.groupDao(),
            forward = db.forwardDao(),
            db = db,
            savedFiles = db.savedFileDao(),
        )
    }
    private val identities by lazy { AttachmentIdentities(messages, blobs) }

    private val photo = "a photo's plaintext bytes".toByteArray()
    private val photoHash = sha256Hex(photo)

    /** [photo] sealed as a DM would seal it: its ciphertext hash and base64 key. */
    private suspend fun sealed(plain: ByteArray = photo): Pair<String, String> {
        val seal = AttachmentCrypto.seal(plain)
        val hash = sha256Hex(seal.blob)
        db.blobDao().insert(BlobEntity(hash, "image/jpeg", seal.blob))
        return hash to b64(seal.key)
    }

    private suspend fun row(
        id: String,
        hash: String,
        key: String?,
        sentAt: Long,
        plainHash: String? = null,
        sourceHash: String? = null,
        conversation: String = "peer",
        animated: Boolean = false,
    ) = db.messageDao().upsert(
        MessageEntity(
            id = id,
            senderId = "peer",
            conversationId = conversation,
            body = "",
            sentAt = sentAt,
            attachmentHash = hash,
            attachmentMime = "image/jpeg",
            attachmentKey = key,
            attachmentPlainHash = plainHash,
            attachmentSourceHash = sourceHash,
            attachmentAnimated = animated,
        ),
    )

    private suspend fun plainOf(id: String) =
        db
            .messageDao()
            .observeById(id)
            .first()
            ?.attachmentPlainHash

    @Test
    fun aSealedCopyIsFoundByItsPlaintextAndOpensToIt() =
        runTest {
            val (hash, key) = sealed()
            row("dm", hash, key, sentAt = 10, plainHash = photoHash)

            val held = checkNotNull(identities.find(photoHash))

            assertEquals(hash, held.hash)
            assertEquals(key, held.key)
            assertEquals(photoHash, held.plainHash)
            assertArrayEquals(photo, identities.plaintextOf(held))
        }

    @Test
    fun theRoomsPublicNameWinsOverASealedCopyOfTheSameFile() =
        runTest {
            val (hash, key) = sealed()
            row("dm", hash, key, sentAt = 1, plainHash = photoHash)
            db.blobDao().insert(BlobEntity(photoHash, "image/jpeg", photo))
            row("room", photoHash, key = null, sentAt = 20, conversation = Conversations.NEARBY)

            val held = checkNotNull(identities.find(photoHash))

            assertEquals(photoHash, held.hash)
            assertNull(held.key)
        }

    @Test
    fun ofTwoSealedCopiesTheEarliestIsTheOne() =
        runTest {
            val (later, laterKey) = sealed()
            val (earlier, earlierKey) = sealed()
            row("b", later, laterKey, sentAt = 20, plainHash = photoHash)
            row("a", earlier, earlierKey, sentAt = 10, plainHash = photoHash)

            assertEquals(earlier, identities.find(photoHash)?.hash)
        }

    @Test
    fun anIdentityWhoseBlobIsGoneIsNoIdentity() =
        runTest {
            row("dm", "c".repeat(64), "a2V5", sentAt = 10, plainHash = photoHash)
            assertNull(identities.find(photoHash))
            assertNull(identities.localCopy(photoHash, author = "peer"))
        }

    @Test
    fun aSourceSentWithItsFramesWinsOverTheStillItWasFlattenedToBefore() =
        runTest {
            // Before #133 an animated WebP went out as a still; a pick of it since goes out animated (ADR 2026-10.8hmx).
            val source = "5".repeat(64)
            val (still, stillKey) = sealed("the flattened still".toByteArray())
            val (frames, framesKey) = sealed("the animation".toByteArray())
            row("then", still, stillKey, sentAt = 10, plainHash = "1".repeat(64), sourceHash = source)
            row("now", frames, framesKey, sentAt = 20, plainHash = "2".repeat(64), sourceHash = source, animated = true)

            val held = checkNotNull(identities.find(source))

            assertEquals(frames, held.hash)
            assertEquals(true, held.animated)
        }

    @Test
    fun ourOwnSendIsFoundByThePickersBytes() =
        runTest {
            val (hash, key) = sealed()
            row("mine", hash, key, sentAt = 10, plainHash = photoHash, sourceHash = "5".repeat(64))

            val held = checkNotNull(identities.find("5".repeat(64)))

            assertEquals(hash, held.hash)
            assertEquals(photoHash, held.plainHash)
        }

    @Test
    fun aSealedRowThatDoesNotKnowItsPlaintextIsNeverMistakenForOne() =
        runTest {
            val (hash, key) = sealed()
            row("dm", hash, key, sentAt = 10, sourceHash = "5".repeat(64))
            assertNull("its plainHash would read the ciphertext's", identities.find("5".repeat(64)))
        }

    @Test
    fun aWrongKeyOrAMismatchedPlaintextOpensToNothing() =
        runTest {
            val (hash, _) = sealed()
            val (_, otherKey) = sealed()
            val held = AttachmentIdentities.Held(hash, otherKey, "image/jpeg", animated = false, plainHash = photoHash)
            assertNull(identities.plaintextOf(held))
            assertNull(identities.plaintextOf(held.copy(key = "not base64 !")))
        }

    @Test
    fun theDmPeerWeGotItFromMayHaveItShownFromOurSealedCopy() =
        runTest {
            val (hash, key) = sealed()
            row("dm", hash, key, sentAt = 10, plainHash = photoHash) // from "peer", in the thread with "peer"

            val (bytes, mime) = checkNotNull(identities.localCopy(photoHash, author = "peer"))

            assertArrayEquals(photo, bytes)
            assertEquals("image/jpeg", mime)
            assertNull("a ciphertext hash is nobody's plaintext", identities.localCopy(hash, author = "peer"))
        }

    @Test
    fun aStrangerPostingTheFileLearnsNothingFromUs() =
        runTest {
            // The confirmation oracle the author rule closes: anyone holding a file could post it to the room and
            // watch which phones show it without pulling it. Only someone who already knows we hold it may.
            val (hash, key) = sealed()
            row("dm", hash, key, sentAt = 10, plainHash = photoHash)
            assertNull(identities.localCopy(photoHash, author = "stranger"))
        }

    @Test
    fun aMemberOfTheGroupItCameThroughMayHaveItShownFromOurCopy() =
        runTest {
            val (hash, key) = sealed()
            val group = "g-crew"
            db.groupDao().upsert(
                GroupEntity(
                    groupId = group,
                    name = "",
                    members = GroupMembersStore.encode(listOf("me", "peer", "bob")),
                    createdBy = "peer",
                    createdAt = 1,
                ),
            )
            row("group", hash, key, sentAt = 10, plainHash = photoHash, conversation = group)

            assertNotNull("bob was in the group", identities.localCopy(photoHash, author = "bob"))
            assertNull("a prefix of a member's id is not a member", identities.localCopy(photoHash, author = "bo"))
            assertNull(identities.localCopy(photoHash, author = "stranger"))
        }

    @Test
    fun theDmPeerWeSentItToMayHaveItShownFromOurCopy() =
        runTest {
            val (hash, key) = sealed()
            db.messageDao().upsert(
                MessageEntity(
                    id = "mine",
                    senderId = "me",
                    recipientId = "carol",
                    conversationId = "carol",
                    body = "",
                    sentAt = 10,
                    attachmentHash = hash,
                    attachmentMime = "image/jpeg",
                    attachmentKey = key,
                    attachmentPlainHash = photoHash,
                ),
            )
            assertNotNull(identities.localCopy(photoHash, author = "carol"))
        }

    @Test
    fun aSealedRowLearnsItsPlaintextNameOnceItsBlobIsHere() =
        runTest {
            val (hash, key) = sealed()
            row("dm", hash, key, sentAt = 10)
            db.blobDao().insert(BlobEntity(photoHash, "image/jpeg", photo))
            row("room", photoHash, key = null, sentAt = 11, conversation = Conversations.NEARBY)

            identities.notePlain(hash)
            identities.notePlain(photoHash) // a key-less row: nothing to learn

            assertEquals(photoHash, plainOf("dm"))
            assertNull(plainOf("room"))
        }

    @Test
    fun aSaveRecordsWhatItOpenedWithoutOpeningItAgain() =
        runTest {
            row("dm", "c".repeat(64), "a2V5", sentAt = 10)
            identities.notePlain("c".repeat(64), photo)
            assertEquals(photoHash, plainOf("dm"))
        }
}
