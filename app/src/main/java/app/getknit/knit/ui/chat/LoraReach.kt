package app.getknit.knit.ui.chat

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.lora.LoraSizeHint

/**
 * What to tell the user about one DM's LoRa reach — the board's counterpart of [RelayReach]. Only the
 * "LoRa alone can hear them" cases render anything: a peer the phone radios also reach needs no ornament,
 * and a board that is down says nothing here (the header glyph already does).
 */
enum class LoraReach {
    /** Nothing to say: the room, a group, a peer another plane reaches, or no board. */
    Silent,

    /** The board is the only plane that has heard this peer lately: slow, short, no photos. */
    LoraOnly,

    /** As [LoraOnly], but private messages over LoRa are switched off — so nothing reaches them at all. */
    LoraOnlyDmsOff,

    /** As [LoraOnly], but the board has spent its airtime window (ADR 054): messages wait for air, minutes not seconds. */
    LoraOnlySaturated,
}

/**
 * Whether a draft in this thread would ride the LoRa plane, and in which form — what sizes the composer's
 * length hint ([LoraSizeHint]). [None] when the board is down, in a group (the plane carries no group
 * conversation), or in a DM with private messages over LoRa off.
 */
enum class LoraCarry { None, Room, Dm }

/**
 * The [LoraReach] for [conversationId], given the plane's [facts], the radios the peer is currently
 * reachable over ([kinds], from `MeshController.peerTransports` — null when it is reachable over none) and
 * the thread's Internet reach. A relay-covered DM has a better carrier than the board and stays quiet. The
 * room never renders (it is not addressed to anyone), and a group id never appears in the peer map, so
 * groups fall out on their own. LoRa's reachable set lingers 45 min, so the copy says "last heard".
 */
fun loraReachFor(
    conversationId: String,
    facts: LoraFacts,
    kinds: Set<TransportKind>?,
    relayReach: RelayReach,
): LoraReach =
    when {
        conversationId == Conversations.NEARBY -> LoraReach.Silent
        facts.plane != LoraPlane.Live -> LoraReach.Silent
        kinds != setOf(TransportKind.LoRa) -> LoraReach.Silent
        relayReach == RelayReach.Covered -> LoraReach.Silent
        !facts.dms -> LoraReach.LoraOnlyDmsOff
        facts.airtimeSpent -> LoraReach.LoraOnlySaturated
        else -> LoraReach.LoraOnly
    }

/** The [LoraCarry] for a draft in [conversationId]. */
fun loraCarryFor(
    conversationId: String,
    isGroup: Boolean,
    facts: LoraFacts,
): LoraCarry =
    when {
        facts.plane != LoraPlane.Live -> LoraCarry.None
        conversationId == Conversations.NEARBY -> LoraCarry.Room
        isGroup -> LoraCarry.None
        facts.dms -> LoraCarry.Dm
        else -> LoraCarry.None
    }

/** The composer's body budget in bytes for [carry], or null when the draft would not ride LoRa at all. */
fun loraBudgetFor(
    carry: LoraCarry,
    replying: Boolean,
    attached: Boolean,
): Int? =
    when (carry) {
        LoraCarry.None -> null
        LoraCarry.Room -> LoraSizeHint.budget(LoraSizeHint.ROOM_BODY_BYTES, replying, attached)
        LoraCarry.Dm -> LoraSizeHint.budget(LoraSizeHint.DM_BODY_BYTES, replying, attached)
    }
