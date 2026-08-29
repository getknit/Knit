package app.getknit.knit.data.peer

/**
 * The `(nodeId, name)` projection of a peer row — what [app.getknit.knit.identity.PeerLabels] needs to
 * index the universe of names, without hydrating keys and prekeys for up to 2,000 rows on a suspend
 * path (a notification). A Room POJO projection, not an entity: no schema, no migration.
 */
data class PeerName(
    val nodeId: String,
    val name: String,
)
