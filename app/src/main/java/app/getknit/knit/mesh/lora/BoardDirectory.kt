package app.getknit.knit.mesh.lora

/** A bonded Meshtastic board offered in the LoRa settings picker. */
internal data class BoardRef(
    val address: String,
    val name: String,
)

/** Lists the bonded Meshtastic boards for the picker; implemented in `mesh/bluetooth/meshtastic/`. */
internal interface BoardDirectory {
    fun bonded(): List<BoardRef>
}
