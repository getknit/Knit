---
id: "2026-09.66cw"
slug: a-spool-bearer-token-is-stored-in-the-clear
title: "A spool bearer token is stored in the clear, and one canonical URL is what gets stored"
date: 2026-09-15
topics: [spool, privacy, settings, data]
---

# ADR 2026-09.66cw — A spool bearer token is stored in the clear, and one canonical URL is what gets stored

Status: Accepted (2026-09-15). GitLab work item #26. Records an existing property of `SettingsStore`
rather than changing it; the URL half amends `SpoolUrl` and the two doors that write a relay.

**What was observed.** A security audit of `v2.2.3..HEAD` read the storage of a private relay's
credential and found it in cleartext. A tokened relay URL — `wss://host/spool/v1?k=<token>`, spec
B-7.1-1 — is one string in the `KEY_SPOOL_URLS` string-set of the plain Preferences DataStore, so the
token sits unencrypted under `datastore/`. Every other secret this app keeps is behind something:
`knit.db` is SQLCipher, `db.key` and `identity.key` are AndroidKeyStore-wrapped, and a commons room's
32-byte invite secret is a `CommonsEntity` column *inside* the encrypted DB. The token is the only
credential in the clear, and by S-7.1-3 it is the whole admission control for a private spool — a
mismatch is a constant-time compare and a `4001` close. The audit's second finding was in the same
file family and is the same mistake in a different register: `SpoolUrl.isAcceptable` compared the
scheme with a case-sensitive `startsWith`, so `WSS://host/spool/v1` was refused by both callers even
though a URL scheme is case-insensitive (RFC 3986 §3.1) and OkHttp normalises one before it dials.

**What was decided about the token: nothing changes, and here is why that is not an oversight.** The
first move a reader reaches for is to put the token where everything else is — a column in `knit.db`,
or a `KeystoreSecret` wrap like `db.key`. It buys less than it looks. The attacker it would stop is
one who can read app-private storage as a raw filesystem image but cannot use the KeyStore: a
chip-off, or a forensic dump of an FBE-unlocked device. Against that attacker the token alone is a
*resource* credential, not a confidentiality one. It admits the holder to the relay's socket; it does
not decrypt anything there, because a scope's contents are sealed under `ScopeCrypto` from the
pairwise ratchet root or the group root, and those roots — with the commons secrets — are inside
SQLCipher, which the same attacker cannot open. So a stolen token yields the ability to connect to
someone's relay and see ciphertext they already could not read, at the cost of a DB migration, a
DataStore/DB split for one setting, and a read that `ScopeSync` needs at startup before the DB is
warm. Against the attacker who *can* use the KeyStore — root on a running, unlocked device — moving
the token changes nothing at all, because the same access opens `knit.db`.

The two real mitigations already hold and are the reason this is tolerable rather than merely
tolerated: `datastore/` is excluded from **both** cloud backup and device-to-device transfer
(`res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`), so the token never leaves the
device by a path we control; and every *rendering* of a spool URL goes through `SpoolUrl.redact`, so
it reaches no settings row, log line, diagnostics dump or crash report. The exposure is exactly
app-private storage, and no wider.

**What changed: the scheme rule, and with it the stored form of a URL.** `SpoolUrl` now reads a
scheme through one private `schemeOf`, case-insensitively, and both `isAcceptable` and `sourceUrl`
are built on it. `sourceUrl` had to move with it or the fix would have opened a hole rather than
closed one: it picks `https://` or `http://` by testing the same scheme, so a case-insensitive
`isAcceptable` over a case-*sensitive* `sourceUrl` would have let a release build accept a `WSS://`
relay and then GET its `/source` document in the clear — precisely what ADR 019's rule exists to
stop. `CrashRedactor`'s URL rule moved for the same reason: it matched `(wss?|https?)` literally, so
an uppercase-scheme URL would have fallen past it to the generic base64 rule, which a short or
`-`/`_`-bearing token slips through intact.

Accepting mixed case then forced a canonical form, because relays are matched **by exact string**:
`SettingsStore` holds a `Set<String>` and `RelayInviteApplier` pairs a link with a stored row by
comparing redacted URLs. Without canonicalisation a hand-typed `WSS://host/spool/v1` and the same
host arriving later as an invite's `wss://host/spool/v1?k=…` would be two rows for one relay, and the
untokened one would sit refused `4001` forever — the exact failure the applier's redacted-match rule
was written to prevent. `SpoolUrl.canonical` lowercases the scheme and nothing else (a path is
case-sensitive and a bearer token is case-sensitive key material), and the two doors that write a
relay — `InternetRelayViewModel.addRelay` and `RelayInviteApplier.preview` — both put a URL through
it. Only the scheme: the authority is case-insensitive too, but rewriting what the user typed buys
nothing here.

**What it costs, and the trap.** Nothing is migrated: a relay stored before this change keeps
whatever case it was saved in, and a URL already lowercase — every one that came from the shipped
`res/values/spools.xml` defaults or a generated invite link — is unaffected. A row typed in uppercase
*before* this change was never stored at all, because the editor refused it. The trap is the one
`SpoolUrl`'s own header warns about in the other direction: any new reader of a spool URL that tests
the scheme with a bare `startsWith(WSS_SCHEME)` re-opens the `sourceUrl` downgrade, so a scheme is
read through `schemeOf` or not at all. Kept true by `SpoolUrlTest` (the scheme rule, the no-downgrade
case, the stored form), `CrashRedactorTest.drops the bearer token whatever case the scheme is written
in`, and `RelayInviteApplierTest.anInviteWithAnUppercaseSchemeIsTheSameRelayAsTheStoredRow`. If the
token's storage is ever revisited, the thing that would change the calculus is a second credential
landing in DataStore that *is* a confidentiality key — at that point the DataStore-vs-SQLCipher split
stops being "settings vs secrets" and the whole file needs the wrap, not one entry.
