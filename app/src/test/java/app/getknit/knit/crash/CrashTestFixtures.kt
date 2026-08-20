package app.getknit.knit.crash

/** A fixed environment so header assertions state only what they care about. */
internal fun testEnvironment(
    versionName: String = "2.3.0",
    obfuscated: Boolean = false,
) = CrashEnvironment(
    versionName = versionName,
    versionCode = 13,
    buildType = "debug",
    obfuscated = obfuscated,
    manufacturer = "Google",
    model = "Pixel 8",
    device = "shiba",
    board = "shiba",
    hardware = "shiba",
    soc = "Google Tensor G3",
    sdkInt = 36,
    release = "16",
    abis = "arm64-v8a, armeabi-v7a",
    fingerprint = "google/shiba/shiba:16/BP41.250822.001/13456789:user/release-keys",
)

/** A throwable whose message is [message] and whose frames are stable across runs. */
internal fun throwableWith(message: String): Throwable =
    IllegalStateException(message).apply {
        stackTrace =
            arrayOf(
                StackTraceElement("app.getknit.knit.mesh.bluetooth.BluetoothMeshTransport", "hello", "BluetoothMeshTransport.kt", 552),
                StackTraceElement("app.getknit.knit.mesh.MeshRouter", "route", "MeshRouter.kt", 91),
            )
    }
