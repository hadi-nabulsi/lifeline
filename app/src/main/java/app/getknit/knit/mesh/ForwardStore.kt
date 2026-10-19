package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope

/**
 * A chat frame carried for store-and-forward: the decoded routing [envelope] plus the immutable,
 * re-floodable core — the frame [signed] bytes and their [sig]. The throwaway routing counters
 * (ttl/hops) are deliberately NOT stored: a fresh [app.getknit.knit.mesh.protocol.WireEnvelope] wrapper
 * is stamped around [signed]/[sig] each time the frame is re-served, so it re-floods with a full hop
 * budget. A plain class (it holds [ByteArray]s); identity is the frame id ([envelope].id).
 */
class CarriedFrame(
    val envelope: RelayEnvelope,
    val sig: ByteArray,
    val signed: ByteArray,
)

/**
 * Durable store of chat frames carried for store-and-forward, abstracted so [ForwardSync] stays free of
 * Android/Room (and unit-testable). The app's implementation is backed by the encrypted database (see
 * `ForwardRepository`); methods are `suspend` because they read/write it. DM, group, and broadcast-room
 * chat frames are stored (all signed, so a carrier authenticates without decrypting); a DM carries a
 * cleartext recipient to deliver toward, a group its roster, and a broadcast neither (offered to all).
 */
interface ForwardStore {
    /**
     * Persists [frame] seen at [now], tagged [origin] ([ORIGIN_SELF]/[ORIGIN_RELAY]). The implementation
     * stamps a frame-global TTL (`sentAt + ttl`) and enforces the storage caps; a frame whose id is already
     * held is ignored. Returns whether the frame is now (or already was) in custody — false means it was
     * **refused as dead on arrival** (its frame-global expiry has already passed, e.g. a skewed-clock peer
     * re-served a frame every node has swept), so the caller must skip follow-on custody work like pulling
     * the frame's attachment blob.
     */
    suspend fun store(
        frame: CarriedFrame,
        origin: Int,
        now: Long,
    ): Boolean

    /** Non-expired carried frames (at [now]), newest first, for re-offering to a freshly-joined neighbor. */
    suspend fun liveFrames(now: Long): List<CarriedFrame>

    /** Ids of the non-expired carried frames (at [now]) — advertised to a neighbor for the data-path id-diff. */
    suspend fun liveIds(now: Long): List<String>

    /**
     * The newest non-expired carried **chat** frames addressed to [recipientId] and written by anyone but [me],
     * at most [limit] — the relayed half of the long-range re-offer (ADR 039, ADR 2026-10.a4pm). The default
     * derives it from [liveFrames]; the Room implementation queries the indexed column.
     */
    suspend fun liveFramesToFromOthers(
        recipientId: String,
        me: String,
        now: Long,
        limit: Int,
    ): List<CarriedFrame> =
        liveFrames(now)
            .filter { it.envelope.recipientId == recipientId && it.envelope.type == FrameType.CHAT && it.envelope.senderId != me }
            .sortedWith(NEWEST_FIRST)
            .take(limit)

    /**
     * The non-expired carried frames among [ids] (at [now]), newest first by `sentAt` — the own half of the
     * long-range re-offer, chosen by id from our unacked DMs (ADR 2026-10.a4pm). The default derives it from
     * [liveFrames]; the Room implementation reads the rows by key.
     */
    suspend fun liveFramesByIds(
        ids: Collection<String>,
        now: Long,
    ): List<CarriedFrame> {
        val wanted = ids.toHashSet()
        return liveFrames(now).filter { it.envelope.id in wanted }.sortedWith(NEWEST_FIRST)
    }

    /**
     * The non-expired carried **group chat** frames (at [now]) authored by anyone but [excludingSender] — what the
     * custody replay re-enters once a late seed lands (`MeshManager.replayCustodiedGroupFrames`). The default derives
     * it from [liveFrames]; the Room implementation queries the indexed `groupId` column, so a phone in no group
     * answers with zero rows and decodes nothing.
     */
    suspend fun liveGroupChatFrames(
        excludingSender: String,
        now: Long,
    ): List<CarriedFrame> =
        liveFrames(now).filter {
            it.envelope.group != null && it.envelope.type == FrameType.CHAT && it.envelope.senderId != excludingSender
        }

    /**
     * Content hashes referenced by a carried chat frame whose blob we don't yet hold — the carrier's side of
     * the "still-missing blobs" set. Re-requested on startup / neighbor-join so a carrier keeps pulling the
     * image it is custodying until it (or the frame's TTL) resolves. Each maps to the author of the earliest frame
     * naming it, whom the re-armed want asks first (ADR 2026-10.f7gg).
     */
    suspend fun attachmentsNeedingFetch(): Map<String, String>

    /**
     * Every carried frame naming an attachment whose blob we don't yet hold, one entry per frame: whom it is for and
     * when we took it into custody. What [CarrierPullPolicy] reads to hold a pull back while the recipient is linked
     * (ADR 2026-10.g24x). The default holds nothing back, for the fakes; the Room implementation reads the rows.
     */
    suspend fun carriedAttachments(): List<CarriedAttachment> = emptyList()

    /** The carried frame [id]'s cleartext recipient, or null if not held. */
    suspend fun recipientOf(id: String): String?

    suspend fun has(id: String): Boolean

    /**
     * The carried frame [id] as this store holds it — the bytes every plane must put on air for that id
     * (ADR 2026-09.y5f3): a second signing of the same id over content that moved underneath it is a variant
     * no digest can reconcile. Null when not held or expired. The default walks [liveFrames]; the Room
     * implementation reads the row.
     */
    suspend fun frame(
        id: String,
        now: Long,
    ): CarriedFrame? = liveFrames(now).firstOrNull { it.envelope.id == id }

    suspend fun remove(id: String)

    /** Drops every frame whose TTL has elapsed by [now]; returns how many were removed. */
    suspend fun sweepExpired(now: Long): Int

    companion object {
        /** [store] origin: a frame relayed for others — shed first under cap pressure. */
        const val ORIGIN_RELAY = 0

        /** [store] origin: a frame this device authored — kept longest. */
        const val ORIGIN_SELF = 1

        /** `sentAt DESC, id DESC` — the Room queries' order, so a default and the database agree on a tie. */
        private val NEWEST_FIRST: Comparator<CarriedFrame> =
            compareByDescending<CarriedFrame> { it.envelope.sentAt }.thenByDescending { it.envelope.id }
    }
}

/** One carried frame naming attachment [hash]: its cleartext recipient (null for a group or room frame), when we took
 *  it into custody, its author, its group if it is a group's, and whether we are in that group (the store does not
 *  know: its reader fills it in) ([ForwardStore.carriedAttachments]). */
data class CarriedAttachment(
    val hash: String,
    val recipientId: String?,
    val carriedAt: Long,
    val senderId: String? = null,
    val groupId: String? = null,
    val member: Boolean = false,
)
