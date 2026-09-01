---
id: "049"
slug: a-board-set-up-for-knit-is-renamed-for-knit
title: "A board set up for Knit is renamed for Knit, from its own node number, and named back on restore"
date: 2026-08-26
topics: [lora, provisioning]
---

# ADR 049 — A board set up for Knit is renamed for Knit, from its own node number, and named back on restore

Status: Accepted (2026-08-26; `mesh/lora/BoardName`, `MeshtasticProto` `get_owner`/`set_owner`,
`spliceStringFields`, `BoardSettings.owner`, `SettingsStore.loraBoardSetup`, the LoRa screen's setup section)

A Meshtastic node's `User.long_name` is its whole public identity — the board's own screen, every other
radio's node list, the Meshtastic app. A Knit board kept saying `Meshtastic abcd`, which meant the one piece
of hardware the user had just handed over to Knit was the one piece of hardware they could not identify. ADR
045 already decided a board is either **set up for Knit or a stock Meshtastic node**, with no middle setting,
so the name belongs on that same switch rather than behind a preference: setup renames, restore names back.

**`Knit abcd`, not `Knit`.** The firmware builds its default out of the low two bytes of the node number
(`Meshtastic %02x%02x` off the last two MAC bytes); keeping that shape and swapping the prefix means two
boards in one pocket stay distinguishable, which a bare `Knit` on every board would destroy exactly when it
matters. The short name — the four characters the small screens have room for, `char[5]` in the firmware —
is `Knit` in full, which is a coincidence worth taking.

**Deliberately not the user's display name.** `NodeInfo` is cleartext on the public frequency. The plane
already accepts that a DM's `senderId`/`recipientId`, timing and size travel in the open (ADR 039); adding a
human name to the standing broadcast is a different and worse kind of leak, for a cosmetic gain.

**The write is a read-modify-write, for a reason unlike the config writes'.** `AdminModule::handleSetOwner`
*merges* — it copies each non-empty string — so a `User` built from scratch would not clobber the rest. But
`is_licensed` is a presence-less proto3 bool: absent reads as `false`, and the firmware answers that by
clearing `config.lora.override_duty_cycle`, the escape hatch a licensed operator set on purpose. So the
board's own `User` from `get_owner_request` is the base and only the two names are spliced
(`spliceStringFields`, the string sibling of ADR 045's `spliceVarintFields`); the public key, hardware model
and licence survive byte-for-byte. A board that will not return its `User` aborts the setup with nothing
written, exactly as a board that will not return its config does.

**A board already carrying the Knit channel still gets renamed** — one `set_owner`, and nothing else. That
is the one exception to ADR 045's "a re-run is a reported no-op", and it exists because every board set up
before this ADR is in that state. **Which means the screen has to offer it**, and ADR 045's setup section
hides its one button the moment the board carries the Knit channel — so the action was unreachable exactly
where it was needed. The board's own name turned out to be in the handshake already: `NodeInfo.user` on the
board's own entry, arriving in the same `want_config` stream ADR 041's battery is read from. Decoding it
into `BoardInfo.owner` lets the screen tell "set up" from "set up and named", and a board in the second
state gets the action back as a **rename button that says the new name in full** and goes straight through
— no confirmation, because unlike the setup it writes one reversible, visible field and nothing about the
board's battery or broadcasts. Firmware that never sends its own `NodeInfo` reports no name, which reads as
"no reason to think it needs renaming": going on offering a rename that may already be done is the worse
failure, the same benefit of the doubt ADR 045 gives an unreadable channel table. It must not re-record the intervals: they are the *quieted* ones by then,
and overwriting the stored record with them would destroy the only copy of the board's own. So the caller's
existing record is carried forward with the old name filled in, and when there is no record the old name is
simply not recoverable — a restore then writes the name the firmware itself would have chosen
(`BoardName.stock`), because leaving a restored board saying `Knit` is the one visible trace of a restore
that is meant to leave none.

Wire: none of Knit's. Meshtastic only — admin `get_owner_request` (3) / `get_owner_response` (4) /
`set_owner` (32), `User.long_name` (2) / `short_name` (3), and `NodeInfo.user` (2) off the handshake — all
pinned by golden vectors in `MeshtasticProtoTest`. `WIRE_COMPAT`/`NEXT_WIRE_BREAK` untouched.

Honest residuals (accepted): the rename is one more admin write in the setup transaction, so one more way
for it to half-fail on a flaky link (the commit is still a single transaction, so a failure leaves the board
untouched); a user who renames the board back by hand in the Meshtastic app gets renamed again by the next
setup tap, which is the right default but is not asked about; the name says a board runs Knit to anyone
listening on the public frequency, which is a deliberate trade of anonymity for findability and the reason
it is tied to the setup switch the user consents to; and the four-character short name leaves no room for a
per-board tag, so two Knit boards are told apart by their long names only.
