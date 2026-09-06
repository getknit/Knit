@file:Suppress("MagicNumber")

package app.getknit.knit.demo

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity

/*
 * Declarative content for the demo-screenshot builds (see [DemoSeeder]). Each [DemoScenario] is one
 * marketing "theme" — a self-contained cast, conversation history, and group — that the seeder writes
 * verbatim through the real repositories. Adding a theme is pure data here; no seeder changes.
 *
 * The numeric fields are "minutes ago" offsets (how long before now a message was sent / a group was
 * created), so [MagicNumber] is suppressed for the whole file — naming each offset would only add noise.
 *
 * Convention: message lists are ordered oldest-first. For a group, the first message's sender/time is
 * taken as the group's creator/creation time.
 *
 * The plane-shaped demo state that is NOT cast-flavoured — the Internet relays and the LoRa board — is
 * theme-independent and lives with the seeder ([DemoSeeder.DEMO_RELAYS]) and the fake board
 * ([app.getknit.knit.mesh.DemoLoraPlane]), for the same reason [DemoSeeder.ONLINE_NODE_IDS] does: a
 * relay URL and a Heltec's node number read the same at a trailhead and on the playa.
 */

/** A conversation participant. [ME] is the local profile; the rest map to the stable demo node ids in
 *  [DemoSeeder] (so [DemoSeeder.ONLINE_NODE_IDS] and the fake transport stay theme-independent). */
enum class Slot { ME, SAM, DANI, THEO, PRIYA, JONAS, LENA, JONAS_TWO, RIVER, NOAH, MARLO }

/** A reaction left on a message: who reacted, the emoji, and how long ago. */
data class DemoReaction(
    val reactor: Slot,
    val emoji: String,
    val minsAgo: Long,
)

/**
 * A link-preview card attached to a message (ADR 2026-09.n752). The **sender** fetches the page and sends
 * the card as an ordinary attachment under its own MIME, so a seeded one is the same thing the real path
 * produces: [url] must appear verbatim in the message body — a recipient draws a card only when it can find
 * the card's link there — and [image] is the base name of a bundled asset under
 * `demo/images/<theme>/<image>.jpg`, kept small enough to clear `LinkPreviewBlob.IMAGE_MAX_BYTES`.
 */
data class DemoLink(
    val url: String,
    val title: String,
    val description: String,
    val image: String? = null,
)

/**
 * One seeded message. [mentionsMe] adds an @-mention of the local user (highlighted in the room);
 * [reactions] attach an emoji cluster. [id] must be unique within a scenario.
 *
 * [replyTo] makes this a quoted reply to another message in the same scenario (by that message's [id]);
 * the seeder denormalizes the quoted author/snippet onto the row, so the referenced original need not be
 * loaded for the quote to render. [image] attaches an inline photo: the base name of a bundled asset under
 * `demo/images/<theme>/<image>.jpg`, seeded as a plaintext blob (see [DemoSeeder]).
 */
data class DemoMsg(
    val id: String,
    val from: Slot,
    val body: String,
    val minsAgo: Long,
    val mentionsMe: Boolean = false,
    val reactions: List<DemoReaction> = emptyList(),
    // Members whose delivery receipt has come back, for one of OUR messages — the per-recipient split on
    // "Message info". Seeded rather than implied by `received`, because a partial delivery (some members
    // in, one still waiting) is exactly the state worth auditing and no aggregate flag can express it.
    val deliveredTo: List<Slot> = emptyList(),
    val replyTo: String? = null,
    val image: String? = null,
    // MIME of [image]: "image/jpeg" for the bundled scene photos, "image/webp" for the animated GIF beat
    // (an animated WebP under demo/images/<theme>/<image>.webp, played by Coil's AnimatedImageDecoder).
    val imageMime: String = "image/jpeg",
    // Seconds of voice note. Set it and the row renders as a voice-note bubble with a synthetic waveform,
    // so the seeded UI and accessibility suites cover that bubble the way they cover a photo one. The blob
    // is a valid but silent ADTS stream — the bubble is what is being audited, not the audio.
    val voiceSeconds: Int? = null,
    /**
     * The plane this row crossed ([app.getknit.knit.data.message.MessageEntity.receivedVia]) — for an
     * inbound message the plane its own frame arrived on, and for one of ours the plane its first delivery
     * receipt came back on. It is what puts the globe (Internet) or the board glyph (LoRa) beside a bubble
     * and beside its ✓✓ tick, so a marketing capture can show a conversation being carried by something
     * other than the radios without the demo build ever opening a socket. [DeliveryPlane.Nearby] — a radio
     * — renders no glyph, which is why it is the default.
     */
    val via: DeliveryPlane = DeliveryPlane.Nearby,
    /**
     * A link-preview card for a link in [body], attached the way the sender's own fetch attaches it: a blob
     * of its own MIME on [DemoMsg]'s attachment slot. Mutually exclusive with [image] and [voiceSeconds] —
     * one message, one attachment — and the seeder honours that order.
     */
    val link: DemoLink? = null,
)

