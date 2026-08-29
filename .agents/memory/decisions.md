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
(Tink/SQLCipher/TFLite/ARSCLib) were left un-tightened here and **tightened in ADR 050**, which also
root-causes a second, subtler consequence of the same kxml2 duplication described above. Detail: the `keepRules/knit-r8.keep` header + the
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

## 032. The scope table is gated on a confirmed session, never on the Message Requests rule

Status: Accepted (2026-08-22)

`ScopeRegistry.dmScopes` filtered DM peers through `Conversations.isAccepted` (via
`MeshManager.isAcceptedConversation`) from the day the client plane landed (`5f185c4`, spool-m3 phase
1/3). Removed: the scope table now follows `RatchetSessions.exportedRoots`, which already admits only
**confirmed** sessions, and nothing else.

The gate contradicted ADR 009, which states the acceptance rule is "a **local presentation decision
only** — never folded into custody/relay, so it is *not* convergence-critical". The Internet plane is a
custody-plane sibling of `ForwardSync`, so gating scope derivation on it is exactly the fold ADR 009
rules out.

The failure it caused is worse than the doc violation, because acceptance is *asymmetric*: it is largely
"I have authored in this thread". A pair where one side has only ever **received** therefore derives a
scope on the sender's device and none on the receiver's — so the two never share a scope id, and the
plane carries nothing **in either direction**, including the sealed receipts that draw the ✓✓. Both ends
report `connected: true`, `lastError: null`, `invalid: 0` and a scope that merely never converges; the
sender's thread reads `RelayReach.Covered`, which renders no ornament, so nothing anywhere says the pair
is not on a shared scope. Observed 2026-08-22 between two lab Pixels whose sessions were byte-identical
mirrors (same `rootHash`, same `establishedAt`, confirmed both ways) while their *group* scopes matched
and synced normally — groups were never gated. The DMs only landed when the devices came back into radio
range, which is what disguised it as a radio problem.

A confirmed session is the right bound and a stronger one than acceptance: it costs a completed X3DH,
which no stranger reaches unsolicited, and it is already the export gate. The spam argument the old gate
implied does not survive contact with the asymmetry — a rule that silently disables the relay for
ordinary one-way threads is not a spam control.

**Not fixed here:** `reachFor` still answers `Covered` from the local scope table alone, so it reports
one side's ability to push, not that the pair share a scope. Honest per-thread coverage needs either
new copy for the outbound-only case or a peer-participation signal inferred in `ScopeSync.accept`
(a spool-sourced blob whose `senderId` is the scope's peer). The extension register's `CAP_SPOOL` bit
does **not** answer it: it says the peer's build speaks the plane, not that the peer derived this scope.

## 033. Group delivery ticks escalate into custody when the author is absent, batched as one sealed ctl frame

Status: Accepted (2026-08-22)

The sealed group tick was the last delivery fact with no delay tolerance beyond process memory: the
*message* converges via custody, but its `CTL_RECEIPT` tick was `relay = false` and sent by AckSync
straight over `transport.send`/`fastSend` — never through `originateSigned`, so never flooded
(`MeshRouter` refuses `relay = false`), never custodied anywhere (the `isStorable() && wire.relay` gate;
not even the acker held a copy), and never visible to the Internet plane (`ScopeSync` seals from
`ForwardStore.liveFrames` only). An author offline past AckSync's in-memory window simply never saw ✓✓.

The fix is an **escalation**, not a replacement, keyed at the *sender* (a local emission choice, the
CAP-gate precedent — never a carry/convergence rule): a **live-linked** author keeps today's cheap
unicast `relay = false` tick (zero custody load for the co-present case); an **absent, sealed-capable**
author's acks batch per author in AckSync and, after a 45 s debounce (a `flushScope` coroutine wake;
`retryPending()` on the heal heartbeat is the backstop), escalate as **one** sealed ctl frame carrying
every pending id — the additive `MessageContent.acks` list (the sixth additive content change,
`docs/WIRE_COMPAT.md`) — originated `relay = true`: flooded, custodied, and spool-eligible with **zero
frame-set-rule change** (a DM-shaped v2 ctl between the pair already satisfies `ScopeFrames.eligibleForDm`
and SPOOL_PROTOCOL §4.4 C-4.4-6; §6.2's "delivery facts do not exist at this layer" is untouched — the
tick is just another opaque scope frame). One chain key per batch, however many messages it acks.
Escalated ids land in a done-but-remembered ledger so the exists-gate's re-ack on every custody re-serve
no-ops instead of re-sealing; an author who links *during* the debounce gets the batch over the live link
(`relay = false`, no custody rows); a failed seal at flush (author unpinned meanwhile) re-materializes the
ids as the legacy per-id cleartext entries. The receiver applies `ack` and every `acks` id under the same
per-id forged-ack guard, `distinct` and bounded at 2× the send cap; `markReceived` stays idempotent and
first-evidence-wins, so re-serves and duplicates are absorbed.

Why custody is legal for this frame: the custody rule stays keyed on frame bytes identical at every node
(`type = chat`, `relay = true` — ADR 006 holds untouched), and ADR 018's vaccine table is unchanged — a
sealed receipt still purges nowhere, so the escalated tick ages out on the frame-global 24 h TTL exactly
like the group message it acks (which was never purge-eligible anyway). The quota math is why batching is
mandatory rather than nice: per-message custodied ticks would cost up to roster × messages frames against
each ticker's 200-per-sender bucket (100 messages × 6 members ≈ 500 frames mesh-wide); one batch per
(member, author) costs ~5, precisely in the offline-author scenario the feature exists for.

Deliberately NOT done: **cleartext ticks never escalate** (a cleartext receipt in custody would re-leak
the delivery event ADR 018 sealed away — the legacy population keeps the unicast retry loop);
**broadcast-room ticks never escalate** (the ambient, shorter-lived class; `owe(escalatable = false)`);
**no group-form tick** (a sender-key-sealed tick would be all-or-nothing on member eligibility and would
broadcast delivery facts to the whole roster — the DM form degrades per-author and every v2 group message
implies an author↔member session by construction, via its seed ctl DM); **no per-member ack matrix** (the
tick's "≥1 member received it" semantic is unchanged; the null arm of the forged-ack guard IS the group
tick); **batches never ride the coordination plane** (a 16-ack batch already outgrows the ≤2-fragment
compact budget — pinned by `CoordinationPlaneSizeBudgetTest.batchedSealedReceiptNeverRidesTheFastPlane` —
so escalation goes through origination, structurally never `fastSend`). Accepted residuals: a process
restart forgets the in-memory ledgers, so a custody re-serve can re-seal a fresh tick while the old frame
still rides custody (the DM-receipt precedent — one duplicate, absorbed idempotently); a ratchet-era lab
build without the `acks` field consumes a batched tick as the pinned chain-advancing no-op (legal only
because the whole sealed-ctl era is on the unreleased v2 train). Diagnostics: `receiptsCustodied` beside
`receiptsSealed`/`receiptsSealedFallback` in Diagnostics and `…debug.STATE`. Tests: `AckSyncTest` (the
escalation suite + the reframed seal-once pin), `InboundPipelineTest` (batched apply under the per-id
guard; end-to-end group delivery → batch → originate), `MessageContentTest`/`GoldenVectorTest` (both
receipt forms pinned; the byte-identical-defaults proof extended). Scheme doc:
`docs/ENCRYPTED_RECEIPTS_REACTIONS.md`; wire precedent: `docs/WIRE_COMPAT.md`; context:
`context/store-and-forward.md`, `context/e2e-encryption.md`.

## 034. A voice note is an ordinary attachment with an audio MIME

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

## 035. An attachment's MIME type leaves the cleartext frame

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

## 036. Per-member group delivery is a local acker table; the tick's wire semantic is untouched

Status: Accepted (2026-08-23)

ADR 033 closed the last delay-tolerance gap in the group tick, so an author now reliably learns their group
message landed — but only *that* it landed. `messages.received` is one boolean the first member's ack flips
for the whole roster, so "Message info" could never answer the question people actually open it for: **who
has this, and who doesn't.**

The acker was never missing from the wire. Every receipt form arrives as a signed `RelayEnvelope` whose
`senderId` is the acking member — the cleartext `RECEIPT` frame, the sealed `CTL_RECEIPT`, and ADR 033's
batched `acks` alike — and both apply paths used it as a guard and then dropped it
(`InboundPipeline.handleReceipt` / `applySealedReceipt`). So this is **receive-side bookkeeping, not a wire
change**: one local `message_receipts` table (DB v6, `MIGRATION_5_6`, PK `(messageId, ackerNodeId)`, the
`reactions` shape one table over), written by `MessageReceiptRepository.record` in the **same transaction**
as the tick it accompanies. No new frame type, no new ctl value, no new `MessageContent` field, no
capability bit, nothing folded into any digest, and `AckSync` is untouched end to end. **ADR 033's "no
per-member ack matrix" therefore stands as written** — it was a statement about the wire, and the tick still
means "≥1 recipient received it".

**The one new guard, and why it is only on the row.** The forged-ack guard's null arm accepts *any* signed
sender for a group message — that null arm IS the group tick (ADR 018/033), and it must keep doing so.
Storing the sender turns that same null arm into a roster-spoofing surface, so `ackerFor` gates the **row**
(and only the row) on membership: a DM's addressed recipient, a group's *effective* roster member, any
signed peer in the public room (no roster to check against), and nobody at all for a message we don't hold
— so a receipt can never plant an orphan. `markReceived` is reached identically in every case; a non-member's
ack still ticks and simply names nobody.

Three UI rules that follow from what we can honestly claim:

1. **A ticked message with zero rows predates the table** → show no roster at all. Naming every member as
   "waiting" would contradict the ✓✓ above it, and the migration deliberately backfills nothing: we never
   observed who acked those, and inventing it is the one thing worse than saying nothing.
2. **The broadcast room gets an open "received by N" list, no denominator, no waiting half** — it has no
   roster, so there is nobody to be waiting on. Hidden until an ack lands (an empty list is not a fact).
   Its ticks also never escalate into custody by design, so the list is patchier than a group's.
3. **A DM never shows the split** — its single ✓✓ already names the only recipient there is.

`notedAt` is **our** clock at apply time ("when their receipt reached you"), deliberately not the acker's
`sentAt`: mesh devices have no time sync and an escalated batch's `sentAt` is its 45 s flush time, so a
peer-clock value could render a delivery *earlier* than the send it acknowledges. `via` is first-evidence-wins
like `markReceived`'s. Blocked ackers are listed, not filtered — the reactions precedent on the same screen.
Deliberately NOT done: read receipts (they exist nowhere in the app), and no fraction on the bubble/chat-list
tick — the aggregate stays the aggregate.

Tests: `MessageReceiptRepositoryTest` (real SQL, plus the pin that `record` **nested inside the ctl commit's
transaction commits rather than deadlocking** — the failure mode would otherwise be a silent coroutine hang),
`InboundPipelineTest` (cleartext/sealed/batched acks each name their sender; a non-member's ack ticks and
names nobody; a re-serve keeps the first crossing; an unheld id records nothing),
`MessageDetailsViewModelTest` and `MessageDetailsScreenContentTest` (the three UI rules),
`KnitDatabaseMigrationTest` (5→6, empty and un-backfilled). **`AckSyncTest`, `GoldenVectorTest` and `WireSerializationTest` are untouched and pass
unmodified — the executable proof that nothing on the wire moved.** Scheme doc:
`docs/ENCRYPTED_RECEIPTS_REACTIONS.md`; context: `context/store-and-forward.md`.

## 037. A bundled model that crashes the process natively is latched off, on evidence rather than on failure

`MlTextModerator` and `NsfwImageModerator` absorb every *Java* failure a TFLite load can produce — two
nested `runCatching` layers, down to `UnsatisfiedLinkError` and `OutOfMemoryError` — and degrade to
allow-all. A **native** crash inside the interpreter cannot be caught at all: it takes the process, and
`CrashHandler` never sees it (ADR 028 says so in its own KDoc). The toxicity warm-up runs from
`KnitApplication.onCreate` on every launch, so on a device where that reproduces the app is in a launch
loop with **no way out**: open, wait five seconds, die, repeat — no crash report, and clearing app data
does not help, because the trigger is the bundled asset plus the device, not stored state. That is
hypothesis 2 of getknit/knit#9 (LineageOS 23.2 / Android 16, "crash ~10 s after opening"); unconfirmed,
but the only startup-path failure that produces exactly that report, and the one the app could not survive.

So: `moderation/ModelLoadGuard` records a marker before the first touch of a model and clears it after,
and a launch that finds the marker still set knows the previous process died in there. A latched model is
simply not loaded, and the moderator degrades to the state it already reaches with missing assets.

**The marker means "the process died in there", not "the load failed".** This is the decision everything
else follows from. The clear-side runs in a `finally` under `NonCancellable`, so a load that returned
nothing, threw, or was cancelled clears it just the same. Without that the mechanism latches itself off
during ordinary use, three ways: a build shipped without the models takes the "no asset" path on *every*
launch; a Java-level load failure is caught and looks identical; and backing out of a chat while the 17 MB
image model loads cancels `viewModelScope` mid-flight. Only the `finally` makes the marker mean one thing.

**The exit record is a false-positive filter first, an accelerator second.** A process death mid-load that
has nothing to do with the model leaves identical evidence. `5da5601` fixed a Java
`ForegroundServiceDidNotStartInTimeException` that fired at ~10 s on slow devices — five seconds *after*
this marker goes down — so a bare counter would have latched the classifier off for a reason twice removed
from moderation. `crash/ProcessExitReasons` reads
`ActivityManager.getHistoricalProcessExitReasons(pkg, 0, 1)` (the follow-on ADR 028 named) and classifies
three ways: a native fault latches on the first strike; an *explained* exit — Java crash, ANR, low memory,
force-stop, package update — is discarded **without counting**; anything else counts toward `MAX_FAILS`.
Reading only the newest record is sound because the app declares no `android:process` anywhere, so it
always describes the process immediately before this one; and `exit.at >= pendingSince` keeps an older,
unrelated crash from being credited to this attempt. API 30+, so on our minSdk 29 it degrades to counting.

**`reason` alone is not enough — `status` decides the signalled case.** `WifiAwareTransport` kills its own
process on a NAN wedge, which surfaces as `REASON_SIGNALED` status 9 (SIGKILL), indistinguishable by
reason from a real SIGSEGV (status 11). Reading the status separates them, and it is the hedge that
matters most here: if a ROM's debuggerd never files the tombstone that produces `REASON_CRASH_NATIVE`, the
fault-signal arm still catches the crash. #9 is a LineageOS build — "the ROM does it differently" is the
premise, not an edge case.

**`MAX_FAILS` is 2.** The asymmetry is lopsided: a wrong latch is visible, resettable, and clears itself on
the next version bump, while a missed one leaves the app unusable. With every ordinary cause already
filtered out, two consecutive *unexplained* deaths inside a sub-three-second window is evidence enough —
and it halves how many times a genuinely affected user watches the app die. It is only ever reached on API
29 or when the platform returns no usable record.

**DataStore, not a `CrashStore`-style file.** `edit {}` writes a scratch sibling, `fsync`s it and renames
before it resumes (`datastore-core` 1.2.1 `FileStorage.kt`; its own `TODO(b/151635324)` notes the
*directory* is unsynced, which would matter for a power cut, not a process death) — so awaiting it before
the load is a real barrier. `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` already
exclude `datastore/` in all three sections, so the backup argument that justified ADR 028's
`noBackupFilesDir` does not apply. Room would engage ADR 008 — a `@Database` bump and a tested migration —
for two Longs and a String. `data/settings/ModelLoadJournal` is the narrow seam (the `InboundSettings`
precedent) that keeps the state machine's test on plain JVM.

**The stamp is version code + OS fingerprint**, and the OS half is the one that earns its place: a ROM
update is exactly the event that might fix a SoC/driver fault, and without it a user who updates
LineageOS stays latched off until Knit ships a new version code. It is passed in as a value, so
`ModelLoadGuard` holds no `android.os.Build` reference.

**Deliberately not done.** No `warmUp()` for `NsfwImageModerator` "for symmetry" — that would move a 17 MB
load onto the launch path, which is precisely what `5da5601` moved off it. No lexical fallback for
`ScopedTextModerator.direct` when latched: profanity-in-private-only-sometimes is a worse product state
than the one being fixed, and it would make moderation policy depend on an invisible flag.

**What it does not claim, stated because the UI must not overclaim either.** It catches the launch-loop
shape — a fault on the *first* touch of a model. A native crash on the five-hundredth inference leaves no
marker and will recur; that is not a launch loop, and latching on it would assert far more than the
evidence supports. And the honest cost of a latch: the Nearby room keeps its word-list pass, but
`ScopedTextModerator.direct` is the bare ML classifier, so **DMs and groups lose text screening entirely**,
and images lose NSFW screening. That is not a new hole — a missing asset already produces it — but it is
now a sticky, user-chosen-recoverable state rather than a transient one, so Diagnostics says which
screening stopped, and the reset dialog says it takes effect on the next start (the moderator latches
`loaded` in memory, so nothing reloads inside a running app).

