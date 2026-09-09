---
id: "2026-09.tss4"
slug: a-shared-location-is-a-geo-uri-in-the-body
title: "A shared location is a geo URI in the body, read only when you send it"
date: 2026-09-08
topics: [location, privacy, ui, permissions, moderation]
---

# ADR 2026-09.tss4 — A shared location is a geo URI in the body, read only when you send it

Status: Accepted (2026-09-08)

**What was observed.** Two people who had lost each other on the mesh had no way to say *where* beyond
prose. The obvious build, with a link-preview card one ADR old, was another attachment kind: a small
CBOR container under its own MIME, pulled and rendered like a card. Weighed against what the message is
for, that was the wrong carrier three times over. An attachment is a second round trip — the frame lands
and the bytes follow by `BlobExchange` when a holder is reachable, which in the "I can't find you"
scenario is exactly when they are not. A card never rides LoRa (`cardWanted` refuses when `loraCarry !=
None`), and the long-range plane is where separated parties most need this. And a ~40-byte sealed blob
beside a DM is a fingerprint: ADR 035 §6 already concedes that size does not close, and "this message
carried a location" is a louder fact than "this message carried a photo".

**What changed.** A shared position is text: an RFC 5870 `geo:` URI on the body's last line,
`geo:37.421998,-122.084000;u=12`, written and read by `location/GeoUri` (pure, strict grammar, `Locale.ROOT`
— a German locale would otherwise emit `37,42…`). No wire field, no capability bit, no schema change:
`MessageContent.body` travels verbatim (`CanonicalText` never touches bodies), the position arrives with
the frame on every plane, LoRa included, and a build that predates this one shows a legible line a person
can paste into a maps app. A build that has it parses the first token that passes the grammar into
`ChatRow.location` and draws `LocationCard` in the line's place: coordinates, the sender's stated radius,
tap to hand a `geo:` intent to whatever maps app is installed (offline ones included; no `<queries>` entry
is needed, since package visibility never filters an implicit `startActivity`), and a copy button for one
that wants them typed. No map tile — the phone drawing it usually has no Internet. The chat list, the
request list, message details, a notification and a reply quote all name it (`📍 Location`, through
`messagePreview` and the pipeline's mirror) rather than print it, and copying the message copies the raw
body on purpose.

The send side is the whole of the feature's privacy surface, and it is read on demand or not at all. The
pin in the message field (the inboard slot, so the mic and paperclip stay where they were) stages a
position; `ChatViewModel.startLocation` is **the only collector of
`LocationSource.fixes`** in the app, and `location/AndroidLocationSource` is the only `android.location`
importer (detekt's `ForbiddenImport` now says so). The tile listens for at most
`LocationFixPolicy.REFINE_WINDOW_MS` (60 s) or until a reading is within 8 m, freezes what it has, pauses
when the chat leaves the screen (the existing `onChatBackground` observer, on `ON_PAUSE`) and re-arms when
it returns, and stops on send, on clear and on `onCleared`. Nothing runs at app start, in a service, or
in onboarding: `ui/Permissions.kt`'s API 33+ tier stays location-free and `PermissionsTest` pins it. The
grant is asked the first time the pin is used, by `rememberLocationGate` (both permissions, since Android
12 wants the pair; either counts, and an approximate-only grant is staged with its two-kilometre radius
said out loud), behind a one-time disclosure sheet recorded in `SettingsStore.locationShareConsented`. On
Android 10–12 the radios already hold the location grant from onboarding, so that sheet is the feature's
only explicit opt-in there — which is the reason it exists at all rather than leaving the OS prompt to do
the asking.

Offered in DMs, groups and the Nearby room; the room's disclosure and tile say that everyone within radio
range sees the exact position, unencrypted, and that phones carrying the room can hand it to people who
come into range over the next day (broadcast custody). The Meshtastic public room stays out: its channel
carries one line of text under a hard 166-byte cap, and folding the token into that counter is its own
change (roadmap).

**What it costs.** The moderator must not see the token. `LexicalTextFilter` maps leet digits to letters
and splits on the rest, so a run of coordinates spells a blocked word by accident
(`LexicalTextFilterTest` pins `…9455…` → `ass`), and the ML pass was never shown a digit string. So
`MeshManager.isTextFlagged` classifies `GeoUri.strip(text)` and returns false when nothing is left —
one function, and every send path and the inbound classify go through it. The body cap needs a reserve:
the receiver clamps at `TextLimits.MESSAGE`, so `send()` cuts the text first and puts the token last,
under the cap. A staged position takes `GeoUri.RESERVE_BYTES` off the LoRa body hint. And `send()` with a
tile still looking refuses (`chat_location_not_ready`) and keeps the draft, the rule every refusal in the
composer follows. Not built: live location (a stream of these, with a stop control), a receiver-side
distance-and-bearing "Guide me" mode (it needs the receiver's own position on demand, under the same
opt-in), and a static map preview. The trap for the next person: a second `fixes()` collector anywhere —
a "distance to this pin" readout in a bubble is the tempting one — breaks the promise the disclosure makes,
so it goes through the same gate and the same tile, or it does not go in. What keeps this true:
`GeoUriTest`, `LocationFixPolicyTest`, the location cases in `ChatViewModelTest` (a `FakeLocationSource`
that counts collectors), `ChatScreenContentTest`, `MeshManagerTest`'s capture of what the moderator was
handed, `LexicalTextFilterTest`, `MentionTextTest` (a geo URI is not a link), and `PermissionsTest`.