/** A peer contact. [verified] pins a (fake) key + out-of-band confirmation so the DM header shows the
 *  verified badge; [openToChat] is the profile flag the Profile Details badge renders. The avatar is loaded
 *  from `demo/avatars/<theme>/<nodeId>.jpg`. */
data class DemoPeer(
    val slot: Slot,
    val name: String,
    val status: String,
    val verified: Boolean = false,
    val openToChat: Boolean = false,
    /**
     * The Meshtastic node number this contact's own profile claims as their board (`ProfileContent.loraNode`),
     * which is what lets a post heard on the radio channel resolve to them — the Profile Details "their board"
     * row, and the attributed author in the Meshtastic room. Null for a contact with no board.
     */
    val loraNode: Long? = null,
    /**
     * The key that board signs under (`ProfileContent.loraKey`). A fake value: nothing in a demo build ever
     * checks a signature — the verdict is seeded on the row ([DemoMeshPost.signed]) exactly as ingest froze
     * it — but the column has to be non-null for the profile row to claim a signing board at all.
     */
    val loraKey: String? = null,
)

/**
 * A 1:1 DM thread with [peer]. [read] true seeds a read watermark (no unread badge); false leaves the
 * peer's messages unread so the chat list shows a count.
 *
 * A thread the local user has never posted in — every message [from] the peer, and the peer unverified —
 * is a **message request** rather than a chat (`Conversations.isAccepted`), which is how
 * [DemoScenario.requests] gets the Requests inbox and its chat-list badge populated without a second
 * mechanism. Nothing enforces that here; the seeder simply never accepts the threads in that list.
 */
data class DemoThread(
    val peer: Slot,
    val read: Boolean,
    val messages: List<DemoMsg>,
)

/**
 * One post in the **Meshtastic room** (`Conversations.MESHTASTIC`) — the paired board's own primary channel,
 * mirrored into a room on this phone and nowhere else (ADR 2026-09.26q3). Not a [DemoMsg]: almost nothing a
 * Knit message carries is true here. There is no Knit sender (the row sits in our sender column by
 * convention, and the attribution beside it says the words are somebody else's), no receipts, no reactions,
 * and no verified identity — only what the radio said and what the board could prove about it.
 *
 * [node] is the speaker's Meshtastic node number, rendered as the `!hex` id every Meshtastic client shows.
 * [name] is `User.long_name` as the board's NodeDB had it, or null when it had never heard the speaker named.
 * [peer] is the contact whose profile claimed [node] as their board, resolved once at ingest — an
 * attribution, not an identity, so the bubble keeps the muted styling unless [signed] is
 * `ORIGIN_SIGNED_BY_CONTACT`. [mine] is a post the local user typed here, which leaves through the board and
 * never earns a ✓✓, because nothing on the channel acks.
 */
data class DemoMeshPost(
    val id: String,
    val body: String,
    val minsAgo: Long,
    val node: Long = 0L,
    val name: String? = null,
    val hops: Int? = null,
    val snrDeci: Int? = null,
    val viaMqtt: Boolean = false,
    val peer: Slot? = null,
    val signed: Int = MessageEntity.ORIGIN_UNSIGNED,
    val mine: Boolean = false,
)

/** A group thread: its name, its member set (which derives the group id), and its history. */
data class DemoGroup(
    val name: String,
    val members: List<Slot>,
    val messages: List<DemoMsg>,
)

