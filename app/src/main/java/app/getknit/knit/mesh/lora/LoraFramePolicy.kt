package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * Decides which mesh frames are worth an ~2.5-second LoRa airslot. On the fan-out path: the plaintext
 * **Nearby room** — broadcast `chat` and its cleartext `reaction` — the cleartext `profile` frame (the far side
 * must pin the author's key before it can verify anything), and, since ADR 039, **DM-form chat**: a `chat`
 * frame with a recipient and no group. That form is deliberately opaque — a 1:1 DM, its sealed receipt or
 * reaction, a session reset, a group-key seed and an escalated delivery tick are wire-indistinguishable
 * (ADR 016/018), so all of them ride and this policy never tries to tell them apart. On the targeted path:
 * the delivery **receipt** back to a message's author, in either form.
 *
 * Everything else is refused: group-form chat (`group != null` — the plane carries no group conversation),
 * group metadata (useless without it), `typing` (worthless a moment later), and the point-to-point
 * `blobreq`/`keyreq`. Pure — decides on the already-decoded [RelayEnvelope], never re-encoding it
 * ([app.getknit.knit.mesh.lora.LoraFramePolicyTest]).
 */
internal object LoraFramePolicy {
    /** How the frame is being offered: a flood-to-everyone fan-out, or a targeted point-to-point send. */
    enum class Path { FANOUT, TARGETED }

    /**
     * Whether [env] (with its outer [wire], carrying the flood flag) may ride LoRa on [path]. On the
     * targeted path, [to] is the intended recipient's node id, so a sealed delivery tick — a `relay = false`
     * chat frame addressed to the author — is admitted while a flooded DM (`relay = true`) is not: a DM
     * reaches this plane only through the fan-out path, so no `fastSend` caller can widen what it carries.
     */
    fun eligible(
        env: RelayEnvelope,
        wire: WireEnvelope,
        path: Path,
        to: String? = null,
    ): Boolean =
        when (path) {
            Path.FANOUT -> isBroadcastRoom(env) || isDmForm(env) || env.type == FrameType.PROFILE
            Path.TARGETED -> env.type == FrameType.RECEIPT || isSealedTickTo(env, wire, to)
        }

    /** DM-form chat: addressed to one recipient, no group — a DM, or any sealed ctl frame riding as one. */
    fun isDmForm(env: RelayEnvelope): Boolean = env.type == FrameType.CHAT && env.recipientId != null && env.group == null

    /** The Nearby room: a chat or reaction with no DM recipient and no group. */
    private fun isBroadcastRoom(env: RelayEnvelope): Boolean =
        (env.type == FrameType.CHAT || env.type == FrameType.REACTION) &&
            env.recipientId == null &&
            env.group == null

    /** A `relay = false` chat frame addressed to [to] — AckSync's sealed CTL_RECEIPT tick, never a flooded DM. */
    private fun isSealedTickTo(
        env: RelayEnvelope,
        wire: WireEnvelope,
        to: String?,
    ): Boolean = env.type == FrameType.CHAT && !wire.relay && to != null && env.recipientId == to
}
