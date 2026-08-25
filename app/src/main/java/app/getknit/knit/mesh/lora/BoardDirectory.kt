package app.getknit.knit.mesh.lora

/**
 * A bonded Bluetooth device the LoRa settings picker may offer as a board. [meshtastic] is the directory's
 * verdict on whether it looks like one ([BoardFilter.looksLikeBoard] on its name, or the Meshtastic service
 * UUID in the stack's cache); the picker hides the rest behind a "show all" toggle rather than dropping them,
 * because the verdict is a heuristic.
 */
internal data class BoardRef(
    val address: String,
    val name: String,
    val meshtastic: Boolean = true,
)

/** Lists every bonded device for the picker; implemented in `mesh/bluetooth/meshtastic/`. */
internal interface BoardDirectory {
    fun bonded(): List<BoardRef>
}