/** A full marketing theme: the local profile, the contacts, the Nearby room, the DMs, and the groups. */
data class DemoScenario(
    val theme: String,
    val meName: String,
    val meStatus: String,
    val peers: List<DemoPeer>,
    val nearby: List<DemoMsg>,
    val nearbyReadMinsAgo: Long,
    val dms: List<DemoThread>,
    val group: DemoGroup,
    /**
     * Threads from strangers the local user has never answered — seeded exactly like [dms] but never
     * accepted, so they land in the Message Requests inbox and put its badge on the chat list instead of
     * showing up as chats.
     */
    val requests: List<DemoThread> = emptyList(),
    /** A group a stranger added the local user to: a request until somebody they know posts in it. */
    val requestGroup: DemoGroup? = null,
    /** Peers seeded into the blocked set, so the "Blocked users" screen has rows rather than its empty state. */
    val blocked: List<Slot> = emptyList(),
    /**
     * The Meshtastic room's history — what the demo board "heard" on its primary channel, plus one post of
     * our own. Seeded because the room's row appears the moment a board is bound ([DemoPlanes] binds one),
     * so without it every capture of the chat list carries an empty room, and the room screen itself can
     * only be photographed in its "nothing has been said yet" state.
     */
    val meshRoom: List<DemoMeshPost> = emptyList(),
)

/** Returns the scenario for [theme] (the `-PdemoTheme` build value), falling back to hiking. */
fun demoScenarioFor(theme: String): DemoScenario =
    when (theme) {
        FESTIVAL_SCENARIO.theme -> FESTIVAL_SCENARIO
        else -> HIKING_SCENARIO
    }

// --- Hiking (default) --------------------------------------------------------------------------------

