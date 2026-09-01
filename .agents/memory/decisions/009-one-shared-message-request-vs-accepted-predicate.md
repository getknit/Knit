---
id: "009"
slug: one-shared-message-request-vs-accepted-predicate
title: "One shared \"message request vs accepted\" predicate (`Conversations.isAccepted`)"
date: 2026-07-08
topics: [data, privacy, message-requests]
---

# ADR 009 — One shared "message request vs accepted" predicate (`Conversations.isAccepted`)

Status: Accepted

The Message Requests rule — a conversation is *accepted* (not a stranger's request) iff it is Nearby ∨
in the accepted set ∨ the DM peer is verified ∨ the user has authored in it — is the single source of
truth for the local notify gate (`InboundPipeline`), the retention sweep (`MeshManager.sweepLocalStorage`),
and the Message Requests UI (`ui/requests/`, chat-list partition). It lives as a **pure, Android-free**
function in `data/message/Conversations.kt` taking the three signals as sets, so a per-conversation check
and a whole-list partition share one rule that can't drift. It is a **local presentation decision only** —
never folded into custody/relay, so it is *not* convergence-critical (unlike the custody quota in ADR 006).
