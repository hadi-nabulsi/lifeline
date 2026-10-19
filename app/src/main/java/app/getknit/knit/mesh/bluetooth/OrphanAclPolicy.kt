package app.getknit.knit.mesh.bluetooth

/**
 * Finds the LE connections a Bluetooth channel left up when it closed, lets each one go, and says what it found (#142,
 * ADR 2026-10.b8yf).
 *
 * An LE ACL can outlive its last L2CAP channel (Knit's CoC). On Android 12 the ATT fixed channel never idles out
 * unless a local GATT client held the link and left. On Android 16/17 the side that dialed never gets the no-app idle
 * timeout (the stack's `gatt_set_idle_timeout … status=false` at connect), so its ACL stays too. Such an orphan sits
 * idle for half an hour and takes a share of the controller's buffers from every other link, which halved a phone's
 * file drain. The
 * transport arms every ACL a channel opened on (a
 * GATT client attaches and leaves, [BluetoothMeshTransport]'s `armAcl`): at link-up, and when a channel closes without
 * becoming a link (a refused dialer, a failed HELLO, a dial that connected and then failed). The stack then drops the ACL
 * about 4 s after the channel closes on Android 16/17 (1–4 s on Android 12, by source). This is the net under those arms,
 * and the field log's record of where an arm missed.
 *
 * **The oracle is the ACL broadcasts** ([onAcl]), never `getConnectedDevices(GATT)`, which shows another device's LE
 * connection only while some app's GATT server is registered. An ended channel's address is watched only while its
 * connect edge was seen and no disconnect edge has followed, so a missed edge can make the policy miss an orphan, never
 * flag a connection that is gone. A link up on the address again (a relink over the same ACL) forgets it ([onLinkUp]).
 *
 * **The rules**, read on the transport's minute tick ([check]):
 * - An ACL still up [graceMs] after its channel ended, or after its last [Release], is an orphan. The arm drops it in
 *   about 4 s (1–4 s on Android 12), so the grace is the arm's margin.
 * - An orphan is released (attached and left again) at most [maxTries] times, then reported once as kept. One that goes
 *   down is reported once as cleared, with the tries it took. Exactly one [Report] per orphan, never one for an ACL that
 *   went down inside the grace.
 * - A disconnect is believed only once the address has stayed down for [graceMs]: Android 12 re-dials every dropped LE
 *   peer from its controller's accept list within 2–3 s, and that re-made ACL carries no channel. It is the same orphan
 *   back, so its tries carry over, and an entry that came back that way ([REDIALED_TRIES]) gets one try, not
 *   [maxTries]: each release it gets only invites the next re-dial. A peer's RPA rotation stops further re-dials but
 *   does not end an ACL already up, so a re-made orphan the tries did not clear stays until it times out, the adapter
 *   cycles, or a Knit dial reuses it (and arms it at link-up).
 * - An address in `busy` (a live link, a dial in flight) is left alone that round.
 * - With the arm switched off (`…debug.LINKTUNE --ez arm false`) nothing is released and an orphan is reported with
 *   `tries = 0`.
 * - At most [maxEntries] addresses are watched; the oldest goes first, a HELLO-less channel's before a named one's.
 * - At [stop], an ACL still up whose arm never attached, nor is still trying, is handed back once ([Unarmed]): nothing
 *   watches it after the transport stops.
 *
 * **The arms are bounded** (#150, the ADR's amendment). Each arm is one GATT client registration, of the 32 a device
 * shares with the doorbell, the payload reader, a Meshtastic board, the watch and every other app, and a dialer that
 * opens and closes channels over one ACL many times a second ends one unlinked channel each time.
 * - Every arm and every release is a *flight*, named by the token its begin returns, and holds one of
 *   [MAX_ARMS_IN_FLIGHT] slots from its begin until it attaches, or, when it never attached, for [SECOND_CLOSE_MS] more
 *   (its late registration's second close); one that never made a client frees it at once ([onNeverRegistered]). A
 *   flight that never reports back lapses to failed after [ARM_LAPSE_MS]. A HELLO-less channel's arms hold at most
 *   [MAX_UNNAMED_ARMS] slots, so one stays for releases and named arms.
 *   [check] emits a [Release] only into a free slot, the fewest tries first; one that finds none waits for the
 *   next round, its tries intact.
 * - Each address keeps a row: its latest arm and whether any arm attached to the ACL. It outlives a channel's end
 *   (neither [onLinkEnded] nor [onLinkUp] erases it), and an arm's end is read against the row's token, so a superseded
 *   arm's failure (a cross-dial's link-up arm replaced an unlinked one) never downgrades the row, while any attach marks
 *   it attached.
 * - [tryArm] refuses an unlinked arm while one is pending on the address, while one attached and no disconnect edge has
 *   come since (the idle timeout it set lives on that ACL), for [ARM_RETRY_MS] after one failed, and while every slot
 *   is held; [msUntilOpen] says how long until a retry or slot refusal may pass. A link-up arm ([onArming]) and a
 *   doorbell's attach ([onDoorbellAttached]) are never refused: every link is armed, and links are few.
 * - An attached row goes with the ACL's disconnect edge, and with a connect edge more than [EDGE_TRAIL_MS] after its
 *   attach (a connect edge is a new ACL; one that close may only trail the attach it raced). A pending or failed row
 *   survives a disconnect edge: its registration may still exist. [clear] and [stop] forget everything, and an arm's end
 *   reported after them is ignored.
 * - A HELLO-less channel's watch ([onLinkEnded] with `named = false`) never replaces a named entry, at most
 *   [MAX_UNNAMED] of them are watched, and the trim takes them first, so a flood of them cannot push a peer's orphan out.
 *
 * Pure and thread-safe: the edges arrive on the main thread, link ends on any, the tick on the transport's scope.
 */
