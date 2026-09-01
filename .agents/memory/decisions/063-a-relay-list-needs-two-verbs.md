---
id: "063"
slug: a-relay-list-needs-two-verbs
title: "A relay list needs two verbs: the plane's switch says whether, each relay's says which"
date: 2026-08-30
topics: [spool, ui, relays]
---

# ADR 063 — A relay list needs two verbs: the plane's switch says whether, each relay's says which

The Internet plane shipped with one control, and the relay list inherited its shape from that: a
`Set<String>` where membership *is* use. So the only way to stop using a relay was to **delete** it —
which forgets the address, forgets the `?k=` bearer token that for a private spool is the whole access
control (§7.1), and, because `seedDefaultSpools` writes its seeded marker so that a removal sticks,
cannot be undone by reinstalling or restarting. A user who wanted to quiet a flaky relay for an evening,
or run two and compare them, had to choose between that and turning off the whole plane.

The two questions are genuinely different. The master switch asks **whether** sealed copies of a
conversation may leave this device at all — a threat-model change, which is why it carries a one-time
disclosure and defaults off. A relay's own switch asks **which** third parties carry them, given that
they already do. Nothing about the second question needs consent the first did not already obtain, and
it can only ever narrow what the first permits, so the per-relay switch writes straight through with no
sheet and no confirmation.

**Parked is stored, not derived.** A second `stringSetPreferencesKey("spool_urls_disabled")` holds the
**disabled** subset rather than the enabled one, which is what makes "in use" the meaning of a bare URL:
every list that predates this setting, every `seedDefaultSpools` default, every `--es url` from the debug
bridge and every `DemoPlanes` seed stays live without a migration or a backfill. The alternative shapes
were both worse — encoding `url|0|1` into the existing set breaks the set arithmetic two callers depend
on (`ContactImporter`'s `card.spools - spoolUrls`), and moving the list to Room engages ADR 008's
migration discipline for a value that is pure device-local preference. `removeSpoolUrl` clears the
parked flag in the same `edit {}`, so re-adding an address later comes back on; a stale flag would read
as the app ignoring the user.

**The composition lives in `SettingsStore`, for ADR 031's reason.** `activeSpoolUrls` = `spoolEnabled ?
spoolUrls - disabled : emptySet()`. That is the same argument `spoolEnabled` makes for folding
`BuildConfig.INTERNET_PLANE` into itself rather than leaving each consumer to remember the flag: one
flow answers "which relays may carry for us", so `ScopeSync`'s url supplier, the contact card's `sp`
list and `RelayStatusRepository`'s counts cannot drift apart, and a future consumer cannot forget either
gate. Two gates composed at each of five call sites would be ten chances to get it wrong.

Which consumer reads which, and why:

- **`ScopeSync`'s url supplier** reads the active set — the one line that actually stops a socket.
  `reconcile()` already stops and drops a worker whose URL left the config, so parking needed no
  transport change at all.
- **The contact card** reads the active set. `docs/CONTACT_CARD.md` §2 defines `sp` as "relay URLs the
  owner uses"; publishing one we have switched off points a new contact at an address we never read a
  frame from.
- **`ContactImporter.unknownRelays`** deliberately keeps reading the *full* list. It asks "is this URL
  in my list", and the action it offers is "add it" — a relay we know and chose to park is not missing.
- **`mintGroupRootsIfDue`** deliberately keeps reading `spoolEnabled`. Spec C-3.2-1 gates minting on the
  plane being enabled for the group, not on any particular relay; the root is durable and gossiped, so
  minting while every relay is parked costs nothing and losing the mint would cost a rotation.

**`RelayFacts` gained `active` beside `configured`**, and `planeFor`/`reachFor` turn on the new one: a
device whose relays are all parked reaches nothing, however long its list is. `configured` survives only
so the Profile subtitle can tell "no relays added" from "none in use" — two states that ask opposite
things of the user.

**In the row, intent outranks liveness.** A parked relay keeps its worker for up to one 15 s reconcile
tick, and a row that still read "Connected" through those seconds would look like the switch had not
worked — so the dot and the status line test the two off states first, and they are separate strings:
"the plane is off" repeats down every row, while "you turned this one off" is a fact only that row can
state. The `toggleable` sits on an inner row holding the dot, text and switch, with the delete button as
a sibling outside it; putting it on the outer row would nest a button inside a toggle and announce as
one control that does two things. The switch greys out while the master switch is off, like
`LoraRadioScreen`'s sub-switches.

**Local, and staying local.** Nothing here reaches a spool or a peer: §1.2 says spools never talk to each
other and no spool is load-bearing, and nothing in the spec obliges a client to dial every spool it
knows — which spools a device connects to is client policy. So there is no wire, DB or protocol change,
and no §13 vector moves. When `CTL_SCOPE_CONFIG` (ctl 7) eventually ships, `spools` becomes conversation
config and this flag becomes a local override on top of a shared list; that is the shape to build then,
not a reason to withhold the switch now.

**The one cost, accepted.** Parking a relay discards that worker's in-memory accounted set (ADR 062,
spec §9.6), so un-parking re-pulls one band, bounded by `maxFrames`. That is exactly what a process
restart already costs, which C-9.6-4 permits; what §9.6 protects against is a band per *automatic*
reconnect, and this is a deliberate user action. Not worth a durable set.

`--es park <url>` / `--es unpark <url>` on the `…debug.SPOOL` bridge action flip one relay from a locked
lab device without losing its token, and the dump reports `disabled` beside `configured`.
