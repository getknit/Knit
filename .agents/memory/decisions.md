# Architecture decision records

Terse index of the load-bearing decisions and *why* they hold, so future work stays consistent. Each
points to the `context/` file with the full reasoning. Append new ADRs; change `Status:` to `Superseded`
rather than deleting. Dates are given where grounded; toolchain choices predate the 1.0 baseline.

## 001. DI is Koin, not Hilt

Status: Accepted

Hilt's Gradle plugin is broken on AGP 9.x in this window (dagger#5083 / #5099). Koin is pure-Kotlin
runtime DI with no Gradle plugin / annotation processor, so AGP can't break it. Detail:
`context/toolchain.md`.

## 002. Built-in Kotlin is overridden to 2.4.0 (not AGP's bundled 2.2.10)

Status: Accepted

The Kotlin-2.2 compiler can't read class metadata produced by Kotlin 2.4. KGP 2.4.0 goes on the root
buildscript classpath. Bumping AGP does not move Kotlin — the override is the lever. Detail:
`context/toolchain.md`.

## 003. Two radios behind one `MeshTransport` seam, no GMS/Nearby

Status: Accepted

Wi-Fi Aware (NAN) + Bluetooth LE run simultaneously behind `CompositeMeshTransport` (Bluetooth
preferred). Direct `android.*` radio APIs, no Google Nearby / GMS, so a device with only one radio still
meshes. Import boundary is enforced as a rule (`rules/mesh.md`); detail: `context/mesh-transport.md`.

## 004. Two-plane NAN design (cue plane + ephemeral NDP) with an accept-any responder

Status: Accepted · Re-audited 2026-07-04 (`docs/NAN_CONCURRENCY_REAUDIT.md`)

`maxNdiInterfaces == 1` is per-*role*: a single accept-any responder multiplexes many inbound NDPs, but
each initiator needs its own NDI. So coordination rides Wi-Fi Aware messages (no data path) and data rides
one ephemeral NDP brought up only for a digest-differing peer. Per-peer responders don't compose. Detail:
`context/mesh-transport.md`. A concurrent-serve redesign is proposed in
`docs/NAN_CONCURRENCY_REAUDIT.md` §5.

## 005. Layered opaque-CBOR wire + one frame signature

Status: Accepted

Three layers (`WireEnvelope` / `RelayEnvelope` / per-type content) of opaque `@ByteString` CBOR so a relay
rewrites only ttl/hops and passes `signed`+`sig` byte-for-byte — one Ed25519 signature authenticates every
type. Evolves additively. Detail: `context/wire-format.md`; break rules: `docs/WIRE_COMPAT.md`.

## 006. Convergent custody quota (frame-global `sentAt`, live-only, `ORIGIN_SELF` included)

Status: Accepted (DB v18, `forward_store.sentAt`)

The cue plane brings up a scarce NDP only when two peers' content digests differ, so the custody bound
must be identical on every node or the mesh churns forever. Evict newest-N by frame-global `(sentAt, id)`
on every origin, fold live ids only. Makes TTL constants convergence-critical. Detail:
`context/store-and-forward.md`.

## 007. Static analysis via standalone CLI; Kover is the one plugin exception

Status: Superseded by 011

detekt/ktlint run as standalone CLIs in isolated root-build configs so they can't perturb `:app`'s
Kotlin-2.4 classpath. Coverage must instrument bytecode, so Kover is the deliberate plugin exception
(low-risk, no codegen; keep ≥ 0.9.8). Detail: `context/toolchain.md`.

## 008. DB v1 is the frozen launch baseline — migrations mandatory from v1

Status: Accepted

No destructive fallback: every `@Database` bump adds a tested `Migration` + a migration-test case; a
missing migration throws at open time (caught in CI). Pre-1.0 destructive v2…v22 history is collapsed.
Detail: `context/testing.md`; break record: `docs/WIRE_COMPAT.md`.

## 009. One shared "message request vs accepted" predicate (`Conversations.isAccepted`)

Status: Accepted

The Message Requests rule — a conversation is *accepted* (not a stranger's request) iff it is Nearby ∨
in the accepted set ∨ the DM peer is verified ∨ the user has authored in it — is the single source of
truth for the local notify gate (`InboundPipeline`), the retention sweep (`MeshManager.sweepLocalStorage`),
and the Message Requests UI (`ui/requests/`, chat-list partition). It lives as a **pure, Android-free**
function in `data/message/Conversations.kt` taking the three signals as sets, so a per-conversation check
and a whole-list partition share one rule that can't drift. It is a **local presentation decision only** —
never folded into custody/relay, so it is *not* convergence-critical (unlike the custody quota in ADR 006).

## 010. Blocking is local presentation only — a blocked sender's broadcast/group message is still acked

Status: Accepted

Blocking suppresses *surfacing* (persist / notify / group-roster reconcile) but must not change what the
mesh observes about delivery. `InboundPipeline.handleChat` therefore still sends the best-effort
broadcast/group delivery tick for a blocked sender (`ackBlockedRoomChat` → `AckSync.owe`); it only skips
the local surfacing. Two reasons: (1) blocking must stay **invisible** to the blocked party; (2) that
broadcast/group receipt is a *fragile* unicast `relay = false` tick (unlike a DM's flooded, custodied
one), so when the blocker is the sender's only reachable acker, dropping it strands their Nearby/group
✓✓ forever — the observed 4-phone bug. A **DM is deliberately still not acked**: its receipt floods and
is custodied (real delay-tolerance, no single-hop trap) and acking it would also vaccine-purge it from
mesh-wide custody. This is a local-delivery-path decision (like ADR 009), never folded into custody/relay,
so it is not convergence-critical. Regression tests: `InboundPipelineTest` (broadcast/group acked, DM not).

## 011. Static analysis + Room schema run as Gradle plugins (supersedes 007)

Status: Accepted (2026-07-08, branch `build/gradle-plugins`)

Reverses ADR 007's "standalone CLI" doctrine. detekt (`dev.detekt` 2.0.x — the first line supporting
Gradle 9; 1.23.x capped at Gradle 8.12.1), ktlint (`org.jlleitschuh.gradle.ktlint` 14.x), and Room schema
export (`androidx.room`) now run as ordinary Gradle plugins. Safe because each analyzer runs in its own
isolated task classpath and adds nothing to `:app`'s compile/runtime graph (verified: `assembleDebug` +
`lint` unaffected), so the Kotlin-2.4-metadata hazard that motivated the CLIs (and Koin-not-Hilt, ADR 001)
doesn't apply — none does compile-time codegen on `:app`'s sources. Kover was already a plugin (its old
"one exception" framing is retired). ktlint's check task is now `ktlintCheck` (and `ktlintFormat`
autocorrects); the CI `verify:detekt` job runs `./gradlew detekt` (a `verify:ktlint` job was added).
Detail: `context/toolchain.md`.

## 012. R8 obfuscation (name mangling) enabled — the wire stays safe by construction

Status: Accepted (2026-07-09)

Release/staging now shrink + optimize + **obfuscate** (removed the lone `-dontobfuscate`; R8 full mode).
Safe because the wire is kotlinx.serialization compiler-plugin CBOR/JSON: map keys are the literal property
spellings baked into the generated `$$serializer` descriptors at compile time (no `@SerialName` / no
polymorphism / no reflective lookup), which R8 does not rewrite — so renaming is byte-safe as long as the
`$$serializer` infra is kept. Belt-and-suspenders: the frozen wire/identity DTOs (`mesh.protocol.**`,
`MessageContent`, `PublicKeyBundle$Proto`, `FileHeaderWire`/`DigestWire`, `IdentityKeyStore$Stored`) are
pinned unrenamed in `keepRules/knit-r8.keep`, and `-keepattributes SourceFile,LineNumberTable,…` keeps stack
traces symbolicatable. The one name-coupled path — `FileKind` serialized by *constant name* over the JSON
file-header sidecar — is fixed in **code** (`FileKind.wire` / `FileKind.fromWire`, frozen "AVATAR"/
"ATTACHMENT" tokens), not a keep rule, so a regression can't be masked. `mapping.txt` is now the
deobfuscation map — retain it per release. **Runtime gotcha found + fixed on enablement:** the first
obfuscated on-device launch crashed with `IncompatibleClassChangeError` because kxml2 (transitive via
ARSCLib) bundles `org.xmlpull.v1.*` into the apk, so R8 full mode renamed the *platform* `XmlPullParser`
interface and the framework's `XmlBlock$Parser` no longer satisfied it during resource-XML (vector/font)
inflation — fixed by pinning `-keep class org.xmlpull.v1.**` (see the keep-file section). Verified: the
obfuscated staging build launches and renders on an API-37 emulator. The broad library `{ *; }` keeps
(Tink/SQLCipher/TFLite/ARSCLib) are intentionally left un-tightened (a separate follow-up). Detail: the `keepRules/knit-r8.keep` header + the
release buildType comment; wire-break rules: `docs/WIRE_COMPAT.md`.

## 013. Accessibility checks run via Compose's ATF integration, not Espresso

Status: Accepted (2026-07-09, branch `build/accessability-test-framework`)

The Play Console pre-launch report runs Google's Accessibility Test Framework (ATF). We run the *same*
framework locally so a11y regressions (missing labels, sub-48dp targets, low contrast, bad traversal) fail
before upload. Knit is Compose-only and uses **no** Espresso view actions, so ATF's classic
`AccessibilityChecks.enable()` (an Espresso `ViewAction` hook) has nothing to fire on — the right seam is
Compose's own `androidx.compose.ui:ui-test-junit4-accessibility` (`compose.enableAccessibilityChecks(...)` +
`onRoot().tryPerformAccessibilityChecks()`), which pulls ATF transitively. The suite
(`app/src/androidTest/…/a11y/`, `AccessibilityInstrumentedTest`) reuses the seeded `SeededUiTest` harness to
deep-link and audit each screen. **Gated to API 34+**: the Compose ATF API is `@RequiresApi(34)`, so tests
carry `@SdkSuppress(minSdkVersion = 34)` (skip, don't fail, on older devices; also the lint `NewApi` guard —
`@RequiresApi` in a test is rejected by lint's `UseSdkSuppress`) and run on a new `pixel8api34` managed
emulator / FTL API-34+ device. **Gate policy**: errors fail the test, warnings/info are logged (validator
`setThrowExceptionFor(ERROR)` + `addCheckListener`). Detail: `context/testing.md`.

## 014. F-Droid ships *our* signed APK (reproducible `Binaries:`), not an F-Droid-signed rebuild

Status: Accepted (2026-07-21)

F-Droid offers two publishing models: it builds from source and signs with **its own** key, or — with
`Binaries:` + `AllowedAPKSigningKeys` — it rebuilds from source, byte-compares against the APK we publish,
and on a match distributes **ours**. We take the second, which Meshtastic (`metadata/com.geeksville.mesh.yml`)
also uses.

The deciding argument is Knit-specific, not ideological. `ui/invite/ShareApk.kt` hands a nearby phone this
app's own APK over Quick Share/Bluetooth — offline app distribution is a *product feature*. Under
F-Droid-signed builds the F-Droid APK would carry a different certificate from the one we hand out, so a
peer who received Knit over the mesh could never take an in-place update from F-Droid. One
distribution-key-signed APK serving GitHub Releases, F-Droid, sideload, and mesh-share keeps that coherent.
It is also a **one-way door**: publishing an F-Droid-signed APK first and switching later would break every
existing install. Play is unavoidably separate — Play App Signing holds a key we don't have.

Consequences, all load-bearing (detail: `context/distribution.md`):

- **The release APK must not depend on the build machine.** Native-symbol extraction (`ndkVersion` +
  `debugSymbolLevel`) is gated behind `-Pknit.nativeSymbols=true` — the Play AAB path only — because AGP's
  strip step degrades *silently* without an NDK. `packaging { jniLibs { keepDebugSymbols } }` opts out of
  stripping explicitly instead. Measured cost: **+8 bytes**; the shipped `.so` were already stripped
  upstream, so the strip was always a no-op. Verified: `clean` + `assembleRelease` reproduces an identical
  sha256 across full rebuilds.
- **No VCS stamping.** `vcsInfo { include = false }` on release. AGP otherwise embeds the builder's HEAD
  revision in `META-INF/version-control-info.textproto`. Verified empirically: rebuilding this commit inside
  F-Droid's actual buildserver container reproduced the APK byte-for-byte *except* this single entry (1 of
  185, same total size) — it was the whole delta.
