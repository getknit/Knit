---
id: "2026-09.6ww7"
slug: a-group-chat-says-lora-will-not-carry-it
title: "A group chat says LoRa will not carry it"
date: 2026-09-02
topics: [lora, ui]
---

# ADR 2026-09.6ww7 — A group chat says LoRa will not carry it

Status: Accepted (2026-09-02; `ui/chat/LoraReach.kt` `LoraReach.GroupUnsupported`/`loraGroupReachFor`,
`ChatViewModel.LoraAudience`)

`LoraFramePolicy` refuses group-form chat outright — it is in the "everything else" list beside `typing`
and `blobreq`, and has been since ADR 038. So a group member the board alone can hear simply does not get
group messages: they wait in custody for that member to come back inside phone-radio range, or for the
Internet plane to carry the group scope. Every other LoRa notice in a chat is about **congestion** (ADR 054's
spent window, ADR 2026-09.ursc's room); this one is about a **capability**, and no amount of airtime changes
it. Until now the chat said nothing at all, so a group post to somebody over the hill looked delivered.

Decisions worth not relitigating:

1. **The audience is the group's roster, never the directory.** `loraGroupReachFor(facts, loraOnlyMember,
   relayReach)`, where the caller computes `members.any { it != me && it in loraOnlyIds }`. A LoRa-only
   *stranger* says nothing about whether this group's messages land — that is the room's question
   (ADR 2026-09.ursc), and reusing the room's existential test here would fire on a passer-by. Self is
   excluded explicitly, matching `recipientTotal`'s rule: we are not somebody we can fail to deliver to.
2. **`RelayReach.Covered` silences it, unlike the room's notice.** A group scope **is** scope-eligible on
   the Internet plane (`SPOOL_PROTOCOL`; the client plane carries DM *and* group scopes), where the room
   permanently is not. So a covered group has a real carrier and the same exemption the DM rule takes.
3. **No airtime gate and no `facts.dms` gate.** Both would be category errors: the plane refuses the frame
   whatever the ledger holds and whatever the DM switch says. The rule reads `plane == Live` only because a
   board that is down is the header glyph's business, not this notice's.
4. **The audience projection became a sealed interface.** `ChatViewModel.LoraAudience` is now
   `Peer`/`Room`/`Group`, chosen once at construction from `Conversations.kindFor(conversationId)` — three
   mutually exclusive shapes, so a thread computes only the one it can use (a DM stays a single map lookup,
   the room a boolean, a group the id set). The nullable-fields data class it replaced would have grown a
   third field that two of the three kinds must ignore.
5. **The copy says what still works.** "Group chats don't travel over LoRa — distant members get these
   later", and the body ends "Nothing is lost — the messages wait for them". Custody really does hold them,
   so the honest statement is *deferred*, not *dropped*; saying "won't reach them" flat would be a lie the
   next reconnection disproves.

Cost and residuals (accepted): the notice stands for as long as a member is LoRa-only, which in a group with
one distant member is most of the time — accepted, because unlike the room's mixed audience the consequence
here is non-delivery, which is worth a standing line and is why this one is not dismissible either. Sealed
group *machinery* (seeds, key req/ack, escalated ticks) does cross opaquely, so the copy is careful to say
*messages*. It shares the room's dependence on having **heard** the member: one who has been silent past the
45-min linger is not in `peerTransports` and the group says nothing. Tests: `LoraReachTest` (the rule both
ways, plus that a spent window and a DMs-off switch are beside the point), `ChatViewModelTest`
(`aGroupSaysItsMessagesDoNotTravelOverLoraWhileAMemberIsBehindTheBoard`,
`aGroupIgnoresLoraOnlyIdsThatAreNotOnItsRoster` — the roster and self exclusions), `ChatLoraIndicatorTest`
(the strip, the explanation, and — via a single-node `hasText and hasText` matcher, because a group header
legitimately renders the thread title — that the body formats no name into itself).
