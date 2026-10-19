package app.getknit.knit.fieldlog

/**
 * Where an event writes its fields. There is deliberately **no string field**: a value is a number, a
 * boolean, an enum constant's name (compiled into the APK, the same on every install) or a peer, and a peer
 * is written as its per-log pseudonym ([Pseudonyms]), never its node id. So nothing a user typed, no name,
 * no node id, no hash and no address can reach the journal — redaction holds by construction (ADR
 * 2026-10.a239, `docs/FIELD_LOG.md`), and `FieldEventSchemaTest` pins every event's line.
 *
 * Field names are the caller's compile-time constants; they are the schema.
 */
interface FieldWriter {
    fun num(
        name: String,
        value: Long,
    )

    fun flag(
        name: String,
        value: Boolean,
    )

    fun enum(
        name: String,
        value: Enum<*>,
    )

    /** A peer, by node id: the writer emits the log's pseudonym for it. */
    fun peer(
        name: String,
        nodeId: String,
    )
}

/**
 * One thing the phone's radios did, kept in the field log. Each kind has a short wire name ([kind], the
 * line's `e`) and writes its own fields; the time (`t`) is stamped by [FieldLog] at record time.
 *
 * Adding a kind: give it a new [kind], write only numbers / flags / enums / peers, add its golden line to
 * `FieldEventSchemaTest`, and document it in `docs/FIELD_LOG.md`.
 */
sealed interface FieldEvent {
    val kind: String

    fun write(out: FieldWriter)

    /** The mesh session started ([MeshManager.start]); [versionCode] dates a log that spans an upgrade. */
    data class MeshOn(
        val versionCode: Long,
    ) : FieldEvent {
        override val kind get() = "mesh_on"

        override fun write(out: FieldWriter) = out.num("vc", versionCode)
    }

    /** The mesh session stopped (Stop, a pause, or a restart's first half). */
    data object MeshOff : FieldEvent {
        override val kind get() = "mesh_off"

        override fun write(out: FieldWriter) = Unit
    }

    /** The phone's power state changed — the input every scan cadence and pace reads. */
    data class Power(
        val screenOn: Boolean,
        val charging: Boolean,
        val batteryLow: Boolean,
    ) : FieldEvent {
        override val kind get() = "power"

        override fun write(out: FieldWriter) {
            out.flag("screen", screenOn)
            out.flag("charging", charging)
            out.flag("low", batteryLow)
        }
    }

    /**
     * One radio's status changed: health, live links, or a flag. [nearby] rides along but does not by itself
     * make an event (it is smoothed and moves constantly); [FieldLog] samples it with the counters instead.
     */
    data class Radio(
        val radio: Enum<*>,
        val health: Enum<*>,
        val linked: Int,
        val nearby: Int,
        val contended: Boolean,
        val initiatorHeld: Boolean,
    ) : FieldEvent {
        override val kind get() = "radio"

        override fun write(out: FieldWriter) {
            out.enum("radio", radio)
            out.enum("health", health)
            out.num("linked", linked.toLong())
            out.num("nearby", nearby.toLong())
            out.flag("contended", contended)
            out.flag("held", initiatorHeld)
        }
    }

    /**
     * What the mesh's counters moved by since the previous [Counters] (or since the process started, for the
     * first of a process). Only the counters that moved are written; [deltas] keys are [FieldCounters]'
     * compile-time names.
     */
    data class Counters(
        val deltas: Map<String, Long>,
    ) : FieldEvent {
        override val kind get() = "counters"

        override fun write(out: FieldWriter) = deltas.forEach { (name, value) -> out.num(name, value) }
    }