- **No JDK auto-provisioning.** The `foojay-resolver-convention` plugin is gone from `settings.gradle.kts`
  and the `toolchainUrl.*` lines are stripped from `gradle/gradle-daemon-jvm.properties`; both fetch an
  unpinned compiler over the network. Gradle now fails loudly and the recipe installs JDK 21 via `sudo:`.
- **Git LFS is banned.** F-Droid's buildserver has no LFS support (fdroidserver#1190), and the moderators
  degrade to allow-all on an unreadable model — LFS would ship a silently unmoderated app. `*.tflite` are
  plain blobs, and `checkModerationModels` hard-fails on a pointer stub.
- **A Gradle property, never a product flavor.** Adding a flavor would move Room's exported schema off the
  flat `app/schemas/<db>/<version>.json` path that `KnitDatabaseMigrationTest` reads (`context/toolchain.md`).
- **The tag must equal `v<versionName>`** — fdroiddata expands `%v` to versionName in both `Binaries:` and
  `AutoUpdateMode`. Hence 2.2.0, not the 2.1 / `v2.1.0` mismatch shipped at 2.1.0.

## 015. QR scanning is CameraX + zxing core, not zxing-android-embedded

Status: Accepted (2026-07-31)

`com.journeyapps:zxing-android-embedded:4.3.0` hard-crashed the "Scan their code" flow for an F-Droid
reviewer ("Knit has stopped") on hardware the maintainer's Pixel 7/8/9 XL could not reproduce. Cause,
found by tracing the shipped 2.2.1 APK: the library runs its decode loop on a bare `HandlerThread` with
**no `try`/`catch` anywhere in the chain** —
`DecoderThread.handleMessage → decode → createSource → RawImageData.cropAndScale` — and crops the preview
buffer using the frame geometry the camera *reported* rather than what it *delivered*. Where those
disagree (ordinary camera-HAL variance) `System.arraycopy` throws `ArrayIndexOutOfBoundsException` off the
main thread and the process dies. Its one guard compares the Y-plane size against a 1.5x NV21 buffer, so a
mismatch slips through. R8, resource shrinking, the manifest merge and the permission declarations were all
cleared first — this was never a build-config problem.

It could not be patched in place: `BarcodeView.startDecoderThread()` is `private` and constructs
`DecoderThread` directly, so the only public seam (`DecoderFactory`) sits *downstream* of the throw. The
library was last released Feb 2021 and still drives the deprecated Camera1 API, which is where the device
variance comes from in the first place.

So we own the analyze loop: **CameraX** (`camera-core`/`camera2`/`lifecycle`/`view`) drives the camera and
**zxing core** — already a dependency, it renders the identity QR in `ui/image/QrCode.kt` — decodes.

- **`ui/scan/QrDecoder.kt` is deliberately Android-free**, so the arithmetic that broke the old library is
  a plain-JVM unit-test target (`QrDecoderTest` pins padded `rowStride`, `pixelStride > 1`, truncated
  buffers and degenerate geometry). Robolectric was avoided on purpose — it intermittently crashes Gradle
  9.5's test-result serialization in this suite.
- **`QrDecoder.decode` never throws.** Any frame — mis-strided, truncated, absurdly sized, or simply
  without a code — yields `null`. A camera frame must not be able to take the app down. Rotation handling
  is gone too: zxing finds QR finder patterns in any orientation, which deletes the rotate-and-crop stage
  that crashed.
- **The scanner is a composable, not an Activity or `Dialog`** (`ui/scan/QrScannerContent.kt`), rendered in
  place of the calling screen's content. Screens here take lambdas and `KnitApp` owns navigation; a camera
  `SurfaceView` in a `Dialog` window has z-ordering quirks on exactly the hardware this exists to support.
- **CameraX 1.6.1 is pinned against four constraints** — `minCompileSdk=36`, no upgrade pressure on the
  pinned lifecycle/core, 16 KB-page-aligned `.so`, and its own consumer R8 rules. Re-check all four before
  bumping; they are spelled out at the `cameraX` pin in `gradle/libs.versions.toml`.
- **Never add `camera-mlkit-vision`** — it pulls GMS, which this app does not ship.

The four-ABI `.so` CameraX adds ride the existing `keepDebugSymbols` no-strip opt-out, so ADR 014's
reproducibility contract is unaffected (verified: unstripped, timestamps normalized). Detail:
`context/distribution.md`.

## 016. DM forward secrecy is an epoch-rekey ratchet (not Double Ratchet, not libsignal)

Status: Accepted (2026-08-14; DB v2, crypto scheme `EncEnvelope.v = 2`)

Three locked choices: an **epoch-rekey ratchet** (PFS at epoch granularity — a far smaller state
machine than Signal's Double Ratchet, bought with a bounded compromise window instead of per-message
FS); **X3DH-style bootstrap off a signed prekey published in `ProfileContent`** (offline-first first
DM preserved; nodeId untouched — the prekey deliberately does NOT join `PublicKeyBundle`, whose hash
IS the nodeId); **session state in Room** (ratchet advance + message row commit in one transaction).
libsignal was rejected up front: its prebuilt Rust `.so`s break ADR 014's reproducible-build contract
and its server-shaped prekey model doesn't fit a mesh. Tink's public subtle API (X25519/HKDF) plus the
existing AES-GCM helper is the whole primitive set — no new dependency.

Two deviations from the obvious design, both forced by custody semantics: **no cumulative root chain**
(custody quota eviction leaves permanent mid-chain holes; an in-order root ratchet would wedge a
session forever — epochs derive independently off a static session root, and healing is round-trip-
granular via fresh DH), and **session reset rides as an ordinary v2 chat frame with a `MessageContent.ctl`
marker** (a new frame type would not be custodied by v1 relays — `isCustodial` is a fixed list — so
resets would lose delay tolerance exactly when they matter). The **pre-decrypt exists-gate** in
`decryptAndDeliver` is load-bearing: custody re-serves the same ciphertext routinely, and deleting used
message keys (the whole point) is only safe because a persisted frame never reaches decrypt again.
Own send-epoch numbering is monotone across root replacements so `(peer, se)` stays unambiguous; only
a device wipe restarts it, and a wipe also discards the state that would collide. Scheme spec + threat
model: `docs/FORWARD_SECRECY_RATCHET.md`; wire precedent: `docs/WIRE_COMPAT.md` (the additive
crypto-scheme bump); context: `context/e2e-encryption.md`.

## 017. Group forward secrecy is a sender-key ratchet over the pairwise DM sessions (not pairwise fan-out, not MLS-lite)

Status: Accepted (2026-08-14; folded into the unreleased v2 bump — DB v2, `EncEnvelope.v = 2` group
form (`g` header, split on addressing), `CAP_RATCHET` covering both forms — released version numbers
are append-only, unreleased ones are still editable)

