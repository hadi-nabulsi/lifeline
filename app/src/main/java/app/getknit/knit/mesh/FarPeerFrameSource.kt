package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * Supplies the carried frames worth re-offering to a peer a long-range plane
 * ([app.getknit.knit.mesh.lora.LoraMeshTransport]) has just heard for the first time (ADR 039). That plane
 * has no custody sync — `neighbors` is always empty, so `ForwardSync`'s digest exchange never runs over it —
 * and a DM sent while the peer's board was off is otherwise lost to it until radio or spool contact.
 * Since ADR 2026-10.a4pm it is also the delivery path for a DM the LoRa reach gate held because its
 * recipient was not heard. Implemented by [MeshManager] as our newest few unacked DMs to the peer, chosen by
 * id (`MeshManager.FAR_REOFFER_OWN_LIMIT`), then the newest few live custody frames to it that others wrote
 * (`FAR_REOFFER_RELAYED_LIMIT`), re-wrapped verbatim like a custody re-serve. Late-bound (the transport is
 * constructed before [MeshManager]); resolved at call time. Sibling of [ProfileFrameSource].
 */
fun interface FarPeerFrameSource {
    /** Signed frames to re-offer to [nodeId], our own first, each half newest first; empty when nothing is worth an airslot. */
    suspend fun framesFor(nodeId: String): List<WireEnvelope>
}
