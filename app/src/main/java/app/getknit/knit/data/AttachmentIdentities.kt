package app.getknit.knit.data

import app.getknit.knit.data.message.AttachmentIdentityRow
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.sha256Hex

/**
 * The one rule about a file's name on the mesh (ADR 2026-10.mptm): **a phone seals a given plaintext once.** A file
 * sent again — a forward, a repeat, a re-share of something saved from a chat — goes out under the identity it
 * arrived under, so every phone that already holds it, carriers included, holds it under that name and pulls
 * nothing. A picture from the room keeps its public, key-less name; one from a DM or a group keeps its ciphertext
 * hash and key. And a room post of a picture this phone holds sealed is shown from its own store instead of pulled —
 * when its author was party to the conversation the sealed copy came through, so the copy tells nobody that we
 * hold the file who did not know already.
 *
 * What it costs is said in the ADR: a carrier that sees two frames name one hash learns they carry one file, and a
 * DM forwarding a room picture says so to every phone nearby that holds the post. What it never does is let
 * someone who merely has the file find it, which is why this is not convergent encryption.
 *
 * The identities live on the `messages` rows (`attachmentPlainHash` / `attachmentSourceHash`), so one goes when the
 * last row naming it does, with the blob.
 */
class AttachmentIdentities(
    private val messages: MessageRepository,
    private val blobs: BlobRepository,
) {
    /**
     * An identity a file already goes by: the [hash] it travels under, its [key] (null for a key-less, plaintext
     * one), the [mime] and [animated] flag its first row gave it, and the hash of its plaintext, [plainHash] —
     * equal to [hash] when there is no key.
     */
    data class Held(
        val hash: String,
        val key: String?,
        val mime: String,
        val animated: Boolean,
        val plainHash: String,
    )

    /** The held identity a file whose bytes hash to [bytesHash] already goes by, or null. */
    suspend fun find(bytesHash: String): Held? = messages.attachmentIdentityFor(bytesHash)?.toHeld()

    /**
     * The plaintext behind [held] — its blob as stored when key-less, opened with its key when sealed — or null
     * when the blob is gone, the key does not open it, or what comes out does not hash to [Held.plainHash].
     */
    suspend fun plaintextOf(held: Held): ByteArray? {
        val stored = blobs.bytes(held.hash) ?: return null
        val plain = if (held.key == null) stored else open(stored, held.key) ?: return null
        return plain.takeIf { sha256Hex(it) == held.plainHash }
    }

    /**
     * The bytes and MIME of [hash] when it is the plaintext of a sealed attachment this phone holds from a
     * conversation [author] was party to — the copy a key-less frame of theirs (a room post, a forward of one) is
     * shown from instead of the mesh. Null otherwise, which for a stranger's picture is one indexed read.
     *
     * Never for anyone else: a copy made here is visible from outside (we would then serve bytes nobody saw us
     * pull), so letting a stranger's post or a bare `blobreq` trigger it would tell anyone holding a file which
     * phones hold it privately — the confirmation oracle this rule exists to avoid.
     */
    suspend fun localCopy(
        hash: String,
        author: String,
    ): Pair<ByteArray, String>? {
        val held = messages.sealedAttachmentHolding(hash, author)?.toHeld() ?: return null
        return plaintextOf(held)?.let { it to held.mime }
    }

    /**
     * Records the plaintext name of the sealed attachment [hash] on the rows naming it, once its blob is here —
     * called when a blob lands and when a row arrives for one already held. Decrypts only when some row still
     * lacks the name, so a carrier (no row) and a repeat cost one read.
     */
    suspend fun notePlain(hash: String) {
        val key = messages.attachmentKeyWantingPlain(hash) ?: return
        val stored = blobs.bytes(hash) ?: return
        val plain = open(stored, key) ?: return
        messages.setAttachmentPlainHash(hash, sha256Hex(plain))
    }

    /** [notePlain] for a caller already holding the opened [plain] bytes — a save, which decrypted to write. */
    suspend fun notePlain(
        hash: String,
        plain: ByteArray,
    ) {
        messages.attachmentKeyWantingPlain(hash) ?: return
        messages.setAttachmentPlainHash(hash, sha256Hex(plain))
    }

    /** [stored] opened with the base64 [key] — a peer's, off a sealed frame — or null when either is no good. */
    private fun open(
        stored: ByteArray,
        key: String,
    ): ByteArray? = runCatching { b64d(key) }.getOrNull()?.let { AttachmentCrypto.open(stored, it) }

    private fun AttachmentIdentityRow.toHeld() = Held(hash = hash, key = key, mime = mime, animated = animated, plainHash = plainHash)
}