private val HIKING_SCENARIO =
    DemoScenario(
        theme = "hiking",
        meName = "Maya Okonkwo",
        meStatus = "On the trail 🥾",
        peers =
            listOf(
                DemoPeer(Slot.SAM, "Sam Rivera", "Trail mix enthusiast", openToChat = true),
                // Verified, open to chat, and carrying a board of her own: the one Profile Details capture then
                // holds all three — the safety number, the availability badge, and the claimed-board row.
                DemoPeer(
                    Slot.DANI,
                    "Dani Cho",
                    "Summit or bust",
                    verified = true,
                    openToChat = true,
                    loraNode = DANI_BOARD,
                    loraKey = DEMO_LORA_KEY,
                ),
                // The contact who carries a board. His profile claims it, so the post his radio put on the
                // Meshtastic room's channel resolves to him — and, signed, wears the shield.
                DemoPeer(Slot.THEO, "Theo Blake", "Mostly lost", loraNode = THEO_BOARD, loraKey = DEMO_LORA_KEY),
                DemoPeer(Slot.PRIYA, "Priya N.", "Golden hour chaser 🌅"),
                DemoPeer(Slot.JONAS, "Jonas W.", "Will hike for coffee"),
                DemoPeer(Slot.LENA, "Lena F.", "Map nerd"),
                // A second "Jonas W." — the seeded name collision that exercises the ` (Alias)` discriminator
                // (ADR 058) in the room, contacts and the mention picker. No avatar asset, on purpose.
                DemoPeer(Slot.JONAS_TWO, "Jonas W.", "The other Jonas"),
                // Strangers. Neither is verified and the local user has posted in neither thread, so both
                // stay message requests; no avatar assets, so they render the letter-circle fallback the
                // Requests inbox actually shows for someone whose profile has not arrived yet.
                DemoPeer(Slot.RIVER, "River Salas", "Just got here"),
                DemoPeer(Slot.NOAH, "Noah Adeyemi", "Ridge run 2026"),
                DemoPeer(Slot.MARLO, "Marlo K.", ""),
            ),
        nearby =
            listOf(
                DemoMsg("demo-nearby-1", Slot.THEO, "Anyone else seeing the storm roll in over the ridge? ⛈️", 95),
                DemoMsg("demo-nearby-2", Slot.PRIYA, "Yeah just felt the first drops. Heading back to camp.", 92),
                DemoMsg("demo-nearby-3", Slot.ME, "Same here — the east trail's already mud.", 90),
                DemoMsg(
                    "demo-nearby-4",
                    Slot.SAM,
                    "Trail's clear up top @Maya Okonkwo 🎉 come join us!",
                    70,
                    mentionsMe = true,
                ),
                DemoMsg("demo-nearby-5", Slot.DANI, "Saved you a spot by the fire 🔥", 66),
                DemoMsg("demo-nearby-6", Slot.ME, "On my way — give me 20.", 64),
                DemoMsg(
                    "demo-nearby-7",
                    Slot.LENA,
                    "Heads up: bridge near the falls is out, take the upper loop.",
                    40,
                    reactions =
                        listOf(
                            DemoReaction(Slot.SAM, "👍", 39),
                            DemoReaction(Slot.THEO, "👍", 39),
                            DemoReaction(Slot.ME, "👍", 38),
                            DemoReaction(Slot.PRIYA, "❤️", 39),
                            DemoReaction(Slot.DANI, "❤️", 38),
                        ),
                ),
                DemoMsg("demo-nearby-8", Slot.JONAS, "Good call, thanks for the warning.", 38),
                DemoMsg("demo-nearby-8b", Slot.JONAS_TWO, "Other Jonas here — the upper loop is icy, bring spikes.", 25),
                // Carried the long way: Theo is over the ridge and out of radio range, so his line came in
                // over a LoRa board. The bubble wears the board glyph.
                DemoMsg(
                    "demo-nearby-8c",
                    Slot.THEO,
                    "Made the col — no phones up here for miles, this is going out over the board 📻",
                    18,
                    via = DeliveryPlane.LoRa,
                ),
                DemoMsg("demo-nearby-9", Slot.PRIYA, "Sunset from the summit is unreal tonight 🌄", 12, image = "summit"),
                // The room's NEWEST line, deliberately: a link with the card its SENDER fetched and attached
                // (ADR 2026-09.n752) — nobody who reads it ever contacts the site. Last because a card's
                // picture loads after layout and grows its row, which shoves anything below it off a freshly
                // anchored screen; at the bottom, that growth scrolls the card INTO frame instead of out.
                DemoMsg(
                    "demo-nearby-10",
                    Slot.SAM,
                    "Ranger station just updated the closure list: https://trails.getknit.app/ridge-loop",
                    8,
                    link =
                        DemoLink(
                            url = "https://trails.getknit.app/ridge-loop",
                            title = "Ridge Loop — conditions & closures",
                            description = "Bridge out at the falls; upper loop open. Updated this morning by the ranger station.",
                            image = "ridge-card",
                        ),
                ),
            ),
        nearbyReadMinsAgo = 20,
        dms =
            listOf(
                DemoThread(
                    Slot.DANI,
                    read = true,
                    messages =
                        listOf(
                            DemoMsg("demo-dm-dani-1", Slot.DANI, "Hey! Did you make it down okay?", 180),
                            DemoMsg("demo-dm-dani-2", Slot.ME, "Yeah, just got back. That last descent was sketchy 😅", 178),
                            DemoMsg("demo-dm-dani-3", Slot.DANI, "Told you the trekking poles were worth it 😏", 176),
                            DemoMsg("demo-dm-dani-4", Slot.ME, "Fine, you were right. Same time next weekend?", 150),
                            DemoMsg("demo-dm-dani-5", Slot.DANI, "Absolutely. I'll bring the good coffee ☕", 148),
                            // Dani has driven home and is off the mesh entirely; her phone acknowledged this
                            // over an Internet relay, so the ✓✓ wears the globe. The one seeded relay
                            // delivery — the DM the marketing capture opens for the relay story.
                            DemoMsg("demo-dm-dani-6", Slot.ME, "Deal.", 120, via = DeliveryPlane.Internet),
                        ),
                ),
                DemoThread(
                    Slot.SAM,
                    read = false,
                    messages =
                        listOf(
                            DemoMsg("demo-dm-sam-1", Slot.ME, "Great hiking with you today!", 30),
                            DemoMsg("demo-dm-sam-2", Slot.SAM, "Likewise! Same crew next time?", 9),
                            DemoMsg("demo-dm-sam-3", Slot.SAM, "Oh and I found your water bottle 💧", 7),
                            // The one seeded voice note. It lives in this DM because `chat/samr1v00` is the
                            // route the accessibility audit and the seeded UI runs already open, so the
                            // voice bubble gets the same scrutiny the photo bubble does — and a received
                            // one, since the receive side is where the waveform is derived rather than
                            // carried.
                            DemoMsg("demo-dm-sam-4", Slot.SAM, "", 5, voiceSeconds = 9),
                        ),
                ),
            ),
        group =
            DemoGroup(
                name = "Trailhead Crew",
                members = listOf(Slot.ME, Slot.SAM, Slot.PRIYA, Slot.THEO),
                messages =
                    listOf(
                        DemoMsg("demo-group-1", Slot.SAM, "Trailhead Crew assemble! Saturday 7am?", 300),
                        DemoMsg("demo-group-2", Slot.PRIYA, "I'm in 🙌", 298),
                        DemoMsg("demo-group-3", Slot.THEO, "Same. Carpool from the usual spot?", 295),
                        DemoMsg(
                            "demo-group-4",
                            Slot.ME,
                            "Works for me. I'll grab snacks.",
                            290,
                            // Partial delivery, deliberately: this is the message the seeded/a11y runs open
                            // "Message info" on, so it must exercise both halves of the delivered/waiting split.
                            deliveredTo = listOf(Slot.SAM, Slot.PRIYA),
                            // Several reactors, deliberately: this is the message the seeded/a11y runs open
                            // "Message info" on, and one reactor would not exercise the emoji filter chips.
                            reactions =
                                listOf(
                                    DemoReaction(Slot.SAM, "👍", 289),
                                    DemoReaction(Slot.PRIYA, "👍", 288),
                                    DemoReaction(Slot.THEO, "❤️", 287),
                                ),
                            replyTo = "demo-group-3",
                        ),
                        DemoMsg("demo-group-5", Slot.PRIYA, "You're the best 🥟", 288),
                        // A voice note in the group as well as the DM: the group bubble is the wider one, and
                        // it is the layout the store screenshot of a group thread actually shows.
                        DemoMsg("demo-group-6", Slot.THEO, "", 240, voiceSeconds = 12),
                    ),
            ),
        requests =
            listOf(
                DemoThread(
                    Slot.RIVER,
                    read = false,
                    messages =
                        listOf(
                            DemoMsg(
                                "demo-req-river-1",
                                Slot.RIVER,
                                "Hi! Saw you on the mesh at the trailhead — are you with the ridge group?",
                                55,
                            ),
                            DemoMsg("demo-req-river-2", Slot.RIVER, "No worries if not, just trying to find them 🙂", 52),
                        ),
                ),
            ),
        requestGroup =
            DemoGroup(
                name = "Ridge Run 2026",
                members = listOf(Slot.ME, Slot.RIVER, Slot.NOAH),
                messages =
                    listOf(
                        DemoMsg("demo-req-group-1", Slot.NOAH, "Adding everyone I found on the mesh — route drops Friday!", 48),
                    ),
            ),
        blocked = listOf(Slot.MARLO),
        meshRoom =
            listOf(
                // A stranger's radio, named by the board's NodeDB, three hops out: the ordinary case, and the
                // one the room's styling is built around — a name off an open channel that proves nothing.
                DemoMeshPost(
                    "demo-mesh-1",
                    "Repeater on Bald Knob is back up, coverage to the north side again.",
                    minsAgo = 84,
                    node = 0x7A41C0E2L,
                    name = "Bald Knob Relay",
                    hops = 3,
                    snrDeci = -78,
                ),
                // Off an MQTT uplink, so it may have come from anywhere on earth — the row says so.
                DemoMeshPost(
                    "demo-mesh-2",
                    "Anyone else on LongFast up here, or is it just me and the repeater?",
                    minsAgo = 61,
                    node = 0x2C90B711L,
                    name = "KJ7ZQF",
                    hops = 4,
                    snrDeci = -102,
                    viaMqtt = true,
                ),
                // Theo, resolved to a contact AND verified under the key his profile advertises (ADR
                // 2026-09.ggq4): the one post in the room that wears the shield and a Knit author's name.
                DemoMeshPost(
                    "demo-mesh-3",
                    "Made the col. No phones for miles — this is the board talking.",
                    minsAgo = 18,
                    node = THEO_BOARD,
                    name = "Knit 4b1e",
                    hops = 1,
                    snrDeci = 64,
                    peer = Slot.THEO,
                    signed = MessageEntity.ORIGIN_SIGNED_BY_CONTACT,
                ),
                // A radio the board has heard but never heard *named*, so the row falls back to the `!hex`
                // id every Meshtastic client shows — the case a name-less NodeDB entry actually produces.
                DemoMeshPost(
                    "demo-mesh-3b",
                    "Weather station at the saddle: 4C, gusting 30 km/h from the west.",
                    minsAgo = 41,
                    node = 0x5D08E4A1L,
                    hops = 2,
                    snrDeci = -35,
                ),
                // Ours, typed here and sent out through our own board. It never earns a ✓✓: nothing on the
                // channel acks.
                DemoMeshPost("demo-mesh-4", "Copy Theo — heading up the upper loop, bridge is out.", minsAgo = 14, mine = true),
                // One more heard post after ours, so the newest-anchored window opens on a full screen rather
                // than a thread that ends halfway up it.
                DemoMeshPost(
                    "demo-mesh-5",
                    "Roger. Falls trail is closed at the washout, sign is down though.",
                    minsAgo = 9,
                    node = 0x7A41C0E2L,
                    name = "Bald Knob Relay",
                    hops = 3,
                    snrDeci = -81,
                ),
            ),
    )