internal class OrphanAclPolicy<D>(
    private val graceMs: Long = GRACE_MS,
    private val maxTries: Int = MAX_TRIES,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    /** What [check] asks of the transport for one watched address. */
    sealed interface Action<out D> {
        val address: String
        val nodeId: String
    }

    /** Attach a GATT client to the orphan's ACL and leave again: try [tries]. Its end goes to [onArmed] with [token]. */
    data class Release<D>(
        override val address: String,
        override val nodeId: String,
        val device: D,
        val tries: Int,
        val endedAgoMs: Long,
        val token: Long,
    ) : Action<D>

    /**
     * One orphan's record: whether its channel ended a link ([linked]) or never became one, whether its arm attached,
     * how many releases it had, whether it went down, and how long it outlived its channel (to the disconnect when
     * [cleared], else to the report).
     */
    data class Report(
        override val address: String,
        override val nodeId: String,
        val linked: Boolean,
        val armed: Boolean,
        val tries: Int,
        val cleared: Boolean,
        val ageMs: Long,
    ) : Action<Nothing>

    /** An ACL still up at [stop] whose arm never attached: armed once more by the transport, and recorded. */
    data class Unarmed<D>(
        val address: String,
        val nodeId: String,
        val device: D,
        val linked: Boolean,
        val endedAgoMs: Long,
    )

    /** Where an address's arm stands: none made, one still trying, or one that attached or gave up. */
    enum class Arm { NONE, PENDING, ATTACHED, FAILED }

    /**
     * What [tryArm] says of an unlinked arm: [Open] (begin it, and report its end with [Open.token]), or refused because
     * an arm is [Pending] on the address, one [Attached] to its ACL, one failed too recently to [Retry], or every slot
     * is held ([Full]).
     */
    sealed interface ArmGate {
        /** A refusal that time alone may lift ([Retry], [Full]): [msUntilOpen] says when. */
        val passesLater: Boolean get() = this == Retry || this == Full

        data class Open(
            val token: Long,
        ) : ArmGate

        data object Pending : ArmGate

        data object Attached : ArmGate

        data object Retry : ArmGate

        data object Full : ArmGate
    }

    private class Entry<D>(
        val nodeId: String,
        val device: D,
        val linked: Boolean,
        val endedAt: Long,
        var arm: Arm,
        val named: Boolean,
    ) {
        var up = true

        // When the grace started: the channel's end, the ACL's last connect edge, or the last release.
        var since = endedAt
        var downAt = 0L
        var tries = 0
        var orphan = false
        var reported = false

        // Came back up inside the grace after going down: the stack re-dialed it (Android 12's accept list).
        var redialed = false
    }

    // One arm or release that may hold a GATT client registration; [address] is null for a release (outside the rows).
    private class Flight(
        val address: String?,
        val startedAt: Long,
        // A HELLO-less channel's arm: those hold at most [MAX_UNNAMED_ARMS] slots.
        val unnamed: Boolean,
    ) {
        var arm = Arm.PENDING
        var endedAt = 0L
    }

    // An address's latest arm ([token]) and where the ACL's arms stand: attached once any attached.
    private class ArmRow(
        var arm: Arm,
        var token: Long,
        var endedAt: Long,
    ) {
        // What the row said before [token] began: put back if that arm never made a client.
        var prevArm = Arm.NONE
        var prevEndedAt = 0L
        var prevToken = 0L
    }

    // Addresses whose connect edge was seen and no disconnect since, oldest first.
    private val aclUp = LinkedHashSet<String>()

    private val arms = LinkedHashMap<String, ArmRow>()
    private val flights = LinkedHashMap<Long, Flight>()
    private var nextToken = 0L
    private val entries = LinkedHashMap<String, Entry<D>>()

    /** Releases asked since the transport was made. */
    @Volatile
    var released = 0
        private set

    /**
     * Gate refusals since the transport was made (`skipped=` on `bt state`): one per [tryArm] that refused, so a settle
     * that waited and asked again adds one per ask, channels merged into a settle add nothing, and a settle that found an
     * arm begun meanwhile adds nothing. A count of refusals, not of channels: never size a flood from it.
     */
    @Volatile
    var armsSkipped = 0
        private set

    /** Orphans reported since the transport was made. */
    @Volatile
    var reported = 0
        private set

    /** Ended channels' addresses being watched now. */
    val watching: Int @Synchronized get() = entries.size

    /** An ACL edge for [address] (`ACTION_ACL_CONNECTED` when [up]). */
    @Synchronized
    fun onAcl(
        address: String,
        up: Boolean,
        now: Long,
    ) {
        aclUp.remove(address)
        val row = arms[address]
        if (up) {
            aclUp.add(address)
            trim(aclUp, MAX_ACLS)
            // A connect edge is a new ACL; an attach just before it may be this ACL's, its edge trailing.
            if (row?.arm == Arm.ATTACHED && now - row.endedAt > EDGE_TRAIL_MS) arms.remove(address)
        } else if (row?.arm == Arm.ATTACHED) {
            arms.remove(address) // the idle timeout the arm set went with this ACL; a re-dial's new one needs its own
        }
        val entry = entries[address] ?: return
        if (up && !entry.up) {
            if (now - entry.downAt < graceMs) entry.redialed = true
            entry.up = true
            entry.since = now
        } else if (!up && entry.up) {
            if (now - entry.since >= graceMs) entry.orphan = true
            entry.up = false
            entry.downAt = now
        }
    }

    /** Whether [address]'s connect edge was seen and no disconnect edge since. */
    @Synchronized
    fun aclUp(address: String): Boolean = address in aclUp

    /**
     * A link came up on [address]: whatever was watched there is that link's ACL now. Its arm's row stays: an arm that
     * attached on this ACL already armed it.
     */
    @Synchronized
    fun onLinkUp(address: String) {
        entries.remove(address)
    }

    /** A link-up arm on [address] began at [now]: never refused, and it takes a slot. Returns its token for [onArmed]. */
    @Synchronized
    fun onArming(
        address: String,
        now: Long,
    ): Long = begin(address, now)

    /**
     * Whether an unlinked channel's arm on [address] may begin at [now] ([ArmGate.Open], which marks it pending), or
     * why not; each refusal is counted in [armsSkipped]. A HELLO-less channel's (not [named]) finds the slot kept for
     * releases and named arms held ([MAX_UNNAMED_ARMS]).
     */
    @Synchronized
    fun tryArm(
        address: String,
        now: Long,
        named: Boolean = true,
    ): ArmGate {
        sweep(now)
        val row = arms[address]
        val gate =
            when {
                row?.arm == Arm.ATTACHED -> ArmGate.Attached
                row?.arm == Arm.PENDING -> ArmGate.Pending
                row?.arm == Arm.FAILED && now - row.endedAt < ARM_RETRY_MS -> ArmGate.Retry
                !slotFree(named) -> ArmGate.Full
                else -> ArmGate.Open(begin(address, now, unnamed = !named))
            }
        if (gate !is ArmGate.Open) armsSkipped++
        return gate
    }

    /**
     * How long from [now] until [tryArm] on [address] may pass a [ArmGate.Retry] or [ArmGate.Full]: the failed arm's
     * retry window, else the soonest a slot may free (a pending flight is guessed done at its attach timeout). Null when
     * the address is pending or attached (no wait opens it), zero when it would pass now.
     */
    @Synchronized
    fun msUntilOpen(
        address: String,
        now: Long,
        named: Boolean = true,
    ): Long? {
        sweep(now)
        val row = arms[address]
        if (row?.arm == Arm.ATTACHED || row?.arm == Arm.PENDING) return null
        val retry = if (row?.arm == Arm.FAILED) maxOf(0L, row.endedAt + ARM_RETRY_MS - now) else 0L
        val any = if (flights.size < MAX_ARMS_IN_FLIGHT) 0L else soonestFree(flights.values, now)
        val unnamed = flights.values.filter { it.unnamed }
        val own = if (named || unnamed.size < MAX_UNNAMED_ARMS) 0L else soonestFree(unnamed, now)
        return maxOf(retry, any, own, 0L)
    }

    /**
     * The arm or release [token] ended at [now] without making a client (its ACL was gone at the attach, or `connectGatt`
     * refused): nothing can register late, so its slot is free at once, and its address's row says what it said before.
     */
    @Synchronized
    fun onNeverRegistered(token: Long) {
        val flight = flights.remove(token) ?: return
        val address = flight.address ?: return
        val row = arms[address] ?: return
        if (row.token != token || row.arm != Arm.PENDING) return
        row.endedAt = row.prevEndedAt
        val replaced = flights[row.prevToken]?.takeIf { row.prevArm == Arm.PENDING }
        when {
            // The arm it replaced is still in flight: the row is that arm's again, so its own end or lapse settles it.
            replaced?.arm == Arm.PENDING -> {
                row.arm = Arm.PENDING
                row.token = row.prevToken
            }

            // It ended meanwhile, its failure ignored as superseded: the row takes that failure now.
            replaced?.arm == Arm.FAILED -> {
                row.arm = Arm.FAILED
                row.endedAt = replaced.endedAt
            }

            // A pending arm whose flight is gone can never settle the row: nothing stands on the ACL.
            row.prevArm == Arm.PENDING -> {
                row.arm = Arm.NONE
            }

            else -> {
                row.arm = row.prevArm
            }
        }
        entries[address]?.arm = row.arm
    }

    /**
     * The arm or release [token] ended at [now], having [attached] or not. One reported after [clear] or [stop], or
     * after it lapsed, is ignored. A superseded arm's failure leaves its address's row alone; any attach marks it.
     */
    @Synchronized
    fun onArmed(
        token: Long,
        attached: Boolean,
        now: Long,
    ) {
        val flight = flights[token] ?: return
        flight.arm = if (attached) Arm.ATTACHED else Arm.FAILED
        flight.endedAt = now
        val row = flight.address?.let { arms[it] } ?: return
        when {
            attached -> setRow(flight.address, row, Arm.ATTACHED, now)
            row.token == token && row.arm == Arm.PENDING -> setRow(flight.address, row, Arm.FAILED, now)
        }
        sweep(now)
    }

    /** A doorbell's lookup attached to [address]'s ACL at [now]: that attach is the link's arm. */
    @Synchronized
    fun onDoorbellAttached(
        address: String,
        now: Long,
    ) {
        val row = arms.getOrPut(address) { ArmRow(Arm.NONE, token = 0, endedAt = now) }
        setRow(address, row, Arm.ATTACHED, now)
        trim(arms.keys, MAX_ACLS)
    }

    /**
     * A channel to [nodeId] on [address] ended at [now] — a link's ([linked]), or one that never became a link — and its
     * ACL is watched if it is known to be up. A HELLO-less channel's (not [named]) never replaces a watched entry.
     */
    @Synchronized
    fun onLinkEnded(
        address: String,
        nodeId: String,
        device: D,
        now: Long,
        linked: Boolean = true,
        named: Boolean = true,
    ) {
        if (!named && address in entries) return
        val arm = arms[address]?.arm ?: Arm.NONE
        entries.remove(address)
        if (address !in aclUp) return
        if (!named) trimUnnamed(MAX_UNNAMED - 1)
        entries[address] = Entry(nodeId, device, linked, now, arm, named)
        if (entries.size > maxEntries) trimUnnamed(entries.count { !it.value.named } - (entries.size - maxEntries))
        trim(entries.keys, maxEntries)
    }

    /**
     * One round: what to release and what to report now. [busy] addresses (live links, dials) are skipped, and a release
     * that finds every slot held waits for the next round.
     */
    @Synchronized
    fun check(
        now: Long,
        busy: Set<String>,
        armOn: Boolean,
    ): List<Action<D>> {
        sweep(now)
        val out = ArrayList<Action<D>>()
        val gone = ArrayList<String>()
        // The fewest tries first: a release the slots deferred goes ahead of a second try at another orphan.
        for ((address, entry) in entries.entries.sortedBy { it.value.tries }) {
            if (address in busy) continue
            val action = if (entry.up) whileUp(address, entry, now, armOn) else whileDown(address, entry, now, gone)
            action?.let(out::add)
        }
        gone.forEach(entries::remove)
        return out
    }

    /**
     * The transport is stopping and nothing will watch these ACLs again: hands back, once, each one still up whose arm
     * neither attached nor is still trying (and not already reported), counting it as reported, then forgets everything.
     */
    @Synchronized
    fun stop(now: Long): List<Unarmed<D>> {
        val out =
            entries
                .filter { (_, e) -> e.up && !e.reported && e.arm != Arm.ATTACHED && e.arm != Arm.PENDING }
                .map { (address, e) -> Unarmed(address, e.nodeId, e.device, e.linked, now - e.endedAt) }
        reported += out.size
        clear()
        return out
    }

    /** Forgets every edge, every arm and every watched address: the adapter went off or on, or the transport stopped. */
    @Synchronized
    fun clear() {
        aclUp.clear()
        arms.clear()
        flights.clear()
        entries.clear()
    }

    private fun begin(
        address: String?,
        now: Long,
        unnamed: Boolean = false,
    ): Long {
        sweep(now)
        val token = ++nextToken
        flights[token] = Flight(address, now, unnamed)
        if (address != null) {
            val row = arms.remove(address) // re-inserted last: the trim takes the oldest first
            // An attach on this ACL stands; a new arm on it changes nothing a report or the gate reads.
            val arm = if (row?.arm == Arm.ATTACHED) Arm.ATTACHED else Arm.PENDING
            arms[address] =
                ArmRow(arm, token, endedAt = row?.endedAt ?: now).apply {
                    prevArm = row?.arm ?: Arm.NONE
                    prevEndedAt = row?.endedAt ?: 0L
                    prevToken = row?.token ?: 0L
                }
            entries[address]?.arm = arm
            trim(arms.keys, MAX_ACLS)
        }
        return token
    }

    private fun setRow(
        address: String,
        row: ArmRow,
        arm: Arm,
        now: Long,
    ) {
        row.arm = arm
        row.endedAt = now
        entries[address]?.arm = arm
    }

    private fun slotFree(named: Boolean): Boolean =
        flights.size < MAX_ARMS_IN_FLIGHT && (named || flights.values.count { it.unnamed } < MAX_UNNAMED_ARMS)

    // How long from [now] until one of [held] frees its slot: a pending one is guessed done at its attach timeout.
    private fun soonestFree(
        held: Collection<Flight>,
        now: Long,
    ): Long =
        held.minOf { f ->
            if (f.arm == Arm.PENDING) maxOf(MIN_OPEN_WAIT_MS, f.startedAt + ATTACH_MS - now) else f.endedAt + SECOND_CLOSE_MS - now
        }

    // Lapses the flights that never reported back and forgets those that hold no slot any more.
    private fun sweep(now: Long) {
        val it = flights.entries.iterator()
        while (it.hasNext()) {
            val (token, f) = it.next()
            if (f.arm == Arm.PENDING && now - f.startedAt >= ARM_LAPSE_MS) {
                f.arm = Arm.FAILED
                f.endedAt = f.startedAt + ARM_LAPSE_MS
                val row = f.address?.let { arms[it] }
                if (row != null && row.token == token && row.arm == Arm.PENDING) {
                    setRow(f.address, row, Arm.FAILED, f.endedAt)
                }
            }
            val holds = f.arm == Arm.PENDING || (f.arm == Arm.FAILED && now - f.endedAt < SECOND_CLOSE_MS)
            if (!holds) it.remove()
        }
    }

    // Leaves at most [keep] HELLO-less entries, the oldest going first.
    private fun trimUnnamed(keep: Int) {
        var excess = entries.count { !it.value.named } - maxOf(keep, 0)
        val it = entries.values.iterator()
        while (excess > 0 && it.hasNext()) {
            if (!it.next().named) {
                it.remove()
                excess--
            }
        }
    }

    // Past the grace an ACL still up is an orphan: released while tries remain, the arm is on and a slot is free, else
    // reported once.
    private fun whileUp(
        address: String,
        entry: Entry<D>,
        now: Long,
        armOn: Boolean,
    ): Action<D>? {
        if (now - entry.since < graceMs) return null
        entry.orphan = true
        val tries = if (entry.redialed) minOf(maxTries, REDIALED_TRIES) else maxTries
        return when {
            entry.reported -> {
                null
            }

            armOn && entry.tries < tries -> {
                if (flights.size >= MAX_ARMS_IN_FLIGHT) return null // next round, its tries intact
                entry.tries++
                entry.since = now
                released++
                Release(address, entry.nodeId, entry.device, entry.tries, now - entry.endedAt, begin(null, now))
            }

            else -> {
                report(address, entry, cleared = false, ageMs = now - entry.endedAt)
            }
        }
    }

    // Down for a grace, an ACL is gone for good (Android 12's accept list re-dials within seconds): an orphan that went is
    // reported as cleared, unless its report already went.
    private fun whileDown(
        address: String,
        entry: Entry<D>,
        now: Long,
        gone: MutableList<String>,
    ): Action<D>? {
        if (now - entry.downAt < graceMs) return null
        gone += address
        return if (entry.orphan && !entry.reported) report(address, entry, cleared = true, ageMs = entry.downAt - entry.endedAt) else null
    }

    private fun report(
        address: String,
        entry: Entry<D>,
        cleared: Boolean,
        ageMs: Long,
    ): Report {
        entry.reported = true
        reported++
        return Report(address, entry.nodeId, entry.linked, entry.arm == Arm.ATTACHED, entry.tries, cleared, ageMs)
    }

    private fun trim(
        keys: MutableSet<String>,
        max: Int,
    ) {
        val it = keys.iterator()
        while (keys.size > max && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    companion object {
        /** How long an ACL may outlive its channel before it counts as an orphan: the arm drops one in about 4 s. */
        const val GRACE_MS = 15_000L

        /** Releases per orphan before it is reported as kept: bounds Android 12's accept-list re-dial churn. */
        const val MAX_TRIES = 2

        /** Releases for an orphan the stack already re-dialed once: a release only invites the next re-dial. */
        const val REDIALED_TRIES = 1

        /**
         * Arms and releases that may hold a GATT client registration at once before an unlinked arm is refused or a release
         * waits (#150): a device has 32, shared with the doorbell, the payload reader, a board, the watch and other apps.
         */
        const val MAX_ARMS_IN_FLIGHT = 3

        /**
         * Slots a HELLO-less channel's arms may hold: one is kept for releases and named arms, so a flood of HELLO-less
         * channels from rotating addresses cannot starve a real peer's refused dial or an orphan's release.
         */
        const val MAX_UNNAMED_ARMS = MAX_ARMS_IN_FLIGHT - 1

        /**
         * How long an unattached arm keeps its registration: `BleLinkTuner`'s second close runs 5 s after the attach
         * gave up (its `SECOND_CLOSE_MS`).
         */
        const val SECOND_CLOSE_MS = 5_000L

        /**
         * How long after a failed arm another on the same address is refused: the 2 s attach plus the 5 s second close,
         * with margin, so a late registration is gone before the next one starts.
         */
        const val ARM_RETRY_MS = 10_000L

        /** A pending arm that never reported back counts as failed after this: its tuner ends within the 2 s attach. */
        const val ARM_LAPSE_MS = 30_000L

        /**
         * How long before a connect edge an attach may still be that ACL's own: the edge comes from the Bluetooth app's
         * process and trails (the transport's 2 s settle is sized on the same trail).
         */
        const val EDGE_TRAIL_MS = 2_000L

        /** HELLO-less channels watched at once: a flood of them must not push a peer's orphan out of the watch. */
        const val MAX_UNNAMED = 4

        // When a pending flight is guessed done ([msUntilOpen]): `BleLinkTuner`'s 2 s attach timeout, and never sooner
        // than [MIN_OPEN_WAIT_MS] from now.
        private const val ATTACH_MS = 2_000L
        private const val MIN_OPEN_WAIT_MS = 500L

        /** Ended channels watched at once: a phone holds at most eight LE connections. */
        const val MAX_ENTRIES = 16

        // Connected addresses remembered at once; a missed disconnect edge must not grow the set without bound.
        private const val MAX_ACLS = 64
    }
}
