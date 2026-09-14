---
id: "2026-09.tmbq"
slug: a-relay-invite-is-a-bearer-link
title: "A relay invite is a bearer link, applied on consent and never silently"
date: 2026-09-14
topics: [spool, relays, ui, contacts]
---

# ADR 2026-09.tmbq — A relay invite is a bearer link, applied on consent and never silently

Status: Accepted (2026-09-14) — `docs/RELAY_INVITE.md`; amends ADR 042 (two sentences) and closes the
"deep-linked invites" follow-on ADR 2026-09.wx8e listed.

**What was observed.** Joining a private spool was six steps across two pasted secrets: Settings › Internet
relays, the master switch and its disclosure, Add relay, retype `wss://host/spool/v1?k=token`, wait for the
row to connect, Join, paste `knit-commons:v1:…`. The operator had to send two strings and explain where each
went; the newcomer retyped a bearer token by hand. Meanwhile the contact card (ADR 042) already carried relay
hints that the Add-contact preview *displayed* with "add it under Settings › Internet relays" — a trip the
user rarely made, so a card-holder's relay stayed unknown and the pair met nowhere.

**What changed.** One link, `https://getknit.app/r#…` / `knit://r/…`, carries the relay URL (token
included), and — when the minter had joined the relay's commons — the room's 32-byte secret and name
(`mesh/spool/RelayInvite`, `{v:1, u, c?, n?}` in the house CBOR, base64url in the URL fragment). Tapping it,
sharing it to Knit, or pasting it lands on the Internet-relays screen (`ui/relay/RelayInviteInbox`, the
`ContactCardInbox` shape, kept across onboarding for the card's reason) and raises **one sheet**
(`ui/relay/RelayInviteSheet`): the host in the largest type, whether the relay is private, which room it
joins, and — the first time — the master switch's whole disclosure. One confirmation runs **one apply
sequence** (`data/relay/RelayInviteApplier`): consent through `SettingsStore.acceptSpoolConsent()` when
missing (else `setSpoolEnabled(true)` when off), the relay stored and un-parked, the room joined,
`MeshController.refreshRelays()` so the row goes green now. The relay row gained Share / Copy, which mint
the same link from what the row stores. The contact card's relay hints gained an inline **Add** that goes
through the same sheet and the same applier.

Four decisions, and the alternative each turned down:

- **Unsigned.** The card is signed because it asserts an identity; an invite asserts nothing — the URL
  *is* the capability, and a relay has no identity key to sign as. Signing with the *sharer's* key would
  let the sheet say "shared by Alice", but Alice is not who the newcomer needs to trust here; the host is.
  So the host is the headline instead. A signed-or-expiring invite is deferred; revocation today is
  rotating the token or the room, which is what the daemon already offers.
- **The link carries the token, on purpose.** ADR 042 said "tokened `?k=` URLs never leave the minter";
  that stays true *of the card* — a card is broadcast-shaped (shared with anyone, pasted anywhere) and a
  token in it would leak to everyone who ever saw it. An invite is a deliberate hand-off of exactly that
  credential: sharing it is the act of admitting someone. The alternative — a link with the untokened URL
  and the token pasted separately — is the two-string onboarding this replaces.
- **Never applied silently — which is what ADR 042's "never applied" was protecting.** "Adding a relay
  hands it every scope id and IP this device has" is still the threat; the defence was never the trip to
  Settings, it was that the user *chose*. So the sheet names the host, states the per-relay cost every
  time ("this relay will see your IP address and which of your conversations are active"), folds in the
  full disclosure when it has not been accepted, and nothing is stored before the tap. ADR 063 is
  preserved exactly: `acceptSpoolConsent()` remains the only consent write; the sheet is the same
  disclosure raised from a second door, not a second consent. `ContactImporter.import` still never
  touches the relay list (`relayHintsAreSurfacedNeverAppliedSilently`).
- **A relay is identified by its redacted URL.** A card's hint is untokened and an operator's invite is
  tokened; matched by exact string they would be two rows for one host, the untokened one refused `4001`
  forever. A tokened invite therefore *replaces* an untokened entry (said on the sheet; a room joined under
  the old entry is re-bound, not lost), and an untokened invite over a tokened entry is "already in your
  list" — never a downgrade of the stored credential.

**What it costs, and what it does not cover.**

- **Rotation leaves the old room.** Spec §7.4 is one commons per spool and the relays screen keys rooms by
  URL, so a different secret at the same relay can only be a rotated invite; a second room there would have
  no Leave. The sheet says the old room and its history leave this phone — the Leave dialog's own sentence —
  and confirming leaves then joins. A room already joined is never re-joined: `CommonsRepository.join`
  upserts the whole row, and re-writing it with the link's (possibly absent) name would erase the one the
  relay advertised.
- **COMMONS off ignores the room half** without comment — the same `CommonsRepository?` seam wx8e chose,
  null while the feature is dark. The relay half still applies. No new gate.
- **No bare form.** The card accepts a bare base64url run (≥ 200 chars, a QR-era input); an invite is
  short, only ever minted as a link, and `looksLikeInvite` feeds `MainActivity.handleShareIntent`'s
  "this is not a draft" early return, where a false positive silently eats a shared paragraph. The two link
  kinds are disjoint by prefix and pinned so (`RelayInviteTest`, `RelayInviteInboxTest`).
- **The https form still opens in the browser on Android 12+** until `getknit.app` publishes assetlinks
  and a `/r` landing page building the `knit://r/` link client-side — the same out-of-repo debt as `/c`
  (`docs/RELAY_INVITE.md` §6). `knit://` and share-to-Knit work today.
- Not built: a QR rendering, a signed or expiring invite, a share toggle that omits the room secret, an
  onboarding page that asks for an invite. The daemon printing the link is the `knit-spool` repo's
  (contract in `docs/RELAY_INVITE.md` §4; the golden vectors are the bytes it must reproduce).

**The trap.** The `Parsed.Invite` and the applier's `Preview` are plain classes, not data classes, on
purpose: a data class's `toString` would print the bearer token and the room secret into any log line that
mentions the invite (`theParsedInviteNeverPrintsItsCredentials`). Kept true by `RelayInviteTest` (two
golden vectors, every refusal, never throws), `RelayInviteApplierTest` (the consent write, the redacted
match, the rotation, the no-store build), `InternetRelayViewModelTest`, `InternetRelayScreenContentTest`
(the sheet per state), and `InternetPlaneLabTest.aRelayInviteTurnsThePlaneOnAddsTheRelayAndJoinsTheRoomInOneStep`
— a node with the plane off applies an invite minted by a member and ends up subscribed, in the room, and
holding its first post. Device trial NOT run.
