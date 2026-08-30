package app.getknit.knit.di

import app.getknit.knit.BuildConfig
import app.getknit.knit.demo.DemoDirector
import app.getknit.knit.demo.DemoSeeder
import app.getknit.knit.mesh.DemoBoardDirectory
import app.getknit.knit.mesh.DemoLoraPlane
import app.getknit.knit.mesh.DemoTransport
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.core.Koin

/**
 * Debug-variant demo wiring. Two debug-only modes swap in the no-op [DemoTransport] and seed the DB, and
 * neither compiles into release (the demo classes live in `src/debug`; `src/release` provides no-op stubs):
 *  - `-PseedDemo=true` → a static full history for screenshots (fixed connected set, via [DemoSeeder]).
 *  - `-PdemoDirector=true` (implies `seedDemo`) → an animated trailer: the transport reports a roster the
 *    [DemoDirector] grows over time, and the director seeds a live baseline instead of the static history.
 *
 * `src/main` calls these seams (`di/MeshModule.kt` for the transport, the LoRa plane and the board
 * directory; `KnitApplication` for the seed and the director start). [demoRoster] is the shared neighbor
 * set: the director mutates it and DemoTransport reports it as reachable, so the "connected to N" header
 * climbs live — a process-singleton so the lazily-built transport and the director (started from
 * KnitApplication) share the one instance.
 */
private val demoRoster = MutableStateFlow<Set<Peer>>(emptySet())

fun demoTransportOrNull(): MeshTransport? =
    when {
        BuildConfig.DEMO_DIRECTOR -> {
            // Open at one connected node (Kai / SAM); the director grows the roster from here.
            demoRoster.value = DemoTransport.peersOf(setOf(DemoSeeder.SAM))
            DemoTransport(demoRoster)
        }

        BuildConfig.SEED_DEMO -> {
            DemoTransport(MutableStateFlow(DemoTransport.peersOf(DemoSeeder.ONLINE_NODE_IDS)))
        }

        else -> {
            null
        }
    }

// The static seeder runs ONLY when the director is off, so plain `-PseedDemo` is unchanged and the director
// never fights a pre-filled room — it seeds its own live baseline (see DemoDirector).

/**
 * The LoRa plane's demo stand-in: a bound, connected, Knit-provisioned board, *reported* only. Null unless
 * a demo build is running, so the ordinary debug build still dials real hardware. Both demo modes get it —
 * the trailer's chat header wears the board glyph for the same reason the still capture does.
 */
internal fun demoLoraPlaneOrNull(): LoraPlaneStatus? = if (BuildConfig.SEED_DEMO) DemoLoraPlane() else null

/** The bonded-device list behind the LoRa picker, faked alongside [demoLoraPlaneOrNull]. */
internal fun demoBoardDirectoryOrNull(): BoardDirectory? = if (BuildConfig.SEED_DEMO) DemoBoardDirectory() else null

fun seedDemoIfEnabled(koin: Koin) {
    if (BuildConfig.SEED_DEMO && !BuildConfig.DEMO_DIRECTOR) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            DemoSeeder(koin).seed()
        }
    }
}

fun startDemoDirectorIfEnabled(koin: Koin) {
    if (BuildConfig.DEMO_DIRECTOR) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            DemoDirector(koin, demoRoster).run()
        }
    }
}
