package app.getknit.knit.mesh.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tiny cross-component gate that lets one holder pause [BluetoothMeshTransport]'s LE scan while it drives
 * a GATT connect on the same controller. The mesh's own scan already starves *its* in-flight L2CAP connects
 * (`mesh-transport.md`, "the BLE scan is demand-gated"), but a connect from another class — the Meshtastic
 * board dial ([app.getknit.knit.mesh.bluetooth.meshtastic.MeshtasticGatt]) — is invisible to that check, so
 * without this the scan would black out the board's short connect→discover→MTU→subscribe window. Android-free
 * and thread-safe (begin/end are called from the GATT callback thread; [busy] is read from the scan loop).
 */
class BleConnectArbiter {
    private val lock = Any()
    private val holders = HashSet<String>()
    private val _busy = MutableStateFlow(false)

    /** True while any holder is mid-connect; the scan loop treats this like one of its own in-flight connects. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun begin(tag: String) {
        synchronized(lock) {
            holders.add(tag)
            _busy.value = holders.isNotEmpty()
        }
    }

    fun end(tag: String) {
        synchronized(lock) {
            holders.remove(tag)
            _busy.value = holders.isNotEmpty()
        }
    }
}
