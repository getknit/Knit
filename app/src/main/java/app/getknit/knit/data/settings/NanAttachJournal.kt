package app.getknit.knit.data.settings

/**
 * What survives a process death about this device's ability to open a Wi-Fi Aware session.
 *
 * The two-method slice of [SettingsStore] that `mesh/wifiaware/WifiAwareTransport` needs to stop re-learning
 * the same thing after every kill — the [ModelLoadJournal] precedent, extracted as a seam for the same reason.
 *
 * The value is a **stamp**, not a flag: the app version code plus the OS build fingerprint under which we gave
 * up, empty when we have not. Both halves are resets. A new app version may ship a different attach path, and
 * the OS half is the one that earns its place here — getknit/Knit#9 is a LineageOS device whose vendor HAL
 * publishes no STA+NAN interface combination, and a ROM update is exactly the event that might fix that.
 * Without it, a user who flashes a fixed ROM stays latched off until Knit ships a new version code.
 *
 * Why it needs to be durable at all: `MeshService` is `START_STICKY`, so an AMS kill for excessive binder
 * objects is followed by a restart, and a fresh process starts with an empty failure streak and a full leak
 * budget. Re-spending that budget on every restart is how a permanently-refusing radio turns into an endless
 * kill/restart cycle instead of a single quiet giving-up.
 */
interface NanAttachJournal {
    /** The stamp under which Wi-Fi Aware was last abandoned, or `""` if it never has been. */
    suspend fun awareGiveUpStamp(): String

    /** Records [stamp] as the build/ROM that gave up on Aware; `""` re-arms it. */
    suspend fun setAwareGiveUpStamp(stamp: String)
}
