package app.getknit.knit.data

import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.identity.IdentitySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * The people the name-clash fade never lets go stale (ADR 2026-10.k8ge): accepted DM peers, DM peers we have
 * written to, and every member of a group we have not left. [PeerRepository] adds the verified peers and this
 * device. Only a stranger's clash fades — a contact that went quiet beside a fresh namesake would otherwise read
 * suffixed while the newcomer read plain, which is the look-alike's best case.
 *
 * Shared across every directory a screen collects, so the authored-in query runs once per messages write rather
 * than once per screen. Never seeded with an empty set: a directory built before the first real value would
 * treat every quiet contact as a stranger for that one emission.
 */
class LabelAnchors(
    accepted: Flow<Set<String>>,
    authored: Flow<Set<String>>,
    groups: Flow<List<GroupEntity>>,
    scope: CoroutineScope,
) {
    private val shared: SharedFlow<Set<String>> =
        // The accepted set is DataStore-backed and re-emits on every preferences write (read watermarks, intro state).
        combine(accepted.distinctUntilChanged(), authored, groups, ::labelAnchors)
            .distinctUntilChanged()
            // Replay dies with the last subscriber, so a later one never starts from a set that has since grown.
            .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)

    fun observe(): Flow<Set<String>> = shared

    /** The set now — the live shared value when a screen holds one, else read once. */
    suspend fun current(): Set<String> = shared.replayCache.lastOrNull() ?: shared.first()

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** The production anchors: the accepted set, our authored-in DMs, and the groups table. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun from(
            messages: MessageRepository,
            groups: GroupRepository,
            settings: InboundSettings,
            identity: IdentitySource,
            scope: CoroutineScope,
        ): LabelAnchors =
            LabelAnchors(
                accepted = settings.acceptedConversations,
                authored =
                    flow { emit(identity.nodeId()) }
                        .flatMapLatest { me -> messages.observeConversationsIAuthoredIn(me) }
                        .map { it.toSet() },
                groups = groups.observeGroups(),
                scope = scope,
            )
    }
}

/**
 * The anchor rule: DM ids among [accepted] and [authored] (a DM's conversation id is its peer's node id), and the
 * members of every group in [groups] we have not left — accepted or not, since an extra anchor only keeps a clash
 * as it is today. Wider than the contact rule on purpose; `LabelAnchorsTest` pins every contact inside it.
 */
internal fun labelAnchors(
    accepted: Set<String>,
    authored: Set<String>,
    groups: List<GroupEntity>,
): Set<String> =
    buildSet {
        accepted.filterTo(this) { Conversations.kindFor(it) == ConversationKind.DM }
        authored.filterTo(this) { Conversations.kindFor(it) == ConversationKind.DM }
        groups.filterNot { it.left }.flatMapTo(this) { GroupMembersStore.decode(it.members) }
    }
