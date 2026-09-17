package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.bluetooth.BleSideChannel
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The air the BLE side channel's pages cross (ADR 2026-09.sjaa): every [LabTransport] that joined hears every
 * page another member airs, so one `LabPages` is one radio range — as one `FakeMeshtasticAir` is one LoRa
 * channel — and a member is a phone whose advert carries the side-channel flag. What a page changes for
 * everything above the transport, and what a scenario on it pins:
 *
 * - **A page arrives from its author, never from the hop.** A page carries no hop identity, so the frame
 *   enters `inbound` with `fromNodeId` = the envelope's sender (the LoRa plane's rule) — a node two hops from
 *   the author hears it as the author's own copy and then the neighbor's link relay as a second hop, which
 *   is the count `MeshRouter.countOverheard` suppresses on.
 * - **A page reaches a node with no link.** A member holding no pipe at all still hears it — the
 *   sighted-but-unlinked phone (over the link budget, in connect backoff) the channel exists for.
 * - **A page bypasses a held link.** [LabTransport.hold] parks what the *stream* carries; the page is the
 *   other carrier, so it lands anyway. That is the head-of-line case the channel was built for, and a
 *   scenario that wants a frame stranded on a paged node must make the pages [lossy] for it too.
 *
 * The bytes are the real carrier's: the frame is run through `FastFrameCodec.encodeBest(transcode = true)`,
 * fragmented past `BleSideChannel.PAGE_BYTES` and refused past `FastFrameCodec.MAX_PARTS` (recorded in
 * [tooBig] — the frame then rides the links and the flood alone, as it does on the phone), and decoded again
 * on the way in, so a page that reaches a member carries the signature-verified bytes the codec rebuilt,
 * not the sender's object. Which frames are offered is `BleFastRoutePolicy`'s call, made inside
 * [LabTransport.fastFanout]; nothing DM-form ever reaches [air].
 *
 * [lossy] is a listener's scan window missing a page — the device trial's phone that was receiving a file
 * stream caught four pages in ten — judged per (listener, frame), so one member can be deaf to the pages
 * while the rest hear them.
 */
class LabPages {
    private val members = CopyOnWriteArrayList<LabTransport>()

    /** Whether the page for [WireEnvelope] is missed by the member whose node id is the first argument. */
    @Volatile
    var lossy: (to: String, WireEnvelope) -> Boolean = { _, _ -> false }

    /**
     * Every page aired, as `from type id form parts=N` in air order — the diagnosis of "did that ride a page".
     * `form` is `dm` for a frame with a recipient, `groupchat` for an E2E group message, `flood` for the rest
     * (the room, and the cleartext metadata frames — a group roster update among them). The first two never
     * appear unless a caller widened `shouldFastFanout` or `BleFastRoutePolicy.send` grew a page.
     */
    val aired = CopyOnWriteArrayList<String>()

    /** Every frame offered that no encoding fits in `MAX_PARTS` pages (`bleSideTooBig` on the phone). */
    val tooBig = CopyOnWriteArrayList<String>()

    /** Every page a member's scan missed, as `to type id`. */
    val missed = CopyOnWriteArrayList<String>()

    fun join(transport: LabTransport) {
        if (members.none { it === transport }) members += transport
    }

    fun leave(transport: LabTransport) {
        members.removeIf { it === transport }
    }

    /** The members [transport] can page — the side-channel capable peers around it. */
    fun others(transport: LabTransport): List<LabTransport> = members.filter { it !== transport }

    /**
     * Airs [wire] from [from] to every other member, through the real codec's size gate and round trip.
     * Returns false when no encoding fits — the caller's `bleSideTooBig`.
     */
    fun air(
        from: LabTransport,
        wire: WireEnvelope,
        env: RelayEnvelope,
    ): Boolean {
        val best = FastFrameCodec.encodeBest(wire, transcode = true) ?: return refuse(from, env)
        val parts =
            if (best.frame.size <= BleSideChannel.PAGE_BYTES) {
                1
            } else {
                FastFrameCodec.fragment(best.frame, BleSideChannel.PAGE_BYTES, 0)?.size ?: return refuse(from, env)
            }
        // What a listener decodes off the page — for a 0x05 frame the transcoder's rebuild, which must
        // reproduce the signed bytes exactly or the signature fails on every receiver.
        val heard = checkNotNull(FastFrameCodec.decodeCompact(best.frame)) { "${from.nodeId}: the codec refused its own page" }
        val heardEnv = checkNotNull(WireCodec.decodeEnvelope(heard.signed)) { "${from.nodeId}: a page decoded to no envelope" }
        aired += "${from.nodeId.take(NODE_ID_CHARS)} ${env.type} ${env.id} ${formOf(env)} parts=$parts"
        others(from).forEach { to ->
            if (lossy(to.nodeId, wire)) {
                missed += "${to.nodeId.take(NODE_ID_CHARS)} ${env.type} ${env.id}"
            } else {
                to.hearPage(heard, heardEnv)
            }
        }
        return true
    }

    private fun refuse(
        from: LabTransport,
        env: RelayEnvelope,
    ): Boolean {
        tooBig += "${from.nodeId.take(NODE_ID_CHARS)} ${env.type} ${env.id}"
        return false
    }

    private fun formOf(env: RelayEnvelope): String =
        when {
            env.recipientId != null -> FORM_DM
            env.type == FrameType.CHAT && env.group != null -> FORM_GROUP_CHAT
            else -> FORM_FLOOD
        }

    companion object {
        private const val NODE_ID_CHARS = 6
        const val FORM_DM = "dm"
        const val FORM_GROUP_CHAT = "groupchat"
        const val FORM_FLOOD = "flood"
    }
}
