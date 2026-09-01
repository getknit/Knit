---
id: "012"
slug: r8-obfuscation
title: "R8 obfuscation (name mangling) enabled — the wire stays safe by construction"
date: 2026-07-09
topics: [build, release, r8, wire]
---

# ADR 012 — R8 obfuscation (name mangling) enabled — the wire stays safe by construction

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
