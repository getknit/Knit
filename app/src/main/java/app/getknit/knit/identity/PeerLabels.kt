package app.getknit.knit.identity

import java.text.Normalizer
import java.util.Locale

/**
 * Collision-aware display labels: the answer to "which Alice?" when two identities render to the same
 * name (ADR 058; the growth rule below is ADR 2026-09.wuqj).
 *
 * A display name is free text and the mesh has no authority that could make it unique, so the label a
 * peer is shown under is `name` alone in the common case and `name (discriminator)` whenever another
 * identity the device knows renders to the same [NameKey]. The discriminator is the peer's [Alias] — a
 * deterministic function of the node id that every device derives identically with no exchange, and the
 * same token the owner sees as their own placeholder — so two people can tell each other apart by quoting
 * it ("I'm the Alice with ReallyJoyfulFerret").
 *
 * **Labels grow instead of capping out.** Every identity in an index consumes some prefix of its alias
 * phrase ([Alias.phrase]): nothing when its name is unique, one token when another identity shares the
 * name, and one more token each round its rendered text still coincides with another's — a name *chosen*
 * to read like someone's label, or an alias ground to match one. A blank-named peer, whose rendered name
 * already *is* the first token, grows a continuation instead (`ReallyJoyfulFerret (QuietlyBoldCedar)`).
 * Distinct ids have distinct digests, so any two separate within the ten tokens the digest carries:
 * every label in a [PeerLabelIndex] is distinct by construction, and only a SHA-256 collision reaches the
 * guard that stops the loop.
 *
 * This is collision-*evident*, **not** anti-impersonation. A token is 24 bits, so a keypair whose alias
 * matches a target's is minutes of grinding — but the match can only make *both* labels grow to two tokens
 * (48 bits, weeks on a GPU) and then three (72 bits, out of reach), never coincide; the growth is the tell.
 * A plain adoption of a contact's name, with no grinding, still shows two Alices with different aliases and
 * nothing says which is real. Trust stays with `verified` and the safety number.
 *
 * Pure Kotlin, no Android dependencies, unit-tested on the JVM (`PeerLabelsTest`, `NameKeyTest`).
 */
object PeerLabels {
    /**
     * Builds the index over the **universe** of identities the device knows — every cached peer row plus
     * [self] — keyed by node id (a seeded self row and [self] collapse to one entry; a peer never collides
     * with itself). [peers] pairs a node id with its stored, possibly blank, profile name.
     *
     * Pass 1 groups the universe by [NameKey] and gives every member of a shared name one token. The
     * rounds that follow group the rendered texts and give every member of a shared text one more token,
     * until all texts are distinct.
     */
    fun index(
        peers: Iterable<Pair<String, String>>,
        self: Pair<String, String>? = null,
    ): PeerLabelIndex {
        val stored = LinkedHashMap<String, String>()
        for ((id, name) in peers) stored[id] = name
        if (self != null) stored[self.first] = self.second
        val phrases = HashMap<String, List<String>>()
        val names = HashMap<String, String>()
        val idsByKey = HashMap<String, MutableSet<String>>()
        for ((id, storedName) in stored) {
            phrases[id] = Alias.tokens(id)
            val name = storedName.takeIf { it.isNotBlank() } ?: phrases.getValue(id).first()
            names[id] = name
            idsByKey.getOrPut(NameKey.of(name)) { LinkedHashSet() }.add(id)
        }
        val consumed = HashMap<String, Int>()
        for (group in idsByKey.values) {
            if (group.size > 1) for (id in group) consumed[id] = tokensInName(stored[id]) + 1
        }
        val texts = HashMap<String, String>()

        fun render(id: String) {
            val k = consumed[id] ?: tokensInName(stored[id])
            texts[id] = PeerLabel.text(names.getValue(id), discriminatorOf(phrases.getValue(id), stored[id], k))
        }
        for (id in stored.keys) render(id)
        growUntilDistinct(texts, consumed, stored, ::render)
        val idsByText = HashMap<String, MutableSet<String>>()
        for ((id, text) in texts) idsByText.getOrPut(text) { LinkedHashSet() }.add(id)
        return PeerLabelIndex(stored, idsByKey, idsByText, consumed, phrases)
    }