Verification is a build flag, not a runtime seam: `-PmodelFaultOnLoad=segv|kill` raises the fault inside
the guard, defaults off in build-script source (so F-Droid's `-P`-less rebuild stays byte-identical) and is
forced off in `release`. The two are not interchangeable, and not in the way first assumed: only `segv`
produces the native-crash evidence that latches, while `kill` is the **negative control** — on API 30+ a
SIGKILL is classified *explained*, so it must never latch however often it happens. The counting arm is
only reachable where the platform returns no usable record at all (API 29, or an unclassified reason).
`…debug.MODEL` dumps the journal *and* the platform's exit record, which is where you find out what the
target ROM actually reports.

Verified on a Pixel 9 Pro XL (Android 17 / SDK 37), which is where the `status` decision earned itself.
A healthy launch completes and closes its marker (`pendingSince:0, fails:0`). `-PmodelFaultOnLoad=segv`
produces `reason=5 (APP CRASH(NATIVE)) status=11` — *both* arms match on this ROM — and the very next
launch comes up latched, alive, with the fault still armed, because a latched model is never loaded and
the injection is never reached. `-PmodelFaultOnLoad=kill` produces `reason=2 (SIGNALED) status=9` three
times running with `fails` staying 0: had `REASON_SIGNALED` alone been read as a native fault, three
ordinary kills would have disabled moderation on a phone with nothing wrong with it. With the model
latched, a word-list hit in the Nearby room is still blocked (nothing stored or transmitted), and the
Diagnostics row appears under "Problem reports" **with no crash row above it** — the pairing a
`lastCrash`-keyed section header would have hidden — and disappears live when the reset is confirmed.

Tests: `ModelLoadPolicyTest` (the decision table, pure JVM), `ModelLoadGuardTest` (write-before-load
ordering, and that a no-asset / throwing / cancelled load all clear the marker), `SettingsStoreTest`
(per-model round-trip), `MlTextModeratorWarmUpTest` (latched ⇒ allow-all, loader untouched),
`DiagnosticsScreenContentTest` (the latch row renders **with no crash report**, the pairing that a
`lastCrash`-keyed section header would have hidden). Docs: `docs/CONTENT_MODERATION.md` §8.

## 038. LoRa range extension is a fast-plane-only `MeshTransport` child over a Meshtastic board (BLE GATT)

Status: Accepted (2026-08-24; `mesh/lora/`, `mesh/bluetooth/meshtastic/`, `BuildConfig.LORA_PLANE`)

A Meshtastic LoRa board attached over BLE extends the reach of the **Nearby room** beyond BLE/NAN range.
The board is driven as a third `CompositeMeshTransport` child (`LoraMeshTransport`, last = lowest
send-preference) that has **only a fast plane**: `neighbors` is always empty, so the reliable flood,
custody digest sync, `keyreq`, blob pulls and the `watchNeighbors` hooks never touch a ~1 kbps link;
`send`/`sendFile`/`sendDigest` are no-ops. Only `fastFanout`/`fastSend` ride it. Locked with the
maintainer: broadcast `chat` + its `reaction`, the ✓✓ delivery `receipt`, and the cleartext `profile`
(the far side must pin the author's key to verify) — nothing else (`LoraFramePolicy`). Decisions worth
not relitigating:

1. **Not a wire change — the ADR 030 argument reused.** Outbound decodes `wire.signed` only to apply the
   policy, then reuses `FastFrameCodec` to compact/fragment; `sig`/`signed` pass through byte-exact, so
   the originator's Ed25519 signature verifies unchanged at the far endpoint. Meshtastic's `Data.payload`
   cap is 233 B, so a frame splits into ≤ 3 fragments (`LoraFrameCodec`, ceiling 3 × 229 = 687 B ≈ a
   400–500-char post); a larger frame is `loraTooBig` and rides the radios/custody. The profile bootstrap
   fits ≤ 3 packets (pinned in `CoordinationPlaneSizeBudgetTest`).
2. **Hand-rolled protobuf, zero new dependencies.** `MeshtasticProto` + `ProtoIo` speak the dozen fields
   the board API needs (`ToRadio`/`FromRadio`/`MeshPacket`/`Data`/`QueueStatus`/`Routing`); vendoring a
   protobuf runtime or a codegen plugin would fight the toolchain (ADR 001/002) for no benefit. Golden
   byte vectors pin the field numbers; every decode is total (malformed → null, never a throw).
3. **The `shortRange` flag (new `MeshTransport` member, LoRa = false).** A LoRa sighting doesn't imply
   physical proximity, so `CompositeMeshTransport` excludes non-short-range children from every
   `onForeignReachable` union (else BLE scan-chases a peer kilometres away and NAN's wedge watchdog
   corroborates a Tier-2 self-kill for it) and exposes `shortRangeReachable`, which feeds
   `AttachmentDeferPolicy` (a LoRa-only sighting can't carry an image, so it must not defer a spool
   upload). `TransportKind` stays diagnostics-only.
4. **The ✓✓ tick is sealed after first contact, so the targeted path admits it.** Post-profile the author
   is ratchet-capable and `AckSync` seals every tick as a `CTL_RECEIPT` (a `relay = false` chat frame
   addressed to the author, its kdoc forbidding a cleartext downgrade), so `LoraFramePolicy`'s targeted
   path admits `receipt` **and** `chat && !relay && recipientId == to` — which does not open DMs (a real
   DM is always `relay = true` and never reaches `fastSend`).
5. **Sig-keyed dedup (first 8 B of `wire.sig`, 10 min = SeenSet TTL), recorded on send AND receive.** It
   stops a frame heard over LoRa from being re-fanned back over it (the composite re-calls `fastFanout`
   on relay), and bounds `AckSync`'s verbatim 24 h tick retries (a re-send inside the receiver's SeenSet
   window is a duplicate anyway).
6. **Key bootstrap = the existing `watchReachable` reflood + a floored self-profile beacon.** `LoraMeshTransport`
   beacons its signed profile (via `ProfileFrameSource` ← `MeshManager.sign(currentProfileEnvelope())`)
   on session-up and on first hearing a peer, under a 5-minute floor. **No periodic beacon** (N × 3
   packets × the board's 3-hop rebroadcast). The floor check is overflow-safe against a `NEVER` sentinel
   — the naive `now - Long.MIN_VALUE` wraps and would have blocked the first beacon in production too.
7. **`reachable` lingers 45 min** (no periodic cues on LoRa; a short linger would make every message a
   "newcomer" and re-trigger profile refloods). `Peer.capabilities = 0` for a LoRa sighting is harmless
   (the composite's `richer()` keeps any BLE/NAN peer; a NAN cue-only peer already looks like this).
8. **Pacing is the transport's job; the board only reports back-pressure** (`LoraPacePolicy`: 3 s min gap,
   12-frame queue dropping the oldest **whole** frame, NAK back-off widening the gap, `queueFree == 0`
   hold). The board session (`MeshtasticSession`, a pure actor over the `MeshtasticGattDialer` seam)
   handles the want_config handshake, drain-until-empty reads on FromNum, the 180 s keep-alive heartbeat,
   client-assigned packet ids for `queueStatus`/NAK correlation, and reconnect-with-backoff.
9. **Gated like ADR 031, not stripped.** `BuildConfig.LORA_PLANE` (debug true, release/staging false,
   `-PloraPlane=` override) gates the composite child, the `relays`-style settings route, and
   `SettingsStore.loraEnabled`; the classes stay in the APK (R8 prunes the `if (LORA_PLANE)` branches).
10. **Knit provisions its own channel (2026-08-24 addendum).** "Set up Knit channel" (or `…debug.LORAPROV`)
    writes the derived `KnitChannel` (name "Knit" + a 16-byte AES128 PSK) as a **secondary** channel over
    the Meshtastic **admin** API — `get_channel` for a `session_passkey`, then `begin_edit → set_channel →
    commit_edit` echoing it, into a free slot (reusing an existing same-named channel; one fresh-key retry
    on `ADMIN_BAD_SESSION_KEY`). The commit reboots the board to apply it, so the session re-handshakes.
    The PSK is HKDF-SHA256-derived from **public** constants (`"nearby"` + a domain label), so it is
    deterministic and shared but **not secret** — a **rendezvous** channel, honest for the cleartext Nearby
    room, never a confidentiality boundary. Written SECONDARY so the board's primary/radio config (region,
    modem preset) is untouched. Pinned by `KnitChannelTest` (the derivation — changing it strands
    already-provisioned boards) + `MeshtasticProtoTest` (the admin wire). A confidential per-deployment PSK
    (shared out-of-band via a channel QR/URL) is deferred.

Import boundary honoured: `mesh/lora/` is pure/Android-free and JVM-tested end-to-end (a `FakeMeshtasticAir`
floods bytes between two fake boards); the only `android.bluetooth.*` importer is
`mesh/bluetooth/meshtastic/MeshtasticGatt` (device-verified only). A `BleConnectArbiter` lets the board
dial pause the mesh BLE scan for its connect window, since scanning starves connects.

Honest residuals (accepted for the MVP): one board per BLE clique (two would each re-transmit every
locally-seen frame — the board dedups `(from,id)`, so that doubles airtime); a Nearby-only LoRa peer
appears in the contact picker but a DM to it strands in custody until radio/spool contact; and a sealed
tick over LoRa establishes a ratchet session with a far author over a plane that can't carry the DMs it
enables (harmless). Deferred: a user-set/shared **private** PSK (the shipped channel is a public
rendezvous), DM-over-LoRa, a periodic beacon, and multi-board dedup. Scheme + device bring-up:
`context/lora-bridge.md`. *Addendum (2026-08-24): DM-over-LoRa shipped as ADR 039, which also relaxes
point 6 (a 60-s first-hearing beacon gap) and makes point 8's "profile never dropped" true.*

## 039. Sealed DMs ride the LoRa plane through a long-range fan-out seam, re-offered on first hearing

Status: Accepted (2026-08-24; `MeshTransport.longRangeFanout`, `mesh/lora/`, `FarPeerFrameSource`,
`SettingsStore.loraDmEnabled`)

ADR 038 shipped the LoRa bridge carrying only the cleartext Nearby room and deferred DMs: a Nearby-only LoRa
peer showed up in the contact picker, and a DM to it stranded in custody until radio or spool contact. This
lands 1:1 DMs on the plane. A DM is a `chat` frame with a recipient and no group, sealed under the v2
ratchet, and it was refused in exactly two places — the transport-agnostic `shouldFastFanout` (which excludes
DM-form chat from *every* fast plane, kept that way by ADR 030) and `LoraFramePolicy`. Everything else already
fit: the epoch ratchet is loss-tolerant by design (independent epochs, ≤ 200 skipped keys per epoch), X3DH
needs only the pinned profile the beacon already carries, the ✓✓ is itself a sealed DM-form frame, and a
100-char DM compacts to 387 B (2 LoRa packets; 439 B with the X3DH init that rides every frame until the
first reply — pinned in `CoordinationPlaneSizeBudgetTest`; the 3-packet ceiling is ≈ 400 characters).
Decisions worth not relitigating:

1. **A separate seam, not a relaxed `shouldFastFanout`.** `MeshTransport.longRangeFanout` is a defaulted
   no-op honoured only by a plane with **no data path at all** (`shortRange = false`, `neighbors` always
   empty), for which it is the only path a frame can take. `CompositeMeshTransport` forwards it to every child
   with **no** `send(wire, null)` fallback (the router's flood already carries a DM over a link child's links —
   `fastFanout`'s fallback would double-flood every DM over BLE). `shouldLongRangeFanout` admits exactly the
   DM-form chat `shouldFastFanout` excludes, and both call sites (`originateSigned`, `onDeliver`) call it
   beside the old predicate. ADR 030's "relax `shouldFastFanout` to a size probe" one-liner was rejected on
   purpose: it would route DMs through Wi-Fi Aware's `emitFastWire` to every cue-only peer too, a NAN
   airtime change nobody asked for. The NAN and BLE planes are byte-for-byte unchanged.
2. **Broadcast at the Meshtastic layer, still** (`to = 0xFFFFFFFF`). The recipient may be board-less behind
   another board-holder — the far phone's router relays a broadcast over its BLE/NAN clique, which a
   Meshtastic unicast would not reach — no nodeNum↔nodeId map exists, and `want_ack` would hit the session's
   Routing `error_reason == NONE`-treated-as-NAK path. Unicast + link-layer acks stay a later optimization.
3. **The DM form is admitted opaque — all of it.** A DM, its sealed `CTL_RECEIPT`/`CTL_REACTION`, a session
   reset, a group-key seed/req/ack and an escalated group tick are wire-indistinguishable (ADR 016/018), so
   every one rides and none is singled out; the transport cannot tell them apart and must not try. What stays
   out is group-*form* chat (`group != null`) and the cleartext `groupupdate`/`groupleave` frames — the plane
   carries no group conversation, so those would only burn airtime. The delivery receipt therefore crosses for
   free: `InboundPipeline.acknowledge` originates it `relay = true`, and it re-runs on every re-delivery via
   the pre-decrypt exists-gate, which is how a tick lost over LoRa heals when the DM is re-offered.
4. **The targeted path stays strict; the re-offer is a private path.** `LoraFramePolicy`'s TARGETED rule is
   unchanged (`receipt`, or `chat && !relay && recipientId == to` — AckSync's sealed tick), so no `fastSend`
   caller (`AckSync`, `sendTyping`) gains a new frame class; `LoraFramePolicyTest` still pins that a
   `relay = true` DM never rides it. The re-offer enqueues through its own path inside `LoraMeshTransport`
   (decode → DM-form to the peer → sig-dedup → class DM).
5. **The queue sheds by class** (`FrameClass`: BOOTSTRAP > DM > ROOM). When full, `LoraPacePolicy` evicts
   the oldest whole frame of the lowest class present — the newcomer included, so a room post alone at the
   bottom is `REFUSED` rather than evicting a DM, and nothing ever evicts the profile bootstrap (038's
   "profile is never dropped" was only ever a comment over a label-blind FIFO). Dequeue stays FIFO.
6. **A freshness gate on the fan-out paths, the room included.** A `chat`/`reaction` whose `sentAt` is more
   than 15 min old is a custody re-serve (the router's SeenSet lapses at 10 min, so a fresh flood never looks
   this old) and stays custody's business — without it a newcomer's whole backfill re-fanned over the air,
   twelve frames at a time. Profiles (their `sentAt` is the publish stamp, up to 12 h old) and receipts are
   exempt, as are the targeted path (AckSync's verbatim 24 h retries) and the re-offer. The gate reads an
   injected wall clock: the transport's own `clock` is `elapsedRealtime` (pacing, dedup, linger) and is not
   comparable to a frame's epoch `sentAt`. A peer whose clock lags by more than the window keeps its fresh
   frames off LoRa only — there is no past-side skew clamp anywhere, and none is added.
7. **A bounded, sender-driven re-offer instead of custody sync.** The plane still has no `neighbors`, so
   `ForwardSync`'s digest exchange never runs over it. Instead, on first hearing a peer (once per 45-min
   linger window), after the beacon, the transport pulls `FarPeerFrameSource.framesFor(peer)` — `MeshManager`
   answers with the newest 4 live custody frames addressed to it (`ForwardStore.liveFramesTo`, an indexed
   query with a default over `liveFrames` so the fakes need nothing), minus our own frames the peer already
   acked (`MessageDao.unackedDmsTo`; an own frame with no unacked row is either delivered or a sealed ctl) —
   re-wrapped verbatim like a custody re-serve and enqueued class DM. A peer another plane carries
   (`foreignReachable`) is skipped: custody syncs to it for real there. ≤ 4 frames × ≤ 3 packets per
   sighting; a re-offer that lands after the receiver's SeenSet lapsed hits the exists-gate and re-draws the
   receipt (one chain key, bounded). Best-effort by construction: a DM outside the newest four, or one that
   missed both the live flood and a sighting, still waits for radio or spool contact.
8. **The first-hearing beacon needs a 60-s gap** (a relaxation of 038 §6; session-up keeps the 5-min floor,
   one timestamp, two gaps). A peer that just appeared has demonstrably never heard us, and without a periodic
   beacon this is the only way a late arrival learns our key: A beaconed two minutes ago, B just came up — A
   must speak again or B's parked frames (`PendingInbound`, 2 min) expire, and with them B's first DM.
9. **Metadata exposure is the price; a default-on toggle is the control.** Content stays end-to-end sealed,
   but a DM's cleartext `senderId`/`recipientId`, timing and size now travel on a public-PSK rendezvous
   channel at kilometre range, where the radios exposed them at ~50 m. `SettingsStore.loraDmEnabled`
   (default on, gated on `BuildConfig.LORA_PLANE`) rides into `LoraConfig.dms` and is applied inside the
   transport — the fan-out and the re-offer refuse DM-form when it is off while the room keeps riding —
   so `MeshManager`/`InboundPipeline` stay plane-agnostic and a `longRangeFanout` call is a cheap no-op.
   Each side gates its own sends. The confidentiality fix for the metadata remains the deferred private PSK.
10. **Not a wire change; custody untouched.** `sig`/`signed` still pass through `FastFrameCodec` byte-exact;
    no new frame type, field, ctl code or capability bit. Nothing new is stored and no custody rule changes,
    so the content digest's inputs are identical on every node as before (ADR 006).

Counters: `loraDmSent`/`loraDmReceived` (DM-form, sealed ctl included — the transport cannot tell),
`loraReoffered`, and `loraSuppressed` now actually counts (dedup-window and stale suppressions).

Honest residuals (accepted): the re-offer targets only the peer that was heard — a board-less recipient
behind another board-holder gets live DMs via that phone's relay but no re-offer (no routing table; the
"true DM routing" deferral); a peer that only listens never triggers a re-offer or a beacon exchange (the
periodic beacon stays deferred); after a session reset custody keeps the first-stored ciphertext
(`ForwardSync.onSeen` early-returns on `has(id)`), so a re-offer can serve bytes a wiped peer cannot open
until the fresh seal reaches it another way — airtime, not correctness; ~400-char steady / ~335-char
first-message ceiling, and a DM with an image arrives as text plus a loading placeholder until a radio or
spool path exists (`blobreq` never rides LoRa); sealed group machinery crosses opaquely although group chat
does not; and airtime is roughly SMS pace — ~2 packets per DM plus ~2 per receipt at ~2.5 s each, ~1–2
DMs/min sustained under the EU 868 duty cycle (the board's DUTY_CYCLE NAK already backs the pacer off).
Scheme + device bring-up: `context/lora-bridge.md`.

## 040. The LoRa plane gets a face: an arrival plane per message, a header glyph, a board-only picker

Status: Accepted (2026-08-25; `InboundFrame.kind`, `DeliveryPlane.LoRa`, `mesh/lora/LoraPlane.kt`,
`LoraStatusRepository`, `BoardFilter`, `ui/chat/LoraReach.kt`, `LoraSizeHint`)

ADR 038/039 shipped a plane the app could not see: a message that crossed the board was stored as
`DeliveryPlane.Nearby`, the connection header had no LoRa glyph where the Internet plane has its cloud, and
the LoRa screen offered every bonded Bluetooth device as a board. This lands the presentation layer by
mirroring the Internet plane's existing shapes rather than inventing new ones. Decisions worth not
relitigating:

1. **`InboundFrame.kind` reverses 038 §3's "`TransportKind` is diagnostics-only" — for presentation only.**
   `CompositeMeshTransport` stamps each child's kind on the frames it merges (the one place that knows;
   `FramedLink` is shared by BLE and NAN and cannot), the router hands it to `InboundPipeline.onDeliver`, and
   `planeOf(fromNodeId, kind)` maps a board frame to the new `DeliveryPlane.LoRa` (code 5). The phone radios
   still collapse to `Nearby` on purpose (the UI has nothing different to say about them). ADR 019's rule
   stands: carry, relay and convergence never read the kind; `MeshRouter` only forwards it. The plane is never
   encoded into `fromNodeId` — that feeds split horizon and reply addressing. `PendingInbound` keeps the kind
   through the key-bootstrap replay, which LoRa relies on (a DM heard before the sender's beacon).
2. **An inbound row is first-write-wins (`MessageDao.insertIfAbsent`), not an upsert.** The plaintext room
   path bypasses the exists-gate and re-ran `deliverChat`'s upsert on every custody re-serve; its comment argued
   the plane it rewrote was always the same "because the room never crosses the Internet" — false once LoRa is
   a plane, since the room is exactly what LoRa carries. A re-served frame is identical signed bytes and can
   never carry anything new, while the upsert also wiped the voice-note metadata `setVoiceMeta` adds after the
   insert and, for our own room post looping back after the SeenSet lapsed, reset its ✓✓ to ✓. Blob re-pull and
   the typing clear still run on a re-serve (they sit after the persist). The v2 hooks stay on `save` (they are
   exists-gated and commit with the ratchet delta).
3. **The header glyph mirrors the cloud exactly.** `LoraPlane { Off, Down, Live }` is the board's `RelayPlane`,
   folded by a *pushed* `LoraStatusRepository` (the transport's status is already a `StateFlow`; no ticker) into
   `LoraFacts(plane, dms)` — one injected flow, not two, because a second never-emitting relaxed mock stalls
   every ViewModel test. `Icons.Outlined.Sensors`/`SensorsOff` (the only radio glyph with an off variant, needed
   for the colour-blind-safe Down state) sit after the cloud; the label is never rewritten — a board needs this
   phone's Bluetooth, so "radios off but LoRa live" cannot happen, and a LoRa-heard peer already counts in the
   mesh line. The Down edge waits 45 s, not the cloud's 12: the session reconnects on a 5 s-and-up backoff and a
   Knit-channel write reboots the board on purpose.
4. **The transport's UI face is a seam (`LoraPlaneStatus`), bound to the transport only when the build ships
   the plane and to a dark stand-in otherwise.** `MeshModule` promises release never instantiates the
   GATT/session singletons; a repository resolved by every open chat would have, through `get<LoraMeshTransport>()`.
5. **The picker's board verdict is a heuristic with an escape hatch, never a filter that drops.** A device is
   board-like when LE-capable and named `Meshtastic_xxxx`, or `<short>_xxxx` (a renamed board — the firmware
   keeps the four MAC hex digits), or carrying the Meshtastic service UUID in the stack's cache (positive-only;
   the cache is empty for most LE bonds). `BoardFilter` shows those plus the bound board; the rest are counted
   behind "Show all paired devices". The bonded list is a Binder call, read on its own arm (resume, the toggle,
   a bound-address change) — never on link churn, which it was.
6. **A connected board earns a channel verdict.** The selected slot's *name* is shown ("Channel 1 · Knit") and
   a slot that is not `KnitChannel.NAME` — index 0, the unnamed primary, included — is flagged with the
   "Set up Knit channel" button emphasized: both boards must be provisioned before a frame crosses, and this
   was the setup step most people still owed.
7. **A DM whose peer only the board has heard gets a pinned notice, like the relay notice.** `LoraReach`
   reads `peerTransports[peer] == {LoRa}` with the plane live and the thread not relay-covered (a covered DM
   has a better carrier); a `LoraOnlyDmsOff` variant says nothing reaches them while the switch is off. The
   copy says "last heard" — the plane's reachable set lingers 45 min. The room and groups never render it.
8. **The composer's "long message" hint is sized by body budgets pinned against real frames.**
   `LoraSizeHint` (room 400 B, session-initial DM 320 B, minus 260 B for a quoted reply and 170 B for an
   attachment reference) sits below the true ceilings, and `CoordinationPlaneSizeBudgetTest` builds frames at
   exactly those sizes — deflate-hostile bodies, the largest reply, an attachment ref — and checks they fit in
   ≤ 3 packets, so the hint can under-warn but never over-promise. Shown only when the draft would ride LoRa
   (`LoraCarry`: the room, or a DM with private messages over LoRa on; never a group), read off the draft
   in the composer via `derivedStateOf` so it recomposes on threshold crossings, not per keystroke.

Not a wire change, no migration (`receivedVia` existed since DB v4; a new code), custody untouched; old
builds read code 5 as `Unknown` → "arrived nearby". Deferred, still: an in-app scan + bond flow
(`MeshtasticScanner`/`MeshtasticBonder` are written but unwired — device-only verifiable, and the scan must go
through `BleConnectArbiter`), and a per-message marker for a post that was `loraTooBig` (no persisted
evidence; the composer hint covers the sending side). Surfaces + tags: `context/lora-bridge.md`.

## 041. The board's battery is read off the handshake and its per-minute telemetry, never polled

**Context.** On a bench a Meshtastic board is USB-powered; in the field it runs on a cell the phone can't
see, and nothing in ADR 040's face said when the board was about to go dark. The firmware already tells the
phone: the config handshake streams `FromRadio.node_info` for every NodeDB entry, the board's own first, with
`device_metrics { battery_level, voltage, … }`, and `DeviceTelemetryModule` sends the phone (and only the
phone) a fresh `Telemetry.device_metrics` on `PortNum.TELEMETRY_APP` about once a minute while connected.
The bridge decoded neither: `node_info` fell into `FromRadio.Other`, and the telemetry packet — addressed
*from* the board's own node — died on the self-echo guard in `MeshtasticSession.onPacket`.

**Decision.**

1. **Decode only what the reading needs.** `MeshtasticProto` gains `FromRadio.NodeInfo(num, metrics)` and
   `decodeTelemetry` → `DeviceMetrics(batteryLevel, voltage)`; every other `NodeInfo`/`Telemetry` field is
   skipped and the other telemetry variants (environment, power, …) decode to null. Golden vectors pin the
   field numbers (node_info 4 / device_metrics 6; Telemetry.device_metrics 2 / battery_level 1 / voltage 2)
   like every other message the bridge speaks.
2. **Only the board's own entry counts.** The session reads a `NodeInfo` whose `num` is `my_info`'s and a
   TELEMETRY packet whose `from` is — in the handshake path and in the session drain — and surfaces neither
   as an inbound packet. A neighbour's telemetry stays what it was: a foreign-port packet the transport
   ignores.
3. **A `StateFlow<BoardBattery?>` beside `rxQuality`, not in `LinkState`.** A once-a-minute reading must not
   churn `Ready` (which re-derives `maxPayload`, counts a session-up and re-beacons the profile). Cleared on
   `handshake()` and `stop()`, so a reading never outlives its board.
4. **The firmware's conventions are folded once, in `BoardBattery.of`.** `battery_level` 0–100 is a charge;
   above 100 means external power (`percent = null, powered = true`); absent, negative (the int8 "unknown"
   cast through a uint32), or 0 with no voltage is *no reading* — a board without battery sense, not an empty
   cell (which still shows a voltage). `low` is ≤ 20 % on battery.
5. **Shown where the board is, plus the glance.** The LoRa radio screen's status row reads "Battery 78% ·
   3.92 V" / "Plugged in · 4.10 V" (`lora_battery`, error-coloured when low) under the firmware line; the
   Profile row appends "· battery 78%" / "· plugged in" while the link is live. `LoraFacts.battery` carries
   it for the Profile row only, is null unless the plane is `Live`, and is never a reach input. The header
   glyph is unchanged — a low-battery badge there is the obvious follow-up once the reading has been seen on
   hardware.

Not a wire change; no setting, no persistence — the reading is at most a minute stale and evaporates with
the link. No poll: the firmware pushes on its own schedule, so Knit adds no GATT traffic to get it. Surfaces +
tags: `context/lora-bridge.md`.

## 042. Contacts at a distance: a signed contact card, the `CTL_PROFILE` intro, and an identity-derived pair scope

Status: Accepted (2026-08-25; `mesh/crypto/ContactCard`, `contacts/`, `mesh/IntroSync`,
`ScopeCrypto.pairSecret`/`pairScopeId`, `ScopeRegistry.pairs`, `ui/addcontact/` — no mesh-wire change, no DB
migration, `knit-spool` untouched)

Two people far apart — reachable only over the Internet plane, or across a LoRa hop — could not become
contacts: the only message-less pin was the QR scan (co-presence), a DM scope needs a *confirmed* session,
a session needs the peer's prekey, and the prekey travels only on a `profile` frame inside a scope the
pair already shares. `docs/CONTACT_CARD.md` is the scheme; `docs/SPOOL_PROTOCOL.md` §3.5 the rendezvous.

Three pieces, and what each deliberately is *not*:

1. **The contact card is the QR payload as a signed link, not a token.** `{v, id, pk(64 B), name?, sp?,
   iat}` under an Ed25519 signature over the opaque body (`"knit/card/v1" ‖ body`, the `WireEnvelope`
   discipline — never re-encoded to verify). Self-certifying like a profile. Shared via the share sheet or
   the clipboard as `https://getknit.app/c#…` (the fragment never reaches the server) and `knit://c/…`;
   the legacy `knit-id:v1` QR string parses too, and the QR composer keeps emitting it so older scanners
   are not broken. **Import pins + accepts but never verifies** (Briar's posture): the channel is
   unauthenticated and the name attacker-chosen, so the safety number stays the verification. Relay hints
   (`sp`) are displayed, never applied — adding a relay hands it every scope id and IP, a phishing vector
   for a hostile card. Tokened `?k=` URLs never leave the minter. A mutual exchange is the product: each
   person shares theirs and imports the other's; the OOB channel is two-way already.
2. **The handshake is the existing sealed `CTL_PROFILE` DM (ADR 020), not a new ctl.** `sendProfileDm`
   to a peer with no session makes `ratchet.sealDm` run the X3DH initiation off the card-pinned prekey;
   the init rides every copy until confirmed; every deployed build reads the frame; a stale or version-0
   payload is the receiver's ordinary no-op. Accept and verify never enter the mechanism — ADR 009/032
   already made the scope follow the session, not acceptance, and the session confirms with or without a
   request UI. `IntroSync` (pure, `KeyExchange`-shaped) owns the *when*: send as soon as the peer's pinned
   profile carries `CAP_RATCHET` + a prekey from any plane; re-send every 20 h while unconfirmed (under the
   24 h custody TTL); answer a frame whose header still carries the init — proof its sender is
   unconfirmed — once per hour, which also cures the pre-existing wedge where a wiped initiator stayed
   unconfirmed until the responder happened to edit its profile (`broadcastSealedProfile` dedups per
   version). State is two settings-store sets (ADR 028/037's posture for ids-not-rows); ≤ 8 pending.
3. **The rendezvous is a pair scope derived from the two identity keys, not a random invite token.**
   `pairSecret = X25519(IK_self, IK_peer)` (the `hpkePub` half of each bundle — a static-static agreement
   used nowhere else; X3DH has no identity-identity term, HPKE pairs the identity key with an ephemeral)
   → `HKDF(…, "knit/scope/v1/pair/id" ‖ dmContext)` and the shared seal label. Computable by exactly the
   two parties; a spool, a node-id holder or a card holder cannot. It is an ordinary DM-form `Scope`
   (`peerId` set), so `ScopeFrames.eligibleForDm`, the push half, the attachment pass and the relay
   indicator need no change, and — because a party cannot derive it before pinning the peer — every
   pulled frame passes `canCarry`. Subscribed only while the intro is pending plus a 48 h **grace** after
   our own confirmation (the responder's answer must still reach a peer that holds no DM scope yet).

**Why not the invite-token design the brief started from.** A token-derived scope any link holder can
compute has four costs the skeptic review made concrete: the owner's *whole* DM set would seal into it
under an owner-endpoint frame rule (any link holder reads the owner's correspondent graph); a chat blob
from a not-yet-pinned requester is quarantined forever by `ScopeSync.accept` (`canCarry` fails on the
unpinned sender, the `accepted` slot is never released, `processed` skips it) and needs a defer-not-
quarantine rewrite of a delicate path; token state, expiry-in-flight deadlocks and reinstall loss; and a
labelling leak (link ↔ scope ↔ connection ↔ every other scope). The pair scope has none of them. What it
costs instead, recorded in §10.3: the "identity file only → no scope key" claim narrows to *conversation*
scopes — a stolen identity file plus the peer's public bundle yields this one scope's id and outer seal,
i.e. the routing metadata of bootstrap-era frames while subscribed, never content, never a DM or group
scope — and the id is stable per pair (bounded by the subscription window).

**What the mesh sees.** Nothing new: a `CTL_PROFILE` is wire-indistinguishable from any sealed DM, it
floods, custodies and rides LoRa's DM-form path (ADR 039); a LoRa listen-only peer is reached once its
beacon pins the profile. `GoldenVectorTest`, `SpoolRecordsTest`, `KnitDatabaseMigrationTest` are untouched;
`ScopeVectorTest` gained four appended rows; `ContactCardTest` pins the card's golden bytes.

**Deferred, with reasons** (roadmap): the one-sided invite (a *profile-only* token scope + a contact-request
inbox — safe only with per-token caps, revoke, expiry, and the observability caveat); a prekey in the card
gated on `iat < 7 d` (lets the importer seal at once and reach a LoRa listen-only peer; a stale one wedges
silently at `EPOCH_GONE`); node-id-only import over the radios via `KeyExchange.want`; session recovery
over the pair scope for existing contacts (no `unsub` record, `maxScopes` pressure). Out of repo:
`getknit.app/.well-known/assetlinks.json` with **both** signing certificates and a `/c` landing page
building the `knit://` link client-side — until then Android 12+ opens the https link in the browser.

## 043. A refused foreground start retires the service instead of crashing, and the wedge cure asks first

Play reported `ForegroundServiceStartNotAllowedException` out of `MeshService.onCreate` on Android 15
(Galaxy A14 5G, v2.2.3, still reproducible at HEAD — `5da5601` fixed the *other* one, the 10 s
`ForegroundServiceDidNotStartInTimeException`). The tell is **where** it threw: at `Service.startForeground`
inside `handleCreateService`, not at the `startForegroundService` call site, which is where a blocked
`MeshService.start` would have thrown. So the *creation* was allowed and only the foreground *promotion*
was refused — the signature of a `START_STICKY` restart. Android 12+ lets a backgrounded app claim the
foreground only under a listed exemption, and a system-initiated sticky restart is not one of them. Any
process death off screen — low memory, an OEM app-sleep sweep, or our own Tier-2 wedge cure — therefore
came back to a guaranteed throw out of `onCreate`, which is a guaranteed process kill.

**Why it isn't everyone.** The battery-optimization exemption *is* on the list, and Knit offers it on the
onboarding permission screen. Opt-in, so it splits the install base: users who granted it never see this,
users who didn't crash on every background restart. That is also why the fix cannot be "assume the
exemption" — the unexempted case is the common one.

**The service declines rather than crashes.** `postForeground` returns a `Boolean` and catches
`IllegalStateException` — `ForegroundServiceStartNotAllowedException` extends it, so the catch needs no
`Build.VERSION` dance and no API-31 class reference on a minSdk-29 file. A refused claim makes the instance
a **stillbirth**: `onCreate` calls `stopSelf()` and returns before resolving a single injected field, and
`onStartCommand`/`onDestroy` bail on the same flag — `onDestroy` especially, because `powerMonitor` and
`meshManager` are `by inject()` and touching them there would build the exact Koin graph the early return
exists to skip. `onDestroy` still cancels the heartbeat alarm (no graph needed) so an alarm left armed by an
earlier ungraceful death stops waking the device every 15 minutes for a start the system will refuse anyway.

**`stopSelf()`, not `START_NOT_STICKY`.** Dropping stickiness would fix the crash and cost the mesh every
recovery it currently gets — including the Tier-2 cure, which is *built* on it. `stopSelf()` in `onCreate`
clears the sticky restart record for this instance only, so the system stops retrying a start that cannot
succeed while `START_STICKY` keeps meaning what it means everywhere else. `meshEnabled` is deliberately
left true: recovery is the next foreground app open (`KnitApp`'s `LaunchedEffect`) or the next reboot
(`BootReceiver`, which *is* exempt — `connectedDevice` is boot-permitted through Android 16), both of which
already start the mesh with no new machinery.

**The wedge cure now asks whether it can come back.** `WifiAwareTransport.checkWedge`'s Tier 2 kills the
process to clear a leaked NAN request, relying on `START_STICKY` in its own KDoc. Against an unexempted
backgrounded app that trade was: a wedged data plane in, no mesh at all out, plus a crash. It is now gated
on `canReclaimForegroundService` (`mesh/MeshService.kt`, a top-level function beside `shouldStartMeshOnBoot`
so the transports need no dependency on the service class), which checks the two exemptions we can read
cheaply — a visible activity via `ActivityManager.getMyMemoryState` (`IMPORTANCE_FOREGROUND`; the service
alone only reaches the weaker `IMPORTANCE_FOREGROUND_SERVICE`, so it can't self-satisfy) and the
battery-optimization exemption. **The gate lives in the transport, not in `NanWatchdogPolicy`**: the policy
is the pure episode clock and stays untouched, the transport owns the side effects, and a binder call per
30 s watchdog tick becomes a binder call only when a kill is actually on the table. Declining leaves the
episode clock running and `lastRestartAt` unstamped, so the cure fires on the next check once the app is
foreground or exempt and the wedge has persisted; Tier 1's session cycle keeps retrying at its own cooldown
throughout, so nothing is lost in the meantime.

**Not covered, deliberately.** The 15-minute heartbeat is `setInexactRepeating` + `PendingIntent.getService`,
and only *exact* alarms carry an FGS-start exemption — so after an ungraceful death that alarm cannot revive
the service either; the system blocks the background `startService` silently. Left as is: the alarm's job is
to nudge a *running* service, moving it to an exact alarm would need `SCHEDULE_EXACT_ALARM` for a
best-effort wakeup, and the two real recovery paths above already cover the case.

*Amendment (2026-08-26, work item #32 — the caller side).* The above hardens the *promotion*; the *request*
was still bare. `Context.startForegroundService` throws `ForegroundServiceStartNotAllowedException` at the
**call site**, before the service is ever created, so `postForeground`'s catch sits downstream of that throw
and can never see it — and `KnitApp`'s effect is keyed on the nav destination, so it re-fires on every
navigation and can be scheduled while foreground yet land after a task switch, a screen-off or an incoming
call has taken the foreground away. `MeshService.start` now returns a `Boolean` and carries **both** guards,
neither redundant: `canReclaimForegroundService` declines the starts we can predict will be refused, and a
`catch (IllegalStateException)` closes the check-to-binder race that *is* the bug. `BootReceiver` keeps
ignoring the result — `ACTION_BOOT_COMPLETED` is a listed exemption.

**A refusal is deferred, not dropped.** A bare `runCatching` would stop the crash and leave a messenger with
no transport behind a "searching" notification that never resolves — the loud failure traded for a quiet one.
The retry is the `ON_RESUME` observer that already calls `meshManager.heal()`, where the foreground state is
guaranteed, and it is **unconditional** rather than gated on the pending flag. Unconditional because the flag
is not the only way the mesh can be down without the composition knowing: the route-keyed effect above only
re-fires on a *navigation*, so any live composition that comes back to a dead service — a stillbirth
`stopSelf`'d into a process the Activity kept alive, an OEM sweep that took the service and not the process —
waits for a screen change the user may never make. (This is defence in depth, not a reproduced bug: a sticky
restart that dies in `onCreate` normally takes a fresh process, so the *next* open is a cold start and the
effect covers it.) Starting an already-running service is an idempotent null-action `onStartCommand`, so the
redundant case costs one binder call per resume — next to the `heal()` on the same line, nothing.

**`MeshStartGate` is observability, not control flow.** A Koin single (`mesh/MeshStartGate.kt`) holding one
`StateFlow<Boolean>`, recorded by the *callers* so `MeshService.start` stays graph-free, and surfaced as
`meshStartDeferred` in the debug bridge's `…debug.STATE` reply. Without it a dead mesh is indistinguishable
from a live one with no peers: `transportHealth` sits at its default and `MeshManager.heal()` no-ops on its
own `started` flag. Recovery does not depend on the flag being right — the retry above is unconditional —
so it can only ever be a diagnostic. No Diagnostics-screen row: the refusal is transient by construction
(it clears on the very next resume), so a row would be unobservable in practice.

## 044. Pockets bridge over LoRa: an elected gateway, an airtime budget, and a gossiped custody window

Status: Accepted (2026-08-25; `mesh/lora/` `LoraCtl`/`LoraGatewayPolicy`/`LoraGossipPolicy`/`LoraAirtime`,
`mesh/BridgeFrameSource`, `SettingsStore.loraBridgeEnabled`)

Two groups of users, each meshed over BLE/NAN, too far apart to reach each other, one board-holder in each,
the boards in LoRa range. **Half of this already worked and was not rebuilt**: `InboundPipeline.onDeliver`
re-fans every first-seen *relayed* frame onto `fastFanout`/`longRangeFanout`, and `LoraMeshTransport.fanout`
has no authorship check, so a room post or DM authored anywhere in pocket A already crosses to pocket B and
floods it from there. What was missing was everything that is not *live*: a frame said while the far board
was off never crossed (`LoraFramePolicy.isFresh`, 15 min, refuses a custody re-serve — ADR 039 §6), the
Nearby room had no backfill at all, nothing measured airtime, and two boards in one pocket each paid for
every frame (ADR 038's accepted "one board per clique" residual, which a bridge makes structural). Decisions
worth not relitigating:

1. **A LoRa-local control packet, not a wire change.** `LoraCtl` claims first byte `0x10`, beside
   `FastFrameCodec`'s `0x03`/`0x04`. `LoraMeshTransport` already dropped an unrecognised first byte as
   `FastPathDrop.UNKNOWN_TAG`, so **a build predating the tag ignores it** — the whole feature is additive by
   construction. No frame type, field, ctl code, capability bit or DB migration; `sig`/`signed` still cross
   byte-exact. The ADR 030/038 argument, reused a third time.
2. **The OFFER announces a *window*, by 4-byte id prefixes.** One packet carries ≤ 48 prefixes (top 32 bits
   of `StoreDigest.hash64(frame.id)` — the mesh's existing id hash, not a new one), so a far gateway computes
   what we lack and serves exactly that: no request round trip, no blind re-transmission. **Never
   fragmented** — a control packet that needs reassembly to be useful is worse than a shorter one, so it
   truncates. A prefix collision (~1 in 4·10⁹) skips one frame for a round; nothing here is a trust boundary,
   every frame still carries the originator's signature.
3. **The election costs no airtime, because the composite already tells us who holds a live link.**
   `suppressDataPath` hands the LoRa child the higher-preference planes' `neighbors` — "who in my pocket can be
   handed my traffic" — and anything that publishes an OFFER has a board by definition. A publisher we are
   linked to is a co-pocket rival, one we are not is the bridge peer and is **never** suppressed. Lowest
   publisher key wins (the 64-bit id hash — all the packet has room for, and uniformly distributed, so no node
   is structurally favoured). PASSIVE suppresses the **floodable** paths — fan-out, beacon, offer, backfill —
   and nothing else, so a spare board keeps feeding its pocket. Recovery runs on a lost link, on an OFFER, and
   on the 60-s sweep, because both event sources can fall silent together and being wrongly passive is total
   silence.

   *Amendment (2026-08-25, field).* This first shipped keyed on `onForeignReachable`, and that was wrong:
   `reachable` is a **sighting**, not a data path — BLE publishes presence adverts far beyond L2CAP range,
   Wi-Fi Aware keeps a 150-s ghost, and the member's own kdoc says "not necessarily linked here". Two Pixels
   across a field sighted each other without ever linking; the higher-keyed one stood down and went completely
   silent — no room posts, no DMs, no ✓✓ — with nothing carrying its traffic. Standing down is only ever safe
   toward a board our frames can actually reach. Two rules fall out and are pinned by
   `LoraGatewayPolicyTest`/`LoraBridgeTest`: elect on **links**, and **never gate `fastSend`** — a
   `relay = false` targeted send is owed by exactly one node and never flooded, so no co-pocket gateway holds a
   copy to relay *or* to duplicate, and suppressing it only stranded AckSync's ticks for their full 24 h of
   retries.

   The same audit found ADR 039's own two `foreignReachable` guards making the identical mistake, and the
   `fastSend` one would have kept the field test's receipts stranded even after the role was fixed. "Another
   plane already carries this peer's traffic" and "custody syncs to it for real there" both describe a **data
   path**: `ForwardSync`'s digest exchange runs off `neighbors`, and a sighting never triggers it. Both now
   read the link set. `foreignReachable` is kept, recorded and surfaced as `pocketSightings` — nothing routes
   on it, and it sits beside `pocketLinks` precisely so the gap between heard and linked is visible. That gap
   being invisible is how this survived review, so `…debug.LORA` now reports `role` with both its inputs.
4. **Airtime is measured, not inferred from a refusal.** `LoraAirtime` computes time-on-air from the LoRa
   formula at the board's own modem preset, keeps a rolling hour, and holds two budgets from one allowance:
   `min(the region's duty cycle, a 10 % politeness ceiling) x 0.5`. LIVE may spend all of it; BRIDGE — gossip
   plus backfill plus the ADR 039 re-offer, which stops being unmetered — is capped at 30 %, so a busy bridge
   degrades into serving less history rather than into delaying somebody's message. `FrameClass.BOOTSTRAP` is
   always admitted and merely recorded — refusing the profile costs more air than it saves, since every frame
   from that peer afterwards is unverifiable. Region and preset come off the board: `FromRadio.config` →
   `Config.LoRaConfig`, decoded during the existing handshake, golden-vector pinned, total on malformed input,
   conservative 5 % fallback when absent. The firmware's own `override_duty_cycle` is honoured — the user took
   the regulatory call — but the politeness ceiling stays.
5. **Trickle, and "consistent" means the same set.** `LoraGossipPolicy` doubles 5 → 15 min while nothing
   changes, snaps to the floor on news (a new far gateway, a frame crossing either way), and transmits at a
   random point in each interval's second half. Suppression requires **set equality**, not coverage: our
   OFFER's job is to tell a far gateway what we *lack*, and a peer holding a superset has said the opposite on
   our behalf. That means a crowd of listeners is **not** free on the offer side — what bounds a crowd is the
   serving side. The ceiling is deliberately far below `STALE_MS`: an active gateway's OFFER is also its
   liveness beacon.
6. **The frame-set rule lives in `LoraFramePolicy`, once.** `Path.BACKFILL` admits exactly what `Path.FANOUT`
   does — the bridge re-serves history, so it must carry only what the live plane would have carried — and the
   difference is `isFresh`, applied on one and not the other. `MeshManager` implements `BridgeFrameSource` by
   calling that predicate rather than restating it: a rule stated twice drifts. Group-form chat stays refused
   (the cleartext roster costs ~200 B more than a DM, and the plane carries no group conversation).
7. **`ForwardStore.liveFrames` is already the eligible set.** It is TTL-bounded (6 h broadcast, 24 h
   otherwise) and quota-trimmed by the ADR 006 rules, identically on every node, so backfill needs no age gate
   of its own and can never serve an expired frame. Bytes are a verbatim re-wrap, exactly as
   `MeshManager.framesFor` already does — nothing stored, nothing re-encoded, no custody rule touched, so the
   content digest's inputs are unchanged.
8. **Four bounds on serving, and only one of them is the real one.** Per-offer limit (4), per-publisher hourly
   cap (12), the sig dedup, and the BRIDGE airtime budget. The cap exists because a hostile node can publish an
   empty OFFER repeatedly to walk a gateway through its whole custody set on the air; the budget is what
   actually bounds the cost. Serve priority is profile → DM-form → room, newest first, because a frame the far
   side cannot verify is airtime thrown away.
9. **Two loop bugs the change surfaced, both fixed.** `LoraPacePolicy.take` can now decline for a reason the
   inter-packet gap does not describe (budget spent, or the pre-existing `queueFree == 0`), which left the
   pacer computing a zero wait and spinning; its wait is now floored. The gossip loop had the same shape when
   the board was down, and now consumes its transmit slot before consulting the link.
10. **Dequeue is by class, then FIFO — a deliberate reversal of ADR 039 §5's "dequeue stays FIFO".** That was
    right while everything on this plane was something a human had just typed. Gossip and backfill arrive in
    bursts nobody is waiting for, and at a 3-second gap a burst of four puts a live message twelve seconds
    behind. The class order already states what matters most; running it on the way out costs nothing.
11. **A default-on switch, off in both directions.** `SettingsStore.loraBridgeEnabled` governs publishing
    offers and serving backfill together — a node that stops offering also stops being served, which is the
    honest reading of "don't use my board for other people's backlog". Live traffic crosses either way.

Multi-hop between pockets stays **Meshtastic's** job: a frame injected at a far gateway is blocked from
re-transmission by the sig dedup, and any second board in that pocket is PASSIVE, so a third pocket is reached
by the board's own 3-hop flood rather than by a second phone-level transmission.

**Radios and people are different numbers, and the row asks for radios.** `LoraStatus.heard` counts distinct
frame **authors** (`noteReachable(Peer(env.senderId))`) and always did — which is right for `reachable`, since
the question there is who this mesh can reach. The bridge makes it diverge sharply from the hardware, because
backfill serves frames authored by people nowhere near any radio: the field saw "3 peers heard over LoRa" with
two radios in existence. `boardsHeard` now counts distinct Meshtastic `packet.from` on our channel — control
packets and incomplete fragments included, so a board that only gossips still registers — and the settings row
leads with that, showing the author count beneath only when the two differ. Routing is untouched.

Counters: `loraOfferSent`/`loraOfferReceived`, `loraBridged`, `loraBridgeRefused`, `loraPassive`, plus the
airtime ledger on `LoraStatus`. Surfaced on the LoRa radio screen (role, radio, airtime percent) and in
`…debug.LORA` (`role`, `radio`, `airtime`, `--ez bridge`).

Honest residuals (accepted): 48 prefixes is a window, so a pocket busier than that under-reports its oldest
frames (a Bloom filter would hold ~190 and is the upgrade if it bites); backfill is unacknowledged, so a
served frame lost to the air waits for the next round; a passive gateway is dead weight for redundancy until
`STALE_MS` elapses if the active one dies without leaving `foreignReachable`; the offer side is O(peers), not
O(1); and the time-on-air figure is an estimate that does not know the board's preamble length or how many
neighbours repeated us. `KnitChannel`'s derivation is untouched, so `docs/NEXT_WIRE_BREAK.md`'s open question
about the LoRa rendezvous marker does not come due. Scheme + device bring-up: `context/lora-bridge.md`.

## 045. Board setup is one step that stays on the public frequency, quiets the board, and stops it repeating

Status: Accepted (2026-08-26; `mesh/lora/` `BoardQuiet`/`ProvisionMode`, `MeshtasticProto` admin config
codec, `spliceVarintFields`, `SettingsStore.loraBoardSetup`, the LoRa screen's setup section)

*Three revisions the same week, none shipped — worth recording, because the reasoning moved twice.* The first
cut found that ADR 038's claim was wrong: a **secondary** channel does not move the radio, because the
firmware derives its RF slot from `hash(primary channel name) % numChannels` whenever `lora.channel_num` is 0
(`RadioInterface::getChannelNum`), so every Knit board was transmitting on the stock LongFast frequency. It
"fixed" that by writing Knit as the **primary**, and kept the old secondary write beside it as a lighter
mode. The second revision deleted the lighter mode — three states for one board, and two kinds of fleet that
cannot hear each other. The third, recorded here, deleted the frequency move itself, because the *cost* had
been read backwards:

1. **Sharing the public frequency is the feature, not the bug.** The stock `rebroadcast_mode = ALL` is
   documented as "rebroadcast any observed message, if it was on our private channel **or from another mesh
   with the same lora params**". A stock Meshtastic node that cannot decrypt a single byte of Knit therefore
   repeats our packets anyway, up to its hop limit, purely because we share frequency and preset. That is
   free range through infrastructure somebody else paid for and powers — which is the entire point of the
   LoRa plane. Moving to a Knit-derived slot bought congestion relief that Knit's traffic (SMS pace, capped
   by `LoraAirtime` at half a 10 % politeness ceiling) does not need, and paid for it in the only currency
   that matters here: who can hear us. On a sparse fleet the quiet slot is loudest — a Knit board would hear
   only other Knit boards, which early on is nobody. It bought nothing in privacy either: `KnitChannel`'s PSK
   is derived from public constants, so the slot is exactly as findable as the key. So Knit goes into a free
   **secondary** slot and the board's own primary is never touched — which is also the idiomatic Meshtastic
   private-group setup rather than a novelty.
2. **The board stops repeating everyone else's traffic.** The corollary of sharing the band is that a board
   left on `ALL` spends its battery relaying every packet it hears, invisibly, on hardware tethered to a
   phone. The setup writes `rebroadcast_mode = LOCAL_ONLY` — "only rebroadcasts messages on the node's local
   primary / secondary channels" — so a board still relays Knit between pockets (ADR 044 depends on exactly
   that) and stops paying for strangers. Mildly freeloading, stated plainly in the confirmation; the
   alternative is a user's board flattening its battery for a mesh it is not part of.
3. **It is one bargain, so it is one button — and no lighter setting beside it.** The setup also stretches
   `node_info_broadcast_secs`, `position_broadcast_secs` (with smart broadcast cleared) and
   `telemetry.device_update_interval` to six hours, and writes `position_precision = 0` on the Knit channel.
   The GPS is left alone: silencing what the board broadcasts is Knit's business, powering down the user's
   hardware is not. Restoring is the only other state — it disables the Knit channel and puts every setting
   back — and it leaves **no** Knit channel behind, so the plane is switched off with it rather than fanning
   frames out over whatever channel remains. Restoring a board that carries no Knit channel is refused.
4. **Every config write is a read-modify-write, at the byte level.** `AdminModule::handleSetConfig` assigns
   the whole sub-config (`config.device = c.payload_variant.device`), so a `Config { device { … } }` built
   from scratch would reset `role`, `gps_mode` and everything else this codec does not model — including,
   ironically, the GPS setting decision 3 promises to leave alone. The board's own `get_config` reply is
   therefore the base and `spliceVarintFields` replaces only the intended fields, copying every other field
   through byte-for-byte. A read that fails aborts the whole provision **before** `begin_edit_settings`, so a
   board that will not report its config is never half-written.
5. **The settings the board had are the user's, and are recorded.** A setup returns them
   (`ProvisionResult.Provisioned.previous`) and they are persisted per board address
   (`SettingsStore.loraBoardSetup`); restoring writes those back, falling back to the firmware defaults only
   when nothing was recorded. Re-running the setup on a board that already carries the channel is a no-op
   that reports `alreadyPresent` — overwriting the record with the quieted values would destroy the only copy
   of what the board looked like before. Forgetting the board forgets the record with it.
6. **Convergence now rests on the board's primary, so a renamed one is called out.** Two Knit boards meet
   only if their primaries hash alike — automatic for stock boards (an empty primary name falls back to the
   preset's own, e.g. `LongFast`) and false for anyone who renamed theirs, which is a silent and total
   failure. The screen says so when it sees one (`LoraRadioUiState.customPrimary`, against
   `ModemPreset.defaultChannelName`); it is the one warning worth keeping on a screen we deliberately
   stripped back. The transport also refuses to transmit when the bound slot is not the Knit channel, since
   Knit's frames are cleartext and the channel they would otherwise land on is very likely the public one.

Wire: none of Knit's. This is the Meshtastic admin API only — `get_config`/`set_config` (34),
`get_module_config`/`set_module_config` (35), `ChannelSettings.module_settings` (7) — pinned by golden vectors
in `MeshtasticProtoTest` beside the rest. `WIRE_COMPAT`/`NEXT_WIRE_BREAK` are untouched.

Honest residuals (accepted): the free-repeater effect is upside, not a guarantee — only nodes on `ALL` carry
us, community routers are often deliberately `LOCAL_ONLY`/`KNOWN_ONLY`, and `CORE_PORTNUMS_ONLY` drops us
outright since Knit rides `PRIVATE_APP`; our messages are heavier than a typical Meshtastic text (2–3 packets
plus a receipt) and each is amplified by every neighbour that repeats it, which the airtime governor bounds
but does not erase; a renamed primary is warned about, not fixed; the six-hour intervals are a judgement, not
a measurement; and stretching the *mesh* telemetry interval must not silence the phone-only telemetry ADR
041's battery row reads — a separate firmware timer, and the thing the device trial has to watch. If
congestion ever genuinely bites, the instrument is the board's own measured `channel_utilization` /
`air_util_tx`, not hiding on a frequency where nobody can hear us.

## 046. Arrival time is our own clock, stamped once inbound, and never backfilled

Status: Accepted (2026-08-26; DB v7 `messages.arrivedAt`, `KnitMigrations.MIGRATION_6_7`,
`InboundPipeline.deliverChat`, `MessageDetailsViewModel`/`Screen`)

"Message info" could show everything a message row records about its journey — who sent it, the sender's
`sentAt`, the tick, the plane — and could not answer **when**. `messages` had exactly three timing-ish
columns and none of them was a local observation: `sentAt` is the author's wall clock off the wire frame,
`received` is a boolean, `receivedVia` is a plane code. So the gap between "Sam says 19:29" and "it reached
this phone at 19:34" — which on a mesh *is* the store-and-forward latency, the most interesting number the
app could show — was invisible, and it was the only piece of a message's journey nothing recorded.

One nullable `messages.arrivedAt`, stamped in `deliverChat` — the single inbound `MessageEntity` builder all
four flavors (cleartext room, v1 HPKE, v2 ratchet DM, v2 group sender-key) route through. Four properties
that fall out of *where* rather than needing enforcement:

1. **Our clock, not theirs**, for the reason `MessageReceiptEntity.notedAt` gives one table over (ADR 036):
   mesh devices have no time sync, so a peer-clock value can render an arrival earlier than the send it
   belongs to. Stamped with the injected `clock()`, not the raw `System.currentTimeMillis()` the neighbouring
   `sentAt` clamp uses — the plumbing was already there and already overridden in tests.
2. **Stamped once.** The default persist is `messages.saveIfAbsent` (`OnConflictStrategy.IGNORE`), so a
   custody re-serve no-ops. This matters most on the **cleartext room path**, which deliberately skips the
   pre-decrypt exists-gate and re-enters `deliverChat` on every re-serve: first-write-wins rests entirely on
   that IGNORE, exactly as `receivedVia`'s arrival plane already does.
3. **Never on a row we authored.** `MessageRepository.save` is shared by inbound-v2, `MeshManager.sendChat`
   and `GroupRepository.recordDeparture`, so the stamp is set at row-build time in `deliverChat` and nowhere
   in the repository. It is additionally gated on `env.senderId != me`, because one of our own room posts can
   loop back after the SeenSet lapses and (if retention took the row) be written afresh — we did not receive
   that message, we sent it. Null therefore means exactly "we did not observe this arriving".
4. **Never backfilled.** Every row on disk at upgrade keeps null, forever, the same argument MIGRATION_5_6
   makes for un-acked receipts: we never made the observation, and inventing a plausible number is worse than
   saying nothing. The UI renders the absence, not a zero.

**No wire change** — both stamps are local observations. No CBOR field, no `type`, no ctl value, no
capability bit, nothing folded into a digest; a node that never learns the value simply shows nothing.
`GoldenVectorTest`, `WireSerializationTest`, `ScopeVectorTest` and `SpoolRecordsTest` pass **unmodified**,
which is the executable proof.

**Named `arrivedAt`, not the issue's `receivedAt`.** `messages.received` is the *outbound* delivery tick, so
a `receivedAt` beside it would read as "when `received` flipped" — the opposite direction. The concept
already had a name in the codebase: `forward_store.receivedAt` is local arrival for a custody frame.

**Skew is shown, not clamped.** `sentAt` is bounded only by `Protocol.MAX_FUTURE_SKEW_MS` (5 min into the
future), so "sent 19:29, arrived 19:24" is a thing a skewed sender can produce and the screen renders it.
A details screen is where an odd clock should be visible; clamping would display a number we did not store.

**The outbound half needed no column at all.** Work item 29 originally proposed one column reused per
direction, with a first-evidence-wins `CASE` in `markReceived` to keep it idempotent. ADR 036 overtook that:
`message_receipts.notedAt` is already our clock at apply time, already `insertIfAbsent`, and already written
for DMs (`InboundPipeline.ackerFor` attributes a DM ack to its addressed recipient) — the row existed and was
simply never displayed. So a DM's "Delivered …" line is a pure read, matched on the addressed recipient so an
orphan row can't supply it. **ADR 036 rule 3 stands unchanged**: a DM still shows no per-member split, because
its single ✓✓ already names the only recipient there is. Saying *when* is not saying *who*.

Deliberately not done: no arrival time on the bubble or chat list (`compactTimeAgo` stays their job — the
exact time is what the details screen is *for*); `sentAt` remains the retention comparator, so `arrivedAt` is
display-only and no sweep behaviour moves. Tests: `KnitDatabaseMigrationTest` (6→7, both directions null),
`MessageDaoTest` (a re-serve cannot restamp), `InboundPipelineTest` (our clock not the sender's; a re-served
room post keeps its first crossing; our own looped-back post is never stamped), `MeshManagerTest` (outbound
rows stay null), `MessageDetailsViewModelTest` / `MessageDetailsScreenContentTest` (both lines, and their
absence).

## 047. Motion is a vocabulary in one file, gated on the platform's own reduce-motion setting

Knit shipped with three authored animations in the whole app (the typing indicator's knit-stitch sweep, the
chat-list skeleton's pulse, the reply-jump bubble highlight). Everything else changed between frames: a new
message appeared, a thread jumped up the list, ✓ became ✓✓ silently, the connection dot swapped colour, the
send button hard-cut between send / attach / spinner, and all eighteen navigation destinations used
navigation-compose's bare default. This ADR records how that was fixed and, more importantly, the two rules
that keep it from rotting.

**Call sites name a meaning, not a duration.** `ui/theme/Motion.kt` holds the whole vocabulary — `KnitMotion`
with `spatial`/`fastSpatial`/`effects`/`fastEffects` specs and `enterFade`/`enterPop`/`enterReveal` (plus
their exits), and `rememberPressScale`. Nothing anywhere else in the app writes a `tween`, an easing or a
duration. That is what stops the app from accumulating twelve slightly different fades.

**The specs are Material 3's own standard motion tokens, copied.** `MotionScheme` and
`MaterialTheme.motionScheme` are still `internal` in Material3 1.4.0 — the JVM symbols are public, which is
why `javap` says otherwise, but the Kotlin metadata is not — so they cannot be referenced yet. The damping
and stiffness constants in `Motion.kt` are `StandardMotionTokens` verbatim: **spatial** motion is slightly
underdamped (0.9) so a moving thing settles with a hint of follow-through, **effects** are critically damped
(1.0) so a fade or a colour change never overshoots into a value nobody asked for. The *standard* scheme, not
the expressive one, which is louder than this app wants. When `MotionScheme` goes public those four functions
are the only place that changes.

**Reduce-motion is the platform's setting, not ours.** `LocalReduceMotion` is provided by `KnitTheme` from
`Settings.Global.ANIMATOR_DURATION_SCALE` — what Android's accessibility **Remove animations** setting
zeroes — read the same permission-free way `ConnectionStatusRow` reads airplane mode, but observed with a
`ContentObserver` rather than sampled, because toggling that setting does not recreate the activity. Every
spec collapses to `snap()` and every transition to `None`. **There is deliberately no in-app toggle**: the
user has already told the system once, for every app, and a second switch in Settings would be a second
answer to the same question.

**Rule 1 — an infinite animation may only ever be composed transiently.** `rememberInfiniteTransition` on a
screen that has *settled* never lets Compose go idle, and both `SeededUiTest` (`createEmptyComposeRule`,
every `awaitTag`/`awaitText`) and the Robolectric `*ScreenContentTest`s (`createComposeRule`, default
`autoAdvance = true`) settle through Compose's idling resource — so it does not merely look wrong, it hangs
the suite until the 25 s timeout and reds the ATF audit for that screen. The two that exist get away with it
because the typing indicator only composes while someone is typing and the skeleton only while
`ChatListUiState.isLoading`. Finite animations are safe: the test clock advances them to completion. Nothing
in `.agents/` said this before, and it is the trap most likely to catch the next contributor.

**Rule 2 — when a hard swap becomes an `AnimatedContent`, hoist the `contentDescription` to the container.**
Mid-transition both the outgoing and incoming children are composed, so two labelled `Icon`s means the
delivery tick announces "Sent" and "Delivered over the Internet" at once — and on the send button, whose
`combinedClickable` merges descendants into the button node, it means the button announces two actions.
Putting the single description on the `AnimatedContent` and making the children decorative leaves exactly one
labelled node at every instant, with spoken output unchanged. This is strictly better than what was there.

Three smaller rulings worth keeping:

1. **The message list fades; it does not slide.** `Modifier.animateItem(placementSpec = null)` in the chat
   thread, because three `LaunchedEffect`s already drive `animateScrollToItem(0)` when a message or a typing
   peer arrives — a placement animation would slide rows one way while the scroll slid the viewport the
   other. The **chat list** is the opposite (`placementSpec = KnitMotion.spatial()`): `ChatListViewModel`
   sorts by `lastMessageAt`, so a thread receiving a message genuinely travels, and watching it move is the
   clearest signal in the app that something arrived.
2. **Bands that come and go use `animateContentSize` on the band, not `AnimatedVisibility` on the content.**
   The pinned relay/LoRa notices and the radio-warning banner all early-`return` when they have nothing to
   say, so an `AnimatedVisibility` would need a retained copy of the text to draw while collapsing. Animating
   the height of the `Column` they sit in gets the same "the list slides rather than jumps" result with no
   retained state and no write-during-composition.
3. **Press feedback scales the drawing, never the layout.** `rememberPressScale` returns a factor for
   `graphicsLayer` rather than a `Modifier`, so a pressed control keeps its declared touch target and the
   48dp minimum the ATF audit checks can't be quietly shrunk by a press.

Haptics arrived with the same change and needed no permission — Compose routes `LocalHapticFeedback` through
`View.performHapticFeedback`, not the `Vibrator` API. They are used only where the app had no other way to
confirm something: `Confirm` on send, `ToggleOn`/`ToggleOff` on a reaction chip (a tap, unlike a long-press,
gets nothing for free), and `GestureThresholdActivate` / `Reject` / `GestureEnd` on the voice recorder's
lock, cancel and stop — a raw `pointerInput`, so `combinedClickable`'s automatic long-press haptic never
applies. **Do not add a manual `LongPress` haptic** to the message bubble, the chat-list row or an
attachment: `combinedClickable`'s `hapticFeedbackEnabled` already defaults true, and a second one double-buzzes.

Deliberately not done: no shared-element transition between the chat list and the thread (fragile with a
ViewModel per route, and a much larger change than polish); no `Shapes` scale in the theme (a static design
decision, not motion); no `core-splashscreen` (it would touch the locked dependency set and the F-Droid
reproducible-build path); and no motion on the low-traffic settings screens, where it would buy nothing. The
whole change added **no dependency** — `androidx.compose.animation` was already on the classpath
transitively, so the lockfile is untouched.

## 048. The baseline profile is a committed text file; everything that produces it is quarantined behind a flag

Knit shipped with no baseline profile of its own. The release APK carried 5,507 rules — every one of them
merged out of the AndroidX AARs, which ship profiles for their own code — and **zero** for Knit's. So on a
fresh install ART interpreted `KnitApplication.onCreate`, Koin's graph, the SQLCipher open and every
composable on the chat path until the JIT caught up, which is exactly the window a user forms their opinion
in. Now: 36,066 merged rules, 4,572 of them Knit's own, and `dumpsys package dexopt` reports
`[status=speed-profile] [reason=install-dm]` — ahead-of-time compiled at install from the shipped profile.

**The release build consumes `app/src/main/baseline-prof.txt` and nothing else.** No Gradle plugin on
`:app`, no dependency, and `app/gradle.lockfile` is byte-unchanged. AGP picks that path up unaided —
verified on AGP 9.3.1 by adding one rule and watching `mergeReleaseArtProfile` go from 5507 to 5509 lines —
merges it with the library profiles, lets R8 rewrite the names through its own mapping, and packages
`assets/dexopt/baseline.prof`.

That shape is forced by ADR-level constraints, not chosen for tidiness. `.agents/context/distribution.md`'s
contract is that F-Droid rebuilds the tagged commit and byte-compares against our APK, so the release build
must not be a function of the build machine. **Generating a profile during the build would need a connected
device and would differ per run.** A committed text file is an input like any other source file. Verified:
two clean `assembleRelease` runs produce an identical APK
(`24a39291f2ad9a17a143f42ba9279c27bcd0b35cf01166323cb5d3c3843cd91a`), and `./gradlew projects` on a default
invocation lists only `:app`.

Everything needed to *produce* the profile is therefore off the default build entirely:

- `:baselineprofile` (a `com.android.test` module driving macrobenchmark's `BaselineProfileRule`) is
  included by `settings.gradle.kts` only under `-Pknit.baselineProfile=true`.
- `:app`'s `nonMinifiedRelease` build type is created under the same flag — which is also why the lockfile
  does not move: a new build type would otherwise add `nonMinifiedRelease*` configurations to every one of
  its ~600 lines, and `dependencyLocking` here is not in strict mode, so a flag-only variant resolves
  without lock state.
- `androidx.benchmark` is pinned in the catalog but reaches nothing that ships, which is why an `-rc` is
  acceptable there and would not be on a shipping path.

Two collection rules that are easy to get backwards, both encoded in the build type:

1. **Unminified.** Profile rules name classes and methods in source form and R8 rewrites them on the way
   into the APK, so collecting against an obfuscated build maps the names twice and matches nothing.
2. **Profileable, not debuggable.** A debuggable app is never AOT-compiled, so ART's profile for it does not
   describe how the shipped app runs. `isProfileable = true` opens the profile to the shell and nothing more.

**The journey is deliberately short** — cold start, chat list, into a thread, back — and must stay that way.
A baseline profile buys AOT compilation for the code it names, so naming everything dilutes dex locality and
lengthens install for no gain. Settings, diagnostics, LoRa, relays and verify are reached once by a user who
has already decided; they are not what a first impression is made of. The mesh transports are thin for a
different reason: the run drives a real, un-seeded app with no peers, so `MeshService` starts but never
completes discovery. That is the right trade — radio code runs over seconds in a foreground service, where
interpretation costs nothing anyone can feel, and the profile's job is the sixteen milliseconds after a tap.

The profile is generated against the **un-seeded** app on purpose. The demo seams live in `src/debug`, so a
seeded run would put classes in the profile that the shipped APK does not contain; `nonMinifiedRelease`
takes `src/release/java`'s no-op `DemoWiring` exactly as `staging` does, and those three stub methods are
the only `Demo*` entries in the committed profile — they ship.

Cost: **+253 KB on a 73 MB APK** (0.35%), most of it R8 laying dex out differently rather than the 4 KB the
profile itself grew. Regenerate when the startup or chat path changes shape, not per commit: a stale profile
is a smaller win, never a correctness problem, while a churning one is a large unreviewable diff every time.
Deliberately not done: `src/main/startup-prof.txt` (dex-layout reordering) — a real further win, but a
second thing to prove against the byte-comparison, and worth its own change. How-to lives in
`.agents/context/baseline-profile.md`.

## 049. A board set up for Knit is renamed for Knit, from its own node number, and named back on restore

Status: Accepted (2026-08-26; `mesh/lora/BoardName`, `MeshtasticProto` `get_owner`/`set_owner`,
`spliceStringFields`, `BoardSettings.owner`, `SettingsStore.loraBoardSetup`, the LoRa screen's setup section)

A Meshtastic node's `User.long_name` is its whole public identity — the board's own screen, every other
radio's node list, the Meshtastic app. A Knit board kept saying `Meshtastic abcd`, which meant the one piece
of hardware the user had just handed over to Knit was the one piece of hardware they could not identify. ADR
045 already decided a board is either **set up for Knit or a stock Meshtastic node**, with no middle setting,
so the name belongs on that same switch rather than behind a preference: setup renames, restore names back.

**`Knit abcd`, not `Knit`.** The firmware builds its default out of the low two bytes of the node number
(`Meshtastic %02x%02x` off the last two MAC bytes); keeping that shape and swapping the prefix means two
boards in one pocket stay distinguishable, which a bare `Knit` on every board would destroy exactly when it
matters. The short name — the four characters the small screens have room for, `char[5]` in the firmware —
is `Knit` in full, which is a coincidence worth taking.

**Deliberately not the user's display name.** `NodeInfo` is cleartext on the public frequency. The plane
already accepts that a DM's `senderId`/`recipientId`, timing and size travel in the open (ADR 039); adding a
human name to the standing broadcast is a different and worse kind of leak, for a cosmetic gain.

**The write is a read-modify-write, for a reason unlike the config writes'.** `AdminModule::handleSetOwner`
*merges* — it copies each non-empty string — so a `User` built from scratch would not clobber the rest. But
`is_licensed` is a presence-less proto3 bool: absent reads as `false`, and the firmware answers that by
clearing `config.lora.override_duty_cycle`, the escape hatch a licensed operator set on purpose. So the
board's own `User` from `get_owner_request` is the base and only the two names are spliced
(`spliceStringFields`, the string sibling of ADR 045's `spliceVarintFields`); the public key, hardware model
and licence survive byte-for-byte. A board that will not return its `User` aborts the setup with nothing
written, exactly as a board that will not return its config does.

**A board already carrying the Knit channel still gets renamed** — one `set_owner`, and nothing else. That
is the one exception to ADR 045's "a re-run is a reported no-op", and it exists because every board set up
before this ADR is in that state. **Which means the screen has to offer it**, and ADR 045's setup section
hides its one button the moment the board carries the Knit channel — so the action was unreachable exactly
where it was needed. The board's own name turned out to be in the handshake already: `NodeInfo.user` on the
board's own entry, arriving in the same `want_config` stream ADR 041's battery is read from. Decoding it
into `BoardInfo.owner` lets the screen tell "set up" from "set up and named", and a board in the second
state gets the action back as a **rename button that says the new name in full** and goes straight through
— no confirmation, because unlike the setup it writes one reversible, visible field and nothing about the
board's battery or broadcasts. Firmware that never sends its own `NodeInfo` reports no name, which reads as
"no reason to think it needs renaming": going on offering a rename that may already be done is the worse
failure, the same benefit of the doubt ADR 045 gives an unreadable channel table. It must not re-record the intervals: they are the *quieted* ones by then,
and overwriting the stored record with them would destroy the only copy of the board's own. So the caller's
existing record is carried forward with the old name filled in, and when there is no record the old name is
simply not recoverable — a restore then writes the name the firmware itself would have chosen
(`BoardName.stock`), because leaving a restored board saying `Knit` is the one visible trace of a restore
that is meant to leave none.

Wire: none of Knit's. Meshtastic only — admin `get_owner_request` (3) / `get_owner_response` (4) /
`set_owner` (32), `User.long_name` (2) / `short_name` (3), and `NodeInfo.user` (2) off the handshake — all
pinned by golden vectors in `MeshtasticProtoTest`. `WIRE_COMPAT`/`NEXT_WIRE_BREAK` untouched.

Honest residuals (accepted): the rename is one more admin write in the setup transaction, so one more way
for it to half-fail on a flaky link (the commit is still a single transaction, so a failure leaves the board
untouched); a user who renames the board back by hand in the Meshtastic app gets renamed again by the next
setup tap, which is the right default but is not asked about; the name says a board runs Knit to anyone
listening on the public frequency, which is a deliberate trade of anonymity for findability and the reason
it is tied to the setup switch the user consents to; and the four-character short name leaves no room for a
per-board tag, so two Knit boards are told apart by their long names only.

## 050. The broad library keeps are gone: R8 now optimizes 97% of the app, and the xmlpull duplication bites twice

Status: Accepted (2026-08-26)

Play Console reported Knit at **48% optimization, 48% obfuscation, 48% shrinking** and flagged all three.
The three numbers being identical is the diagnosis: a `-keep` blocks shrinking *and* optimization *and*
obfuscation over whatever it matches, so one set of over-broad rules moves all three together. R8 itself
had been on since ADR 012 — this was never "R8 is off", it was "R8 is not allowed to touch half the app".

R8's own keep-radius report (`configanalyzer.pb`, written by every `assembleRelease` on AGP 9.3; the
standalone task is `:app:analyzeReleaseR8Config`) named the culprits precisely. Three rules accounted for
**56,456 of the 60,652 blocked items — 93%** — and for 41.3% of the dex by size:

| rule | blocked items | dex |
|---|---|---|
| `-keep class com.google.crypto.tink.** { *; }` | 30,085 | 1.79 MB |
| `-keep class com.reandroid.** { *; }` (ARSCLib) | 23,397 | 1.42 MB |
| `-keep class com.android.apksig.** { *; }` | 2,974 | 0.20 MB |

All three were belt-and-suspenders from the ADR 012 flip, kept broad on the theory that a reflective
library is safer pinned than shrunk. None of them earned it. Tink ships the one consumer rule it actually
needs (`protobuf.pro`, `<fields>` of the shaded `GeneratedMessageLite`s) and is otherwise designed to be
shrunk: `TinkInit` calls `HybridConfig.register()` / `SignatureConfig.register()`, which reference their key
managers by static field, and `KeyTemplates.get(String)` reads the registry those calls populate rather than
doing a `Class.forName`. ARSCLib and apksig are reached only from `ui/invite/ApkMerger.kt` and
`ui/invite/ShareSigningKey.kt`, through six ordinary call sites R8 traces like any other code. Dropping all
three cost nothing and bought everything:

- rates **70.6 / 70.4 / 70.6% → 97.0 / 96.8 / 97.0%** (blocked items 60,652 → 4,744)
- dex bytes still carrying original package names **44.3% → 4.6%** (R8 full mode repackages everything it
  renames into the unnamed root package, so a real package name left in the dex *is* a keep rule's footprint)
- dex **9.90 MB → 6.03 MB**, and it stopped needing a second dex file
- APK 70.4 MB → 66.5 MB

**The xmlpull duplication bites twice, and the second bite is worse.** ADR 012 found that ARSCLib bundles a
copy of the platform package `org.xmlpull.v1` into the APK, so R8 renamed the *interface* out from under
the framework's `XmlBlock$Parser` and resource-XML inflation died with `IncompatibleClassChangeError`; the
fix was to pin `org.xmlpull.v1.**`. Pinning the names is not enough. Because the interface is a **program**
class here, R8 full mode also reasons about it under the closed-world assumption, and counts implementors.
The only ones in the APK are ARSCLib's own (`KXmlParser`, `ResXmlPullParser`) — `XmlBlock$Parser` is a
library class implementing the *library* copy of the interface, which the program copy shadows, so it does
not count. While `com.reandroid.**` was pinned wholesale, ARSCLib's implementors were trivially alive and
the question never arose. Shrink them away and R8 concludes the interface is uninhabited, every value of
that type must therefore be null, and it compiles `parser.next()` down to a literal `throw null`. Compose's
`painterResource()` then dies with a `NullPointerException` inside `seekToStartTag` on the first vector
drawable it loads — which is a chat-list avatar, so the app crashes on its first screen, having launched
and rendered onboarding perfectly. Confirmed by reading the shipped dex: `Resources.getXml()` is invoked
for its side effect and the very next instruction is `throw v3` with `v3 = null`.

The fix is one line and keeps only inhabitance, not names:

```
-keep,allowobfuscation,allowoptimization class * implements org.xmlpull.v1.XmlPullParser
```

R8 may still rename and optimize the implementor; it may not delete the last one. Cost: **+29 KB of dex**,
and the three rates do not move. That is the whole price of the bug.

**Verified on a minified, obfuscated `staging` build on an API-37 emulator**, because both remaining
hazards fail *silently* and a launch check proves nothing: Tink throws at `getPrimitive()` rather than at
load, and the moderators fail **open**. Checked: cold launch and render (xmlpull); `libsqlcipher.so` loads
and a sent message survives a restart (SQLCipher JNI); identity minted on first run and the contact-card QR
renders from the keyset (Tink `KeyTemplates.get` → `generateNew` → `TinkProtoKeysetFormat`, HPKE + Ed25519
public export); a genuinely abusive message is **refused** while a benign one sends (tflite text
moderation — the fail-open surface, and the one where "no crash" is the wrong signal); and Install offline
produces `Knit-2.4.0-alpha.0.apk` through the sharesheet (ARSCLib `ApkBundle`/`ApkModule` + apksig
`ApkSigner`). `missing_rules.txt` is empty and `lintRelease` is clean.

**CI now enforces it**, because the realistic regression is not someone re-adding these lines — it is a
dependency upgrade shipping a broad consumer keep rule, which is invisible in a diff and silent until the
next Play upload. `build:release` loses its `allow_failure: true`, gains `:app:analyzeReleaseR8Config`
(archived as HTML — it names the offending rule), and ends with `scripts/r8-dex-gate.sh`, which fails on a
step change in the share of dex bytes R8 was not allowed to rename. That share needs no proto parsing to
measure and no tooling the build does not already install: `apkanalyzer dex packages --defined-only`,
summed over the top-level packages that still have names. It is a coarse alarm (15%, against 4.6% today),
not a target.

Honest residuals (accepted): the **DM seal/open path is still unverified under R8** — it needs two peers
with radios, and an emulator has neither, so what has been proven is that Tink mints, stores, reloads and
exports keys correctly, not that a message round-trips; ARSCLib's `org.xmlpull.v1` copy is still shipped and
still shadows the platform's, so both keep rules stay load-bearing and a future ARSCLib bump must be
re-verified on a device rather than by reading a changelog; `net.zetetic.**` and `org.tensorflow.lite.**`
remain pinned `{ *; }` (together 1.1% of dex — the remaining slack is small enough that the fail-silent
risk is not worth taking); the baseline profile was **not** regenerated, because its rules name source
symbols that did not change and `compileReleaseArtProfile` reported none unmatched; and the 15% gate
threshold is a judgement call with no principle behind it beyond "far enough above 4.6% never to
false-alarm, far enough below 44.3% to catch this class of regression".

## 051. Play named Tink; the unbounded decode was ours, in the notifier, on peer-supplied bytes

Status: Accepted (2026-08-26)

Play Console's app-quality scan flagged the shipped bundle for "using BitmapFactory without downsampling"
and named two locations:

```
com.google.crypto.tink.hybrid.HybridKeyTemplates.<clinit>
com.google.crypto.tink.internal.MutableSerializationRegistry.<clinit>
```

**Both names are wrong, and the way they are wrong is the durable lesson.** Tink is pure crypto and never
touches `android.graphics`. R8 full mode — more so since ADR 050 dropped the broad keeps — moves and merges
methods across classes, and `mapping.txt` records the move but a retracer walking it method-first lands
wherever the residual name collides. In the current build `zm4` retraces to
`androidx.room.RoomDatabaseKt__RoomDatabase_androidKt` at class level while *physically holding*
`IconCompat.toIcon`. Play's scanner walks that same lossy path. **Nothing in such a report is reproducible
from the names it prints; go to the dex.**

The recipe, which is the reusable part:

```bash
unzip -o -q app-release.aab 'base/dex/*.dex' -d /tmp/dex   # or classes*.dex from an APK
$ANDROID_SDK_ROOT/build-tools/*/dexdump -d /tmp/dex/**/*.dex > dump.txt
grep -n 'BitmapFactory;.decode' dump.txt                   # then walk back to the enclosing
                                                           # `Class descriptor` / `name` / `type`
```

Run against both the Aug-23 upload and the current build it gives the same four Options-less call sites and
no others: two in `MessageNotifier` (one source line, `bitmapFor`, inlined into two callers) and two in
androidx.core — `IconCompat.toIcon(Context)` on its `TYPE_URI`/`TYPE_URI_ADAPTIVE_BITMAP` branch, and
`ShortcutManagerCompat.pushDynamicShortcut` with the same method inlined.

**Ours was not a memory-hygiene nit.** `bitmapFor` decoded a peer's avatar — `PeerEntity.avatarHash`, a
profile learned off the mesh — with `BitmapFactory.decodeByteArray(bytes, 0, bytes.size)`. The blob's *byte*
size is bounded; its *pixel* count is not, and a ~30 kB solid-colour PNG at 12000² decodes to ~576 MB. Every
other decode in the app already went through `data/ImageDecode.kt`; the notifier was the one that slipped,
on the one path that runs unattended in the background. It now calls `decodeBoundedFromBytes(bytes,
AVATAR_PX)` — the same helper `ImageScreeningService` uses — which does the `inJustDecodeBounds` pre-pass, a
power-of-two `inSampleSize`, and an exact downscale to 256². Dropping EXIF with it is correct: `AvatarStore`
already stores oriented 256² JPEGs.

The same edit fixes a smaller waste: `buildAndPost` builds a `Person` per message in the
`NotificationHistory` (8) plus self, then `postSummary` posts again, so one notification decoded the same
JPEG about ten times. Decoded avatars are now memoized in a 2 MB `LruCache` keyed by
`bytes.contentHashCode()` — content-keyed, so a peer *changing* their avatar simply misses, and
`LruCache`'s own synchronization covers `bitmapFor` running outside the `states` lock.

**The androidx.core pair stays, at 2.** Knit only ever builds icons with
`IconCompat.createWithAdaptiveBitmap(bitmap)`, so the URI branch is dead for us — but it is reachable on
paper, R8 keeps it, and a static scanner counts it. Not worth patching a library over.

**Guarded at both levels, because they catch different things.** detekt gains `style > ForbiddenImport` on
`android.graphics.BitmapFactory`, excluding `data/ImageDecode.kt` — an *import* rule rather than
`ForbiddenMethodCall` because detekt runs here without type resolution. That covers our sources and nothing
else, and the risk this ADR is really about arrives through a dependency. So `build:release` also ends with
`scripts/dex-bitmap-gate.sh`, a sibling of ADR 050's `r8-dex-gate.sh`: it dexdumps the release APK, counts
every `BitmapFactory.decode*` whose descriptor lacks a `BitmapFactory$Options`, prints the containing class
and method, and fails above a budget of 2. Verified in both directions — 4 and FAIL before the fix, 2 and OK
after, with the two survivors carrying the `IconCompat` signature.

## 052. A Wi-Fi Aware attach that keeps failing is a binder leak, so the retry budget is a leak budget

The discovery loop's detached branch retried `WifiAwareManager.attach` at a flat `ATTACH_RETRY_MS` (3 s)
forever. That is right for every failure it was written for — the chipset needing a beat after a
`reattach()` teardown, Wi-Fi mid-toggle, another app briefly holding the single NAN interface — and on a
device where the attach can **never** succeed it is not merely wasteful. It gets the app killed.

**Each failed attach strands two binder objects in `system_server`.** `WifiAwareManager.attach` mints a fresh
`Binder` token *and* an `IWifiAwareEventCallback.Stub` and passes both to `IWifiAwareManager.connect`. The
success path releases them — `onConnectSuccess` hands the token to the new `WifiAwareSession` and
`session.close()` calls `disconnect(clientId, binder)`. The failure path has no release at all:
`onConnectFail` clears a `WeakReference` and calls `onAttachFailed`, and there is no client id to disconnect
with, so nothing ever tells `system_server` to let go (AOSP `WifiAwareManager`, read against the android-36.1
sources). `ActivityManagerService` watches that count per uid and kills every process of a uid that crosses
its high watermark — `REASON_EXCESSIVE_RESOURCE_USAGE` / `SUBREASON_EXCESSIVE_BINDER_OBJECTS`, description
"Too many Binders sent to SYSTEM". No exception, no trace, nothing in the log.

**`isAvailable` is the trap that let it run.** It reports whether Aware is *enabled*, not whether an interface
can be had, so on a chipset whose vendor HAL publishes no STA+NAN interface combination it stays `true` while
every attach fails at `HalDevMgr: bestIfaceCreationProposal is null` with `wlan0` holding the only slot.
`onAttachFailed` set `Degraded` and logged, the loop came back 3 s later, and that was the whole cycle:
~28.8 k failed attaches a day, ~57.6 k stranded objects, an AMS kill within hours — and then a re-kill within
seconds of each restart, because the dead proxies outlive our process until `system_server` collects them.
That is getknit/Knit#9 (OnePlus 8 `IN2010`, `kona`, LineageOS 23.2), reported as "crashes 10 s to half a
minute after opening, no logs at all". The reporter's `dumpsys activity exit-info` named the subreason; their
earlier logcat had already shown the cause, with the framework's Aware client id past 51 k while `wlan0` sat
at `Id=22`. The live count is readable with `adb shell dumpsys activity binder-proxies`, which also breaks
down by interface — a healthy Knit install sits at ~20 objects for its uid.

So `mesh/wifiaware/NanAttachPolicy` is a **leak budget wearing a backoff's clothes**, and its constants come
from what a failure costs rather than from any cadence one would otherwise pick:

- `BASE_BACKOFF_MS` equals the old flat cadence, so a *lone* failure retries exactly as promptly as it always
  did and the reattach-needs-a-beat case is unchanged.
- It doubles to a `MAX_BACKOFF_MS` of **half an hour** — far longer than a retry cadence is useful, which is
  the point: the only failure that survives it is a permanent one, and 48 attempts a day costs 96 objects a
  day against a watermark in the thousands.
- After `MAX_FAILURES` = 60 consecutive failures it **stops attaching altogether**: ~25 h of trying for ~120
  stranded objects, a couple of per cent of the budget. A radio that has refused for a day is not coming back
  on its own.

**What ends an episode is the load-bearing half.** A successful attach, recorded *before* the supersede check
in `onAttached` because the streak counts whether the radio opened, not whether we kept that session. Then the
availability broadcast, **both** edges: Aware changing state is the one genuinely new fact about the radio, so
a streak that predates it says nothing about the next attempt, and the up edge attaches immediately rather
than serving out a stale backoff. That keeps the common recovery — Wi-Fi off→on, airplane mode — as fast as it
was, and it is why giving up is safe. `heal()` deliberately does **not** clear it: it says the app was opened,
not that the radio changed, and refunding the fast part of the curve on every `ON_RESUME` would put the leak
back on a user-behaviour clock.

**The cost, stated plainly.** There is no broadcast for *another app* releasing the NAN interface, so polling
is the only recovery there: it now lags by up to half an hour, and after a day of refusals it stops until
availability changes. That is the trade, and it is not close — the alternative is a mesh app that silently
kills itself on any device whose chipset won't give it an interface.

Deliberately not done: no health state for "this device cannot do NAN". The plane already reports `Degraded`,
`CompositeMeshTransport` still reads Healthy off Bluetooth, and a permanent refusal is not distinguishable at
runtime from a long contended one — asserting otherwise in the UI would claim more than the evidence supports.
The gate lives inside `attach()`, the single choke point every caller funnels through, with
`rediscoverDelayMs` stretched to the deadline so the loop *sleeps* instead of waking only to be turned away.
`NanAttachPolicyTest` pins the curve, the cap, the overflow guard, the give-up threshold, and the two budget
numbers on the JVM.

## 053. The sealed delivery tick retries on a backoff, because its retry cadence outran the dedup window

A Pixel 9 left running for a few days logged **332 `RATCHET_DUPLICATE` drops**. Nothing was wrong with the
crypto — that reason means a v2 DM landed on a chain index below `epoch.next` (`RatchetEngine.open`), which is
*proof we already decrypted that frame*, which is exactly why ADR 024 took it back out of
`RESET_TRIGGERING_DROPS`. It was `AckSync`, and the count was a cadence bug hiding behind a benign label.

**Two windows in the wrong order.** A sealed delivery tick is sealed **once** and every retry re-sends those
bytes verbatim — sealing consumes a DM chain key, so per-retry re-sealing would burn epochs and starve real
DMs out of the receiver's ≤200/epoch skipped-key budget (ADR 018). Verbatim means one frame id for the whole
life of the entry, and the only thing that can absorb a repeat is the router's `SeenSet`. That window is
**10 minutes**; `retryPending` runs off the heal heartbeat, which is **15** (`MeshService`'s
`INTERVAL_FIFTEEN_MINUTES`). So *every* retry cleared the window, reached the ratchet, and hit a consumed
index — `OWED_TTL_MS` / 15 min ≈ **96 duplicates per stuck tick**, all the way to the 24 h TTL. 332 is three
or four such ticks. The exists-gate cannot help either: a ctl DM never persists a message row, so
`messages.exists` is false for a tick forever.

The entries that drip are the ones holding **cached sealed bytes** — a broadcast-room tick
(`escalatable = false`, and `sealDeliveryTickEnvelope` seals for any `CAP_RATCHET` author regardless of
group-ness) toward an author reachable over the coordination plane but never live-linked, plus the race where
a group author unlinks between the `absent` check and the send. The other two owed forms were already clean:
the cleartext form is rebuilt with a fresh id per attempt, and an escalated batch is remembered in `escalated`
and never re-sent.

**The fix is the schedule, not the seal.** A sealed owed entry now carries `retries` + `nextAttemptAt` and
doubles from one heartbeat — 15 m, 30 m, 1 h, 2 h, 4 h, then an 8 h ceiling — so the same 24 h horizon costs
~8 best-effort re-sends instead of ~96, a 12× cut. Nothing about the seal, the frame, or the TTL changes.
`sweep` still ages the entry out on `recordedAt`, so the backoff cannot extend an entry's life, and a tick
lost outright still self-heals the way it always did: the message re-serves through the deliver path and
re-`owe`s it.

**A live link always overrides the schedule.** It is reliable, it ends the entry, and it is precisely what the
backoff is waiting for — so `sendOwedIfDue` resolves the link first and only throttles the best-effort
coordination-plane send. A re-owe rides the same schedule as the heartbeat for the same reason the heartbeat
does: the bytes are identical, so an off-schedule re-send is one more duplicate at the author.

Rejected: **raising the `SeenSet` TTL above the heartbeat** — one constant, kills the drops outright, but it
is a global flood parameter and lengthening it delays every legitimate re-propagation, not just this one.
And **letting broadcast ticks escalate** like group ticks (sealed once, custodied, remembered ⇒ zero
re-sends), which reverses ADR 033's deliberate choice to keep the broadcast room best-effort-only as the
ambient, short-lived class — trading 332 cheap drops for real custody rows across the mesh.

`receiptsResent` is now counted only when something actually goes out, which makes it a re-send tally rather
than a heartbeat tally and the corroborating counter for this drop reason. Three JVM tests in `AckSyncTest`
pin the curve (8 sends across the 24 h TTL, the exact due steps), the live-link override, and the cleartext
form's exemption.

## 054. Casual texting must not black out the LoRa plane: a recipient gate, a 15-minute window, and coalesced receipts

Status: Accepted (2026-08-27; `mesh/lora/LoraMeshTransport.coveredByLink`, `LoraAirtime`/`LoraPacePolicy`,
`MeshTransport.FanoutHint`, `mesh/DmAckCoalescer`, `Protocol.CAP_INLINE_ACK`, `LoraReach.LoraOnlySaturated`)

Field report: casually texting one person over the mesh drove the LoRa airtime budget to 100 %, after which a
LoRa-only peer stopped receiving anything for up to an hour. The person being texted was on a **Bluetooth
link**. Three causes, all in code, none of them the radio:

1. **Nothing gated a DM-form frame off LoRa by recipient.** `CompositeMeshTransport.longRangeFanout` forwards
   unconditionally and `LoraMeshTransport.fanout` never read `linkedPeers` or self — only `fastSend` did (ADR
   044's amendment). So every DM to a pocket-mate, its sealed ✓✓ coming back, and the gateway's re-fan of
   every relayed DM-form frame (`InboundPipeline.onDeliver`) spent LoRa air for a recipient who already had the
   frame over a link, in both directions, and the far peer then went without for the rest of the window.
2. **The ✓✓ cost as much air as the message.** A 100-char DM compacts to 387 B (2 packets, ≈ 3.8 s at LongFast);
   its sealed `CTL_RECEIPT` is 316 B (≈ 3.25 s), ~280 B of which is fixed overhead around a 22-byte payload. And
   `InboundPipeline.acknowledge` sealed a **fresh** receipt on every re-delivery (the exists-gate, deliberately —
   ADR 039 §3's healing channel), which the LoRa sig dedup cannot catch.
3. **The budget was a rolling hour with a cliff.** `LoraAirtime` allowed `min(region duty, 10 %) × 0.5` of an
   hour — 180 s of air in *every* region, the politeness ceiling binding even where the law is 100 % — so ~25
   round trips spent it and `take()` then blocked until samples aged out an hour later. Firmware facts
   (`Router::send`): EU_868/EU_433 NAK at 10 %/h; every other region enforces no TX-utilization cap at all.
   The 5 % is Knit's own politeness figure, and it stays.

Decisions worth not relitigating:

1. **A recipient gate on the floodable paths, keyed on links and never on sightings — for the third time.**
   `coveredByLink`: a DM-form frame addressed to us or to a peer a higher-preference plane holds a live link to
   (`linkedPeers`, the composite's `suppressDataPath` = BLE ∪ NAN `neighbors`) is skipped on the fan-out and
   the bridge backfill, before the sig-dedup slot is spent. `reofferTo` and `fastSend` already did this. A
   sighting (`foreignReachable`) is not a data path — ADR 044's field lesson — so a merely-sighted peer's DM
   still rides. The room is addressed to nobody and is untouched. Counted as `loraSkippedLinked`.
2. **The window is fifteen minutes at the same percentage.** `LoraAirtime.WINDOW_MS` 60 → 15 min; `SAFETY`,
   `POLITE_CEILING_PERCENT`, `BRIDGE_SHARE` and the fallback unchanged. A burst is capped at 45 s of air (a
   quarter of the old cliff), a dark spell at one window, and the hourly total is unchanged; rolling windows
   straddle, so the worst hour is 5/4 of nominal — ≤ 6.25 %, still under the 10 % the EU firmware refuses at
   (`LoraAirtimeTest` pins it). *Rejected:* letting LIVE spend the full 10 % — every packet we send is
   repeated by the peer's board (and by stock nodes on `rebroadcast_mode = ALL`), so 10 % TX is 20–40 %
   channel utilization, Meshtastic's congested band where stock nodes stop repeating us (their polite
   rebroadcast limit is 25 %); also a token bucket / reserve trickle, which cannot avoid the cliff under a
   hard hourly cap and only moves the pain.
3. **Our own delivery tick is its own class, and the originator says so.** The plane cannot read a sealed frame
   (ADR 039 §3), so `MeshTransport.longRangeFanout` gains `hint: FanoutHint = CONTENT`; `originateSigned`
   threads it, the pipeline's `originateTick` seam carries it, and `originateDeliveryTick` marks the group
   escalation. `FrameClass.TICK` sheds first and dequeues last, and `LoraAirtime.admits` refuses it once a
   window is `TICK_TAIL_SHARE` (25 %) from spent — the last of the air goes to content. A `fastSend` frame is a
   tick by policy (`Path.TARGETED` admits only receipts/ticks) and is classed so unconditionally; relayed
   DM-form frames stay opaque `DM`. `QUEUE_CAP_FRAMES` 16 → 32 so a burst that outruns a window waits for the
   next instead of shedding (≤ ~700 B a frame).
4. **A DM that arrived over the board holds its ✓✓, and a reply carries it.** `mesh/DmAckCoalescer` (pure) holds
   the receipts owed to an author for `HOLD_MS` (45 s, `AckSync`'s figure), anchored on the batch's oldest id;
   `InboundPipeline.acknowledge` holds instead of sealing when `plane == DeliveryPlane.LoRa` and the author can
   read a sealed tick — every other plane keeps today's instant receipt, and an author who cannot read one keeps
   the cleartext form. The exists-gate re-ack goes through the same hold, so a backfill burst yields one tick.
   The flush is `MeshManager.originateDeliveryTick` — the group escalation's own path — with `ack`/`acks`
   (≤ `MAX_LORA_TICK_ACKS` = 12, pinned to fit 3 packets at the ESP32 222-B cap), hinted `TICK`, falling back
   per id to the cleartext receipt like AckSync; `heal()` is the backstop timer. The receive side already
   applied `acks` per id under the forged-ack guard, so the batch is compatible with every ratchet-era build.
   **The piggyback**: `sendChat` takes up to `MAX_INLINE_ACKS` (4) pending ids toward a peer whose profile
   carries `Protocol.CAP_INLINE_ACK = 0x40` and seals them as `MessageContent.acks` on the **plain** DM (v2 arm
   only; a v1 fallback gives them back), reserving `INLINE_ACK_BYTES` (23) each out of the composer's LoRa body
   budget so a reply can never become `loraTooBig` to save a tick; `decryptAndDeliverV2` applies them in the
   same commit as the row (txn outer, lock inner — the `applyCtlInTxn` shape). Additive per WIRE_COMPAT: an
   existing field populated in a new case, behind an append-only bit; an old receiver is never sent one and
   still gets the standalone tick. Not folded into `AckSync`: that class encodes the broadcast/group rules
   (a live-link form, verbatim retries, a done-ledger) and a DM tick has none of them. Process death inside the
   hold loses in-memory acks, accepted as for AckSync — the peer's next re-offer re-delivers, re-holds, re-ticks.
5. **The chat says when the plane is saturated.** `LoraFacts.airtimeSpent` (≥ 90 % of the window's live budget,
   a threshold so the facts flow stays quiet) → `LoraReach.LoraOnlySaturated` wherever the LoRa-only notice
   would show: "airtime is used up, messages are delayed", with the explanation dialog. The DMs-off notice
   outranks it.

Cost per exchange: ~7 s of air → ~4 s (a receipt is ~0.2 s riding a reply, ~3 s standalone, a burst of N DMs
costs one tick); in the observed topology the LoRa budget is no longer touched at all. Counters:
`loraSkippedLinked`, `loraTickDeferred`, `receiptsCoalesced` (the field oracle: every unit is a ~3 s frame not
sent), all in `…debug.LORA`. Tests: `LoraMeshTransportTest` (gate on links not sightings, self, the room; a
hinted tick sheds first), `LoraBridgeTest` (backfill gate), `LoraAirtimeTest` (window, worst hour, tail),
`LoraPacePolicyTest`, `CompositeMeshTransportTest` (the hint), `DmAckCoalescerTest`, `InboundPipelineTest`
(hold / Bluetooth instant / incapable cleartext / inline acks under the guard, never re-applied),
`MeshManagerTest` (one hinted tick per flush; the piggyback carries the ids — its ciphertext grows by exactly
theirs — and a v1 fallback gives them back), `CoordinationPlaneSizeBudgetTest` (a 12-ack tick and a budget DM
with 4 inline acks fit the hop), `LoraReachTest`/`LoraStatusRepositoryTest`/`ChatLoraIndicatorTest`.

Honest residuals (accepted): a frame that waits in the pacer longer than the 10-min sig window can be enqueued a
second time by a late relayed copy (pre-existing, now reachable at 32 slots); the backfill beacon still uses the
60-s first-hearing gap; a session-reset reseal burst rides as `CONTENT`; and the composer's DM budget is pinned
at the nominal 233-B packet while an MTU-255 board takes 222 — a few bytes of over-promise at the very top of
the budget, unchanged here. **Still owed:** the three-phone trial in `context/lora-bridge.md`.

## 055. The attach budget's refund path was the leak: a bound is only worth what refunds it

ADR 052 shipped in 2.3.1 and did not fix getknit/Knit#9. The reporter came back with `versionName=2.3.1` and
a logcat that says exactly what went wrong, in two details:

```
13:58:11.558 W WifiAwareTransport: Wi-Fi Aware attach failed (streak 1) — next attach in 2947ms
13:58:11.562 W WifiAwareTransport: Wi-Fi Aware attach failed (streak 1) — next attach in 3404ms
13:58:11.565 W WifiAwareTransport: Wi-Fi Aware attach failed (streak 1) — next attach in 2810ms
```

**`streak 1` on every line**, so the counter was zero on entry every time; and **3.5 ms apart**, so a computed
3-second backoff delayed nothing. ~286 attaches a second, ~570 binder objects a second, across the AMS
watermark in about ten seconds. `noteAttachFailed` increments before it logs, so only `clearAttachBackoff`
could produce that, and of its three callers `onAttached` and `stop` were both out.

It was the availability receiver — the half ADR 052 called load-bearing. That receiver had no edge detection
at all: it read `mgr.isAvailable` fresh on each broadcast and treated every `true` as "Aware came back". On a
chipset that cannot produce a NAN interface, each refused attach makes the framework churn Aware state and
broadcast again, `isAvailable` still `true`. Attach, fail, broadcast, refund, attach. **The refusal drove its
own refund**, and the tighter the loop, the faster it ran. On a working radio the streak is always 0, so
nothing about this is observable outside a device that refuses.

The correction is not a better refund rule. It is that **a bound whose refund path can be driven by the thing
it bounds is not a bound**, so `NanAttachPolicy` now carries three that fail independently:

- **The streak** (`backoffMs`/`giveUp`) still paces retries and is still refundable, because a genuine change
  in the radio really does make a past streak meaningless. Refunding it is now gated on a real `false→true`
  transition — `lastAvailable` compared against the broadcast, seeded at `registerAvailability`. A repeat of
  `true` is not news.
- **`tooSoon` floors the rate** at `MIN_ATTACH_INTERVAL_MS` (3 s, the same figure, so it is invisible to
  everything that already paced itself). Nothing refunds it; it reads `attachStartedAt`, which is never
  cleared. Had it existed, this bug would have cost one attach per 3 s and no kill at all.
- **`leakBudgetSpent` caps the total** at `MAX_LIFETIME_FAILURES` = 200 for the life of the process, refunded
  only by an attach that actually succeeds — not by availability, not by `stop`. 400 binder objects, ever,
  whatever the other two do. This is the one that makes the kill unreachable rather than merely unlikely.

**Giving up is now durable**, via `data/settings/NanAttachJournal` (the `ModelLoadJournal` seam, same shape).
`MeshService` is `START_STICKY`, so an AMS kill is followed by a restart, and a fresh process was starting
with an empty streak and a full budget — re-learning the same refusal forever. The stored value is a stamp,
app version code plus `Build.FINGERPRINT`, so a new build or a flashed ROM re-arms on its own; #9 is a
LineageOS device, and a ROM update is exactly the event that might publish a working STA+NAN combination.

Honest residuals. The floor and the streak floor are the same 3 s by construction, so the first retry after a
lone transient failure is unchanged — but a caller that legitimately wants two attaches inside 3 s now cannot
have them; `reattach()`'s inline attach is the only one that ever tried, and the discovery loop already
covered it one beat later. A device that reaches the lifetime cap stays off Aware until it is restarted, a
Wi-Fi toggle re-arms it, or the app/ROM stamp changes; Bluetooth carries the mesh throughout, which is why #9
reported crashes and never reported undelivered messages. `NanAttachPolicyTest` pins the floor against a
replay of the reporter's 4-ms broadcast storm (10,000 broadcasts → 14 attaches) and the cap's two budget
numbers; the receiver's edge itself is not unit-tested, because the transport is Android-bound — that is what
the field report is for.

## 056. The key bootstrap gets a share of the window, not an exemption from it

ADR 044 gave the LoRa plane an airtime governor and carved out one class from it: a `FrameClass.BOOTSTRAP`
frame — a `profile`, ours or a relayed one — was admitted unconditionally. The argument was sound. Nothing a
peer sends verifies without its author's profile, so a window that refuses the profile costs more airtime
than it saves: every frame that peer sends afterwards is undecodable and re-served forever.

The hole was that `admits` returned `true` while `record` still booked the cost. An exemption from admission
is not an exemption from spending, so profiles could — and did — drive `liveUsedMs` past `liveBudgetMs` on
their own, after which every DM, room post and tick on the plane was refused for up to a window.

**What the lab gateway had actually been doing.** A field test reported the budget reading 100 % after two
authored messages. The counters said why. Of 376 LoRa frames the Pixel 9 had ever sent, 63 were DM-form and
17 were gossip offers; the remaining **296 — 79 % — were profiles**, and all but one of them were fragmented,
which a single-packet room post never is. At ~4.75 s of air for a 453-B profile that is ~1400 s against ~240 s
for every DM the phone had ever sent. The second gateway showed the same shape at 59 %. The two authored
messages were ~6.5 s of a 45 s window; the rest was a class nobody was metering.

The mechanism is a dedup that outlives nothing. A relayed profile is gated only by the transport's
`sigSeen`, whose TTL is 10 minutes — the same 10 minutes as `MeshRouter`'s `SeenSet`. So a profile that keeps
arriving looks first-seen again on every lapse, re-fans, and rides LoRa every 10 minutes indefinitely.
`LoraFramePolicy.isFresh` exempts non-chat types by design (a profile's `sentAt` is a publish stamp up to 12 h
old), so age never stops it either.

**The decision.** Keep the exemption, bound it. `AirBucket` gains a third member, `BOOTSTRAP`, and
`AirBucket.defaultFor(klass)` pairs it with the class so no call site can book a profile against `LIVE` by
accident. `admits` judges a bootstrap frame against `BOOTSTRAP_SHARE` (0.25) of the allowance **alone**,
never against the total — it still rides when the window is otherwise spent, which is the whole point — while
every other class is judged against a total that now includes what the bootstrap spent. A quarter is two
profiles per 15-minute window and eight an hour, well past what a bootstrap needs given our own beacon's
5-minute floor, and it leaves three quarters of every window to traffic somebody is waiting for.

A refused profile is **deferred, not dropped**: `LoraPacePolicy.take` already skips an over-budget frame and
leaves it queued, and `BOOTSTRAP` is still the highest queue-shedding class, so the frame that loses its
window is first out of the next one. Dropping the key bootstrap is the failure the exemption existed to
prevent, and it is not what this trades away.

The backfilled profile is deliberately **not** in the new bucket: `serveOne` keeps passing `AirBucket.BRIDGE`,
because a re-served profile is history like everything else the bridge carries, and
`LoraFramePolicy.backfillRank` already puts profiles first within that share.

`AirtimeSnapshot` carries `bootstrapUsedMs`/`bootstrapBudgetMs`; the settings row's percentage and the chat
saturation notice sum all three buckets (the bootstrap's air is real air), and `…debug.LORA` reports
`bootstrapMs`/`bootstrapBudgetMs` beside the other two.

**The 10-minute re-fan itself is ADR 057**, landed straight after this one. The cap alone converts it from
"blanks the plane" into "spends its own quarter, forever", which is bounded but still mostly redundant.

## 057. A profile is fanned once per publish, and a lost one is repaired by the digest, not by repetition

ADR 056 bounded what the LoRa key bootstrap can spend. This is the other half: why it was spending it. Of the
376 frames the lab gateway had ever put on the air, 296 were profiles — and they were not 296 different
facts. They were a handful of profiles, re-fanned over and over.

**The mechanism.** A relayed `profile` reaching `LoraMeshTransport.fanout` was gated by exactly one thing:
`sigSeen`, whose TTL is 10 minutes because it is a flood-suppression window. `MeshRouter`'s `SeenSet` lapses
on the same 10 minutes, so a profile that keeps arriving over BLE/NAN looks first-seen again on every lapse,
`InboundPipeline` re-fans it, and the LoRa side has nothing left to say no with. `LoraFramePolicy.isFresh`
cannot help: it deliberately exempts a profile from the staleness check, because a profile's `sentAt` is a
publish stamp that is hours old by design and refusing the key bootstrap for being old is exactly wrong. So
the same publish rode a ~1 kbps shared medium every ten minutes, indefinitely, at ~4.75 s a time.

**The decision.** A second dedup, `profileSeen`, keyed on the frame **id** — which
`MeshManager.currentProfileEnvelope` derives from the publish stamp, so it is stable for a publish and new
for the next one — with `PROFILE_REFAN_MS` = 12 h, the author's own `PROFILE_REPUBLISH_MS`. Anything shorter
only re-sends bytes the horizon already has, and anything longer is moot because a republish mints a new id
and rides on its own merits. The two sets answer genuinely different questions on genuinely different clocks
("is this frame in flight right now" vs "does the LoRa horizon already have this profile"), which is why this
is a second set rather than a longer TTL on the first.

Checked **before** `sigSeen` and **after** `encodeOrNull`, both deliberately: a held-back profile must leave
the signature slot free for the backfill to take, and a frame that could not be encoded must not consume a
window it never rode.

**What repairs a genuinely lost profile, since repetition no longer does.** The bridge's digest-driven
backfill: a far gateway's OFFER names what it holds, `framesMissing` diffs it, and
`LoraFramePolicy.backfillRank` serves profiles first. `serveOne` is therefore **not** gated on `profileSeen`
— pinned by `LoraBridgeTest.theBridgeStillServesAProfileTheFanOutHasStoppedReOffering`, where a pocket that
comes up after the fan-out still gets a key it has no way to ask for (the plane refuses `keyreq`).

**Not gated: our own beacon.** `sendSelfProfile` keeps its 5-minute floor and its 60-second first-hearing
gap. Both are event-driven — a board link coming up, a peer heard for the first time, a backfill about to be
served — and the first-hearing case is precisely "a new listener appeared", which is the one time re-sending
an unchanged publish is the whole point. `beaconProfile`'s own doc has the case: A beaconed two minutes ago,
B just came up, A must speak again or B's parked frames expire.

`MeshMetrics.loraProfileRefanSkipped` counts what the gate holds back; against `loraSent` it is the direct
measure of the redundancy, and `…debug.LORA` reports it.

## 058. A name is a label; the alias is its discriminator

A display name is free text (≤ 32 chars, whitespace-collapsed, nothing else) and the mesh has no authority
that could make it unique, so two peers named "Alice" are a normal event — in a festival crowd an
inevitable one — and until this decision they were pixel-identical on every screen but Diagnostics and
Blocked, which happen to print the raw node id. The letter avatar has one fixed tint for everyone, and the
notification avatar hue hashed the *name*, so it collapsed duplicates too. The mention pipeline was never
confused (`Mention.nodeId` is what "did this mention me" reads), but the picker showed two identical rows
and the highlighter locates spans by the `@name` text, so the reader could not tell which Alice was meant.

**The decision.** The label a peer is shown under is `name` alone in the common case and `name (Alias)`
whenever another identity the device knows renders to the same name. `identity/PeerLabels.kt` is a pure
resolver over the **universe** — every cached peer row ∪ this device's own name (a peer who adopts our name
is discriminated too; a seeded self row and self collapse to one identity, keyed by node id) — grouped by
`NameKey` (NFKC, format characters stripped, lower-cased, whitespace collapsed). Where the alias cannot
disambiguate — the rendered name *is* the alias (a blank profile), or two same-named peers' aliases coincide,
or someone *chose* "Alice (JoyfulFerret)" as a name — a six-character node-id prefix (`NodeId.shortForm`)
steps in, so every label in an index is distinct by construction. `PeerRepository.observeDirectory()` is
the one seam (the peer table plus its index, rebuilt per peer emission and on our own name changing — the
name arm is `distinctUntilChanged`, because `SettingsStore.displayName` re-emits on every DataStore write);
`labelIndex()` is the suspend snapshot for notifications and the contact-card preview. Row models keep their
`String` name (now `PeerLabel.text`), so every string sink — content descriptions, "X left the chat", the
chat-list preview prefix, group default titles, notification titles — is right with no edits, and list rows
carry the discriminator so `PeerNameText` can draw it muted.

**Why the alias.** It already exists, it is a deterministic function of the node id that every device derives
identically with no exchange (nothing to broadcast, persist, or migrate), it changes exactly when the identity
changes, and it is the word pair the owner already sees as their own placeholder — so two people can tell each
other apart by quoting it. Which is why the precision surfaces show it *always*, collision or not: the mention
picker (where the right Alice gets picked, and where `@joyful` narrows to her), a contact's profile, the
Add-contact preview, and the owner's own Settings — until now the alias vanished from the placeholder the
moment a name was typed, so nobody could learn their own.

**Why one global universe, not per-surface sets.** A peer renders identically on every screen, there is one
rule and one O(n) index per emission (n ≤ 2,000, the `sweepCap`), and a contact's label *changing* is itself
the signal that a second Alice has appeared. The accepted cost: a stranger seen once can suffix a contact
indefinitely, since there is no last-seen column to age them out of the universe. If that proves noisy, a
`lastSeen`-bounded universe is the fix — not per-surface sets.

**What this is not.** Anti-impersonation. The alias carries ~15 bits (178 × 182 word pairs) and a matching
keypair can be ground in seconds, so this is *disambiguation*: it separates the accidental collision and makes
the deliberate one visible, and nothing more. Trust stays where it was — `verified` and the safety number.
`NameKey` deliberately does no confusable folding (a Cyrillic "а" stays distinct from a Latin "a"); a
homoglyph impersonator is caught by the discriminator only when the two keys fold together, and that limit is
recorded rather than half-solved.

**The one wire-visible consequence.** The mention token a collided candidate inserts is the label —
`@Alice (JoyfulFerret)` — and `Mention.name` carries that exact text, which is what lets the unchanged
`highlightMentions`, the send-time reconciliation, and every deployed build locate the span. Detection is
still by node id; the wire shape is untouched (`GoldenVectorTest` did not move). The one sink that must
stay plain is `ReplyRef.author`, the quote snapshot a reply puts on the wire — `ChatRow.senderPlainName`
exists for it alone.

**Deferred, by design.** An impersonation *warning* when a non-contact adopts a contact's or your own name;
a node-id-derived avatar hue in-app (the notification hue now keys on the identity, not the name, so two
Alices at least differ in the shade); last-seen pruning of the universe; and resolving `ReplyRef.authorId`
through the directory instead of rendering the sender's snapshot.

## 059. Crypto scheme v3: the nonce is derived, the plaintext is compact, and the live-link tick is unsigned

A steady sealed `CTL_RECEIPT` tick was 316 B compact — two packets on the LoRa hop (233 B nominal, 222 B at
the lab's MTU-255 ESP32 boards) and two Wi-Fi Aware messages (255 B) — and after ADR 030 there was nothing
left for deflate to do: its `signed` is 285 B of which 173 B are random (ids 74, nonce 12, ek 32, ct 55), a
no-dictionary deflate *expands* it, and `DICT_V1` buys 36 B. Measured 2026-08-29 in
`CoordinationPlaneSizeBudgetTest`. The whole cost of a two-packet tick — ~3.25 s of air, a 3-s pacer gap,
loss p² instead of p — sat on the one frame nobody types.

**The three savings, and the one that was not taken.** Crypto scheme **v3** (`EncEnvelope.v = 3`,
`Protocol.CAP_CRYPTO_V3 = 0x100`) is the v2 DM ratchet with nothing changed in its chain, epochs or
header, and two things removed from the bytes: the 12-byte AES-GCM nonce is *derived* rather than carried
(`RatchetCrypto.messageNonce` — HKDF of the single-use message key with the AAD mixed in, so a restored
database re-sealing under the same chain index still gets a distinct nonce from the fresh frame id), and the
plaintext inside `ct` is the labeled `MessageContentV2` layout — integer keys, raw 16-byte ids, raw hashes —
which takes a tick's plaintext from 39 B to 21 and a twelve-ack batch down by 72. The third saving is the
signature: AckSync's live-link tick for a room or group post (`MeshManager.sealDeliveryTick`, `relay =
false`, sealed with the pairwise ratchet, never flooded, never custodied) now travels **unsigned** toward a
v3 author. Its AEAD is the authenticator: v3 binds the ratchet header into the associated data beside the
v2 header (`id|sender|sentAt|recipient`), so the two header fields the derived key never bound — `flags`
and `init.at` — are covered too, and X3DH binds the initiator's identity key, so a forged init cannot open
either. Tick 316 → ~222 B: one message on Wi-Fi Aware, one packet on nominal LoRa, and one packet at the
ESP32 boards once `TORADIO_OVERHEAD` stopped carrying 6 B of hand-summed slack (it is now measured off
`MeshtasticProto.encodePacket`: 27, cap 228). The one saving **not** taken is the DM ✓✓'s signature:
`sealDmReceipt` originates `relay = true` — flooded and custodied since ADR 018 — and a carrier can verify a
signature but cannot open a session, so that tick stays signed and stays two packets until the transcoder
(roadmap, "frame compaction, round 2") takes it under a packet at ~205 B with custody intact.

**Why the nonce field stays, empty.** The first design omitted it (nullable, 19 B instead of 12). It cannot
be omitted: `InboundPipeline.canCarry` decodes the chat payload before custodying a frame, so an envelope a
pre-v3 build cannot decode is an envelope no fielded build will *carry* — a per-build custody rule, the
digest divergence ADR 006 forbids. v3 sends `nonce` as the empty byte string; every deployed shape decodes it
(`WireSerializationTest.everyOlderDecoderShapeDecodesAV3Envelope`), refuses it at the version gate, and keeps
carrying it — the ADR 035 un-populating posture, not a rule-2 re-type. `keys` stays `[]` for the same reason.

**Why the nonce derives from the message key, with the AAD.** `ratchet_skipped_keys` stores only message
keys, so a nonce derived from the *chain* key could not be recomputed for an out-of-order frame opened
later — the mesh's ordinary case. Deriving from the key being tried also means every rung of the open
ladder and every root candidate derives its own, with no store change.

**What an unsigned frame can and cannot do.** Exactly one shape passes `verifyInbound` without a signature:
`type = chat`, `relay = false`, no group, addressed to us by someone else, from a pinned sender. Everything
after that is judged only after the AEAD opens: the unsigned door is checked *before* the plaintext branch
and *before* the pre-decrypt exists-gate (otherwise anyone who overheard a DM id on the air could make us
seal and flood a receipt for it — a receipt oracle), a frame that opens must be a `CTL_RECEIPT` (a signature
stripped off a captured plain DM and re-injected point-to-point would open too, and must not deliver
through this door — refused before commit, so the chain index is untouched and the signed copy still
lands), and an open failure is counted but **never** fed to the reset heuristic — `onRatchetFailure`'s own
comment justifies `AEAD_FAIL` as a reset trigger *because* the frame was signed, and three forged frames
with distinct ids must not buy an attacker a session reset per pair. A relay=false frame never reaches
custody, the flood, the fan-outs or the spool; `canCarry` still demands a 64-byte signature. Accepted: a
forged unsigned frame costs ~3-5× an Ed25519 verify in pre-AEAD work (X25519 per root candidate, up to 199
skipped-key derivations) with nothing persisted; SeenSet dedup-before-verify is a pre-existing weakness of
every frame type; a peer that downgrades keeps the bit pinned until its profile version climbs back (the
same shape as `CAP_RATCHET`; seeding `profileVersion` from the wall clock is the roadmap fix).

**What did not change.** The group form (`g`) stays v2 — sender keys are symmetric across members, so a
group frame can never be unsigned, and its derived nonce is an all-members-gated change for later. Session
resets (`sealResetDm`) and the group-key ctl DMs stay v2, the most compatible form toward a peer that may
have reinstalled. `canCarry`, the DM ✓✓'s custody model, `PendingInbound`, and every v1/v2 golden vector.
The compact codec is canonical-or-nothing: an id, hash or key whose bytes do not re-encode to the exact
string makes `sealBytes` fall back to v2 rather than mangle it — no shipped build ever minted such an id
for anything acked or quoted, so this is hygiene, not compatibility.

**Codec and planes.** `FastFrameCodec` spends flags bit 4 (`FLAG_UNSIGNED`: the sig field is absent);
bits 5-7 stay reserved and an old receiver drops the frame, which is why the bit is only ever emitted
behind the capability. `LoraMeshTransport`'s ten-minute dedup keyed on signature bytes would have collapsed
every unsigned tick onto the empty key — it keys on the frame id for the unsigned form, exact because
AckSync seals a tick once and re-sends it verbatim. Counters: `dmSealedV3`, `ticksUnsigned`,
`DropReason.UNSIGNED_REFUSED`.

**Deferred.** Round 2 — the schema-aware transcoder that re-encodes the *signed* bytes (int keys, raw ids)
and carries the signed DM ✓✓ in one packet; `DICT_V2` (`DICT_V1` now holds a dead `nonce` token; frozen,
harmless); the profile-version seeding above; re-tuning `INLINE_ACK_BYTES` (23 B stays a conservative
reservation for a 17-B compact ack).
