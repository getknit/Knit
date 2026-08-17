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
