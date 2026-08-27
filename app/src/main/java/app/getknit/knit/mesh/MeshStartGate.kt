package app.getknit.knit.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped record of whether a [MeshService.start] was refused and is still owed a retry.
 *
 * Since Android 12 the start can be turned down at the call site when the app is backgrounded and holds no
 * exemption (see [MeshService.start]). Declining is the right answer there, but a decline that leaves no
 * trace is the failure mode work item #32 warned about: the mesh is silently down, `MeshManager.heal()`
 * no-ops on its own `started` flag, and nothing on screen or in `…debug.STATE` distinguishes "never started"
 * from "started with no peers".
 *
 * This holds the flag rather than driving anything: the retry itself is unconditional in `KnitApp`'s
 * `ON_RESUME` observer, so recovery does not depend on this being right. A singleton (Koin, `di/MeshModule`)
 * rather than a field on the service, so [MeshService.start] stays graph-free — it returns the outcome and
 * the *callers* record it — while `DebugBridgeReceiver` can read it. Mirrors the shape of
 * [app.getknit.knit.ui.RouteInbox] and its siblings.
 */
class MeshStartGate {
    private val _deferred = MutableStateFlow(false)

    /** True while a start the system refused is still owed a retry. */
    val deferred: StateFlow<Boolean> = _deferred.asStateFlow()

    /** Record the outcome of a [MeshService.start]; a refusal stays owed until a later start is accepted. */
    fun record(accepted: Boolean) {
        _deferred.value = !accepted
    }
}
