@file:Suppress("MagicNumber")

package app.getknit.knit.demo

import app.getknit.knit.data.message.DeliveryPlane

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
)

/** A peer contact. [verified] pins a (fake) key + out-of-band confirmation so the DM header shows the
 *  verified badge. The avatar is loaded from `demo/avatars/<theme>/<nodeId>.jpg`. */
data class DemoPeer(
    val slot: Slot,
    val name: String,
    val status: String,
    val verified: Boolean = false,
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
                DemoPeer(Slot.SAM, "Sam Rivera", "Trail mix enthusiast"),
                DemoPeer(Slot.DANI, "Dani Cho", "Summit or bust", verified = true),
                DemoPeer(Slot.THEO, "Theo Blake", "Mostly lost"),
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
    )

// --- Festival / Burning Man --------------------------------------------------------------------------

private val FESTIVAL_SCENARIO =
    DemoScenario(
        theme = "festival",
        meName = "Zara Vance",
        meStatus = "Deep playa till sunrise ✨",
        peers =
            listOf(
                DemoPeer(Slot.SAM, "Kai Brooks", "Art car captain 🚐"),
                DemoPeer(Slot.DANI, "Luna Reyes", "Find me at sunrise 🌅", verified = true),
                DemoPeer(Slot.THEO, "Echo Tanaka", "Sound camp till dawn 🔊"),
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
    )
