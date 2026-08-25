package app.getknit.knit.mesh.lora

/**
 * Which bonded devices the LoRa picker offers. The phone's bonded list is mostly headsets and watches; the
 * picker shows the ones that look like a Meshtastic board and counts the rest behind a "show all paired
 * devices" toggle — hidden, never dropped, because [looksLikeBoard] is a heuristic. Pure, so the rules are
 * JVM-tested; the Android side ([BoardRef.meshtastic]) only supplies the per-device verdict.
 */
internal object BoardFilter {
    /**
     * The boards to list: every Meshtastic-looking device plus the board bound at [boundAddress] (a board the
     * user renamed must not vanish from the list while it is the selected one), or everything when [showAll].
     */
    fun visible(
        boards: List<BoardRef>,
        boundAddress: String?,
        showAll: Boolean,
    ): List<BoardRef> = if (showAll) boards else boards.filter { it.meshtastic || it.address == boundAddress }

    /** How many bonded devices [visible] hides without [showAll] — what the toggle would reveal. */
    fun hidden(
        boards: List<BoardRef>,
        boundAddress: String?,
    ): Int = boards.size - visible(boards, boundAddress, showAll = false).size

    /**
     * Whether an advertised [name] looks like a Meshtastic board: the stock `Meshtastic_a1b2`, or a custom short
     * name the firmware suffixes with the same four MAC hex digits (`WALT_a1b2` — `getDeviceName()` in the
     * firmware, so a renamed board is not lost on the name alone).
     */
    fun looksLikeBoard(name: String): Boolean = name.startsWith("mesh", ignoreCase = true) || RENAMED_SUFFIX.containsMatchIn(name)

    private val RENAMED_SUFFIX = Regex("_[0-9a-f]{4}$", RegexOption.IGNORE_CASE)
}
