package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.link.FeedWindow
import app.getknit.knit.mesh.link.PaceConfig
import app.getknit.knit.mesh.protocol.Protocol

/**
 * How [BluetoothMeshTransport] feeds a file over one link: whether the receiver's reports clock it (ADR 2026-10.5buf),
 * the pace it falls back to, and the rate a holder expects of it (ADR 2026-10.nsr7). Pure: the transport hands in what
 * it reads off its links, so the arithmetic is asserted on the JVM.
 */
internal object BleFeedPolicy {
    /**
     * Whether a file is clocked by its receiver's reports (ADR 2026-10.nsr7's phase 2): the switch [feedWindowOn] and a
     * peer that reports — a build carrying [Protocol.CAP_BLOB_HAVE] in [capabilities], an iPhone included — on any
     * PHY, Coded too (ADR 2026-10.5buf's Coded amendment).
     */
    fun windowed(
        feedWindowOn: Boolean,
        capabilities: Long,
    ): Boolean = feedWindowOn && capabilities and Protocol.CAP_BLOB_HAVE != 0L

    /**
     * A link's [base] pace, marked [windowed] when its receiver's reports clock it, with the window's queue and its
     * smallest chunk from [tuning]: on Coded, [PhyTuning.codedWindowChunkBytes], so its slower air holds no more queue
     * than [base], Coded's own pace, did; on any other [phy], [FeedWindow.MIN_CHUNK_BYTES].
     */
    fun pace(
        base: PaceConfig,
        windowed: Boolean,
        phy: LinkPhy,
        tuning: PhyTuning,
    ): PaceConfig {
        if (!windowed) return base
        val chunk = if (phy == LinkPhy.CODED) tuning.codedWindowChunkBytes else FeedWindow.MIN_CHUNK_BYTES
        return base.copy(windowed = true, windowQueueMs = tuning.feedWindowQueueMs, windowChunkBytes = chunk)
    }

    /**
     * How fast a file to a peer would go now (ADR 2026-10.nsr7):
     * - a Coded link, [codedPace]: its own pace, outside the split;
     * - a [windowed] link whose last stream measured a drain ([drained] > 0): that drain, shared with the
     *   [otherWindowedFeeds] going beside it on this phone's one controller;
     * - else the [boosted] share when the link will earn the boost (0 when it will not), or the [pacedShare] beside the
     *   paced feeds, times the share of its pace the link's last measured stream drained ([drainRatio]).
     *
     * The shares are read only when the answer needs them: the boosted one counts the phone's LE connections.
     */
    @Suppress("LongParameterList") // the estimate's inputs, each read off the link or the transport
    fun expectedRate(
        codedPace: Int?,
        drained: Int,
        windowed: Boolean,
        otherWindowedFeeds: () -> Int,
        drainRatio: Double,
        boosted: () -> Int,
        pacedShare: () -> Int,
    ): Int {
        if (codedPace != null) return codedPace
        if (drained > 0 && windowed) return drained / (otherWindowedFeeds() + 1)
        val share = boosted().takeIf { it > 0 } ?: pacedShare()
        return (share * drainRatio).toInt()
    }

    /**
     * Whether a feed can earn the boost at all (ADR 2026-10.2da4): the build's [buildFlag] and the trial's switch
     * [boostOn], an Android peer (an iPhone's [capabilities] carry the doorbell, which owns its parameters), a tuner not
     * given up ([tunerAlive]), and no Bluetooth audio [audioContended] for the air.
     */
    fun canEarn(
        buildFlag: Boolean,
        capabilities: Long,
        boostOn: Boolean,
        tunerAlive: Boolean,
        audioContended: Boolean,
    ): Boolean = buildFlag && !DoorbellPolicy.serves(capabilities) && boostOn && tunerAlive && !audioContended

    /**
     * Whether a feed that [canEarn] will (ADR 2026-10.nsr7): someone will ask for the short interval — it has [earned]
     * now, or did [earnedRecently] (its peer asks: a lit receiver), or this side is [interactive] and asks itself. A dark
     * pair never asks, and scoring one at the boosted share sent it beside an iPhone's feed.
     */
    fun willEarn(
        canEarn: Boolean,
        earned: Boolean,
        earnedRecently: () -> Boolean,
        interactive: Boolean,
    ): Boolean = canEarn && (earned || earnedRecently() || interactive)

    /**
     * This phone's LE connections: its [meshLinks], and every other device in [connected] (a ring, a watch's LE fallback,
     * a LoRa board, a closed link's orphaned ACL), which take a share of the controller's buffers too. [connected] is
     * `getConnectedDevices(GATT)`: every app's GATT clients' connections, and every LE connection while any app has a GATT
     * server registered (#142), so a mesh link may appear there or not; the addresses in [ours] are never counted twice.
     */
    fun leLinks(
        meshLinks: Int,
        connected: List<String>,
        ours: Set<String>,
    ): Int = meshLinks + connected.count { it !in ours }
}