    /** Each round, every id whose text another id shares consumes one more token; bounded by the digest. */
    private fun growUntilDistinct(
        texts: MutableMap<String, String>,
        consumed: MutableMap<String, Int>,
        stored: Map<String, String>,
        render: (String) -> Unit,
    ) {
        for (round in 0 until Alias.MAX_TOKENS) {
            val colliding =
                texts.entries
                    .groupBy({ it.value }, { it.key })
                    .values
                    .filter { it.size > 1 }
                    .flatten()
            if (colliding.isEmpty()) return
            var grew = false
            for (id in colliding) {
                val k = consumed[id] ?: tokensInName(stored[id])
                if (k < Alias.MAX_TOKENS) {
                    consumed[id] = k + 1
                    render(id)
                    grew = true
                }
            }
            // Nothing left to grow: two ids share every token, i.e. a SHA-256 collision.
            if (!grew) return
        }
    }
}

/**
 * The name to show for a person plus, when another known identity renders to the same name, the
 * [discriminator] that tells them apart. [name] is exactly [displayNameFor]'s answer; [alias] is what the
 * person would quote — their [Alias], grown by the tokens the index needed — and is shown outright on
 * precision surfaces such as the mention picker and a profile.
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
 * A snapshot of the universe's name collisions (see [PeerLabels.index]). [labelFor] is O(1) for a member
 * and also answers for a node id *outside* the universe (a sender whose profile has not been pinned yet):
 * it grows exactly as far as it must to read apart from every member, and never changes a member.
 */
data class PeerLabelIndex(
    private val storedNames: Map<String, String>,
    private val idsByKey: Map<String, Set<String>>,
    private val idsByText: Map<String, Set<String>>,
    private val consumed: Map<String, Int>,
    private val phrases: Map<String, List<String>>,
) {
    /** The label for [nodeId] given its stored profile name (defaults to the universe's own record of it). */
    fun labelFor(
        nodeId: String,
        storedName: String? = storedNames[nodeId],
    ): PeerLabel {
        val name = displayNameFor(storedName, nodeId)
        val words = phrases[nodeId] ?: Alias.tokens(nodeId)
        val k =
            consumed[nodeId]?.takeIf { storedName == storedNames[nodeId] }
                ?: resolve(nodeId, name, words, storedName)
        return PeerLabel(
            nodeId = nodeId,
            name = name,
            alias = words.take(maxOf(k, 1)).joinToString(" "),
            discriminator = discriminatorOf(words, storedName, k),
        )
    }

    /**
     * The tokens an id needs against the snapshot — an outside id, or a member asked about under a name
     * other than its stored one (the contact-card preview): one when a member shares the [NameKey], then
     * one more while a member's final text still matches.
     */
    private fun resolve(
        nodeId: String,
        name: String,
        words: List<String>,
        storedName: String?,
    ): Int {
        var k = tokensInName(storedName)
        if ((idsByKey[NameKey.of(name)].orEmpty() - nodeId).isNotEmpty()) k++
        while (k < Alias.MAX_TOKENS && collides(nodeId, PeerLabel.text(name, discriminatorOf(words, storedName, k)))) k++
        return k
    }

    private fun collides(
        nodeId: String,
        text: String,
    ): Boolean = (idsByText[text].orEmpty() - nodeId).isNotEmpty()

    companion object {
        /** An index over nothing: every label is undiscriminated. */
        val EMPTY = PeerLabelIndex(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}

/** Tokens the rendered name already spends: one when it *is* the alias (a blank profile), else none. */
private fun tokensInName(storedName: String?): Int = if (storedName.isNullOrBlank()) 1 else 0

/** The tokens after those in the name, up to [consumed]; null when the label spends none beyond the name. */
private fun discriminatorOf(
    words: List<String>,
    storedName: String?,
    consumed: Int,
): String? {
    val from = tokensInName(storedName)
    return if (consumed <= from) null else words.subList(from, consumed).joinToString(" ")
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
