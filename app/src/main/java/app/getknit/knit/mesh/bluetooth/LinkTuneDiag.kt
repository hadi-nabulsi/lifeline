package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.BuildConfig

/** The connection priority a link is asked for: AOSP's HIGH (11.25–15 ms), BALANCED (30–50 ms), LOW_POWER. */
internal enum class TunePriority { HIGH, BALANCED, LOW_POWER }

/** The PHYs a link is asked for, both ways: 1M alone, 2M alone, or either, leaving the pick to the controller. */
internal enum class TunePhy { ONE_M, TWO_M, ONE_M_2M }

/**
 * One `…debug.LINKTUNE` ask (#120): the links whose node id starts with [node] (`all` for every link), and what to ask
 * each for, in this order — a [phy], a [priority], an ATT [mtu] — then [read] the PHY and link RSSI back. [close] lets
 * the links' tuners go instead, which frees their GATT registrations.
 */
internal data class LinkTuneRequest(
    val node: String,
    val priority: TunePriority? = null,
    val phy: TunePhy? = null,
    val mtu: Int? = null,
    val read: Boolean = false,
    val close: Boolean = false,
) {
    /** Whether this asks anything of a link; a bare report makes no tuner. */
    val asks: Boolean get() = priority != null || phy != null || mtu != null || read
}

/**
 * One link as its tuner last heard it. [params] arrive only when the parameters change after the client registers.
 * [mtu] is what the exchange settled on and [mtuAsked] what was asked: from Android 14 the first ask on an ACL
 * exchanges 517 whatever it names, and a later one exchanges nothing. [refused] says why an ask was not made: an iPhone
 * link, or a PHY asked while the Coded experiment runs on this phone. [codedOwned]: a Coded handle steps this link.
 * [boosting] and [earned] are the link's [BleLinkBoost], null when it has none (no file has crossed it yet).
 */
internal data class LinkTuneStatus(
    val nodeId: String,
    val address: String?,
    val attached: Boolean,
    val txPhy: LinkPhy? = null,
    val rxPhy: LinkPhy? = null,
    val params: LinkParams? = null,
    val paramsStatus: Int? = null,
    val mtu: Int? = null,
    val mtuAsked: Int? = null,
    val rssi: Int? = null,
    val codedOwned: Boolean = false,
    val refused: String? = null,
    val boosting: Boolean? = null,
    val earned: Boolean? = null,
)

/**
 * What `…debug.LINKTUNE` reports: the links it asked, how many links feed a file right now, whether every one of them
 * has earned the boosted budget ([CodedPhyPolicy.pace]), and how many LE connections this phone holds (which picks the
 * full or the crowded boosted budget).
 */
internal data class LinkTuneReport(
    val feeding: Int,
    val links: List<LinkTuneStatus>,
    val earned: Boolean = false,
    val leLinks: Int = 0,
)

/**
 * The debug bridge's handle on per-link tuning (#120), bound by [BluetoothMeshTransport] while it runs, and only on a
 * debug build: the same shape as [CodedPhyDiag], so the bridge needs no `android.bluetooth` import or transport
 * reference. A measuring tool for the drain trial. [boostOn] is the trial's A/B switch: off, every [BleLinkBoost]
 * counts its link neither eligible nor listened to, so it lets a boost go, asks for none and attaches no client
 * (`…debug.LINKTUNE --ez boost false`).
 */
internal object LinkTuneDiag {
    @Volatile var tune: (suspend (LinkTuneRequest) -> LinkTuneReport)? = null

    @Volatile var boostOn: Boolean = true

    /**
     * The ACL arm's A/B switch (#142): on, every ACL a channel opened on is armed to idle out when the channel closes (at
     * link-up, at the end of a channel that never became a link, at stop), and an orphan the arms missed is released;
     * off, nothing is armed or released and an orphan is only reported (`…debug.LINKTUNE --ez arm false`). On in every
     * build; a release build, which binds no bridge, keeps it.
     */
    @Volatile var aclArm: Boolean = true

    /**
     * The feed window's A/B switch (nsr7's phase 2): on, a 1M/2M file to an Android peer that reports its progress is
     * clocked by those reports; off, it is paced as before (`…debug.LINKTUNE --es feed window|pace`). Starts at the
     * build's default, so a release build, which binds no bridge, keeps it.
     */
    @Volatile var feedWindow: Boolean = BuildConfig.BLE_FEED_WINDOW
}
