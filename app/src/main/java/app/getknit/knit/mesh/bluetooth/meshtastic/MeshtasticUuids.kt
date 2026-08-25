package app.getknit.knit.mesh.bluetooth.meshtastic

import java.util.UUID

/** The Meshtastic BLE GATT service + characteristics (`meshtastic.org/docs/development/device/client-api`). */
internal object MeshtasticUuids {
    val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")

    /** Write ToRadio protobufs here. */
    val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")

    /** Read one FromRadio protobuf per read; a 0-length read means the queue is drained. */
    val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")

    /** Notify: a 4-byte little-endian counter; the phone reads FromRadio until it catches up. */
    val FROM_NUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

    /** The standard Client Characteristic Configuration Descriptor, for enabling FromNum notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
