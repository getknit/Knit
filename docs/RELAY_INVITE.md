# The relay invite

**One relay as a shareable link — the Internet-relays screen's whole first visit in a single tap.**

|                    |                                                                                              |
|--------------------|----------------------------------------------------------------------------------------------|
| Layout version     | 1                                                                                            |
| Status             | Built 2026-09-14 (ADR 2026-09.tmbq)                                                           |
| Reference          | `mesh/spool/RelayInvite.kt` (codec), `data/relay/RelayInviteApplier` (the one apply sequence), `ui/relay/RelayInviteSheet` (the preview), `ui/relay/RelayInviteInbox` (the intent hand-off) |
| Executable anchors | `RelayInviteTest` (golden vectors below), `RelayInviteApplierTest`, `RelayInviteInboxTest`, `InternetRelayViewModelTest`, `InternetPlaneLabTest` (the lab scenario) |

## 1. What it is for

Joining a private spool used to be six steps across two pasted secrets: turn the plane on (and read the
disclosure), add the relay, retype `wss://host/spool/v1?k=token`, wait for it to connect, tap Join, paste
`knit-commons:v1:…`. An operator running a household or team instance now shares one link; the newcomer
taps it (or shares it to Knit, or pastes it on the relays screen), reads one sheet that names the host
and says what the link carries, and confirms once. The relay is stored, the plane is switched on — with
the one-time disclosure folded into that same sheet when it has not been shown yet — the room is joined
when the link carries one, and the relay is dialled immediately rather than at the next reconcile tick.

The same sheet sits behind the contact card's relay hints: a card that names a relay you do not use
offers an **Add** that goes through it, so ADR 042's "displayed, never applied" became "never applied
*silently*" — the deliberate act is the confirmation on a sheet that names the host, not the trip to
Settings.

## 2. Layout

```
Body { v: 1, u: str, c?: bstr(32), n?: str }   // the house CBOR: definite-length, unknown keys ignored,
                                               //   defaults omitted; bstr = CBOR byte string
```

- `u` — the spool URL exactly as it should be stored, `wss://host[:port]/spool/v1[?k=<token>]`
  (`docs/SPOOL_PROTOCOL.md` §7.1). The token rides along **on purpose**: the link is how a private spool's
  credential is handed over. At most 256 chars, no whitespace or control characters, and the scheme rule
  is the dialer's own (`SpoolUrl.isAcceptable` — `wss://` always, `ws://` only in a debug build).
- `c` — the commons secret (§7.4), the same 32 bytes `knit-commons:v1:…` wraps; present when the minter
  had joined the relay's room. `n` — the room's name as the minter knew it, single-line, ≤ 32 chars.
- **Unsigned.** The contact card is signed because it asserts an identity; an invite asserts nothing —
  the URL is the capability, and a relay has no identity key to sign as. What a signature could not
  prove anyway (that the host is who the sender says it is) the sheet puts in the largest type on it.
- `v` exists for the change `ignoreUnknownKeys` cannot absorb; a field added to `Body` is additive.

Text forms, both accepted by `RelayInvite.parse`: `https://getknit.app/r#<base64url(Body)>` (the invite
rides the fragment, which a browser never sends to the server — it carries a bearer token) and
`knit://r/<…>`. A link is found anywhere in pasted text. There is **no bare form**: an invite is short
(~60–330 bytes) and only ever minted as a link, and the same probe (`looksLikeInvite`) tells the share
intent "this is an invite, not a message draft", where a base64-looking paragraph must not be swallowed.
The two link kinds are disjoint by prefix (`/c#` vs `/r#`, `knit://c/` vs `knit://r/`) and pinned so.

## 3. Trust

A link is a bearer credential over an unauthenticated channel, so the receiving side never applies it
without a sheet, and the sheet is built around one question — *do you trust this host?*:

