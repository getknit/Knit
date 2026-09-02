package app.getknit.knit.ui.chat

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.lora.LoraSizeHint

/**
 * What to tell the user about a thread's LoRa reach — the board's counterpart of [RelayReach]. Only the
 * "LoRa alone can hear them" cases render anything: a peer the phone radios also reach needs no ornament,
 * and a board that is down says nothing here (the header glyph already does).
 */
enum class LoraReach {
    /** Nothing to say: a group, a peer another plane reaches, a room with no far listener, or no board. */
    Silent,

    /** The board is the only plane that has heard this peer lately: slow, short, no photos. */
    LoraOnly,

    /** As [LoraOnly], but private messages over LoRa are switched off — so nothing reaches them at all. */
    LoraOnlyDmsOff,

    /** As [LoraOnly], but the board has spent its airtime window (ADR 054): messages wait for air, minutes not seconds. */
    LoraOnlySaturated,

    /**
     * The room's one and only LoRa state: the window is spent and somebody here is behind the board, so
     * posts reach them minutes late while everyone in phone-radio range still gets them at once. The room
     * has no [LoraOnly] counterpart on purpose (ADR 2026-09.ursc) — its audience is always a mix, so a
     * standing "some people are far away" strip would be permanent chrome saying nothing the user can act on.
     */
    RoomSaturated,
}

/**
 * Whether a draft in this thread would ride the LoRa plane, and in which form — what sizes the composer's
 * length hint ([LoraSizeHint]). [None] when the board is down, in a group (the plane carries no group
 * conversation), or in a DM with private messages over LoRa off.
 */
enum class LoraCarry { None, Room, Dm }

/**
 * Whether [kinds] means the board alone has heard them — the population every notice here is about, and
 * an exact-set test rather than a `contains`: a peer the phone radios also reach is carried by those.
 * Null (reachable over nothing) is not it either; the ordinary offline behaviour speaks for that peer.
 */
fun isLoraOnly(kinds: Set<TransportKind>?): Boolean = kinds == setOf(TransportKind.LoRa)

/**
 * The [LoraReach] for [conversationId], given the plane's [facts], the radios the peer is currently
 * reachable over ([kinds], from `MeshController.peerTransports` — null when it is reachable over none) and
 * the thread's Internet reach. A relay-covered DM has a better carrier than the board and stays quiet. This
 * is the **DM** rule: the room is addressed to nobody, so it has its own ([loraRoomReachFor]) and falls out
 * here on the first line; a group id never appears in the peer map, so groups fall out on their own. LoRa's
 * reachable set lingers 45 min, so the copy says "last heard".
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
        !isLoraOnly(kinds) -> LoraReach.Silent
        relayReach == RelayReach.Covered -> LoraReach.Silent
        !facts.dms -> LoraReach.LoraOnlyDmsOff
        facts.airtimeSpent -> LoraReach.LoraOnlySaturated
        else -> LoraReach.LoraOnly
    }

/**
 * The [LoraReach] for the Nearby room, whose airtime is the only LoRa story it has (ADR 2026-09.ursc). [loraOnlyPeer] is true
 * while at least one peer anywhere is reachable over the board alone — the room is addressed to nobody, so
 * the question is not "can we reach *them*" but "is there anyone out there a spent window would delay". If
 * the phone radios reach everyone we have heard, the queue holds nothing anybody is waiting for and this
 * stays [LoraReach.Silent].
 *
 * No [RelayReach] gate, unlike [loraReachFor]: the room is never scope-eligible on the Internet plane
 * ([RelayReach.Room] is permanent by design, `SPOOL_PROTOCOL` §4.4), so no better carrier can exist to
 * silence this. And no dismissal: the state clears itself as the rolling window ages air back, so a
 * "never show again" would hide it on the one occasion it matters.
 */
fun loraRoomReachFor(
    facts: LoraFacts,
    loraOnlyPeer: Boolean,
): LoraReach =
    when {
        facts.plane != LoraPlane.Live -> LoraReach.Silent
        !loraOnlyPeer -> LoraReach.Silent
        !facts.airtimeSpent -> LoraReach.Silent
        else -> LoraReach.RoomSaturated
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
