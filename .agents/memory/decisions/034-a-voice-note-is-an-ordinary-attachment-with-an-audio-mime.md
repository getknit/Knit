---
id: "034"
slug: a-voice-note-is-an-ordinary-attachment-with-an-audio-mime
title: "A voice note is an ordinary attachment with an audio MIME"
date: 2026-08-23
topics: [attachments, ui, audio]
---

# ADR 034 — A voice note is an ordinary attachment with an audio MIME

Status: Accepted (2026-08-23)

Voice notes ship with **no wire change, no crypto change, and no custody change**. Everything below the
ingest seam was already content-type-blind: the `blobs` table, `AttachmentCrypto`, `BlobExchange`,
`LinkFraming`'s file records, `ForwardEntity`'s attachment pin, and the whole spool attachment plane move
opaque SHA-256-addressed bytes with a MIME string alongside, and `ChatContent.attachmentMime` /
`MessageContent.attachmentMime` already exist and already ride. So the feature is two ends and no middle:
capture/ingest, and playback/render. `GoldenVectorTest`, `ScopeVectorTest` and `SpoolRecordsTest` are
untouched, which is the executable proof of that claim — see `docs/WIRE_COMPAT.md`'s precedent entry.

Worth not relitigating:

1. **AAC-LC in ADTS, not MPEG-4, and the reason is ADR 029.** Attachment bytes must never exist as
   plaintext on disk. `MediaRecorder`'s MPEG-4 muxer needs a *seekable* sink to rewind and write its `moov`
   atom, so it cannot target a pipe and would have forced a `cacheDir` file — exactly the staging ADR 029
   refused for the camera. `OutputFormat.AAC_ADTS` is a pure stream, so it writes into
   `ParcelFileDescriptor.createPipe()` and is drained into memory. The happy consequence is that ADTS frame
   headers are self-describing, so duration is exact arithmetic over the headers with no decoder — which is
   in turn what makes decision 2 possible.
2. **Duration and waveform are derived on both sides, never carried.** `VoiceAudio.describe` runs on the
   sender's recorded bytes and again on the recipient's pulled bytes (`InboundPipeline.onObtained`, a
   fourth order-independent sibling beside avatar adoption, group-photo adoption and image screening), and
   the two are stored in local-only `messages.voiceDurationMs`/`voicePeaks` (DB v4 → v5). Nullable wire
   fields would have been legal under `docs/WIRE_COMPAT.md` rule 1; they were not spent because the two
   ends then agree *by construction* rather than by one trusting a number the other sent, and because a
   cleartext duration on the frame is a metadata leak a blind carrier does not currently get. The cost,
   accepted: the bubble shows a length-less placeholder until the bytes arrive — identical to how a photo
   bubble already behaves, and fetched by the identical machinery.
3. **The mic is its own button, because the trailing one is spent.** That button already carries tap =
   send-or-attach and long-press = camera (ADR 029), so hold-to-talk would have collided head-on. The mic
   appears only when there is nothing to send, mirroring how the trailing button already swaps its own icon
   on `canSend`. It also carries a **tap-to-toggle** path, not as a lesser fallback but because a
   press-and-hold gesture is structurally unreachable under TalkBack (an accessibility service consumes the
   raw pointer stream), and tap-to-record is the better interaction for anyone who cannot hold a button
   steady. Permission is requested on the press that finds it missing and deliberately does **not**
   auto-start on the grant — the finger left the button seconds ago, and recording then captures the wrong
   moment.
4. **Voice notes are unscreened, and are therefore not offered in the Nearby room.** No on-device model
   classifies speech and the app has no cloud option, so `MODERATION_NONE` is the honest verdict and both
   screening hooks skip audio by MIME rather than relying on the NSFW decoder failing open. The room is the
   one surface that floods unencrypted to strangers in range — and the one where the image classifier
   hard-blocks rather than merely confirming — so unscreenable audio broadcast to everyone nearby is the
   combination refused. DMs and groups are sealed and consented; block-sender and the ADR 009 request gate
   are the remedies. Recorded as a gap in `docs/CONTENT_MODERATION.md` §7.