- The **host** is the title, in the largest type. A private relay says so ("this link carries its access
  key"); a room says which one it joins.
- **Adding a relay is stated as a cost every time**, even after consent: "this relay will see your IP
  address and which of your conversations are active" — the per-relay half of the disclosure. The
  first time, the whole disclosure (`relays_consent_*`) is the sheet's body, and confirming records consent
  through `SettingsStore.acceptSpoolConsent()` — the *only* consent write, so ADR 063's "the master switch
  is where consent lives" still holds; the sheet is that same disclosure raised from a second door.
- Every step of applying is **idempotent**, so a re-tap converges: an already-listed relay says so, a
  parked one is turned back on, a room already joined is a no-op upsert.
- **A relay is identified by its redacted URL** (`SpoolUrl.redact`, the token stripped). A card hint is
  untokened and an operator's invite is tokened; matched by exact string they would make two rows for
  one host with the untokened one refused `4001` forever. So: a tokened invite over an untokened entry
  *replaces* it (said on the sheet); an untokened one over a tokened entry is "already in your list" and
  never downgrades the stored credential.
- **A different secret at the same relay is a rotation.** §7.4 is one commons per spool, and a rotated
  room "simply goes silent"; the relays screen keys rooms by URL, so a second room there would have no
  Leave. The sheet says the old room and its history leave this phone — the same sentence the Leave dialog
  states — and confirming leaves then joins.
- A build with the commons dark (`BuildConfig.COMMONS` off) applies the relay half and ignores the room
  half without mentioning it.

## 4. Minting

- **On the phone:** the relay row's Share / Copy actions mint `u` as stored (token included) plus the
  room's `c`/`n` when this device has joined it. "Invite to my instance" means the room; a member who
  wants to share the relay alone leaves the room first.
- **By the daemon** (out of repo): `knit-spool commons-invite` SHOULD print `https://getknit.app/r#…`
  beside `knit-commons:v1:…`, with `u` the spool's public URL including `?k=` when it is private, `c`
  the same 32 bytes, and `n` `SPOOL_COMMONS_NAME`. The vectors below are the contract.

## 5. Golden vectors

Pinned by `RelayInviteTest` (rows are add-never-move). The secret is `fixture(32, 10)`,
`fixture(n, seed)[i] = (7·i + seed) mod 256` — the same bytes whose text form `CommonsInviteTest` pins as
`knit-commons:v1:ChEYHyYtNDtCSVBXXmVsc3qBiI-WnaSrsrnAx87V3OM`, so one room has one secret in two spellings.

```
fullInvite = u "wss://home.example.org/spool/v1?k=fixture-token", c fixture(32, 10), n "Home":
             pGF2AWF1eC93c3M6Ly9ob21lLmV4YW1wbGUub3JnL3Nwb29sL3YxP2s9Zml4dHVyZS10b2tlbmFjWCAKERgfJi00O0JJUFdeZWxzeoGIj5adpKuyucDHztXc42FuZEhvbWU
bareInvite = u "wss://lax.spool.getknit.app/spool/v1", no c, no n:
             omF2AWF1eCR3c3M6Ly9sYXguc3Bvb2wuZ2V0a25pdC5hcHAvc3Bvb2wvdjE
```

## 6. Out of repo

`https://getknit.app/r` needs what `docs/CONTACT_CARD.md` §6 describes for `/c`: the assetlinks file
listing both signing certificates, and a landing page that builds the `knit://r/<fragment>` link
client-side ("Open in Knit") beside the install links, without redirecting `/r` to `/r/`. Until then an
unverified https link opens in the browser on Android 12+; the `knit://` form and share-to-Knit work
regardless. Lab devices (debug-signed) never verify — `adb shell pm set-app-links --package
app.getknit.knit 2 getknit.app`, or use the `knit://` form.

## 7. Deferred

A QR rendering of the invite for in-person onboarding; a signed or expiring invite (an operator who wants
to revoke rotates the token or the room today); a share toggle to omit the room secret; an onboarding
page that asks for an invite.