    /**
     * A Bluetooth link came up. [dialMs] is how long our dial took, connect to HELLO — null for a link the peer
     * dialed; [sighted] is false for a peer admitted without a sighting (an iPhone, ADR 2026-09.shzv). [relinkMs] —
     * filled by [FieldLog], not the caller — is how long this peer was without a link since its last [BtDown] in this
     * log, null for a first link.
     */
    data class BtUp(
        val peer: String,
        val dialMs: Long?,
        val sighted: Boolean,
        val links: Int,
        val relinkMs: Long? = null,
    ) : FieldEvent {
        override val kind get() = "bt_up"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("dialed", dialMs != null)
            dialMs?.let { out.num("dial", it) }
            out.flag("sighted", sighted)
            out.num("links", links.toLong())
            relinkMs?.let { out.num("relink", it) }
        }
    }

    /**
     * A Bluetooth link ended after [lifetimeMs]. [phy] and [rssi] are the link's last PHY and link RSSI, known only
     * while the link had a PHY handle (the Coded experiment, ADR 2026-10.yvn6); null otherwise.
     */
    data class BtDown(
        val peer: String,
        val reason: BtDownReason,
        val lifetimeMs: Long,
        val links: Int,
        val phy: Enum<*>? = null,
        val rssi: Int? = null,
    ) : FieldEvent {
        override val kind get() = "bt_down"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.enum("reason", reason)
            out.num("life", lifetimeMs)
            out.num("links", links.toLong())
            phy?.let { out.enum("phy", it) }
            rssi?.let { out.num("rssi", it.toLong()) }
        }
    }

    /** A Bluetooth dial failed after [durationMs]; [streak] is the peer's consecutive failures, this one included. */
    data class BtFail(
        val peer: String,
        val reason: Enum<*>,
        val durationMs: Long,
        val streak: Int,
    ) : FieldEvent {
        override val kind get() = "bt_fail"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.enum("reason", reason)
            out.num("dur", durationMs)
            out.num("streak", streak.toLong())
        }
    }

    /** A Bluetooth link stepped PHY (ADR 2026-10.yvn6): to Coded, or back up to 1M/2M, at link RSSI [rssi]. */
    data class BtPhy(
        val peer: String,
        val toCoded: Boolean,
        val rssi: Int?,
    ) : FieldEvent {
        override val kind get() = "bt_phy"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("coded", toCoded)
            rssi?.let { out.num("rssi", it.toLong()) }
        }
    }

    /**
     * One live Bluetooth link at a sample ([FieldLog.SAMPLE_EVERY_MS]): its age, and what its handles last heard —
     * PHY and link RSSI from a PHY handle (the Coded experiment), interval and MTU from a tuner (a file crossed it),
     * and whether it [earned] the boosted file budget (ADR 2026-10.2da4). Null where no handle heard it.
     */
    data class BtLink(
        val peer: String,
        val ageMs: Long,
        val feeding: Boolean,
        val receiving: Boolean,
        val phy: Enum<*>? = null,
        val rssi: Int? = null,
        val intervalUs: Int? = null,
        val mtu: Int? = null,
        val earned: Boolean? = null,
    ) : FieldEvent {
        override val kind get() = "bt_link"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.num("age", ageMs)
            out.flag("tx", feeding)
            out.flag("rx", receiving)
            phy?.let { out.enum("phy", it) }
            rssi?.let { out.num("rssi", it.toLong()) }
            intervalUs?.let { out.num("interval", it.toLong()) }
            mtu?.let { out.num("mtu", it.toLong()) }
            earned?.let { out.flag("earned", it) }
        }
    }

    /**
     * One sighted Bluetooth peer at a sample: its smoothed advert RSSI on the 1M scale, each PHY's own reading, and
     * the 1M listening its Coded hits have outlasted its 1M ones by ([codedLagMs], ADR 2026-10.yvn6).
     */
    data class BtPeer(
        val peer: String,
        val linked: Boolean,
        val rssi: Int,
        val rssi1m: Int? = null,
        val rssiCoded: Int? = null,
        val codedLagMs: Long? = null,
    ) : FieldEvent {
        override val kind get() = "bt_peer"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("linked", linked)
            out.num("rssi", rssi.toLong())
            rssi1m?.let { out.num("rssi1m", it.toLong()) }
            rssiCoded?.let { out.num("rssiCoded", it.toLong()) }
            codedLagMs?.let { out.num("codedLag", it) }
        }
    }

    /** The Bluetooth plane at a sample: links, the LE connections the phone holds, sightings, and the scan's mode. */
    data class BtScan(
        val links: Int,
        val leLinks: Int,
        val sighted: Int,
        val hunting: Boolean,
        val codedMode: Enum<*>,
    ) : FieldEvent {
        override val kind get() = "bt_scan"

        override fun write(out: FieldWriter) {
            out.num("links", links.toLong())
            out.num("le", leLinks.toLong())
            out.num("sighted", sighted.toLong())
            out.flag("hunting", hunting)
            out.enum("coded", codedMode)
        }
    }

    /**
     * A Bluetooth channel's LE connection outlived the channel past the grace (#142, ADR 2026-10.b8yf): written once per
     * such connection. [linked]: the channel was a link; else it never became one (a refused dialer, a failed HELLO or
     * dial). [armed]: its arm attached (the link-up arm, an iPhone's doorbell, or the arm at the failed channel's end),
     * so the stack ignored the arm rather than the arm failing. [tries]: the releases it got (0 with the arm switched
     * off, and for one handed over as the transport stopped). [cleared]: it went down; else it was still up after the
     * last try. [ageMs]: how long it outlived its channel, to its disconnect or to this record.
     */
    data class BtOrphan(
        val peer: String,
        val linked: Boolean,
        val armed: Boolean,
        val tries: Int,
        val cleared: Boolean,
        val ageMs: Long,
    ) : FieldEvent {
        override val kind get() = "bt_orphan"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("linked", linked)
            out.flag("armed", armed)
            out.num("tries", tries.toLong())
            out.flag("cleared", cleared)
            out.num("age", ageMs)
        }
    }

    /** A Wi-Fi Aware data path came up; [initiator] when this phone requested it, else it served the peer's. */
    data class NanUp(
        val peer: String,
        val initiator: Boolean,
        val links: Int,
    ) : FieldEvent {
        override val kind get() = "nan_up"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("initiator", initiator)
            out.num("links", links.toLong())
        }
    }

    /** A Wi-Fi Aware data path ended after [lifetimeMs]; [clean] when it ended on a quiet sync rather than a reset. */
    data class NanDown(
        val peer: String,
        val initiator: Boolean,
        val lifetimeMs: Long,
        val clean: Boolean,
    ) : FieldEvent {
        override val kind get() = "nan_down"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("initiator", initiator)
            out.num("life", lifetimeMs)
            out.flag("clean", clean)
        }
    }

    /** The Wi-Fi Aware session was torn down and re-attached — the plane's own cure for a wedge (ADR 2026-09.jjhg). */
    data object NanCycle : FieldEvent {
        override val kind get() = "nan_cycle"

        override fun write(out: FieldWriter) = Unit
    }

    /**
     * The paired LoRa board at a sample, while its link is ready: the plane's role, the last packet's signal, the
     * queue, what it has heard, and the live share of airtime used against its budget. Never the board's name,
     * address or node number.
     */
    data class Lora(
        val role: Enum<*>,
        val snrTenths: Int?,
        val rssi: Int?,
        val queued: Int,
        val heard: Int,
        val boardsHeard: Int,
        val airUsedMs: Long?,
        val airBudgetMs: Long?,
    ) : FieldEvent {
        override val kind get() = "lora"

        override fun write(out: FieldWriter) {
            out.enum("role", role)
            snrTenths?.let { out.num("snr10", it.toLong()) }
            rssi?.let { out.num("rssi", it.toLong()) }
            out.num("queued", queued.toLong())
            out.num("heard", heard.toLong())
            out.num("boards", boardsHeard.toLong())
            airUsedMs?.let { out.num("airUsed", it) }
            airBudgetMs?.let { out.num("airBudget", it) }
        }
    }

    /**
     * How long messages took, worked out at export from the database (never journaled). [direction] is
     * `IN` (a message reaching this phone: our arrival clock minus the author's `sentAt`, so the two
     * clocks' skew is in it) or `ACK` (a receipt for one of ours reaching us: both ends on our clock).
     */
    data class Latency(
        val direction: LatencyDirection,
        val plane: Enum<*>,
        val count: Int,
        val p50Ms: Long,
        val p90Ms: Long,
        val maxMs: Long,
    ) : FieldEvent {
        override val kind get() = "latency"

        override fun write(out: FieldWriter) {
            out.enum("dir", direction)
            out.enum("plane", plane)
            out.num("n", count.toLong())
            out.num("p50", p50Ms)
            out.num("p90", p90Ms)
            out.num("max", maxMs)
        }
    }
}

/** Why a Bluetooth link ended, from the transport's teardown reasons. */
enum class BtDownReason {
    /** The peer's end closed, or the link broke under us. */
    EOF,

    /** Our mesh stopped. */
    STOP,

    /** We dropped it to make room for a better link. */
    EVICTED,

    /** Bluetooth was switched off. */
    ADAPTER_OFF,

    /** A new link to the same peer took this one's place. */
    REPLACED,
    OTHER,
    ;

    companion object {
        fun of(reason: String): BtDownReason =
            when (reason) {
                "eof" -> EOF
                "stop" -> STOP
                "evicted" -> EVICTED
                "adapter off" -> ADAPTER_OFF
                else -> OTHER
            }
    }
}

/** Which way a [FieldEvent.Latency] summary measures. */
enum class LatencyDirection { IN, ACK }
