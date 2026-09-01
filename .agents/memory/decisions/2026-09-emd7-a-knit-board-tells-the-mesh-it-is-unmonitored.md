---
id: "2026-09.emd7"
slug: a-knit-board-tells-the-mesh-it-is-unmonitored
title: "A Knit board tells the mesh it is unmonitored"
date: 2026-09-01
topics: [lora, meshtastic, provisioning]
---

# ADR 2026-09.emd7 — A Knit board tells the mesh it is unmonitored

Status: Accepted (2026-09-01)

**What was observed.** `LoraMeshTransport.onLoraPacket` keeps only `PORT_PRIVATE_APP` (256) and drops
everything else, so an ordinary Meshtastic text message addressed to a Knit board reaches the radio,
is ACKed by the firmware's own routing layer, and then hits the floor. Nothing in Knit ever renders it
and nothing tells the sender. The failure is not "the message was lost" — it is worse than that: the
sender's app shows a **delivered** tick against a message no human will ever read. A stock neighbour
also spends hops and a slice of a shared duty-cycled band getting it there. ADR 045 already renames the
board, quiets its node-info / position / telemetry to 6 h and drops it to `LOCAL_ONLY` rebroadcast; the
node those writes produce is precisely what Meshtastic means by *unmonitored*, and it was the only part
of that identity Knit was not stating.

**What changed.** The board's Knit identity (`BoardName`) gains `User.is_unmessagable` (field 9,
`optional bool`) alongside the two names, written in the **same** `set_owner` the setup already sends and
recorded in `BoardSettings.owner` so **Restore** clears it again. Clients that honour the flag grey the
node out instead of offering it as a message target. The mark is spliced over the board's own `User`
bytes, never composed from scratch: `AdminModule::handleSetOwner` merges rather than assigns, and *both*
bools it merges are cleared by an omission — `is_licensed` has no presence at all (and takes
`config.lora.override_duty_cycle` with it, the trap ADR 049 already documented), while
`is_unmessagable` does have presence yet is still assigned whenever the two presence bits differ, so an
absent one clears a board that had it set.

The alternative a reader reaches for first is `device.role = CLIENT_MUTE`, which produces roughly the
same "don't talk to me" effect in other clients. It is wrong here: it stops rebroadcast outright, and
ADR 044's pocket-to-pocket bridge needs `LOCAL_ONLY`'s own-channel relaying. The mark is a `NodeInfo`
hint and changes no routing at all, which is exactly the scope wanted.

**A firmware floor, and why it points the other way from the airtime one.** The plumbing landed in
firmware **2.6.9** (`AdminModule::handleSetOwner`, commit `2e72850d`, released 2025-05-25); 2.6.8 and
older drop field 9 as unknown and never echo it back. `BoardName.honoursUnmessagable` gates the mark on
that, and treats **any** version it cannot parse as too old — the opposite reading of the same
`DeviceMetadata.firmware_version` string from `LoraAirtime.signsPackets`, which treats an unreadable
version as signing. The costs are opposite: there, guessing wrong spends airtime; here, guessing wrong
means writing to somebody's hardware on a hunch and — since a board that drops the field never reports
it back — leaving `needsRename` true forever, so the screen nags about an unfinished setup on a board
that is fine.

**What it costs.** One more thing the setup changes on hardware the user may use for other things, so
the confirmation sheet states it out loud (`lora_setup_confirm_body`) alongside the rename and the
quieting. A user embedded in a local Meshtastic community may not want their node greyed out for
neighbours; ADR 045's bargain already says a board is set up for Knit **or** it is a stock node, and
Restore is the way out. The mark propagates only on the board's own `NodeInfo`, which the same setup
quiets to 6 h, so neighbours learn it slowly.

**What it does not cover.** Boards already set up by an older Knit. `needsRename` (and so the one
`set_owner` button on the screen) now fires on the *whole* identity rather than the name alone, which is
the only route to those boards — the plain setup button is gone once `boardSetUp`. The screen tells the
two halves apart by comparing `meshName` against `knitName`: equal names mean the mark is the missing
half, and the button says *Mark this board unmonitored* rather than lying about a rename.

**The trap the next person will hit.** `renameOnly` used to fill `previous.owner` in from the board's
current name unconditionally, which was safe only while the path was reachable exclusively on a
stock-named board. It is not any more — a board that is merely missing the mark is *already* called
`Knit abcd`, and recording that would destroy the only copy of the stock name a Restore has to put back.
It now fills in rather than overwrites (`cmd.spec.previous.owner ?: was.owner`). Regressions:
`MeshtasticSessionTest.provisionOnANamedButUnmarkedBoardMarksItAndKeepsTheNameItRecorded`,
`provisionLeavesTheMarkOffFirmwareThatWouldOnlyDropIt`, and the `restore…` pair that assert the mark is
absent from the restored `User`; the wire number is pinned by
`MeshtasticProtoTest.decodeAdminReadsTheUnmonitoredMarkOffTheOwner`.
