package app.getknit.knit.data.settings

/**
 * The bound Meshtastic board as the profile advertises it (`ProfileContent.loraNode` / `loraKey`): its node
 * number and, on firmware that signs (2.8+), its Curve25519 public key as base64 of 32 bytes. [key] is null
 * on a board whose firmware does not sign: a key that never signs verifies nothing, so it is not advertised.
 *
 * One value on purpose, read from one settings snapshot ([SettingsStore.loraBoard]). The two fields are
 * written and cleared together, and a reader that projected them through two flows could see one edit as
 * two — or, conflated, miss a bind that an unbind followed — and republish a profile naming a board this
 * phone no longer holds.
 */
data class LoraBoard(
    val node: Long,
    val key: String?,
)
