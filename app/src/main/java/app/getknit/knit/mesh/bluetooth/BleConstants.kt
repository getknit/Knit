package app.getknit.knit.mesh.bluetooth

import android.os.ParcelUuid

/** Shared BLE identifiers for the advertiser, scanner, and transport. */
internal object BleConstants {
    /**
     * The 16-bit service UUID both peers advertise (in the service-data AD) and scan-filter on. A **16-bit**
     * UUID (not 128-bit) is required so the advert stays inside the 31-byte legacy budget (a 128-bit UUID +
     * service data would overflow it — see [BleAdvertPayload]). Bumped on every breaking wire change so a build
     * across the break hard-partitions at discovery, exactly like Wi-Fi Aware's `SERVICE_NAME` digit. `0xFE30`
     * is the launch baseline. Still a 16-bit value in the SIG-assigned `0xFE00–0xFEFF` block (not allocated to
     * this app — accepted pre-release risk; a member-assigned UUID would land here).
     */
    val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000FE30-0000-1000-8000-00805F9B34FB")

    /**
     * The service UUID the **side channel**'s extended-advertising pages ride under ([BleSideChannel]). A
     * different UUID from [SERVICE_UUID] on purpose: an older build's hardware scan filter never matches a page
     * (and a legacy scan cannot decode an `ADV_EXT_IND` anyway), so the pages are invisible to it rather than
     * mis-parsed as a presence advert. Numbering rule: presence UUIDs count up from `0xFE30` across wire breaks
     * (`0xFE31`/`0xFE32` were spent pre-launch, docs/WIRE_COMPAT.md), page UUIDs count up from `0xFE38`; a
     * page-layout break bumps this one alone, a wire break bumps both. Same SIG-block risk as above.
     */
    val SIDE_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000FE38-0000-1000-8000-00805F9B34FB")
}
