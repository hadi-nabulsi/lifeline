package app.getknit.knit.blepair

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.peer.PeerEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Edits to one's own profile reach the peer over a real BLE link, through the writes the Profile screen makes (the
 * mesh watches the store and re-broadcasts the profile), and nothing else.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BlePairProfileTest : BlePairTest() {
    @Test
    fun a_aNewDisplayNameReachesThePeer() =
        runBlocking<Unit> {
            awaitLink()
            settings.setProfile(nameOf(self), "")
            val started = System.currentTimeMillis()
            awaitPeerRow("the peer's new name ${nameOf(peer)}") { it.name == nameOf(peer) }
            note("the peer's new name showed in ${System.currentTimeMillis() - started} ms")
        }

    @Test
    fun b_aProfilePhotoReachesThePeer() =
        runBlocking<Unit> {
            awaitLink()
            val photo = noiseBitmap(AVATAR_SIDE, "$run-avatar-$self")
            val hash = checkNotNull(avatars.saveOwnAvatar(photo)) { "the noise photo was refused as explicit" }
            photo.recycle()
            settings.setOwnAvatar(hash, System.currentTimeMillis()) // the stamp re-broadcasts the profile
            note("set our photo $hash")
            val started = System.currentTimeMillis()
            val theirs = awaitPeerRow("a photo on the peer's profile") { it.avatarHash != null }
            awaitHeld(checkNotNull(theirs.avatarHash))
            note("held the peer's photo ${theirs.avatarHash} in ${System.currentTimeMillis() - started} ms")
        }

    private fun nameOf(node: String) = "Pair ${node.take(4)}"

    private suspend fun awaitPeerRow(
        what: String,
        match: (PeerEntity) -> Boolean,
    ): PeerEntity =
        runCatching {
            withTimeout(PROFILE_TIMEOUT_MS) {
                peers.observePeers().first { rows -> rows.any { it.nodeId == peer && match(it) } }.first { it.nodeId == peer }
            }
        }.getOrElse { throw AssertionError("no $what within $PROFILE_TIMEOUT_MS ms; the row: ${peers.find(peer)}", it) }

    private companion object {
        const val AVATAR_SIDE = 320
        const val PROFILE_TIMEOUT_MS = 120_000L
    }
}
