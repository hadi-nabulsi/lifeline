package app.getknit.knit.blepair

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.ui.contacts.ContactsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.koin.core.context.GlobalContext

/**
 * A group chat over a real BLE link. One side starts it the way the Contacts screen does; the other learns of it from
 * its first post (the seed carries the founding roster, ADR 2026-09.mjaj), and both post into it.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BlePairGroupTest : BlePairTest() {
    @Test
    fun a_aGroupOfThePairCarriesPostsBothWays() =
        runBlocking<Unit> {
            awaitLink()
            // The id is the hash of the member set, so both sides know it before either has the group.
            val groupId = Conversations.groupIdFor(listOf(self, peer))
            if (isSmaller) {
                GlobalContext.get().get<ContactsViewModel>().createGroup(listOf(peer))
                runCatching { withTimeout(GROUP_TIMEOUT_MS) { while (groups.find(groupId) == null) delay(POLL_MS) } }
                    .onFailure { throw AssertionError("createGroup never wrote $groupId", it) }
                post(groupId)
                awaitPost(groupId)
            } else {
                awaitPost(groupId)
                post(groupId)
            }
        }

    private suspend fun post(groupId: String) {
        val group = checkNotNull(groups.find(groupId)) { "no group $groupId here" }
        assertTrue(
            "sendChat refused the group post",
            mesh.sendChat(body("group post from $self"), group = group.toGroupInfo()),
        )
    }

    private suspend fun awaitPost(groupId: String) =
        awaitRow(groupId, GROUP_TIMEOUT_MS, "the peer's post in the group") {
            it.senderId == peer && it.body == body("group post from $peer")
        }

    private companion object {
        const val GROUP_TIMEOUT_MS = 120_000L
    }
}
