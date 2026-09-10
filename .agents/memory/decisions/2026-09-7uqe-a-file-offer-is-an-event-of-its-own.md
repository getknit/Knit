---
id: "2026-09.7uqe"
slug: a-file-offer-is-an-event-of-its-own
title: "A file offer is an event of its own, not a line in the chat"
date: 2026-09-10
topics: [transfer, ui, notifications]
---

# ADR 2026-09.7uqe — A file offer is an event of its own, not a line in the chat

Status: Accepted (2026-09-10; `ui/chat/TransferCard`, `TransferView.transferPreview`, `ChatViewModel`
`TransferConsent`, `ui/chat/ChatScreen` `DirectTransferConsentBody`, `SettingsStore.directTransferConsented`,
`ChatListViewModel.speaksForTheRow`, `Notifier.notifyTransferOffer`, `MessageNotifier`,
`res/drawable/ic_direct_transfer.xml`, `ui/icons/KnitIcons`)

A direct transfer (ADR 2026-09.wtmz) breaks the rules the rest of Knit taught its user. Everything else here
is small, sealed, carried by the mesh and kept in the encrypted blob store. This leaves the mesh entirely,
takes the Wi-Fi radio with it for the duration, and ends with a file sitting in shared storage that Knit
never looked inside. The surfaces have to say that, and an offer has to be findable after the notification
is swiped away.

Decisions worth not relitigating:

1. **One disclosure, once per device, on whichever side reaches it first.** `directTransferConsented` gates
   both the sender's file picker and the receiver's first Accept, because both are moments the phone's radio
   changes hands. The sheet's can/cannot halves are shared; one line differs by role — what the sender still
   controls, and where the receiver's copy lands. *Rejected:* two flags, one per role. The facts hold
   whichever end you are, and a person who has sent a file already knows what receiving one costs.
2. **The thread's refusals fire before the picker opens.** `sendFileDirectly()` checks DM-only, capability
   and nearby *first*. Somebody who hunts down a 2 GB video and is only then told this thread cannot take one
   has been made to work for nothing.
3. **The disclosure sheet scrolls.** Its copy is taller than a 320×470 screen, so without
   `verticalScroll` its buttons sit below the bottom of the window with no way to reach them. A disclosure
   whose Continue cannot be tapped is worse than no disclosure. Pinned by a Robolectric case that scrolls to
   the button before clicking it.
4. **A transfer row is the one `kind` the chat list previews.** Status notices are otherwise invisible to
   that list, for two stated reasons: they are not the thread's last *message*, and a notice's `senderId` is
   the event's subject rather than an author. Both reasons point the other way here — the sender really is
   whoever offered the file, and an offer or a gigabyte arriving is not a footnote about the thread, it *is*
   the thread. So it speaks for the row and carries its time, while still earning no delivery tick (nothing
   was sent anywhere) and no unread count (the card in the thread is what wants answering).
5. **The preview line is phrased from the reader's side, so it takes no "You: " prefix.** The sender's own
   row for a refused offer reads "They declined clip.mp4"; prefixed, it would name the wrong person twice
   over. Fifteen lines, one per phase × direction, with a test asserting `TransferPhase.entries.size * 2`
   equals the expected count so a new phase cannot ship without one.
6. **"Interrupted" needs the live state, so the chat list takes a `TransferManager` dependency.** A record
   left non-terminal by a process death is only knowable by folding the persisted row against the live map,
   exactly as the card does. Without it a killed transfer reads "Sending clip.mp4" in the chat list forever —
   and the chat list is precisely where nobody would notice it was lying. `drafts.all` and `transfers.states`
   are paired *before* the combine so it stays on the typed five-flow overload.
7. **An offer notifies on its own, and that is what lets it wear the mark.** A `MessagingStyle` notification
   stands for the whole conversation, so its icon cannot say anything about one message inside it, and
   Android strips `ImageSpan` from notification text — an emoji was the only per-message marker that shape
   allowed. Its own notification (the `notifyMention` precedent: one thread, two kinds) gets its own tag
   namespace, small icon, and a tap that opens the chat. Opening the thread cancels it, since the card with
   Accept and Decline is then in front of the user.
8. **The mark lives in `res/drawable/`, not in Kotlin.** A page with a signal breaking off its corner —
   deliberately not the attachment paperclip, which means the opposite thing. It is needed as a real
   `R.drawable` for the notification and as an `ImageVector` for Compose, so one resource is the source and
   `KnitIcons.DirectTransfer` reads it through `vectorResource`.

Cost and residuals (accepted): **`setSmallIcon` is invisible in the Android 12+ notification shade** — that
header circle draws the *launcher* icon, and the small icon only reaches the status bar. The one shade slot an
app controls is the large icon; badging the mark onto the sender's avatar was built, device-verified on a
Pixel 9, and then rejected on looks. The avatar stays plain and the mark stays a status-bar affordance. The
transfer preview is also the only vector-marked line in a chat list whose other previews (📷 📎 🔗 📍) use
emoji, which is a deliberate inconsistency: it buys consistency with the menu item instead. `Notifier` is now
twelve methods, over detekt's default interface cap, raised to 14 in `config/detekt/detekt.yml` beside the
tuned limits already there. Tests: `ChatViewModelTest` (the consent gate in both directions, and that a
refused thread never reaches it), `ChatScreenContentTest`, `ChatListTransferPreviewTest` (every phase ×
direction, plus interrupted), `InboundPipelineTest`. **Still owed:** a device trial of the consent sheet
itself; only the notification has been seen on hardware.
