---
id: "2026-09.7x8k"
slug: a-send-waits-for-the-card-its-link-is-fetching
title: "A send waits for the card its link is fetching, and a LoRa thread takes one like a photo"
date: 2026-09-17
topics: [attachments, ui, network]
---

# ADR 2026-09.7x8k — A send waits for the card its link is fetching, and a LoRa thread takes one like a photo

Status: Accepted (2026-09-17; `ChatViewModel.attachmentForSend` and `cardWanted` — amends ADR 2026-09.n752
twice: "sending while a fetch is still running sends without the card", and "the thread does not ride
LoRa". No wire change, no DB change, no capability bit. Work item #54)

**What was observed.** A link shared into Knit from another app (`ACTION_SEND` → the share-target picker →
the chat) landed in the composer and went out as bare text, every time, while the same link typed into the
same thread got its card. Nothing about the share path was broken: on an API 37 emulator the card staged
fine when nobody touched Send, warm start and cold. What differs is the *timing of the next tap*. A typed
link is followed by more typing, or at least by a pause; a shared link arrives whole and Send is the very
next thing the user does, one to two seconds after the pick. ADR 2026-09.n752's loop needs more than that:
600 ms of debounce, then the page, then the picture, then the shrink, then the text classifier — which on a
cold start (and a share usually *is* the cold start, the intent launched the app) loads the ~16 MB toxicity
model first. Reproduced on the emulator with a tap 1.2 s after the pick: text alone, `attachmentMime`
null. So "sending while a fetch is still running sends without the card" — a rule written for a keystroke
race — was, for the share sheet, the rule that no shared link ever carries a preview.

That was the emulator. On the lab phones the same share produced *no fetch at all* — no `LinkPreview` line
in the log, the 232-byte body sent bare seven seconds after the pick — for two reasons that had nothing to
do with timing, found by reading each phone rather than the code. On the Pixel 3 the setting was simply off
(`link_previews_enabled` absent from the DataStore, so n752's default), which is not a bug. On the Pixel 9
the setting was on, the default route was validated LTE and Data Saver off, and the refusal was n752's own
LoRa gate: the phone has a live board, so Nearby is `LoraCarry.Room` and every DM `facts.dms` reaches is
`LoraCarry.Dm`, and `cardWanted` required `LoraCarry.None`. A phone with a board paired never fetched a card
in Nearby or a DM, typed or shared — a rule its author had forgotten by the time #54 was filed, which is the
tell that it was stricter than it needed to be: a **photo** is allowed in exactly those threads, rides LoRa
as a 170-byte reference under `LoraSizeHint.ATTACHMENT_RESERVE_BYTES`, and its bytes never cross the board
either.

**What changed.** A send now holds for its card. `attachmentForSend(body)` is what `send` hands to
`route`: whatever is staged, else — when the draft's first link is one `cardWanted` would fetch — the
card, waited for up to `SEND_CARD_HOLD_MS` (5 s). If the loop's fetch is already in flight the send joins
it (`linkPreviewLoading.first { !it }`) rather than starting a second; if the debounce has not fired yet
the send runs the same `stageCard` itself, and `cardWanted` now refuses while any fetch is in flight so
the loop cannot double it. Past the bound, or when the fetch yields nothing, the text goes alone exactly as
before. The user sees the send button's spinner (its 300 ms grace already covers the usual case) and the
composer's "Loading preview…" line; a card that lands during the hold shows staged for the instant before
the field clears. Every gate n752 set still stands: a dismissed link, an empty one, the setting, the route,
LoRa, the audience's capability bit — none of them holds a send, because none of them would have started a
fetch. The alternative a reader reaches for first — skip the debounce for a prefilled draft — narrows the
window by 600 ms and closes none of it: the fetch and the model load are the seconds that matter.

And a thread that rides LoRa now takes a card exactly as it takes a photo: `cardWanted` no longer reads
`loraCarry`. The composer's budget hint already counts a staged card (`loraBudgetFor(attached =
pendingAttachment != null)`), so a long link plus its reference shows the same "long message" line a photo
would, and a board-only reader of the message sees the bare link — the bubble draws nothing for a card it
does not hold, never a spinner, so the LoRa copy degrades to what n752's gate produced anyway. The
alternative weighed was a fit-the-budget rule (fetch only when the draft plus the reserve still fits the
board), declined by the maintainer for consistency with the photo rule: one attachment policy per thread,
not one per attachment kind.

**What it costs.** Up to 5 s on a send whose link is slow, spent with a spinner the user cannot cancel from
(the cross exists only on a staged card, not on the loading line); a send tapped in the first 600 ms after
the link appears now fetches where it used to fly, which is what the issue asked for. The bound is a
guess at what a tap tolerates, not a measurement — the fetcher's own budget is 30 s and would hold a
message hostage. What keeps this true: `ChatViewModelTest`'s four send-hold cases (a send inside the fetch
carries the card; a send before the debounce fetches it itself, once; a 60 s site sends the text at the
bound and stages nothing after; a removed card does not hold) and `aThreadThatRidesLoraTakesACardLikeAPhoto`.
The hold is device-verified on the API 37 emulator only; the LoRa lift is owed a run on the Pixel 9, the
one lab phone with a board. A LoRa phone's room message now carries 170 B less text before the "long
message" hint, when a card is staged — the same cost a photo already had.