// --- Festival / Burning Man --------------------------------------------------------------------------

private val FESTIVAL_SCENARIO =
    DemoScenario(
        theme = "festival",
        meName = "Zara Vance",
        meStatus = "Deep playa till sunrise ✨",
        peers =
            listOf(
                DemoPeer(Slot.SAM, "Kai Brooks", "Art car captain 🚐", openToChat = true),
                // Verified, open to chat, and board-carrying, mirroring the hiking cast: one capture, all three.
                DemoPeer(
                    Slot.DANI,
                    "Luna Reyes",
                    "Find me at sunrise 🌅",
                    verified = true,
                    openToChat = true,
                    loraNode = DANI_BOARD,
                    loraKey = DEMO_LORA_KEY,
                ),
                // The contact whose profile claims a board — the attributed, signed author in the room.
                DemoPeer(Slot.THEO, "Echo Tanaka", "Sound camp till dawn 🔊", loraNode = THEO_BOARD, loraKey = DEMO_LORA_KEY),
                DemoPeer(Slot.PRIYA, "Sage Moreno", "Camp hydration officer 💧"),
                DemoPeer(Slot.JONAS, "Dex Halloran", "Will trade stickers"),
                DemoPeer(Slot.LENA, "Ravi Okafor", "Built the dome 🛖"),
                // The name collision, festival cast (ADR 058). No avatar asset, as in the hiking theme.
                DemoPeer(Slot.JONAS_TWO, "Dex Halloran", "The other Dex"),
                DemoPeer(Slot.RIVER, "Wren Halvorsen", "First burn"),
                DemoPeer(Slot.NOAH, "Cass Ibarra", "Sunrise bike posse"),
                DemoPeer(Slot.MARLO, "Marlo K.", ""),
            ),
        nearby =
            listOf(
                DemoMsg("fest-nearby-1", Slot.THEO, "Sunrise set at the Mayan temple in 20 🌅🔊", 95),
                DemoMsg("fest-nearby-2", Slot.PRIYA, "Bringing a cooler of electrolytes for anyone fading 💧", 92),
                DemoMsg("fest-nearby-3", Slot.ME, "Bless you Sage — on my way 🙏", 90),
                DemoMsg(
                    "fest-nearby-4",
                    Slot.SAM,
                    "Art car 'Dusty Rhino' rolling to deep playa @Zara Vance 🦏 hop on!",
                    70,
                    mentionsMe = true,
                ),
                DemoMsg("fest-nearby-5", Slot.DANI, "Saved you a cushion up top 🛋️", 66),
                DemoMsg("fest-nearby-6", Slot.ME, "Two mins out, don't leave without me 🏃", 64),
                DemoMsg(
                    "fest-nearby-7",
                    Slot.LENA,
                    "Dust storm rolling in from the west — goggles up! 🥽",
                    40,
                    reactions =
                        listOf(
                            DemoReaction(Slot.SAM, "👍", 39),
                            DemoReaction(Slot.THEO, "👍", 39),
                            DemoReaction(Slot.ME, "👍", 38),
                            DemoReaction(Slot.PRIYA, "❤️", 39),
                            DemoReaction(Slot.DANI, "❤️", 38),
                        ),
                ),
                DemoMsg("fest-nearby-8", Slot.JONAS, "Whiteout at center camp already, stay safe out there.", 38),
                DemoMsg("fest-nearby-8b", Slot.JONAS_TWO, "Other Dex — my bike's the one with the blue fur, not the pink.", 25),
                // Deep playa, kilometres from any other phone: this one crossed a LoRa board.
                DemoMsg(
                    "fest-nearby-8c",
                    Slot.THEO,
                    "Out past the trash fence, nothing but the board out here 📻",
                    18,
                    via = DeliveryPlane.LoRa,
                ),
                DemoMsg("fest-nearby-9", Slot.PRIYA, "The glowing dragon out on the playa is unreal tonight ✨🐉", 12, image = "dragon"),
                // The link-preview card, festival side, and last for the same reason the hiking one is.
                DemoMsg(
                    "fest-nearby-10",
                    Slot.SAM,
                    "Full sunrise line-up just went up: https://playa.getknit.app/sunrise-sets",
                    8,
                    link =
                        DemoLink(
                            url = "https://playa.getknit.app/sunrise-sets",
                            title = "Sunrise Sets — the whole week",
                            description = "Every dawn set on the playa, camp by camp, with the walk time from center camp.",
                            image = "sunrise-card",
                        ),
                ),
            ),
        nearbyReadMinsAgo = 20,
        dms =
            listOf(
                DemoThread(
                    Slot.DANI,
                    read = true,
                    messages =
                        listOf(
                            DemoMsg("fest-dm-dani-1", Slot.DANI, "Did you find camp okay last night?", 180),
                            DemoMsg("fest-dm-dani-2", Slot.ME, "Eventually 😅 the playa swallowed me for an hour", 178),
                            DemoMsg("fest-dm-dani-3", Slot.DANI, "Told you to pin a flag on your bike 🚩", 176),
                            DemoMsg("fest-dm-dani-4", Slot.ME, "Lesson learned. Sunrise set tomorrow?", 150),
                            DemoMsg("fest-dm-dani-5", Slot.DANI, "Always. I'll bring the good chai ☕", 148),
                            // Luna's phone acked from town, over an Internet relay — the globe on the ✓✓.
                            DemoMsg("fest-dm-dani-6", Slot.ME, "Deal.", 120, via = DeliveryPlane.Internet),
                        ),
                ),
                DemoThread(
                    Slot.SAM,
                    read = false,
                    messages =
                        listOf(
                            DemoMsg("fest-dm-sam-1", Slot.ME, "Epic set tonight! 🔥", 30),
                            DemoMsg("fest-dm-sam-2", Slot.SAM, "Right?? Same crew at the dome tomorrow?", 9),
                            DemoMsg("fest-dm-sam-3", Slot.SAM, "Oh and I found your goggles 🥽", 7),
                            // Voice-note parity with the hiking theme: the same route (`chat/samr1v00`) that
                            // the a11y audit and the store capture open shows the voice bubble in both themes.
                            DemoMsg("fest-dm-sam-4", Slot.SAM, "", 5, voiceSeconds = 9),
                        ),
                ),
            ),
        group =
            DemoGroup(
                name = "Camp Lost Horizon",
                members = listOf(Slot.ME, Slot.SAM, Slot.PRIYA, Slot.THEO),
                messages =
                    listOf(
                        DemoMsg("fest-group-1", Slot.SAM, "Camp Lost Horizon meetup — Man burn at 9? 🔥", 300),
                        DemoMsg("fest-group-2", Slot.PRIYA, "I'm in 🙌", 298),
                        DemoMsg("fest-group-3", Slot.THEO, "Same. Meet at the bikes?", 295),
                        DemoMsg(
                            "fest-group-4",
                            Slot.ME,
                            "Works for me. I'll bring the LED totem 🔆",
                            290,
                            // Mirrors demo-group-4: the partial delivery + several reactors that make
                            // "Message info" worth capturing (and worth auditing) in either theme.
                            deliveredTo = listOf(Slot.SAM, Slot.PRIYA),
                            reactions =
                                listOf(
                                    DemoReaction(Slot.SAM, "👍", 289),
                                    DemoReaction(Slot.PRIYA, "👍", 288),
                                    DemoReaction(Slot.THEO, "❤️", 287),
                                ),
                            replyTo = "fest-group-3",
                        ),
                        DemoMsg("fest-group-5", Slot.PRIYA, "You're a legend 🔆", 288),
                        DemoMsg("fest-group-6", Slot.THEO, "", 240, voiceSeconds = 12),
                    ),
            ),
        requests =
            listOf(
                DemoThread(
                    Slot.RIVER,
                    read = false,
                    messages =
                        listOf(
                            DemoMsg(
                                "fest-req-wren-1",
                                Slot.RIVER,
                                "Hey! First burn, found you on the mesh — is the dome open tonight?",
                                55,
                            ),
                            DemoMsg("fest-req-wren-2", Slot.RIVER, "Totally fine to ignore me, just saying hi 🙂", 52),
                        ),
                ),
            ),
        requestGroup =
            DemoGroup(
                name = "Sunrise Bike Posse",
                members = listOf(Slot.ME, Slot.RIVER, Slot.NOAH),
                messages =
                    listOf(
                        DemoMsg("fest-req-group-1", Slot.NOAH, "Rolling out at 5:30 from 7:30 & Esplanade — adding everyone nearby!", 48),
                    ),
            ),
        blocked = listOf(Slot.MARLO),
        meshRoom =
            listOf(
                DemoMeshPost(
                    "fest-mesh-1",
                    "Node up at 9 o'clock plaza, solar. Should hold all week.",
                    minsAgo = 84,
                    node = 0x7A41C0E2L,
                    name = "Plaza Solar",
                    hops = 2,
                    snrDeci = -64,
                ),
                DemoMeshPost(
                    "fest-mesh-2",
                    "Testing LongFast from the trash fence — anyone copy?",
                    minsAgo = 61,
                    node = 0x2C90B711L,
                    name = "W7DUST",
                    hops = 4,
                    snrDeci = -110,
                    viaMqtt = true,
                ),
                DemoMeshPost(
                    "fest-mesh-3",
                    "Way out past the fence. No phones out here — the board's carrying this.",
                    minsAgo = 18,
                    node = THEO_BOARD,
                    name = "Knit 4b1e",
                    hops = 1,
                    snrDeci = 58,
                    peer = Slot.THEO,
                    signed = MessageEntity.ORIGIN_SIGNED_BY_CONTACT,
                ),
                DemoMeshPost(
                    "fest-mesh-3b",
                    "Temp at the fence: 41C. Drink something, all of you.",
                    minsAgo = 41,
                    node = 0x5D08E4A1L,
                    hops = 2,
                    snrDeci = -41,
                ),
                DemoMeshPost("fest-mesh-4", "Copy Echo — riding out that way at sunrise 🚲", minsAgo = 14, mine = true),
                DemoMeshPost(
                    "fest-mesh-5",
                    "Bring water for two. It is a long way back in the dark.",
                    minsAgo = 9,
                    node = 0x7A41C0E2L,
                    name = "Plaza Solar",
                    hops = 2,
                    snrDeci = -68,
                ),
            ),
    )

/**
 * The Meshtastic node number the contact-with-a-board carries in both themes (Theo / Echo), and the key their
 * profile advertises for it. Theme-independent for the same reason the relay URLs are: a node number reads the
 * same at a trailhead and on the playa. The key is illustrative — a demo build verifies nothing, it only draws
 * the verdict that was frozen on the row at ingest.
 */
private const val THEO_BOARD = 0x4B1E77A0L

/** A second contact's board, so a Profile Details capture has a claimed-board row of its own to show. */
private const val DANI_BOARD = 0x91C33FD5L
private const val DEMO_LORA_KEY = "ZGVtby1sb3JhLXNpZ25pbmcta2V5LW5vdC1yZWFsLTAwMDA="
