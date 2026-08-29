package app.getknit.knit.identity

import java.text.Normalizer
import java.util.Locale

/**
 * Collision-aware display labels: the answer to "which Alice?" when two identities render to the same
 * name (ADR 058).
 *
 * A display name is free text and the mesh has no authority that could make it unique, so the label a
 * peer is shown under is `name` alone in the common case and `name (discriminator)` whenever another
 * identity the device knows renders to the same [NameKey]. The discriminator is the peer's [Alias] — a
 * deterministic function of the node id that every device derives identically with no exchange, and the
 * same word pair the owner sees as their own placeholder — so two people can tell each other apart by
 * quoting it ("I'm the Alice with JoyfulFerret"). Where the alias cannot disambiguate (the rendered name
 * *is* the alias, or two same-named peers' aliases coincide), a short prefix of the node id
 * ([NodeId.shortForm]) steps in, so every label in a [PeerLabelIndex] is distinct by construction.
 *
 * This is disambiguation, **not** anti-impersonation: the alias carries ~15 bits and a matching keypair can
 * be ground in seconds. Trust stays with `verified` and the safety number.
 *
 * Pure Kotlin, no Android dependencies, unit-tested on the JVM (`PeerLabelsTest`, `NameKeyTest`).
 */
object PeerLabels {
    /**
     * Builds the index over the **universe** of identities the device knows — every cached peer row plus
     * [self] — keyed by node id (a seeded self row and [self] collapse to one entry; a peer never collides
     * with itself). [peers] pairs a node id with its stored, possibly blank, profile name.
     *
     * Pass 1 groups the universe by [NameKey]; pass 2 groups the resulting label texts, so a residual text
     * collision (equal aliases, or a name *chosen* to read like another peer's label) is caught too.
     */
    fun index(
        peers: Iterable<Pair<String, String>>,
        self: Pair<String, String>? = null,
    ): PeerLabelIndex {
        val stored = LinkedHashMap<String, String>()
        for ((id, name) in peers) stored[id] = name
        if (self != null) stored[self.first] = self.second
        val idsByKey = HashMap<String, MutableSet<String>>()
        for ((id, name) in stored) {
            idsByKey.getOrPut(NameKey.of(displayNameFor(name, id))) { LinkedHashSet() }.add(id)
        }
        val provisional = PeerLabelIndex(stored, idsByKey, emptyMap())
        val idsByText = HashMap<String, MutableSet<String>>()
        for ((id, name) in stored) {
            idsByText.getOrPut(provisional.firstPassText(id, name)) { LinkedHashSet() }.add(id)
        }
        return provisional.copy(idsByText = idsByText)
    }
}

/**
 * The name to show for a person plus, when another known identity renders to the same name, the
 * [discriminator] that tells them apart. [name] is exactly [displayNameFor]'s answer; [alias] is always
 * the peer's [Alias] (shown outright on precision surfaces such as the mention picker and a profile).
 */
data class PeerLabel(
    val nodeId: String,
    val name: String,
    val alias: String,
    val discriminator: String?,
) {
    /** The rendered label: `name`, or `name (discriminator)` when one is needed. */
    val text: String get() = text(name, discriminator)

    companion object {
        /** The single format every surface — and the mention token — uses; deliberately not localized. */
        fun text(
            name: String,
            discriminator: String?,
        ): String = if (discriminator == null) name else "$name ($discriminator)"
    }
}

/**
 * A snapshot of the universe's name collisions (see [PeerLabels.index]). [labelFor] is O(1) and also
 * answers for a node id *outside* the universe (a sender whose profile has not been pinned yet): it is
 * discriminated exactly when a known identity renders to the same name.
 */
data class PeerLabelIndex(
    private val storedNames: Map<String, String>,
    private val idsByKey: Map<String, Set<String>>,
    private val idsByText: Map<String, Set<String>>,
) {
    /** The label for [nodeId] given its stored profile name (defaults to the universe's own record of it). */
    fun labelFor(
        nodeId: String,
        storedName: String? = storedNames[nodeId],
    ): PeerLabel {
        val name = displayNameFor(storedName, nodeId)
        val first = firstPass(nodeId, storedName, name)
        val short = NodeId.shortForm(nodeId)
        val residual = (idsByText[PeerLabel.text(name, first)].orEmpty() - nodeId).isNotEmpty()
        val discriminator =
            when {
                !residual || first == short -> first
                else -> listOfNotNull(first, short).joinToString(" ")
            }
        return PeerLabel(nodeId = nodeId, name = name, alias = Alias.aliasFor(nodeId), discriminator = discriminator)
    }

    internal fun firstPassText(
        nodeId: String,
        storedName: String?,
    ): String {
        val name = displayNameFor(storedName, nodeId)
        return PeerLabel.text(name, firstPass(nodeId, storedName, name))
    }

    /** Pass 1: the alias when another identity shares the [NameKey]; the short id when the name is the alias. */
    private fun firstPass(
        nodeId: String,
        storedName: String?,
        name: String,
    ): String? {
        val others = idsByKey[NameKey.of(name)].orEmpty() - nodeId
        return when {
            others.isEmpty() -> null
            storedName.isNullOrBlank() -> NodeId.shortForm(nodeId)
            else -> Alias.aliasFor(nodeId)
        }
    }

    companion object {
        /** An index over nothing: every label is undiscriminated. */
        val EMPTY = PeerLabelIndex(emptyMap(), emptyMap(), emptyMap())
    }
}

/**
 * The collision key of a display name: NFKC-normalized, Unicode format characters (zero-width joiners
 * and spaces, bidi overrides) removed, lower-cased in the root locale, whitespace runs collapsed and
 * trimmed — so "Alice", "alice", "Ａlice" and "Al​ice" are one name. Deliberately no
 * confusable/homoglyph folding (a Cyrillic "а" stays distinct from a Latin "a"): a recorded limit.
 */
object NameKey {
    private val WHITESPACE_RUN = Regex("\\s+")

    fun of(name: String): String {
        val folded = Normalizer.normalize(name, Normalizer.Form.NFKC)
        val kept = StringBuilder(folded.length)
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            if (Character.getType(cp) != Character.FORMAT.toInt()) kept.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return kept
            .toString()
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE_RUN, " ")
            .trim()
    }
}
