package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * Whether a frame `InboundPipeline.onDeliver` accepted shows its author was heard from, for `peers.lastSeenAt` —
 * the stamp the name-clash fade reads (ADR 2026-10.k8ge). Any plane counts (a radio, a relay, a spool pull, LoRa,
 * a custody replay): the fade asks whether this phone still has the person in view, not whether they are near.
 * Two shapes do not, because `verifyInbound` lets them through unauthenticated: the blob traffic (`blobreq` and the
 * `blobhave`/`blobhas` query and answer, [FrameType.isUnsignedPointToPoint]), which it never verifies, and the
 * unsigned sealed tick, whose shape alone it judges.
 *
 * Presentation only, like the stamp it feeds — never a presence tier, a route or a custody input.
 */
internal fun isHeardEvidence(
    wire: WireEnvelope,
    env: RelayEnvelope,
): Boolean = wire.sig.isNotEmpty() && !FrameType.isUnsignedPointToPoint(env.type)

/**
 * The time a frame says its author was heard from: its own `sentAt`, capped at our clock [now] — so a frame a
 * carrier replays days later never makes its author look recent, and a skewed future stamp never outlives the
 * moment we took it.
 */
internal fun heardAt(
    env: RelayEnvelope,
    now: Long,
): Long = minOf(now, env.sentAt)
