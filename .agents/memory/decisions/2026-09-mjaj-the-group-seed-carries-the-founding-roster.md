---
id: "2026-09.mjaj"
slug: the-group-seed-carries-the-founding-roster
title: "The group seed carries the founding roster"
date: 2026-09-18
topics: [groups, roster, spool, wire]
---

# ADR 2026-09.mjaj — The group seed carries the founding roster

Status: Accepted (2026-09-18; `GroupKeyPayload.group`, `GroupEntity.toFoundingInfo`,
`InboundPipeline.pinRosterFromSeed`, `MeshManager.replayCustodiedSeedDms(except)`, spec C-3.2-16,
`InternetPlaneLabTest.aGroupFoundedAcrossTheRelayReachesTheRelayOnlyMember` un-ignored). Amends ADR
2026-09.v6fu (a seed is now one of the frames that carries a rejoin) and ADR 019's M4 (`GroupKeyPayload.gr`
was the plane's first mesh-wire field; this is its second). GitLab work item #47. **Device-verified
2026-09-18** on the lab (Pixel 9 founder, Pixel 3 in radio range, Moto G with Bluetooth off — it has no
Wi-Fi Aware — reachable only over `lax.spool.getknit.app`): the root-gossip seed pinned the group on the Moto
G at once (`pinned group … from the seed`, `groupRootsAdopted 1`, `groupSeedsHeld 0`), the epoch seed adopted
on its own pass, the founding message crossed the relay 26 s after the send, and the Moto G's sealed receipt
came back to the founder over its DM scope. Nothing parked, nothing met by radio.

**What was observed.** The mesh-in-a-box lab, 2026-09-14, first run of
`InternetPlaneLabTest.aGroupFoundedAcrossTheRelayReachesTheRelayOnlyMember`: Alice, Bob and Carol are
contacts with DM sessions both ways and DM scopes on one relay; Carol is out of radio range; Alice founds a
group of the three and sends. Alice and Bob hold it, Carol never does — three seeds parked
(`groupSeedsHeld = 3`, `groupSeedsReplayed = 0`), no group row after 90 s, and the parks expire an hour later.
Three refusals in a ring. Alice's sender-key seed rides Carol's DM scope as a `CTL_GROUP_KEY` ctl DM with the
group root inside it (`GroupKeyPayload.gr`). `InboundPipeline.holdGroupKeyForUnknownGroup` parks it because
Carol holds no row (the cf94a06 hold), and `MeshManager.adoptGroupRoot` refuses the root for the same reason —
spec C-3.2-9/10 need the founding roster to vet the sender and the minter. The roster rides only the group's
own chat frame, which `ScopeFrames.eligibleForDm` refuses (`env.group != null`) and only the group scope
admits; that scope derives from the root Carol just refused. Nothing she is subscribed to can carry the
roster. The radio case works (`CustodyLabTest.groupCreatedWhileAMemberWasAwayArrivesThroughACarrier`)
because custody carries the roster-bearing frame itself, and ADR 064's two-island trial never met it because
that shape founds the group together first.

**What changed.** The seed carries the founding roster. `GroupKeyPayload` gained the nullable
`group: GroupInfo?` — the roster half of the row (`GroupEntity.toFoundingInfo`: id, members, departed,
creator, name; never the photo, which is a blob to pull and rides the group frames the roster unlocks) — on
every distribution the one builder `MeshManager.groupKeyPayload` emits, the C-3.2-5 posture the root already
had, so the four emit sites cannot drift (spec C-3.2-16). On receive, a seed for a group we hold no row for,
or from a sender we hold as departed, pins the group *before* the park decision
(`InboundPipeline.pinRosterFromSeed`) through the one door every roster goes through — `reconcileGroup`, so
`vetRoster`'s derivation check (the id *is* the hash of the founding set, we and the sender are in it, at
most eight) and `rejoinBy`'s `sentAt > leftAt` guard apply exactly as they do to a group frame. It runs on
the lock-free peek, so the same pass's ratchet commit adopts the seed and the root, the ack goes out, and
nothing is parked; `onGroupRootCtl` then re-derives the scope table at once (`ScopeSync.onScopeTableChanged`,
not the 15 s tick), and the relay-only member pulls the founding frame from a scope it now derives. The park
(`PendingGroupKeys`) and the first-sight custody replay (`replayCustodiedSeedDms`) remain, as the fallback
for a seed from a build without the field or with a refused roster. The alternatives the work item named:
pushing the founding frame to each member's DM scope as well (Option 2) means teaching `eligibleForDm` a
group-form frame for the recipient's own roster — widening the scope frame-set rule `rules/mesh.md` warns
against, and a per-member copy of every founding frame at the spool; documenting "found a group by radio"
(Option 3) leaves the relay unable to do the one thing a relay is for. The departed shape is deliberate:
v6fu's rule is "only your own signed frame listing you as a member puts you back", and a roster-carrying seed
is such a frame, so the rejoiner's seed rejoins them itself and the second park shape retires for new builds.

**What it costs.** About 330 bytes more per seed send at the roster cap, sealed — a carrier or spool sees
only longer ciphertext, and the roster is readable by exactly the member the seed is addressed to, who is in
it. Group frames never ride LoRa (`shouldLongRangeFanout` excludes them), so a seed DM that outgrows the
board's three fragments loses nothing a LoRa-only pair could use. Wire-additive under `docs/WIRE_COMPAT.md`
rule 1: no ctl value, no capability bit, no `EncEnvelope.v`, no DB change; an older receiver ignores the
field and parks as before, an older sender omits it and the receiver parks as before. Two traps for the next
reader. The seed is already in custody when the pin runs (`onDeliver` custodies before it dispatches), so
`reconcileGroup`'s first-sight custody replay is told to skip it (`replayExcept`); without that the replay
re-enters the seed nested under its own pin, the nested pass adopts, acks and gossips, and the outer commit
silently finds its chain index consumed — functionally inverted, with no metric to show it, which is why
`InboundPipelineTest` pins the seam call rather than a counter. And a departed sender's re-served pre-leave
seed now runs `reconcileGroup` (within the pin, no rejoin) before it parks, exactly what that member's
pre-leave chat frame already does. Kept true by `GoldenVectorTest` (`groupKeyPayloadRoster`, the older
fixtures byte-identical), `WireSerializationTest.aPreRosterShapedDecoderIgnoresTheSeedsFoundingRoster`,
the eight `InboundPipelineTest` cases from `aSeedCarryingItsRosterPinsTheGroupAndAdoptsOnItsFirstPass`,
`MeshManagerTest.theSeedDmCarriesTheGroupsFoundingRosterWhenTheRowIsHeld`, and in `mesh/lab` the
un-ignored #47 scenario plus the two reshaped ones
(`GroupMembershipLabTest.aRejoinersSeedThatOutrunsTheRejoinFrameRejoinsThemItself`,
`GroupFirstMessageLabTest.firstGroupMessageLandsWhenTheMemberRestartsBetweenTheSeedAndTheFrame`).