Each member mints a random per-group epoch seed driving a forward-only chain
(`GroupRatchetCrypto.deriveEpoch` binds groupId + senderId + epoch); the seed travels pairwise as a
`MessageContent.ctl = CTL_GROUP_KEY` DM sealed under the v2 ratchet — never v1, which would void the
epoch against one harvested static-key DM. No DH, no sessions, no cross-member coordination: that is
the property the mesh demands (no ordering, permanent custody holes), and it is why the alternatives
lost — MLS-lite's shared epoch needs in-order commits (the ADR 016 root-chain wedge, times eight
parties), and pairwise fan-out either breaks id-keyed dedup/receipts (N frame ids) or entangles every
group message with N DM session lifecycles while still dying on session replacement. A ratcheted group frame
carries only `GroupRatchetHeader {se, n}` (~10 B vs v1's ~500 B of wraps at the 8-member cap).

The structural trade, stated as loudly as 016's "no cumulative root chain": **the DM form's key
material rides on every frame; sender-key inverts that** — a group frame is unreadable until a
separate DM with its own
custody fate delivers the seed. Availability is bought back with the persistent seed outbox
(`group_key_sends` + `CTL_GROUP_KEY_ACK`), proactive re-sends (profile arrival, neighbor join,
session reset — the only wipe-side seed plane, since ctl frames are never persisted), and the
age-gated, floored `CTL_GROUP_KEY_REQ` loop (which never advances an epoch — advance-on-request is a
rekey-fan-out amplifier). Custody accelerates seeds; the outbox is the source of truth. Wipe recovery
is mint-stamped (recv rows keyed by `(epoch, mintedAt)`, old era drains 48 h — the prevRoot pattern,
no era on the wire). Leave-rekey is atomic with the roster shrink (`GroupRepository.recordDeparture`
deletes the send chains in-transaction) and **eventual**, bounded by the signed `groupleave` frame's
convergence — never instantaneous revocation. Eligibility is all-or-nothing per message (any
non-capable member demotes that message, not the group, to v1); blocked members still receive seeds
(ADR 010 — withholding would reveal the block). Prerequisite shipped first: the roster-integrity pin
(`vetRoster` — the founding set only ever comes from a roster whose id IS its hash; membership
shrinks only via signed leaves), without which an insider could smuggle a seed recipient. One shared
ratchet `Mutex` serves both facades (seed adoption runs inside a DM commit; two locks would nest).
Scheme spec + threat model: `docs/GROUP_FORWARD_SECRECY.md`; wire precedent: `docs/WIRE_COMPAT.md`
(the second additive crypto-scheme bump); context: `context/e2e-encryption.md`.

## 018. Receipts and reactions are sealed as v2 ctl frames; the DM vaccine-purge is retired for the sealed era

Status: Accepted (2026-08-15; same unreleased v2 train as 016/017 — no new `EncEnvelope.v`, no new
capability bit (`CAP_RATCHET` covers it, the 017 precedent), no DB change)

The last cleartext flooded metadata goes dark: a reaction (which leaked reactor + emoji + target
mesh-wide, to non-members included) and a DM delivery receipt (which leaked the delivery event and
the recipient's activity timing) now ride as `MessageContent.ctl` control frames inside ordinary
v2-sealed CHAT frames — `CTL_RECEIPT = 5` (`ack` = the acked frame id) and `CTL_REACTION = 6`
(`rp = ReactionPayload{messageId, emoji?}`, null emoji = retraction; DM and group forms). The ADR
016 mechanism is the ADR 016 rationale: a new frame type would lose custody on every deployed build
(`isCustodial` is a fixed list), while an unknown ctl is a chain-advancing silent no-op, and on the
wire a sealed receipt/reaction is indistinguishable from chat. A ctl payload must NEVER take the v1
wrap — a pre-ratchet build would decrypt it, strip the unknown field (`ignoreUnknownKeys`), and
persist an empty message bubble; the fallback for an incapable/unsealable target is the legacy
cleartext frame (inbound cleartext stays accepted forever). Broadcast-room receipts and reactions
stay cleartext by design (the room is plaintext; ADR 010's blocked-ack invisibility untouched).

The structural trade, stated as loudly as 016/017's: **a carrier cannot parse what it cannot read,
so the recipient-authenticated carrier-executed vaccine-purge (`ForwardSync.onAck`) does not exist
for sealed receipts — nobody purges, and a delivered DM ages out of custody on the frame-global
24 h TTL uniformly, exactly like group/broadcast custody always has.** Convergence holds because the
rule keys on the receipt's FORM, a property of the frame bytes identical at every node: a cleartext
receipt purges everywhere it always did (old builds included), a sealed one purges nowhere (old
builds custody it as opaque v2 chat; ratchet-era lab builds no-op the unknown ctl). Two composition
rules keep ADR 006 honest: the recipient now custodies its OWN inbound DMs (dropping the carry
gate's `isForMe` exclusion — otherwise every carrier's digest holds a frame the recipient's never
folds, and the mesh re-serves a delivered DM at the recipient forever), and a cleartext ack
self-vaccinates (`onAck` locally after originating — the recipient's own fresh custody row must
follow the same rule every carrier applies, or its digest diverges the other way). Storage cost
accepted: a delivered DM + its sealed receipt ride custody to TTL (two frames where the purge left
zero), bounded by the existing quotas (1000 global / 200 per sender).

The broadcast/group tick keeps its shape (unicast `relay = false` via AckSync, never
flooded/custodied) but seals to a capable author — **once, at owe() time, cached and re-sent
verbatim** (`AckSync.sealTick`): sealing consumes a DM chain key, and re-sealing per retry
(15-min heartbeat × 24 h TTL × 500-entry cap) would burn epochs and starve real DMs out of the
receiver's skipped-key budget. A sealed tick outgrows the ~255 B coordination plane, so it lands
only over a live link — a latency regression, not a reachability loss (fastSend needed radio
proximity anyway), and deliberately NOT downgraded to cleartext when linkless (the form would
become an on-path observable). Blocked-sender posture: ticks to blocked authors still seal/send
(ADR 010, the seed precedent); a blocked member's inbound sealed ctl dies at the chat blocked gate
— diverging from their cleartext receipt (accepted forever, no blocked gate), a version-dependent
tell-free asymmetry; and a blocked member's sealed group reaction still draws a tick from
`ackBlockedRoomChat` (pre-decrypt, we hold no chain for them) — the pre-existing residual class
their undecryptable group chats already exhibit. Scheme doc: `docs/ENCRYPTED_RECEIPTS_REACTIONS.md`;
wire precedent: `docs/WIRE_COMPAT.md` (the third additive `MessageContent` change); context:
`context/e2e-encryption.md`, `context/store-and-forward.md`.

## 019. The internet plane is a scoped-custody spool protocol — M1 ships the public spec plus pure-crypto anchors, nothing else

Status: Accepted (2026-08-15; docs/SPOOL_PROTOCOL.md, `mesh/crypto/scope/`, `mesh/spool/`)

Names committed: **spool** (the store-and-forward relay daemon — "relay" is taken by
`RelayEnvelope`/`relayed()`/the mesh `relay` flag), **scope** (one conversation's internet
presence), **`ScopeSync`** (the future client plane — a custody-plane sibling of `ForwardSync`
under `MeshManager`, deliberately NOT a third `MeshTransport`: the seam is radio-shaped and a scope
has no neighbors), **`knit-spool`** (the reference daemon repo, **AGPL-3.0**; the app stays
GPL-3.0-or-later, no shared code). The spec is the product: `docs/SPOOL_PROTOCOL.md` is normative
and public from day one, `ScopeCrypto`/`SpoolPow`/`SpoolRecords` are its reference implementation
and the vector tests its executable anchors — the daemon (M2) and client (M3) implement the spec,
not each other.

M1 deliberately stops at pure functions — the FS docs' §8 "API-only, no consumer" posture one
layer up. **No mesh-wire fields land**: the scope-config ctl (`CTL_SCOPE_CONFIG = 7`,
`MessageContent.sc`) and the group root (`GroupKeyPayload.gr = {root, version, minter}`) are named
normatively in the spec but ship additively with their consumers (client plane / group scopes),
under WIRE_COMPAT's released-numbers-append-only, unreleased-still-editable rule. Two design-phase
intents were amended with rationale recorded in the spec: the **outer seal is scope-static**, not
epoch-rotating (per-epoch keys deadlock — the DM epoch identifiers needed to select the key live
inside the sealed blob and a fresh epoch's DH pub can't be enumerated; group epoch seals would be
unopenable exactly by the seed-lagging member whose custody/re-flood/key-request signal the frame
must keep feeding — `sealv = 2` reserves the epoch-keyed variant, and the `exportEpochSeal`
surfaces stay API-only for it), and the **scope config rides as a ctl inside sealed v2 chat**, not
as a new frame type (the ADR 016/018 custody argument: `isCustodial` is a fixed list on deployed
builds, and the config is exactly the frame that must survive store-and-forward). The seal is
**deterministic** (SIV-style nonce keyed off a scope secret) so any member seals a frame to the
identical blobId — spool dedup and cross-uploader digest convergence by construction, and the
keyed nonce denies spools a known-plaintext confirmation oracle. The group **shared root** deferred
by ADR 017/GROUP_FORWARD_SECRECY §8 is confirmed along the reserved mechanism: creator-minted,
deterministic re-mint on departure (creator if remaining, else smallest remaining nodeId),
highest-`(version, minter)` wins, gossiped on the existing `CTL_GROUP_KEY` channel.

**Amended 2026-08-16 (M2 shipped):** the `knit-spool` reference daemon + 22-check conformance
suite exist; implementing them surfaced eight spec ambiguities, resolved the same day as semantic
clarifications in SPOOL_PROTOCOL.md — §6.2 tombstone count bound (`max(2 × maxFrames, 1024)`,
§12 row) and forgotten-scope semantics (LIST/PULL answer empty; PUSH recreates through the §6.4
creation gates — the reachable use of `push.pow`), §6.4 recommended shed shape (whole scope,
tombstones included, plus an empty-digest re-anchor), §7.1 post-negotiation hello = `err
malformed` (4000 is pre-hello only), §7.2 unsolicited-digest SHOULD / pull-over-`maxPull`
truncation / duplicate-push acks without re-fan-out / `version` code reserved-never-emitted. No
wire field, vector, or derivation changed — the §13 anchors are untouched.

**Amended 2026-08-16 (M3 MVP — the client plane runs):** `ScopeSync` exists and syncs **DM scopes
only**, off by default, over OkHttp (`mesh/spool/`). Four shape decisions worth not relitigating:

1. **The MVP's spool list is a device setting, not the signed scope config.** `CTL_SCOPE_CONFIG`
   (ctl 7, `MessageContent.sc`) is still unshipped — carrying it is the one *wire* change this plane
   needs, and it wants its own WIRE_COMPAT precedent entry plus golden vectors rather than riding
   in with the first working socket. Until then bounds are the spec's §12 defaults held as
   constants in `ScopeRegistry`, and every scope syncs against every configured spool.
2. **The local blob-id set is derived, never stored.** Because the seal is deterministic, `blobId`
   is a pure function of (scope, frame), so the held-set is re-sealed on demand from
   `ForwardStore.liveFrames` behind an LRU. That is why this milestone needs no `forward_store`
   column and **no DB migration** — worth preserving, since a persisted blobId would have to be
   invalidated on every scope rotation.
3. **Session secrets stay behind `RatchetSessions`.** The plane consumes `exportedRoots()` —
   `pairwiseRoot` exports only, taken under the ratchet mutex, unconfirmed sessions skipped.
4. **Cleartext `ws://` is debug-only**, enforced twice (the debug manifest's `usesCleartextTraffic`
   and the dialer's own scheme check against `BuildConfig.DEBUG`), because `knit-spool` terminates
   no TLS of its own and the lab daemon is plain `ws://` on the LAN.

Deferred with reasons, not just deferred: the validated-Internet `ConnectivityManager` seam (the
MVP reconnects on backoff, which keeps `rules/mesh.md`'s NAN-only `ConnectivityManager` restriction
intact and avoids adding `ACCESS_NETWORK_STATE`); the spool-list editor, which is why the Settings
switch is `BuildConfig.DEBUG`-gated and spools are configured over `…debug.SPOOL`; Tor; group scopes.

**Amended 2026-08-16 (M4 — group scopes ship, and the v1 mint opens to any member):** the plane now
carries groups. Machinery: the shared root persists in a new `group_roots` table (**DB v2 → v3**,
`KnitMigrations.MIGRATION_2_3` — this reverses M3's "no DB migration" note, which held only because
the *blob-id set* is derived, and still does); `GroupRootPolicy`/`GroupRootStore` hold the pure rules
and the seam; `ScopeRegistry` gained a group seam and `Scope` a `groupId`/`roster`; and the plane's
**first mesh-wire field** lands additively as `GroupKeyPayload.gr` (its own `GroupRootPayload` type,
rule 1's `@ByteString` exception), riding the existing `CTL_GROUP_KEY` ctl DM rather than a new frame
type or ctl value — the ADR 016/018 custody argument a third time.

Four decisions worth not relitigating:

1. **Any member may mint version 1**, amending the spec's creator-only rule. The creator-only gap
   ("a group whose creator never opts in gets no scope") was booked as accepted and is now closed by
   damping rather than restricting: the **preferred minter** (creator if still a member, else the
   smallest remaining node id — the function the departure re-mint already used) mints immediately,
   anyone else after a 6 h grace measured from a **persisted** eligibility stamp. Competing v1
   lineages are not an error; `(version, minter)` collapses them and the loser's blobs age out.
2. **The same rule now covers the departure re-mint**, which fixes a latent stall the draft had: a
   deterministic re-minter that is offline or plane-off froze rotation for everyone. `recordDeparture`
   records the obligation (`remintDueAt`) inside the leave-rekey transaction; the heal pass mints. The
   split is what makes rotation crash-safe.
3. **Adoption is never rate-limited, and gains two mandatory bounds.** Refusing a strictly-greater
   root strands the device on a dead lineage permanently, so the bound lives on the send side (the
   per-(group, member) seed-send floor). The two adoption bounds close real insider DoS instead: the
   `minter` must be in the founding roster (else any member wins every tie forever with a
   lexicographically maximal fake id), and the version must stay inside the ceiling/jump bound (else
   one grief-mint at `2³¹ − 1` freezes the scope). The residual — an insider burning the version space
   before departing — is stated honestly in the spec rather than engineered away.
4. **Roots are adopted and gossiped even with the plane switched off**; only minting checks the
   switch. That is what carries a root across a plane-off member sitting between two plane-on ones, and
   it is why `GroupRootStore` is wired in `appModule`, outside `ScopeSync`'s nullable lifetime.

**Amended 2026-08-16 (the M4 device smoke found a deadlock — lock order is now enforced, not
documented):** the fleet smoke wedged a Pixel 8. Not a crash: an ANR on the debug bridge, with the Room
connection held by a *suspended* coroutine, so no thread dump showed an owner. Cause was a lock-order
inversion that predated M4 — `db.withTransaction { commitOpen(…) }` takes **transaction → mutex** while
`sealDm`/`sealGroup`/`currentSeeds`/`sweep`/`exportedRoots` took **mutex → connection** — which M4 made
reachable by calling `currentSeeds` from `gossipGroupRoot` on every inbound `CTL_GROUP_KEY` instead of
only from two rare floored paths. The class docs had stated the order as a rule for *callers*; that was
the bug's hiding place. It is now enforced inside both facades by the injected `SessionTransactor`
(`locked { }` = transaction outer, mutex inner, reentrant so the decrypt path just joins), pinned by
`SessionTransactorOrderTest`, and written up in `rules/mesh.md`. Lesson worth keeping: **a concurrency
invariant that depends on every caller remembering it is not an invariant.**

The one healing subtlety, added to the spec in the same pass: a root has **no ack**, so the send-side
dedup (`lastRootGossipVersion`) would suppress a re-send forever after a single lost gossip. The fix is
the anti-entropy direction — a distribution carrying a root *older* than ours (or none, while we hold
one) is answered with ours, floored like any seed send and self-terminating once they adopt. Without
it, "we already sent it" and "they have it" are silently conflated.

`knit-spool` needs **no change** — a spool is scope-blind, so a group scope is one more opaque id.
No derivation, no seal, and no §13 vector moved: `ScopeCrypto.groupScopeId`/`groupSealKeys` were
already written and vector-pinned at M1.

**Amended 2026-08-16 (M6 — the plane gets a face, and the switch ships):** `ui/relay/` adds the
Internet relays screen (route `relays`, reached from a Profile summary row) with the master switch, a
relay-list editor, per-relay health, and a one-time consent sheet. `ProfileScreen`'s
`BuildConfig.DEBUG` gate is **gone**: the editor was the stated hard prerequisite for un-gating, since
the app seeds a default spool that a release user must be able to remove. No wire, DB or protocol
change — only `SpoolStatus` gained `maxAttachBytes` and `SettingsStore` a `spool_consented` key.

Five UX decisions worth not relitigating:

1. **A relay refusal is not a failed send, and the UI must never imply it is.** The mesh carries the
   frame regardless, so every string is about *reach* ("nearby only", "not covered"), tinted
   `onSurfaceVariant` rather than `error`, and the ✓/✓✓ delivery tick is left completely untouched.
   Conflating the two would teach users to read a working send as a broken one.
2. **Only permanent causes are marked.** `rate`, `pow`, `tombstoned` and an unreachable spool all heal
   on the next heal round, so they stay invisible; the marker fires only on the two conditions that
   stay true until the user changes something — an attachment larger than every connected relay's
   `maxAttachBytes`, and relays that advertise no attachment support at all (§7.3's three-limits gate,
   read through `SpoolLimits.attachments`). A relay outage likewise yields `RelayReach.Silent`, not
   "not covered", so a transient blip does not paint a notice across every open thread.
3. **Markers sit at the altitude of the fact.** Scope coverage is a property of the *conversation*
   (the Nearby room is excluded structurally by §4.4; a DM without a confirmed ratchet session or a
   group without a root simply has no scope yet), so it renders once under the header — not stamped on
   every bubble. Only the attachment case is per-message.
4. **The frame-size case was found to be unreachable and was deliberately not built.**
   `TextLimits.MESSAGE = 2000` caps a body near 8 KB against a 64 KiB `maxBlob`, so `ScopeSync`'s
   `blob.size <= maxBlob` filter is a defensive guard no chat message trips. "Over the spool limit" in
   practice means attachments.
5. **The scheme rule has exactly one home.** `SpoolUrl.isAcceptable` is shared by the dialer and the
   editor, so a release build cannot store a `ws://` relay that would then silently never dial. Two
   copies would eventually disagree, and the one that drifts is the one that lets cleartext in.

Reach derivation is pure and unit-tested (`data/relay/RelayReach.kt`, `RelayReachTest`); the plumbing
that feeds it is `RelayStatusRepository`, a shared polled read of `MeshController.spoolStatus()`, since
`ScopeSync` exposes a snapshot rather than a stream and the value changes on connect/disconnect, never
per frame. One accident kept it cheap: `ScopeFrames.Scope.label` (`peerId ?: groupId`) is already
identical to `Conversations.idFor`, so scope→thread mapping needed no new key.

Scheme spec: docs/SPOOL_PROTOCOL.md; wire posture: docs/WIRE_COMPAT.md (its `GroupKeyPayload.gr`
precedent entry); context: context/e2e-encryption.md; deferred remainder: memory/roadmap.md.

**Amended 2026-08-16 (M5 — attachments ride the scope, in their own namespace):** the plane now carries
image bytes, closing the un-fetchable-image gap the spec's §11 had registered. New spec sections
§4.5/§6.5/§7.3/§9.5, added as fresh sub-numbers so **no existing cross-reference moved**; new client code
is `ScopeCrypto.attachmentId`/`sealChunk`/`openChunk`, `mesh/spool/ScopeAttachments`, five records in
`SpoolRecords`, and the attachment pass in `ScopeSync`. This is the first amendment that asks anything of
spool implementations, and `knit-spool` gained the matching half (both stores, the server, four
conformance checks, `SPOOL_MAX_ATTACH_BYTES`).

**M5 lands with no mesh-wire change at all** — no field, no ctl value, no capability bit, and no DB
migration. Everything a fetcher needs already rides in cleartext on the frame: `ChatContent.attachmentHash`
(the *ciphertext* hash) and `attachmentMime`, put there by the DB v19 precedent precisely so a blind
carrier could custody images. Size is not needed on the mesh because the spool reports the chunk count.
`GoldenVectorTest` is therefore untouched; only `ScopeVectorTest` and `SpoolRecordsTest` gained rows,
regenerated together and mirrored into spec §13 (and re-pinned independently by `knit-spool`'s
`SpecVectorTest`, which is a genuine cross-implementation check — two codebases, byte-identical records).

Five decisions worth not relitigating:

1. **A separate namespace, deliberately outside the frame digest.** Attachments are discovered by asking
   (`ahave`), never by anti-entropy. Folding them in would make a *byte* quota convergence-relevant, and
   two spools with different budgets would then never converge — the ADR 006 lesson, and the same reason
   `ForwardEntity.attachmentHash` stays out of `StoreDigest` and `CARRIER_BLOB_BUDGET_BYTES` is a purely
   local knob. It also means `maxAttachBytes` is the operator's alone and is **not** in the SUB-declared
   `ScopeBounds`: members must agree on what the digest folds, and on nothing else.
2. **`aid` is keyed, not the attachment hash itself.** `aHash` travels the mesh in the clear, so an
   unkeyed id would hand a spool that has any source of candidate hashes a confirmation oracle linking a
   frame to a scope. `HKDF(nonceKey, "…/aid" ‖ scopeId ‖ aHash)` closes it — §4.3's known-plaintext
   argument applied to the one object that actually travels in cleartext.
3. **Fixed 48 KiB chunks are structural, not tunable.** A constant chunk size is what makes a chunk's
   position derivable from the attachment alone, so there is no manifest object for two members to
   disagree about, and the sealed chunk (49221 B) still fits the 64 KiB `maxBlob`. The header
   (`aHash ‖ index ‖ total`) is sealed *inside*, so a member cannot replay a chunk elsewhere; the
   decisive check is still that the reassembled bytes hash to the address the frame named.
4. **Capability negotiation is a gate, not a hint.** Three HELLO limits, present together or absent
   together. The reason is mechanical: an unknown record is *skipped*, and a skipped request is never
   answered, so an optimistic `ahave` to a v1 spool strands that `q` until the 30 s timeout — once per
   attachment, per scope, per round. `FakeSpool(attachments = false)` models exactly that and the test
   asserts not one attachment record goes out.
5. **Partial downloads stay in memory.** M3's "derived, never stored" property is worth more than saving
   a re-fetch, and a partial-chunk table would be the first thing to break it. The cost is honest — a
   process death mid-transfer refetches that attachment — and the spool-side bitmap already makes the
   *upload* half resume for free (a test pins that only the missing chunk is re-sent). Persisting the
   download half is registered in §11.

One bound stated rather than engineered away: the want set derives from **custody**, whose 24 h TTL is
shorter than a scope's 48 h, so a frame that has aged out locally stops driving a fetch even while the
spool still holds the bytes. That matches the mesh carrier's own behaviour and keeps the seam small.

Scheme spec: docs/SPOOL_PROTOCOL.md §4.5/§6.5/§7.3/§9.5; wire posture: docs/WIRE_COMPAT.md (its
no-mesh-change precedent entry); deferred remainder: memory/roadmap.md.

## 020. Profile updates are sealed as a v2 ctl; the cleartext profile frame keeps first contact

Status: Accepted (2026-08-16; `MessageContent.ctl = CTL_PROFILE (8)`, `MessageContent.pr`,
`ProfilePayload` — no `EncEnvelope.v` bump, no capability bit, no DB change)

Field testing after M5 found the honest hole the spool spec had booked and never argued: attachments
crossed the Internet plane but **profile updates did not**, so a peer reachable only over the Internet
kept the name, status and avatar they had at last radio contact. `docs/SPOOL_PROTOCOL.md` §4.4 excluded
profiles in one asserted sentence and pointed at "§11" for the alternative, which §11 never registered.

**A `profile` frame is doing two unrelated jobs and only one of them can ever be encrypted.** First
contact is self-certifying — `verifyInbound` authenticates a profile against the `pubKey` *inside its own
payload*, because the nodeId IS that key bundle's hash — so encrypting it is a contradiction: the
recipient has no key yet. An update to an *established* contact has no such constraint; a v2 session
already exists, which is the same precondition a scope has. So the second job moves to a sealed ctl and
the cleartext frame keeps the first, permanently. The two ship together; this is dual-stack, not
replacement, and a pre-ratchet peer still sees only the cleartext form.

Choosing a ctl over admitting `type = profile` into the scope frame-set rule pays three ways: **§4.4
needs no change at all** (a v2 chat frame between the pair already passes `eligibleForDm`, so the problem
dissolves into carriage that shipped at M3), §10's "content confidentiality is entirely the inner
schemes'" survives intact, and group members get it free over the pairwise ctl DMs that `CTL_GROUP_KEY`
seeds already use. It is the ADR 016/018/019 custody argument a fourth time — `isCustodial` is a fixed
list on deployed builds, so a new frame type would flood and never be carried.

Four decisions worth not relitigating:

1. **The payload carries the sender's profile `version`, not the carrying frame's `sentAt`.** Both
   propagation paths must order against one number or a name silently reverts, and `sentAt` is the wrong
   one: a re-sent ctl is stamped later than a genuinely newer cleartext profile and would gate it out.
   The cleartext frame already puts the profile version in its envelope `sentAt`
   (`maxOf(clock(), previous + 1)`, so wall-clock-scaled and monotonic), and `PeerEntity.updatedAt`
   already stores it — so the sealed path reuses `InboundPipeline`'s existing
   `sentAt < existing.updatedAt` gate rather than inventing a second convention. A payload with
   `version <= 0` is ignored outright: an unorderable update must not be applied at all.
2. **The sealed payload is narrower than `ProfileContent` on purpose.** No `pubKey` (an identity re-pin
   is a TOFU event that must ride the self-certifying cleartext frame — the session proves *who sent
   this*, not what their key is) and no `prekey` (its job is to *start* a session, so sealing it under
   one that must already exist is circular). Presentation fields only. The ingest path likewise never
   touches the pin, the device tag, or the advertised capabilities, and never inserts a peer row — a
   missing row is a no-op, so this path cannot mint a peer that skipped the key pin.
3. **The avatar hash is repeated in cleartext `ChatContent.attachmentHash` on the carrying frame.** The
   DB v19 precedent reapplied verbatim — populating an existing field in a new case, its meaning
   unchanged. It is what lets a blind carrier custody the avatar bytes, and it is why the Internet
   plane's attachment pass needed **no** avatar special case at all: `ScopeAttachments.refFor` already
   reads that field.
4. **Group photos needed no wire change whatsoever.** `groupupdate` was already scope-eligible (§4.4), so
   the group's name, roster and `photoHash` have crossed since M4 — only the bytes were missing.
   `refFor` gained one branch reading `GroupInfo.photoHash`. Two corrections to the record fall out of
   this: a scope has always carried cleartext-payload frames (`groupupdate`, `groupleave`), so "a scope
   holds no cleartext payloads" was never true; and sealed avatars need no per-avatar key and no
   `peers.avatarKey` column, because the §4.5 chunk seal already encrypts under the scope key —
   `AttachmentCrypto`'s inner key exists to blind *mesh relays*, and a spool is blinded already.

Fan-out targets every peer with a confirmed v2 session rather than "accepted conversations": a sealed
profile discloses strictly less than the cleartext frame already floods to everyone, so narrowing it
would cost propagation and buy no privacy. It is deduped per `(peer, version)` rather than floored on a
timer — a profile edit is rare and user-visible, so a time floor would suppress a real second edit, and
one send per version suffices because custody and the Internet plane both carry it to an offline peer.
Prekey rotation bumps the version but deliberately does **not** seal, since it changes no presentation
field and would burn chain keys.

Stated rather than overclaimed: this continues ADR 018's "the last cleartext flooded metadata goes dark"
but does not finish it. Profile *updates* go dark for ratchet-capable peers; the initial cleartext
disclosure at first contact is structural and stays.

Scheme: this file plus `docs/SPOOL_PROTOCOL.md` §4.4; wire precedent: `docs/WIRE_COMPAT.md` (the fourth
additive `MessageContent` change, and the second use of the DB v19 field-reuse rule).

## 021. Attachment uploads are deferred while the radios still carry them; the frame plane stays unconditional

Status: Accepted (2026-08-17; `AttachmentDeferPolicy`, `ScopeSync.deferAttachment`,
`MessageDao.attachmentAcked` — no wire change, no DB migration, no capability bit, no spec vector change)

The Internet plane uploads every scope-eligible object as soon as it enters custody, whether or not the
radios already delivered it. For frames that is correct and should stay that way. For **attachments** it
means a second copy of every photo that already crossed a BLE or NAN link — frames are ~KB against a
64 KiB `maxBlob` ceiling, attachments run to 8 MiB — so the bytes are worth gating and the frames are
not.

Attachments are also the only object class where gating is *free of the plane's own invariants*: they
are deliberately outside the scope digest (§4.5/§6.5, ADR 019), so withholding one signals nothing and
costs no convergence. Gating frames would instead make `localFold` a function of local mesh state, and
the digest would stop converging against members whose radio history differs — the anti-entropy loop
would LIST every tick forever and `ScopeStatus.converged` would become noise.

**The gate is a deferral, never a veto, and it is self-reversing.** That is the whole decision, and it
is what rules out the obvious implementation. Gating on the delivery tick alone would be a *permanent*
veto — and a wrong one, because `MessageEntity.received` says the frame arrived, not the bytes: an
attachment travels by a separate demand-driven `BlobExchange` pull, so "acked but never fetched" is a
real state, and vetoing on the ack strands exactly the image the plane exists to rescue. So the rule
composes two signals that fail in opposite directions:

- **`MeshTransport.reachable`** — the presence plane, which expires. It is the *reversing* half: a peer
  that wanders off stops being recent and the upload happens on the next heal round, with no restart, no
  new custody event and no user action. On its own it would defer into a black hole, since the cue plane
  includes peers we hold no data path to at all.
- **The delivery tick** — proof a data path actually worked for this conversation. It is the half that
  keeps a merely-visible peer from being mistaken for a reachable one.

Every uncertain case resolves to *push*, and three of them are worth naming because they fall out of the
rules rather than being coded: a **carried** frame has no message row we authored, so a carrier never
defers (which is right — a carrier cannot read a sealed receipt at all, per ADR 018, so it has no
delivery knowledge to gate on); an **avatar** writes `PeerEntity` and no message row, so it never defers;
and a **fresh process** has no sightings, so a restart defers nothing. Under-deferring costs relay bytes,
over-deferring strands an image, and the asymmetry is priced in that direction everywhere.

Two bounds it needs and one exclusion:

1. **Last call.** Deferring is only safe while the referencing frame is still in custody to drive a later
   push — once it ages out, `ScopeAttachments.references` stops naming the attachment and the chance is
   gone. So the deferral ends `LAST_CALL_MS` (2 h) before the custody TTL, which is why
   `ForwardRepository.DEFAULT_TTL_MS` is injected rather than restated.
2. **A sighting window** (`RADIO_WINDOW_MS`, 15 min) above the cue plane's own quiet periods — the BLE
   scan floors to ~2 min in a settled clique and a dozing NAN peer goes dark for ~30 s ICM windows — so
   ordinary radio silence does not read as departure.
3. **Group scopes never defer.** `applySealedReceipt` flips one boolean on the *first* member's tick, so
   "acked" can never mean "every member holds it". Deferring on it would silently strand whoever was not
   reached, and a per-member ack matrix does not exist.

The honest cost, and the reason the frame plane keeps uploading unconditionally: a deferred upload tells
a spool roughly when the members were apart, which an unconditional one does not. It is scoped to the
object class that already leaks a size and a time (§10), and it is now written there. `spoolAttachDeferred`
is counted and surfaced in Diagnostics and the `SPOOL` bridge for the same reason — a silent gate reads
exactly like a broken upload.

Scheme: this file plus `docs/SPOOL_PROTOCOL.md` §9.5 (a MAY with two obligations) and §10. The spec's
§13 vectors and the `knit-spool` conformance suite are untouched: a deferring member and an eager one are
the same client to the same server.

## 022. The cleartext profile frame rides the spool plane, and its version leaves `sentAt`

Status: Accepted (2026-08-19; `ScopeFrames.eligibleFor` admits `FrameType.PROFILE` into both scope forms,
`ProfileContent.version` added, `SettingsStore.profilePublishedAt` added — no DB change, no capability bit)

Field testing the plane found that **everything built on a pairwise DM session fails between peers that
have only ever met over the Internet.** One lab device carried a converged DM scope with a remote peer yet
dropped six of its DMs as `RATCHET_EPOCH_GONE`; another had no DM scope with that peer at all, and so also
sat on 67 undecryptable group frames, because `CTL_GROUP_KEY` seeds ride as v2 ctl DMs and the session they
need could not be formed.

**ADR 020 got one thing wrong, and it was load-bearing.** It argued a ctl beat admitting `type = profile`
into §4.4 partly because "§4.4 needs no change at all", and partly because "group members get it free over
the pairwise ctl DMs that `CTL_GROUP_KEY` seeds already use". The second is circular: those ctl DMs are
exactly what a member without the peer's prekey cannot send. Its own closing line named the failure without
following it through — *prekey rotation bumps the version but deliberately does not seal, since it changes
no presentation field*. So a rotation is invisible to an Internet-only peer, and the 7-day cadence makes
this a certainty rather than an edge case: any such pair eventually cannot re-establish a broken session.

Decision 2 of ADR 020 stands unchanged — the prekey still cannot ride a sealed ctl, because sealing a
session-starter under a session that must already exist is circular. That is precisely why the *cleartext*
frame has to reach the plane. It is safe there: `verifierBundle` resolves a profile's key from the `pubKey`
inside its own payload and `canCarry` re-derives the nodeId from it, so the frame is self-certifying inside
a scope exactly as on the mesh, and it discloses strictly less than the copy already flooded to everyone in
radio range. This is dual-stack, not a replacement: `CTL_PROFILE` still carries presentation updates.

Three decisions worth not relitigating:

1. **The profile version leaves the envelope `sentAt`, into `ProfileContent.version`.** Not cosmetic —
   without it the fix is inert. Custody expiry is frame-global (`sentAt + ttl`, ADR 006) and a profile's
   `sentAt` *was* its edit time, so `ForwardRepository.store` refused any profile older than the 24h TTL as
   dead on arrival. Against a 7-day rotation cadence that left roughly six days in seven with no profile in
   custody at all — nothing for `liveFrames()` to return and nothing for a scope to seal. `sentAt` is now a
   publish stamp `republishProfileIfStale` refreshes every 12h; `version` stays put, so a re-publish is not
   mistaken for an edit and cannot advance a receiver's watermark. This also fixes a bug that predates the
   plane: `seedOwnProfileCustody` was a silent no-op for any profile older than a day, so a radio late
   joiner could not pull one either. Keying custody expiry off local receipt was rejected — ADR 006 requires
   every node to expire the same frame at the same instant.
2. **Presentation and prekey are gated on separate watermarks.** `applySealedProfile` advances `updatedAt`
   from a ctl that deliberately carries no prekey, so one shared watermark let a sealed presentation update
   suppress the cleartext frame carrying the prekey — and a live spool `EVENT` outruns a heal-round pull, so
   the race lands exactly when the prekey matters. `handleProfile` now returns early only when *both* halves
   are stale, and `prekeyProfileAt` (already on the row) is the prekey's watermark. `updatedAt` advances
   monotonically so a prekey-only admission cannot drag presentation backwards.
3. **Digest divergence with older builds is the accepted cost.** Profile blobs fold into the scope digest,
   so a member on an older build quarantines them (§9.3) and reports that scope unconverged forever while
   re-`list()`ing each heal round. Messages still flow. Taken deliberately over modelling profiles as a
   separate out-of-digest object class like attachments (§4.5/§6.5), which would have needed new record
   types and a `knit-spool` change for a plane still only in testers' hands. A second consequence to expect
   in a mixed fleet: an older build reads `sentAt` as the version, so its `updatedAt` becomes a publish
   stamp and it then rejects sealed `CTL_PROFILE` updates.

Scheme: this file plus `docs/SPOOL_PROTOCOL.md` §4.4 (C-4.4-5…7, C-4.4-13) and `docs/WIRE_COMPAT.md` (the
fifth additive `ProfileContent`/`MessageContent` change). No spool record, derivation or §13 vector moved —
a spool never decodes a frame, so the plane cannot tell the difference.

## 023. A split-brain ratchet root requests a reset, like every other unreadable v2 DM

Status: Accepted (2026-08-19; `DropReason.RATCHET_AEAD_FAIL` split out of `DECRYPT_FAILED` and added to the
reset trigger — no wire change, no DB change)

Field testing ADR 022 found two lab devices that had finally exchanged prekeys, established sessions, and
then could not read each other in **either** direction: symmetric `AEAD_FAIL`. Both held session state; the
roots disagreed. The reset heuristic that exists for exactly this class of trouble never fired.

`AEAD_FAIL` was folded into the generic `DECRYPT_FAILED` (shared with the v1 path), and the trigger tested
only `RATCHET_NO_SESSION` and `RATCHET_EPOCH_GONE`. Those two mean *we are missing something* and are
self-correcting — the peer's own traffic eventually supplies it. `AEAD_FAIL` means *we both have something
and it disagrees*, which nothing supplies: the pair re-serves the same undecryptable custody at each other
until the frames age out, and then does it again with the next message. It was the one ratchet failure that
could not recover, and it was the only one excluded.

Two things worth not relitigating:

1. **Acting on `AEAD_FAIL` is safe because the frame is already authenticated.** `verifyInbound` checks the
   Ed25519 signature against the pinned bundle *before* any decrypt, so a signature-valid frame that fails
   the AEAD is a real peer whose era diverged, never a tampered or corrupted one — those fail the signature
   first and never reach the ratchet. The trigger therefore cannot be driven by an off-path attacker, and
   the existing bounds (≥3 **distinct** frame ids, a 6 h per-peer floor, a pinned CAP_RATCHET peer with a
   prekey) still hold it to one X3DH init per burst.
2. **The group path already did this.** `GROUP_RATCHET_AEAD_FAIL` has always driven `maybeRequestGroupKey`
   alongside `GROUP_RATCHET_NO_KEY`. The DM path was the inconsistent one, so this is closing a gap rather
   than introducing a policy — which is also why `AEAD_FAIL` deserved its own `DropReason`: a split brain
   filed under the same counter as a v1 decrypt failure is invisible in Diagnostics and the `STATE` bridge,
   and that is precisely how it stayed unnoticed.

A second half surfaced the moment the first shipped: with resets finally firing, the pair deadlocked again
in **one** direction, now as `DUPLICATE`. `sealResetDm` abandoned the old root era but purged only its send
side — our **recv** epochs and skipped keys for that peer survived. The peer adopts our init, purges its own
rows (`OpenDelta.purgePeerRecvState`) and restarts its epoch numbering; its fresh epochs then meet our
surviving row from the dead era and are judged against its stale chain index. `DUPLICATE` is terminal by
construction — a duplicate is benign, so it drives no recovery at all, unlike the `AEAD_FAIL` above.
`RatchetStore.purgePeerRecvState` makes the initiator symmetric with the adopter: whoever abandons a root
era drops their receive state for it.

A third round, from the same lab pair, closed the loop: with both fixes deployed the receiver still sat at
**116 duplicates from 4 distinct frames** and had requested no reset at all. Its heuristic read 1, because
`DUPLICATE` fed nothing and its single `AEAD_FAIL` was one frame custody re-served three times. Every one of
the sender's five DMs had arrived and been discarded as benign.

So `DUPLICATE` joins the set, and the **distinct-frame-id** rule is what makes that safe rather than a reset
storm. A replayed frame is one id arriving repeatedly — custody re-serving it, two links delivering it — and
repetition can never advance a counter keyed on distinct ids. Several *distinct* frames landing on
already-consumed indices is a different statement: the sender restarted its chain while we kept ours. That
is precisely what the peer sees for our side of a half-adopted replacement, the mirror of the `AEAD_FAIL` we
see for theirs. The guard that was supposed to stop one stuck frame from triggering anything was, in the
stuck case, the thing stopping recovery — the pair could not produce three distinct *countable* failures
because the failures it could produce did not count.

The accepted cost: a peer whose skipped-key window evicted keys can accumulate distinct duplicates over a
long period and eventually draw a spurious reset. It is bounded by the 6 h floor and costs one X3DH plus a
skipped-key wipe, which is cheaper than the deadlock it replaces. `BAD_HEADER` stays out — a malformed frame
says nothing about our session state.

The last round was the one the earlier fixes made reachable. With every undecryptable outcome now able to
request a reset, **both** peers reset each other — 13 minutes apart, each landing inside the other's floors —
and the pair sat with every X3DH input present, two sessions, and neither confirmed. Two gaps kept it there:

- **`RatchetHeader.FLAG_RESET` was written and never read.** An explicit reset request was rate-limited as
  if it were an incidental init, so a peer that had waited out its own 6 h floor could still be refused for
  another 60 minutes, silently. It now gets its own short floor: the sender's floor is the real rate limit
  and is 6× stricter, and a peer ignoring it is a pinned contact churning the one conversation it is already
  party to.
- **`resolveRace` adopted the winner's root without purging the loser's receive state.** Its stated
  invariant — "send-epoch numbering continues monotonically either way, so no `(peer, se)` collision
  arises" — holds for an ordinary race and fails for a race between two *resets*, because `sealResetDm`
  restarts numbering by design. The winner's fresh epochs then landed on the loser's surviving rows and read
  as duplicates. The same omission as the `sealResetDm` one above, in the third of the three places a root
  era changes: whoever abandons an era must drop the receive state tied to it. The loser branch keeps its
  own root, changes no era, and correctly keeps its rows.

Verified end to end on the lab pair: a forced reset moved the receiver to `confirmed: true`, and messages
then flowed both ways with delivery ticks returning, after roughly a day wedged.

Scheme: this file only. No wire field, no derivation, no vector, no spool record — a reset request has
always been an ordinary v2 DM carrying `CTL_SESSION_RESET` (ADR 016). `FLAG_RESET` was already on the wire.

## 024. The reset heuristic only counts frames from the era it would abandon; an explicit reset is never a race remnant

ADR 023 gave every unreadable v2 DM the power to request a reset. A field test — one device carried out of
radio range, two DMs sent, neither delivered, and still undelivered after it came back and re-meshed — showed
that power feeding itself. The pair had re-rooted past each other and stayed dark for hours with every X3DH
input present, on both planes.

Two independent defects compose into the loop.

**The heuristic's own evidence is manufactured by its own remedy.** A reset discards the keys of the era it
leaves, so every frame already sealed under that era is unreadable *by construction* — and custody keeps
re-serving that tail for a full TTL, the spool for longer. Those re-serves carry **distinct** frame ids, so
the distinct-frame rule that bounds one stuck frame does nothing about a stuck era. Each side's tail trips the
other's heuristic, whose reset strands a fresh tail. Measured on the lab pair: 204 duplicates and 12 AEAD
failures on one device, 31 and 20 on the other, with the log showing a reset requested 70 ms after a duplicate
drop.

The gate is the era stamp, not wall-clock age: `env.sentAt < session.establishedAt` means the frame predates
the session it is being read as evidence against. `establishedAt` is already the number both peers converge
on — the initiator writes it into `InitPayload.at` and the responder adopts it — so the rule reads identically
on both ends without a new field. Frames sent since the era began still trigger, which is the whole population
that can prove anything. No session means no era and nothing to protect, so those pass unchanged.

**`RATCHET_DUPLICATE` comes back out of the trigger set**, reversing that half of ADR 023. The era gate does
not catch it — a consumed chain index is one we decrypted *in the current era*, so the re-serve is in-era by
definition. The reasoning that put it in was that several distinct frames landing on consumed indices means
the sender restarted its chain; the reasoning that takes it out is that a re-served backlog has exactly that
shape and is overwhelmingly more common, and that a consumed index is *proof the frame already decrypted* —
the one outcome that cannot mean divergence. It was a proxy for the half-adopted-replacement desync, and that
is now fixed at the source, at all three sites where a root era changes. This closes the question
`knit/knit-next#19` was opened to settle; the answer is the opposite of the guess recorded there.

**`unanchoredRaceWinner` must exempt `FLAG_RESET`.** ADR 023's escape hatch — "a genuine wipe of the higher-id
peer is still recovered, their undecryptable traffic trips OUR reset heuristic" — is what field testing
disproved. A confirmed race winner that never processed the loser's init refuses every later init from the
higher-id peer, and it refused the peer's explicit reset request along with the re-served remnants the guard
was written for. Recovery was therefore available only from the winner's side, behind its own 6 h floor: the
pair was dark for up to six hours per cycle, one-directionally, with nothing wrong that either side could act
on. A reset is the opposite of a remnant — minted fresh per request, rate-limited at the sender by that same
6 h floor, and once adopted its ephemeral becomes the idempotence anchor that makes its own re-serves inert.
It cannot defect to a losing root, because a reset abandons that root on the sender's side too.

Observed on the lab pair (P8 `cngt3uzz…` vs P9 `ke4vuj2…`, so P9 is the higher id): P9 originated a reset at
16:39:09.959 and P8 dropped a frame `AEAD_FAIL` 2.1 s later. Forcing a reset from the *low*-id side instead —
the direction the guard does not block — recovered both stranded field-test DMs and restored delivery ticks in
both directions. The one message that stayed dead was the probe sealed under the era P9 abandoned, which is
correct: forward secrecy means stranded ciphertext is stranded, and a reset repairs the channel, never the
backlog.

Scheme: this file only. `OpenContext.resetRequested` mirrors a wire bit that already exists (`FLAG_RESET`,
ADR 016) and keeps the engine wire-agnostic, exactly as `allowReplacement` does. No wire field, no derivation,
no vector, no spool record.

## 025. A spool's advertised limits are a claim, not a bound — the client's own request is the bound

`SpoolLimits` arrives in the spool's `hello` and nothing else vouches for it. Every inbound check written
against `limits.maxBlob` / `maxPull` / `maxAChunk` / `powBits` is therefore a check the attacker
parameterises: advertise `Int.MAX_VALUE` and the check is gone. Before this, the whole receive path had no
size or count check at all — `SpoolConnection.onBlob`/`onAchunk` appended every scope-matching record into
an unbounded list, so a spool that accepted a `pull` and simply withheld the terminal `ok` grew our heap
for the full 30 s request timeout (GitLab #21).

Decision, in three layers, each with a source of truth the spool cannot move:

1. **The request is the bound.** `Pending` carries what the request named — the `pull`'s id set, the
   `aget`'s index window — and spends one slot per named id on arrival. That single mechanism is the
   unsolicited-record check, the duplicate filter and the length cap at once, and it makes the worst case
   `|ids we chose| × |bound we declared|` instead of unbounded × unbounded.
2. **Sizes come from what we declared at SUB**, so `SpoolConnection` keeps `ScopeBounds` per scope rather
   than a bare id set. What we will not push, we will not accept. Structural constants
   (`ScopeCrypto.SEALED_CHUNK_BYTES`) win where the spec pins a size outright.
3. **Advertised limits are clamped at the HELLO boundary**, once, so every present and future reader of
   `conn.limits` inherits the narrowing instead of having to remember it. `powBits` too: it is the
   cheapest attack in the file — one integer buys full-budget mining per scope on every heal round.

**The trap when hardening this again.** A record we reject is not automatically a blob to quarantine.
§9.3's invalid set exists to stop a re-pull loop and is a *bounded* 512-entry per-scope set that evicts
oldest-first, so letting an untrusted party write into it on demand is the same bug in a different shape.
The split: an id we **requested** and got an unusable answer for is quarantined (it is in the spool's
digest and never in ours — merely dropping it is the permanent divergence `rules/mesh.md` forbids); an id
we **never requested** is dropped silently, because it was never pulled and §9.3 does not reach it.

Still open, deliberately: `accept` claims an `accepted` slot before validation and never releases it, and
an unsolicited `event` that fails validation still quarantines. Narrowing that means amending
`rules/mesh.md`'s absolute "never merely dropped" wording, which is a separate call.

## 026. The era gate is single-clock only when we responded; the initiator half needs a local bound

ADR 024's era gate (`InboundPipeline.isLiveEvidence`) compares `env.sentAt` against
`session.establishedAt` and claims the rule "reads identically on both ends". It does not, and that
paragraph is superseded. `sentAt` is always the *sender's* clock; `establishedAt` follows
`weAreInitiator`, and a security audit of `v2.2.3..HEAD` (GitLab `knit/knit-next#22`) found the two are
the same clock in only one of the two directions:

| write site | value | `weAreInitiator` | clock |
| --- | --- | --- | --- |
| `RatchetEngine.initiate` | `now` | `true` | **ours** |
| responder establish | `init.at` | `false` | the peer's |
| replacement adopt | `init.at` | `false` | the peer's |
| race-loser adopt | `init.at` | `false` | the peer's |

The race *winner* changes neither field, so the correlation is total: **`establishedAt` is our own clock
exactly when `weAreInitiator`**. When we responded, `establishedAt` IS the sender's clock and the raw
comparison is exact. When we initiated, it is ours, and a peer whose clock lags ours has every frame it
sends classified pre-era until the skew is worked off — the heuristic silently disabled in that
direction, which is the one-directional blackout ADR 024 was opened to close, reappearing under skew
instead of custody re-serves. On offline mesh devices that may run for weeks without network time, that
is the expected case, not the corner one.

The fix keeps the exact comparison for `!weAreInitiator` and gives the initiator half two bounds:

1. `Protocol.MAX_FUTURE_SKEW_MS` (5 min), the house tolerance already used for inbound `sentAt` clamping
   and custody admission. Absorbs ordinary disagreement.
2. `RatchetSessions.STRANDED_TAIL_MS` (48 h) — if `now - establishedAt` exceeds it, the gate opens
   regardless of what the frame is stamped. This is the clause skew cannot defeat, because it compares
   our own clock against our own stamp. Its warrant is retention, not time-as-such: the reason a pre-era
   frame is not evidence is that custody (24 h) and the spool's default scope retention (48 h) keep
   re-serving the tail. Past both, no tail survives anywhere, so an unreadable frame is real divergence
   whatever the peer thinks the time is.

Honest limit: a skew larger than 5 min still suppresses the heuristic in the initiator direction until
either the peer's clock passes our era stamp or the 48 h window expires. Bounded and self-healing, which
is the property that was missing; not instant.

**`establishedAtLocal` was considered and rejected.** The issue proposed stamping our local clock on the
session row and comparing it against local *arrival* time, so the gate never spans two clocks. That
defeats the gate outright: a re-served pre-era frame arrives *after* the era began, so
`arrival >= establishedAtLocal` always holds and every doomed frame passes — ADR 024's loop back in full.
It is also unnecessary, because on the only broken half `establishedAt` already *is* our local clock, so
`now - establishedAt` is a pure-local elapsed measure with no new column, no DB bump, no migration.
`establishedAt` itself stays untouched — it is the peers' shared idempotence anchor.

Scheme: `InboundPipeline` + one constant in `RatchetSessions`. No wire field, no schema change, no
vector. The suppressing return now logs (id, sender, `sentAt`, `establishedAt`, `weAreInitiator`) —
without it a wedged pair in the field is indistinguishable from one that simply has not reached three
distinct failures yet, which is how this stayed invisible. `RatchetEngineTest` pins the
`weAreInitiator` ⇔ our-clock invariant across all four write sites, since a fifth site that ignored it
would silently disarm the heuristic again.

## 027. Local-epoch retention orders by mint time; a re-minted epoch number replaces the dead era's key

ADR 024 stopped the reset heuristic from feeding itself, and the fleet relapsed anyway — pairs re-broke
within hours of every heal, one direction at a time, `EPOCH_GONE` on frames from a peer whose root matched
ours exactly. The forensic ratchet dump (added for this) showed both sides of a "diverged" pair holding the
**same root and the same `establishedAt`**: the sessions were healthy. What was missing was the local epoch
privs of the live era — on both devices, the table held exactly 16 rows, all numbered 46–62, all minted days
earlier, in eras long abandoned.

The sweep's "newest" was `ORDER BY epoch DESC`. That is correct only while epoch numbering is monotonic, and
a session reset restarts numbering at 1 by design (`sealResetDm` → `initiate`). After one reset, a long-lived
session's dead-era rows outrank every live-era row forever: the 16-per-peer cap keeps the 16 highest *numbers*
— all dead keys — and deletes each fresh epoch within one sweep cycle (≤15 min, the heal cadence) of minting
it. The peer, which received that epoch's pub off the wire, eventually bases its next epoch on it; we no
longer hold the priv; `EPOCH_GONE`; that direction dies, receipts die both ways. The failures are in-era, so
the (correct) ADR 024 heuristic fires a reset at the 6 h floor, the pair heals, the sweep eats the new era's
keys again, and the loop runs on the floor's cadence indefinitely. This was the recurring re-divergence engine
behind every relapse ADR 023/024 were opened on — the purge-site inventory in ADR 024 ("fixed at the source,
at all three sites") missed that retention GC is a fourth place ratchet state dies.

Two changes, both in the DAO:

- **`localEpochsNewestFirst` orders by `createdAt DESC`** (epoch number only as a same-millisecond
  tiebreak). "Keep the newest three", the 16-row cap, and the retire rule now operate on mint time, which is
  what the sweep's KDoc always claimed. Dead-era rows age out through the cap as live epochs mint, and stay
  long enough (≤48 h practical) to serve the prevRoot drain window.
- **`insertLocalEpoch` is `REPLACE`, not `IGNORE`.** Once time-ordering lets old rows survive properly, a
  restarted numbering *will* re-mint a colliding number while the dead era's row is still inside its
  retention window. IGNORE silently kept the dead key, making every peer frame based on the fresh epoch an
  unexplainable AEAD failure. The live era wins the collision; the frames the old key could still serve are
  pre-reset ciphertext, already stranded by the re-root itself.

No schema change (ordering and conflict strategy only), no wire change, no migration. `RatchetPeerState` and
the debug bridge's `RATCHET` dump gained era forensics — `rootHash` (8-hex SHA-256 prefix), `establishedAt`,
`weAreInitiator`, `highestPeAcked`, `prevRootExpiresAt`, `hasPeerInitAnchor`, and the `localEpochs`
(epoch@createdAt) table — because this bug was undiagnosable from drop reasons alone and took three field
sessions to corner: the header logging said which epoch was missing, but only the table said *why* it was
missing while its era's root matched.

Known residue, accepted: frames sealed under an epoch whose base priv was already swept are permanently
unreadable (forward secrecy working as designed). Custody re-serves them in-era, so they can still count
toward one more reset per pair; after that reset they are pre-era and the ADR 024 gate silences them. One
extra reset per historically-wedged pair, then stable.

Open question, deliberately not fixed here: `initiate` discards `peerInitEphPub`, so the resolved-init
idempotence anchor is lost on every reset and a re-served init from an era the peer already left can win
`resolveRace` on the resetter's side. With the sweep fixed this no longer self-sustains (the adopted-back era
still decrypts — the roots converge again on the next reset), and preserving the anchor across `sealResetDm`
is a small, testable follow-up.

## 028. Crash reports are captured on-device, redacted in two phases, and handed over only by the user

Knit ships no crash reporting — no Crashlytics (it would drag GMS back in), no Sentry (F-Droid flags it,
and automatic egress is the one thing this app is built not to do). The cost lands on bug reports:
issue #9 arrived as the word "crash", and `.github/workflows/needs-info.yml` answers that by asking for
`adb logcat -b crash -d` — a computer, a cable, developer options, and a buffer that has usually rotated
by the time anyone reads the request. The app gave the reporter no way to produce what the bot asked for.

So: a `Thread.setDefaultUncaughtExceptionHandler` writes the trace to app-private storage, Diagnostics
grows a "Last crash" row, and the user reads it, copies it, shares it as a file, or opens a prefilled
GitHub bug form. There is no upload path anywhere in it.

**Plain files, not Room.** A crash report has no relational shape and no query needs, and going through
Room would engage ADR 008 — a `@Database` bump, a tested migration, a regenerated schema — for a rotating
directory of five text files. `data/crypto/AtomicFileWrite.kt` already provides the atomic write.

**`noBackupFilesDir`, not `filesDir` plus excludes.** Backup is allow-by-default across three sections of
`res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`. Under `filesDir` a report stays
private only while all three exclude entries exist and stay correct; one missed entry ships crash traces
to Google Drive backup and device-to-device transfer. `noBackupFilesDir` is excluded by construction.
`FileProvider` has no `no-backup-files-path` tag, so a stored report cannot be shared directly — which is
why sharing stages an explicit copy under `cacheDir/crash/` (one new `<cache-path>`, no new provider).
That is a feature: the provider can only ever hand out the one report the user picked.

**Redaction runs in two phases through one idempotent function.** Stack frames pass through
byte-identical — they are compiled-in constants and they are the whole diagnostic payload. Exception
*messages* are the risk, and not hypothetically: `BluetoothMeshTransport` puts two node ids in one
`IllegalStateException`. Phase 1, inside the dying handler, applies structural rules only (node/group/blob
/device ids, safety numbers, urls with their bearer tokens, on-device paths, non-ASCII runs, a 300-char
message cap). Phase 2 adds an exact-match pass over the contact names this device knows — and it *cannot*
run at crash time, because those names live in SQLCipher-backed Room and DataStore, which a dying process
with no Koin graph cannot reach. The stored file is therefore less redacted than the shared one; that gap
is closed by construction, since the store exposes no raw-read API and the phase-1 file has no
`FileProvider` path.

If the redactor itself throws, the fallback is frames-only — every message discarded, class names and line
numbers kept. **No code path writes an unredacted `stackTraceToString()` to disk.**

**Reporting is a link, never a post.** "Report on GitHub" opens the repo's own `bug_report.yml` form
prefilled from the report; the user submits it from their own browser. The app holds no token and calls no
API, so "contacts no servers" stays true. `steps` and `expected` are deliberately left blank: both are
`required` in the template, so GitHub blocks submission until the reporter writes them — which is exactly
what the needs-info bot found missing on issue #9. The form's field ids are now a contract with
`.github/ISSUE_TEMPLATE/bug_report.yml`, pinned by `CrashIssueUrlTest`.

Deliberately out of scope, and the UI says so rather than implying coverage it does not have: native
crashes (Tink, SQLCipher, the tflite moderator), ANRs, `WifiAwareTransport`'s deliberate
`Process.killProcess` on a NAN wedge, and `meshExceptionHandler`'s non-fatal swallow. The natural next
step for the first two is `ActivityManager.getHistoricalProcessExitReasons` (API 30+, and it returns an
ANR trace); the last two are a few lines each once someone wants them.

The `<!-- knit-crash-report -->` marker in every app-filed issue is consumed by
`.github/workflows/crash-report.yml`: it labels the report, links prior ones sharing a crash signature
(exception class plus top frame *locations*, which is what R8 preserves), flags a stale app version, and
names the `mapping-<version>.txt.gz` asset needed to deobfuscate release frames. It is separate from
`needs-info.yml` on purpose — an app-filed report satisfies that bot's trace/device/version rules by
construction, so the two never speak on the same thread.

## 029. Taking a photo in a chat is an in-app CameraX surface, entered by long-press, ingested in memory

Status: Accepted (2026-08-20)

Sending a photo of what is in front of you meant leaving Knit — camera app, back, attach, find it in the
picker. Issue #6 proposed fixing that in two stages: `ActivityResultContracts.TakePicture()` first, an
in-app viewfinder later. We built only the viewfinder.

**No `TakePicture` intent, and therefore no FileProvider change.** The intent contract needs a Uri the
*camera app writes to*, and `res/xml/file_paths.xml` today exposes only outbound staging directories
(`apk/`, `crash/`). Adding an inbound-writable path is a new exposure class, permanently, in exchange for
a flow that still hands the screen to another app — most of what the issue complained about. The photo
picker remains the fallback when the camera can't open, so nothing is left without a route.

**The bytes never reach disk.** `ImageCapture.takePicture(executor, OnImageCapturedCallback)` yields an
in-memory JPEG, which goes straight to `AttachmentStore.ingest(bytes, mime)`. Staging a plaintext JPEG in
`cacheDir` would have broken the invariant in `AttachmentStore`'s KDoc — attachment bytes live in the
encrypted blob store only — for the window between shutter and ingest, and would have left a photo behind
on a process death mid-flight. This is what the byte-source overloads of `decodeOrientedBounded` and
`ingest` exist for; note `decodeBoundedFromBytes` is *not* a substitute, since it skips EXIF orientation
(it only feeds the classifier) and would store every photo sideways.

**In-place composable, per ADR 015.** `ui/camera/PhotoCaptureContent.kt` renders in place of the chat's
content, exactly as `QrScanner` does, for the reasons recorded there. The hardware probe, the `CAMERA`
permission state machine and the non-camera messages moved to `ui/camera/CameraSupport.kt` (`CameraGate`
/ `CameraMessage`) and are now shared by both surfaces rather than duplicated. Consequence, inherited
from the scanner: with no nav route, `demo_route` cannot deep-link it, so the seeded UI, UIAutomator and
ATF suites cannot reach it. The composer button itself *is* ATF-covered, since the chat routes are.

**Long-press the attach button, and only in attach mode.** The composer has no dedicated attach button —
one trailing button morphs between Attach and Send — so a camera action had to either take layout space
or hide in a gesture, and we chose the gesture. Long-pressing *Send* does not open a camera: it would be
surprising and could interrupt the send it appears to trigger. TalkBack parity comes from
`onLongClickLabel`, which names the camera in the actions menu; sighted discoverability is the accepted
cost of this choice.

**That button is now a `Surface` + `combinedClickable`, not a `FilledIconButton`.** `FilledIconButton`
wraps `Surface(onClick = …)`, whose own `clickable` sits *inside* whatever modifier the caller passes and
consumes the gesture — an outer long-press never fires. The colours and shape are `FilledIconButton`'s
defaults, so it is visually unchanged. A long press in Send mode still resolves to an ordinary click on
release, exactly as before.

**A failed capture speaks up; a failed pick still doesn't.** `IngestResult.Failed` has always been
swallowed silently, which is fine when the image is still sitting in the picker. A photo that was just
taken exists nowhere else, so `attachCaptured` surfaces a toast where `attach` stays quiet.

Nothing changed on the wire, in custody, or in the crypto envelope: once ingested, a captured photo is an
ordinary image blob. No new dependency either — `ImageCapture` is in the already-locked `camera-core`
1.6.1 that ADR 015 pinned, so `app/gradle.lockfile` is untouched.

## 030. Coordination-plane compaction is transport-local: compact framing + preset-dict deflate + ≤3-part fragmentation, capability-gated per peer

**Date:** 2026-08-21 · **Status:** shipped

The Wi-Fi Aware fast path framed every message as `[0x01][CBOR WireEnvelope]` under a ~255 B/message
radio cap, which silently excluded every v2 sealed frame: measured legacy sizes (pinned executable in
`CoordinationPlaneSizeBudgetTest`) are 374 B for a steady-state sealed receipt, 436 B with the X3DH
init attached, 388 B sealed reaction, 376 B for a 40-char sealed DM, 554 B for a full profile — so
AckSync's sealed ticks and sealed reactions only ever landed over a live link, and full profiles never
fast-fanned at all.

The fix is three transport-local re-encodings in `mesh/link/FastFrameCodec` (+ `FragReassembler`),
deliberately **not** a wire change: only the outer envelope — whose ttl/hops/relay are unsigned mutable
routing metadata every relayer already rewrites — is re-framed, and `sig`/`signed` pass through
byte-exact, so the frame signature verifies unchanged (WIRE_COMPAT rule 4 holds by construction).
Tag `0x03` = 3-B header + raw sig + `signed`; deflate (`java.util.zip`, raw/nowrap, preset dictionary
`DICT_V1`, stored-flag fallback so expansion is impossible) runs over `signed` only — the 64-B sig
stays outside the stream so its randomness can't poison the Huffman table; tag `0x04` fragments a
compact frame into ≤3 parts reassembled per (discovery session, peer handle, fragId) in a bounded
(8-entry, 5 s, lazily-swept) store. Emission is gated per peer on the new `Protocol.CAP_FAST_COMPACT`
(0x20) read from the SSI-advert copy in `reachablePeers` — a cue-only peer reads caps 0 and keeps the
legacy `0x01` framing forever, so mixed fleets interoperate with **no `SERVICE_NAME` bump** (the
deliberate counter-example to the "cue format change = hard cut" rule, recorded in ARCHITECTURE §3.2).
The tag registry (0x01 forever, 0x02 burned, 0x03/0x04) is append-only like capability bits.

Measured outcome: cleartext metadata gains ~25% headroom (receipt 214→171 B, typing-group 229→154 B);
sealed ctl frames land at **2 fragments** (steady receipt 374→316 B compact) — deflate's ceiling is
real, ~99 B of a sealed frame is incompressible crypto (ct/nonce/ek), so single-message sealed ticks
were never on the table and the plan's honest expectation held. Frag loss² is acceptable because the
plane is best-effort by contract (flood/custody backstops floodable frames; AckSync's owed-entry retry
loop stays the reliability mechanism for relay=false ticks — its no-cleartext-downgrade rule is
untouched). `DICT_V1` is frozen under a SHA-256 golden (`FastFrameCodecTest.dictV1IsFrozen`): a dict
edit would make shipped receivers inflate garbage that dies misattributed at signature verify, so
tuning mints `DICT_V2` under the header's dictId field instead. Deliberately NOT done here:
`shouldFastFanout` still excludes DM/group chat frames (policy unchanged — relaxing it to a size probe
is a one-line follow-up now that the transport size-gates per encoding), and the ~8-deep aware tx
queue stays unhandled (parts go out consecutively per peer so overflow loses whole frames, not
part 2 of everyone; `nanMsgSendsFailed` is the field signal). New counters
(`fastCompactSent`/`fastLegacySent`/`fastFragSent`/`fastReassembled`/`fastTooBig`/`fastDropsByReason`,
incl. the previously-invisible unknown-tag drop) ride `…debug.STATE`. No new dependency; lockfile
untouched.

## 031. The Internet-relay plane ships dark behind `BuildConfig.INTERNET_PLANE`, gated at `spoolEnabled` — not stripped

**Date:** 2026-08-22 · **Status:** shipped

The spool plane is feature-complete through M6 (editor, group scopes, sealed attachments, defer policy)
but has not been introduced publicly, and the two-island device trials the roadmap still owes it are
outstanding. So shipped artifacts hide it rather than carry a half-announced feature: a new
`buildConfigField("boolean", "INTERNET_PLANE", …)` reads **true in debug, false in release/staging**, with
`-PinternetPlane=true|false` overriding either way. Both defaults live in `app/build.gradle.kts` source
(the `?:` fallbacks) rather than in `gradle.properties` or CI, so F-Droid's rebuild — which passes no
`-P` — resolves the same OFF and stays byte-identical.

The load-bearing choice is **where** it is gated: `SettingsStore.spoolEnabled` reads
`BuildConfig.INTERNET_PLANE && stored`, and every consumer of the plane's liveness already goes through
that one flow — `ScopeSync`'s url supplier (no socket), `MeshManager.mintGroupRootsIfDue` (no group-root
mints), and `RelayStatusRepository.facts`, from which `planeFor`/`reachFor`/`attachmentReach` derive the
header cloud, the per-chat relay notice and the "nearby only" attachment markers. One gate is therefore
total, and a future consumer cannot forget it. The visible entry points are hidden on the same flag:
`ProfileScreen`'s row (via a defaulted `showInternetRelays` parameter, so the hidden case stays testable)
and the `relays` route, which is **not registered** in the `NavHost` at all — a screen whose switch would
be inert is better absent than reachable. `seedDefaultSpools` also no-ops **without writing its seeded
marker**, so the shipped default lands on the first run of the build that un-hides the feature, which is
the first run where the user can see and remove it. `RelayStatusRepository.statuses` emits once and stops
instead of ticking every 5 s forever for a plane that holds no workers.

Deliberately **not** a code strip. R8 constant-folds the `if (INTERNET_PLANE)` branches, but `ScopeSync`
and the whole `mesh/spool/` tree stay in the APK because `MeshManager` still constructs them — the plane
is one flag flip from live, and the unit suite (which builds debug, so the flag is true) keeps exercising
the real thing rather than a disabled shell. The stored `spool_enabled` preference is read but never
cleared, so a device that opted in under a flag-on build keeps its choice. The `…debug.SPOOL` bridge is
unaffected: it lives in `src/debug`, where the flag is on.

The globe beside the ✓✓ needs no gate — it is a function of the persisted `MessageEntity.deliveredVia`,
which nothing sets while the plane is parked. Nor do the Diagnostics spool rows (empty `status()` when
`ScopeSync` holds no workers) or its spool metrics (rendered only above zero).
