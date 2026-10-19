package app.getknit.knit.data.peer

/**
 * The light projection of a peer row — what [app.getknit.knit.identity.PeerLabels] needs to index the universe
 * of names, plus the two facts the name-clash fade judges a row by ([verified], [lastSeenAt]; ADR 2026-10.k8ge),
 * without hydrating keys and prekeys for up to 2,000 rows on a suspend path (a notification). A Room POJO
 * projection, not an entity: no schema, no migration.
 */
data class PeerName(
    val nodeId: String,
    val name: String,
    val verified: Boolean = false,
    val lastSeenAt: Long = 0L,
)
