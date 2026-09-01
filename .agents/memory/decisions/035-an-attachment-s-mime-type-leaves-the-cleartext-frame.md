---
id: "035"
slug: an-attachment-s-mime-type-leaves-the-cleartext-frame
title: "An attachment's MIME type leaves the cleartext frame"
date: 2026-08-23
topics: [attachments, wire, privacy]
---

# ADR 035 — An attachment's MIME type leaves the cleartext frame

Status: Accepted (2026-08-23)

ADR 034 recorded the cost it accepted: `ChatContent.attachmentMime` rides in the clear on every sealed
DM/group frame, so `audio/aac` tells a blind carrier the message is a voice note and `image/webp` that it
is a photo. This retires that signal. `MeshManager` stops setting the field on a sealed frame — `sendChat`'s
originate, `sendProfileDm`'s `CTL_PROFILE` avatar hint, and `resealAndFlood`'s retransmit — and the
cleartext frame now names the ciphertext **hash and nothing else**.

**No crypto was built.** The mime was already sealed: `MessageContent.attachmentMime` has carried it inside
the AEAD since the field existed, and `InboundPipeline.plaintextContent` substitutes the decrypted value
over the cleartext shell before `deliverChat` writes the row. Every recipient-side consumer — the bubble
fork, the chat-list preview, the notification stand-in, `deriveObtainedVoiceMeta` — already read the sealed
copy. The cleartext one was a pure duplicate, and this change deletes it.

Worth not relitigating:

1. **Null, not a generic constant, and the reason is rule 2.** Writing a fixed token into `attachmentMime`
   would change the field's *meaning* from "the type of the referenced blob" to "a placeholder", which is
   exactly the `docs/WIRE_COMPAT.md` rule-2 repurpose that needs a new field. Leaving it null repurposes
   nothing — it is the precise mirror of the DB v19 precedent, and the rule it adds is stated there:
   *un-populating a field is additive on the same terms as populating it, provided every deployed reader
   already tolerates its absence.* That absence path is not new code: a `groupupdate` group photo has
   carried a null mime since M5, `ScopeSync` has read `ref.mime ?: FALLBACK_MIME` since then, and both are
   pinned by tests. `encodeDefaults = false` also makes null free — the key vanishes and the frame shrinks,
   where a constant would cost bytes on every attachment frame to say nothing.
2. **The `CTL_PROFILE` avatar hint had to move too, and not for tidiness.** `AVATAR_MIME` is a constant, so
   it never leaked anything about the avatar. But had it stayed the one sealed frame still carrying a
   cleartext mime, mime-*presence* would itself have become a fresh distinguisher, sorting sealed frames
   into "profile update" and "user message" for any carrier. Nulling it is what stops the fix from minting
   a new signal.
3. **ADR 034's "and a spool" was wrong, and the correction matters.** `ScopeFrames.seal` seals the whole
   `signed` blob — `RelayEnvelope`, `ChatContent` and all — into `ScopeCrypto.seal`, so a spool operator
   only ever saw ciphertext and never read `attachmentMime`. Its leak is the chunk-count/timing signal
   `docs/SPOOL_PROTOCOL.md` §10.1 already prices, unchanged by this. The audience that actually read the
   mime is **mesh relays, store-and-forward carriers, and anyone sniffing the radio** — plus a scope
   *member* running `ScopeAttachments`, who can decrypt the frame anyway. This is a radio-plane fix.
4. **The fetcher asks local state instead of the frame.** `ScopeAttachments` and `ScopeSync` are untouched
   and stay pure — `Ref.mime` simply becomes null for `chat` as it already was for `groupupdate`. The
   resolution lives in the implementation of the existing seam, `MeshManager.scopeBlobs().save`, which
   prefers `messages.attachmentMimeForHash(aHash)` and falls back to the hint (an older peer's cleartext
   value, else `ScopeSync.FALLBACK_MIME`). Row before hint, deliberately: our own decrypted row is
   authoritative and a peer's cleartext claim is not. No interface changed, so no test double moved.
5. **`blobs.mime` on a carrier is now deliberately uninformative on the spool path, and that is the
   feature.** A carrier holds no message row, so the fallback stands — exactly how it has always handled a
   group photo. Nothing carrier-side reads it (`orphanHashes` and `carrierOnlyBlobBytes` key on hashes).
6. **Stated rather than overclaimed: the transition is itself visible.** `attachmentHash != null &&
   attachmentMime == null` on a `chat` frame never occurred before, so a carrier can fingerprint a patched
   build — unavoidable in any staged rollout, and it decays as the base upgrades. The *class* signal is
   what closes; **size does not** (an ~8 s voice note is a distinctive byte range), and neither does
   "this frame carries an attachment at all", which is the DB v19 bargain that makes custody of attachments
   possible.

**The residual this does not close, recorded so it is not mistaken for done:** `LinkFraming.FileHeaderWire`
carries the mime on the radio file transfer, and `BlobExchange.onRequest` serves a blob to **any** neighbour
that asks — so a carrier that actually pulls the bytes still learns the type. Deliberately out of scope
here; see `memory/roadmap.md`. Whoever takes it: `mime` is a required non-null `String` under
`encodeDefaults = true`, and `decodeFileHeader` returning null sets `rxAborted = true`, so *omitting* it
hard-breaks blob transfer against deployed builds — only substituting a value is safe, and a capability bit
is the wrong gate (`Protocol.capabilities` is unauthenticated advert data, so gating a privacy control on
the carrier's own claim hands the adversary the off switch).

Tests: `MeshManagerTest` (the two assertions that used to pin the leak, inverted — the sealed copy is now
asserted as the only carrier of the type; plus the broadcast-room exception and the `scopeBlobs` resolution
rule), `InboundPipelineTest` (a sealed frame with no cleartext mime still types its row from inside the
seal), `ScopeAttachmentsTest` (a sealed `chat` ref converges on the `groupupdate` shape; an older peer's
hint is still read). **`GoldenVectorTest`, `ScopeVectorTest`, `SpoolRecordsTest`, `WireSerializationTest`
and `KnitDatabaseMigrationTest` are untouched and pass unmodified — the executable proof that no wire
format, no vector and no schema moved.** Wire precedent: `docs/WIRE_COMPAT.md`; spec: `docs/SPOOL_PROTOCOL.md`
§9.5/§10.1; context: `context/e2e-encryption.md`.
