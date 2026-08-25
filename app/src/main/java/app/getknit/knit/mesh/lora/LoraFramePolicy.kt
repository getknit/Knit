package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * Decides which mesh frames are worth an ~2.5-second LoRa airslot. The MVP subset (locked with the
 * maintainer): the plaintext **Nearby room** — broadcast `chat` and its cleartext `reaction` — plus the
 * cleartext `profile` frame (the far side must pin the author's key before it can verify anything), and,
 * on the targeted path, the delivery **receipt** back to a message's author.
 *
 * Everything else is refused: DM/group chat (E2E, and the wrong product surface for a public LoRa mesh),
 * group metadata (useless without the group chat it describes), `typing` (worthless a moment later),
 * and the point-to-point `blobreq`/`keyreq`. Pure — decides on the already-decoded [RelayEnvelope],
 * never re-encoding it ([app.getknit.knit.mesh.lora.LoraFramePolicyTest]).
 */
internal object LoraFramePolicy {
    /** How the frame is being offered: a flood-to-everyone fan-out, or a targeted point-to-point send. */
    enum class Path { FANOUT, TARGETED }

    /**
     * Whether [env] (with its outer [wire], carrying the flood flag) may ride LoRa on [path]. On the
     * targeted path, [to] is the intended recipient's node id, so a sealed delivery tick — a `relay = false`
     * chat frame addressed to the author — is admitted while a real DM (always `relay = true`) is not.
     */
    fun eligible(
        env: RelayEnvelope,
        wire: WireEnvelope,
        path: Path,
        to: String? = null,
    ): Boolean =
        when (path) {
            Path.FANOUT -> isBroadcastRoom(env) || env.type == FrameType.PROFILE
            Path.TARGETED -> env.type == FrameType.RECEIPT || isSealedTickTo(env, wire, to)
        }

    /** The Nearby room: a chat or reaction with no DM recipient and no group. */
    private fun isBroadcastRoom(env: RelayEnvelope): Boolean =
        (env.type == FrameType.CHAT || env.type == FrameType.REACTION) &&
            env.recipientId == null &&
            env.group == null

    /** A `relay = false` chat frame addressed to [to] — AckSync's sealed CTL_RECEIPT tick, never a DM. */
    private fun isSealedTickTo(
        env: RelayEnvelope,
        wire: WireEnvelope,
        to: String?,
    ): Boolean = env.type == FrameType.CHAT && !wire.relay && to != null && env.recipientId == to
}
