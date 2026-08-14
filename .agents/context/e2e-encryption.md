# End-to-end encryption (implemented)

DMs and group chats are E2E-encrypted; the broadcast "Nearby" room stays plaintext by design (no fixed
recipient set). Two crypto schemes coexist, discriminated by `EncEnvelope.v`:

**v2 — DMs, forward-secret (the epoch-rekey ratchet).** The default between current builds: outbound
v2 whenever the peer's pinned profile advertises `Protocol.CAP_RATCHET` **and** carries a
`ProfileContent.prekey` (both ride one signed frame). X3DH-style bootstrap off the signed prekey (no
round trip — the first DM to an offline peer still works), then per-epoch X25519 rekeying with a
forward-only message-key chain; the AEAD key is *derived*, never wrapped (`EncEnvelope.keys` is empty,
`EncEnvelope.r` carries the ratchet header). Epochs advance on conversational turnaround, at 200
messages (= the per-sender custody quota), and at 24 h (= the custody TTL); retention of our own epoch
privs IS the PFS window. Session state lives in the `ratchet_*` Room tables (dies with a DB wipe — the
reset path recovers); signed-prekey privates live in `identity.key` beside the identity so in-flight
initiations survive a wipe. The pure engine + session service are `mesh/crypto/ratchet/`
(`RatchetEngine`/`RatchetSessions`, plain-JVM-testable); the full normative scheme — derivation labels,
advance rules, both-initiate races, replacement/reset, the honest security claim — is
**`docs/FORWARD_SECRECY_RATCHET.md`**. Two integration rules matter constantly: decrypt is two-phase
(lock-free peek for moderation, then re-open + commit atomically with the message row;
transaction-outer/mutex-inner), and the **pre-decrypt exists-gate** in `decryptAndDeliver` is what
makes deleting used message keys safe under custody's routine re-serves.

**v1 — groups and pre-ratchet peers (static keys).** A per-message random content key AES-256-GCM-
encrypts the `MessageContent` (body + mentions + attachment refs) into an `EncEnvelope` carried inside
the encrypted `ChatContent.enc` payload, and the content key is wrapped (Tink HPKE/X25519) to each
recipient. Inbound v1 stays accepted forever; outbound v1 remains the fallback (peer lacks the cap or
prekey — `dmSealedV1Fallback` counts it).

Identity keypairs live in `IdentityKeyStore` (AndroidKeyStore-wrapped, **outside** the DB, together
with the ratchet prekeys), advertised via `ProfileContent.pubKey`, pinned self-certifying into
`PeerEntity.pubKey` (immutable per nodeId), and confirmed out of band via the safety-number/QR screen
(`PeerEntity.verified`) — identity keys are unchanged by the ratchet, so safety numbers are stable.
Image attachments are encrypted to a per-attachment key and content-addressed by ciphertext hash, so
`BlobExchange`/`BlobStore` are unchanged. **Decrypt/verify failures must never throw out of the
inbound handler** — `onDeliver` runs before the router schedules the relay, so a throw would stop
forwarding; the v2 path's typed failures (`RATCHET_*` drop reasons) are delivery-local for the same
reason, and `canCarry` never inspects the scheme version.

**One signature authenticates every flooded frame** (encrypted *and* plaintext: broadcast `chat`,
`profile`, `groupupdate`, `groupleave`, `reaction`, `receipt`). `WireEnvelope.sig` is raw Ed25519 over
`WireEnvelope.signed` (the canonical `RelayEnvelope` CBOR, which includes the encrypted `ChatContent.enc`
for a DM/group message), and `MeshManager.verifyInbound` (the gate at the top of `onDeliver`) verifies it
**byte-exact over the received `signed` bytes** — no re-encode — and drops any that fail, closing the gap
where a relay could forge a frame (e.g. a profile with a different name) under another node's `senderId`.
Verification reuses the key path (`peers.find(senderId).pubKey` → `PublicKeyBundle.verifier()`, guarded
by `NodeId.fromPublicKeyBundle == senderId`); a `profile` uses the `pubKey` in its own `ProfileContent`
payload since first contact precedes any pin. `blobreq` stays unsigned. `EncEnvelope.v`/`MessageContent.v`
gate the crypto-scheme/content-schema versions (unknown ⇒ drop locally + count, but still relay — a
delivery gate, never a relay gate; see `docs/WIRE_COMPAT.md`).

Still deferred for E2E (see `memory/roadmap.md`): **group** forward secrecy (a group key state — the
group analogue of the DM ratchet), encrypting reactions/receipts (signed now, but still flood as
cleartext metadata), and encrypting the broadcast room.
