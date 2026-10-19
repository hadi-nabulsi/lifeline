package app.getknit.knit.mesh.bluetooth

/**
 * Whether the L2CAP responder keeps a dialer whose HELLO it just read, and what a held link scores for
 * eviction. Pure, so the table is a JVM test ([app.getknit.knit.BleAdmissionPolicyTest]).
 *
 * The larger node id dials, and the responder closes a dialer that sorts below it. That settles a cross-dial
 * between two phones that see each other, and every dialer the scan can see is still judged exactly that way —
 * a foreground iPhone too, once its GATT payload is read (companion change A3, ADR 2026-09.kwq2). A dialer this
 * phone has **not** sighted is different: nothing on this side will dial it (a backgrounded iPhone, whose UUID
 * only iOS can read, or any iPhone while the reader is dark), so refusing it leaves the pair unlinked for good.
 * Such a dialer is admitted whatever the order, and a second link from it replaces the first once the held one
 * is [REPLACE_MIN_HOLD_MS] old: that is its fresh link after the old one died on its side before ours noticed
 * (ADR 2026-09.shzv, which stays whole under A3).
 *
 * A peer whose HELLO carries `CAP_DOORBELL` ([DoorbellPolicy.serves] — an iPhone; Android never claims it) can stop
 * being heard while presence still holds it: backgrounded, its UUID moves to Apple's overflow area, and the presence
 * linger keeps the stale GATT-payload sighting for 90 s. For that peer "sighted" means heard within
 * [DOORBELL_HEARD_MS] (interop audit AND-3, paired with the iOS port), and its link scores the floor, not −127, out
 * of presence. Every other peer — the whole Android mesh — is judged and scored exactly as before.
 */
internal object BleAdmissionPolicy {
    enum class Verdict {
        /** Reply to the HELLO and register the link; no link to this node id is held. */
        Admit,

        /** Reply, register, and close the link already held to the same node id. */
        Replace,

        /** Close the socket without a reply. */
        Refuse,
    }

    /**
     * The verdict on a dialer with node id [dialer], given ours ([local]), whether the scan has it in presence
     * right now ([sighted]), and the age of the link we already hold to it ([heldLinkAgeMs], null if none).
     *
     * A dialer we see is judged by the rule every Android build has had: below us it is refused, because the
     * tie-break makes us its dialer; linked, it is refused too. The HELLO is unauthenticated, so that second
     * refusal is also what keeps a device claiming a sighted peer's id from cutting that peer's link. An
     * unsighted dialer's held link is replaced only once it is [REPLACE_MIN_HOLD_MS] old, so a device claiming
     * its id can cut it at most that often (ADR 2026-09.shzv).
     *
     * A [doorbell] dialer (its HELLO carries `CAP_DOORBELL` and [doorbellTrusted] backs it: ADR 2026-09.kwq2's
     * iPhone) counts as sighted only while [heardAgoMs] — the age of its last sighting, null if none — is within
     * [DOORBELL_HEARD_MS] ([sightedForAdmission]). A foreground iPhone we just heard is still refused below us and over a held link,
     * because we dial it; one gone quiet (backgrounded) falls through to the unsighted rows, so it is admitted, and
     * its redial replaces a held link 30 s old, instead of being refused for the rest of the 90 s linger.
     *
     * [atCap] is the debug link cap (`SettingsStore.debugBleLinkCap`) being full: a dialer that would add a
     * link is refused rather than admitted and shed by eviction twenty seconds later. A replacement does not
     * grow the set, so it stands. Always false in release, where the table is exactly the one above.
     */
    fun decide(
        local: String,
        dialer: String,
        sighted: Boolean,
        heldLinkAgeMs: Long?,
        atCap: Boolean = false,
        doorbell: Boolean = false,
        heardAgoMs: Long? = null,
    ): Verdict {
        val seen = sightedForAdmission(sighted, doorbell, heardAgoMs)
        return when {
            dialer == local -> Verdict.Refuse
            seen && local > dialer -> Verdict.Refuse
            heldLinkAgeMs == null -> if (atCap) Verdict.Refuse else Verdict.Admit
            seen -> Verdict.Refuse
            heldLinkAgeMs < REPLACE_MIN_HOLD_MS -> Verdict.Refuse
            else -> Verdict.Replace
        }
    }