5. **A quoted voice note's label rides `ReplyRef.snippet`, not a new wire field.** `ReplyRef` carries only
   `hasAttachment: Boolean`, so a recipient cannot tell a quoted voice note from a quoted photo. Rather than
   spend an additive field and a golden vector on a cosmetic label, the sender writes the label into the
   snippet — already a free-text string whose documented job is to describe the quoted message.
   The cost, stated: a cross-locale quote shows the label in the *sender's* language. If that ever matters,
   the fix is a nullable `ReplyRef.attachmentMime`, legal under rule 1.

**The trap this cost a device round to find, recorded so it is not re-introduced: a composable that owns a
gesture must outlive the gesture.** The first cut replaced the *whole* composer row with the recording bar
the instant recording began — which removed `MicButton` from composition, and Compose cancels a removed
node's `pointerInput` coroutine. The finger's release therefore never arrived, and every recording ended
about one frame after it started (`STOP ... elapsed=25` against a two-second press). It presented as a
hardware fault — `MediaRecorder.stop()` throwing `RuntimeException`, which is exactly what it does when the
encoder produced nothing — so the logs pointed at the recorder and the bug was in the layout. The rule: the
recording bar replaces the **text field**, never the row; the mic button stays put for the whole press, and
only swaps for the stop button once the recording is *locked*, by which time the finger has already lifted
and the gesture has ended. It is also the better interaction, since the control stays under the thumb.

Two configuration lessons from the same round, both now device-driven rather than assumed. The AAC encoder
on a Pixel 9 does **not** accept 22.05 kHz, and an encoder handed a rate it does not support configures
happily and then emits nothing at all — surfacing much later as that same throwing `stop()`. So the rate is
now whatever the device's own `MediaCodecList` advertises, best-for-speech first (16 kHz wins), and the
audio source falls back `VOICE_RECOGNITION` → `MIC`. And a failed `stop()` no longer discards the capture:
ADTS frames are self-contained, so whatever reached the pipe is playable, and the bytes are judged on
whether they parse and are long enough rather than on whether `stop()` was happy. A press too short to have
encoded a frame is now *cancelled* rather than stopped, which is what that exception was really reporting.

Two consequences worth knowing. `BluetoothAudioMonitor` derives `contended` partly from
`AudioManager.isMusicActive`, so playing a voice note briefly looks like A2DP streaming and floors the BLE
scan (`ScanDemandPolicy`) for the length of the clip; that is tolerable only because `contended` is still
instrumentation-only — whoever builds the deferred BLE promotion gate must not inherit this as a surprise.
And `attachmentMime` is cleartext on the mesh, so an `audio/aac` value tells a blind carrier and a spool
that a message is a voice note, with size implying rough duration: a new *class* signal on top of the
size/timing cost `docs/SPOOL_PROTOCOL.md` §10 already prices, and the direct consequence of the DB v19
design that lets a carrier custody attachments at all.

The waveform normalises to the 95th percentile of its buckets rather than the loudest one: a transient — a
knock, a door, the button itself — is often several times louder than speech, and dividing by it flattens
every syllable to nothing. The exception is real silence, which must not be amplified into a confident
waveform of nothing, and the thing that separates the two cases is **absolute** loudness: in both, the
percentile sits far below the peak, so a fraction-of-the-max floor cannot tell them apart and merely
re-flattens the speech it was meant to rescue. Below an absolute PCM floor the scale reverts to the true
peak.

Tests: `VoiceAudioTest` (the ADTS walk — exact duration arithmetic, and every malformed input degrading to
null rather than throwing or hanging; plus the three normalisation rules above),
`MeshManagerTest` (the description lands on the row's *ciphertext* hash, and an image leaves the voice
columns null), `KnitDatabaseMigrationTest` (4 → 5). `VoiceAudio.peaks` needs a real
platform decoder and is covered on-device, not by a Robolectric shadow that would only assert a stub was
called. Moderation gap: `docs/CONTENT_MODERATION.md` §7; wire precedent: `docs/WIRE_COMPAT.md`.
