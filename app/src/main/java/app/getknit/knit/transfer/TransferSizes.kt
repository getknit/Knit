package app.getknit.knit.transfer

import java.util.Locale

/** A short human byte count for surfaces with no `Context` (the notification body); the card uses the platform's. */
object TransferSizes {
    private const val UNIT = 1000.0
    private val units = listOf("B", "kB", "MB", "GB", "TB")

    fun short(bytes: Long): String {
        var value = bytes.coerceAtLeast(0L).toDouble()
        var i = 0
        while (value >= UNIT && i < units.lastIndex) {
            value /= UNIT
            i++
        }
        return if (i == 0) "${value.toLong()} B" else String.format(Locale.ROOT, "%.1f %s", value, units[i])
    }
}