    /**
     * Whether [decide] judges a dialer as sighted: presence holds it ([sighted]) and, for a [doorbell] dialer, it was
     * heard within [DOORBELL_HEARD_MS] ([heardAgoMs] is the age of its last sighting; null counts as not heard).
     */
    fun sightedForAdmission(
        sighted: Boolean,
        doorbell: Boolean,
        heardAgoMs: Long?,
    ): Boolean = sighted && !(doorbell && (heardAgoMs == null || heardAgoMs > DOORBELL_HEARD_MS))

    /**
     * Whether a dialer's HELLO claim of `CAP_DOORBELL` ([helloDoorbell]) is backed by this phone's own evidence, so
     * [decide] may give it the doorbell rows. The HELLO is unauthenticated (shzv): without this, a device claiming a
     * sighted Android peer's id and the bit would fall into the unsighted rows whenever that peer went 12 s unheard,
     * and could cut its link every 30 s. Both checks hold together: a link held to the id must have carried the bit in
     * its own HELLO ([heldLinkDoorbell]), and a peer presence holds ([inPresence]) must have been last sighted through
     * its GATT payload ([sightedViaGatt], the iPhone path of ADR 2026-09.kwq2), never service data, so a spoofed link
     * that once got in under an absent peer's id loses the rows once the real peer is back. A dialer neither link nor
     * presence knows has nothing to protect: the unsighted rows apply to it with or without the bit.
     */
    fun doorbellTrusted(
        helloDoorbell: Boolean,
        heldLinkDoorbell: Boolean?,
        inPresence: Boolean,
        sightedViaGatt: Boolean,
    ): Boolean = helloDoorbell && heldLinkDoorbell != false && (!inPresence || sightedViaGatt)

    /**
     * What a held link scores in [PromotionPolicy]'s weakest-first eviction: the scan's smoothed RSSI while the
     * peer is in presence. A peer that has left presence scores [ABSENT_LINK_RSSI] so it is shed first, as it
     * always was. A peer the scan has not once sighted for the whole life of the link ([neverSighted] — an
     * inbound iPhone whose GATT payload this phone has not read) scores the promotion floor instead: it has no
     * RSSI to lose, and at −127 it would be the first link shed every time (ADR 2026-09.shzv).
     *
     * A [doorbell] link (its HELLO carried `CAP_DOORBELL`) out of presence scores the floor too: a foreground
     * iPhone sighted through its GATT payload (ADR 2026-09.kwq2) leaves presence 90 s after it is backgrounded,
     * though the link still carries, and must not be the first shed at the cap. A −82 dBm candidate still beats it.
     */
    fun linkRssi(
        sightedRssi: Double?,
        neverSighted: Boolean,
        doorbell: Boolean = false,
    ): Double = sightedRssi ?: if (neverSighted || doorbell) UNSIGHTED_LINK_RSSI else ABSENT_LINK_RSSI

    /** How old a held link must be before a second dial from an unsighted peer may replace it. */
    const val REPLACE_MIN_HOLD_MS = 30_000L

    /**
     * How recently a `CAP_DOORBELL` dialer must have been heard to count as sighted for admission: 12 s, equal to
     * [PromotionConfig.dwellThresholdMs]'s default, the iOS port's matching window (interop audit AND-3).
     */
    const val DOORBELL_HEARD_MS = 12_000L

    /** [PromotionConfig]'s default floor, in dBm. */
    const val UNSIGHTED_LINK_RSSI = -90.0

    /** A sighted peer that has left presence: the weakest link, first to evict. */
    const val ABSENT_LINK_RSSI = -127.0
}
