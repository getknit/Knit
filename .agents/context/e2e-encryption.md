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

**v2, group form — groups, forward-secret (the sender-key ratchet).** Shares scheme version 2 with
the DM ratchet (both landed in one never-released bump; the forms split on addressing — a group
frame carries `EncEnvelope.g`, a DM `EncEnvelope.r`). The default between current builds: outbound
group-form whenever EVERY other member's pinned profile advertises `CAP_RATCHET` + a prekey AND the
epoch's seed seals to every member — all-or-nothing per message,
any shortfall demotes that message to v1 (which every build reads), re-evaluated per send. Each member
mints a random per-group epoch seed driving a forward-only chain (no DH — trust/freshness/healing are
the pairwise v2 DM ratchet's, which carries the seeds as `MessageContent.ctl = CTL_GROUP_KEY` DMs);
the group frame is `EncEnvelope.g = GroupRatchetHeader {se, n}` with empty `keys` (~10 B vs v1's
~500 B of wraps). Availability inverts the DM form's frame-self-sufficiency: a group frame needs its
sender's seed DM
first — recovery is the persistent seed outbox (`group_key_sends`, acked via `CTL_GROUP_KEY_ACK`),
proactive re-sends (profile arrival / neighbor join / session reset), and the rate-limited
`CTL_GROUP_KEY_REQ` key-request loop, which together subsume the old "group key-gap retransmit" gap
for ratcheted groups. Leave-rekey is atomic with the roster shrink and **eventual** (bounded by the signed
`groupleave` frame's convergence). Engine/facade: `GroupRatchetEngine`/`GroupRatchetSessions` (one
shared ratchet mutex with the DM facade); state in the `group_*` tables; normative spec:
**`docs/GROUP_FORWARD_SECRECY.md`**. The roster it distributes to is integrity-pinned
(`InboundPipeline.vetRoster`: the founding set only ever comes from a roster whose id IS its hash;
membership shrinks only via signed leaves).

**v1 — the fallback (static keys: pre-ratchet peers, mixed-capability groups).** A per-message random content key AES-256-GCM-
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

Still deferred for E2E (see `memory/roadmap.md`): encrypting reactions/receipts (signed now, but
still flood as cleartext metadata), and encrypting the broadcast room. (Group forward secrecy shipped
as the v2 group form — above.)
