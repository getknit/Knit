---
id: "2026-09.wuqj"
slug: an-alias-is-a-word-encoded-digest-prefix-that-grows-when-matched
title: "An alias is a word-encoded digest prefix that grows when matched"
date: 2026-09-03
topics: [identity, ui, security]
---

# ADR 2026-09.wuqj — An alias is a word-encoded digest prefix that grows when matched

Status: Accepted (2026-09-03; `identity/Alias.kt`, `identity/AliasWords.kt`, `identity/PeerLabels.kt`)

**What was observed.** The `Name (Alias)` discriminator of ADR 058 drew its alias from an FNV-1a hash of the
node-id string into 178 adjectives × 182 nouns: 32,396 pairs, about 15 bits. A keypair whose alias matches a
target's is a few seconds of grinding, and the fallback for a matched alias was a six-character node-id
prefix: ~30 bits, also grindable, and nothing a person can quote. ADR 058 recorded this as "disambiguation,
not anti-impersonation", which was honest, but it left the alias with no answer at all to a deliberate
match: once someone ground `JoyfulFerret` and typed "Alice", the real Alice's label changed to a hex-ish
fragment nobody had ever seen, and the impostor's to another. The ask was a scheme that is harder to mine
and collides less; the shape chosen was one string, three words.

**What changed.** The alias is a word encoding of `SHA-256("knit-alias-v2:" + nodeId)`. Each digest byte is a
whole index into one of three frozen 256-entry lists (adverb, adjective, noun in `AliasWords.kt`), three
bytes to a token, so the alias is one PascalCase token such as `ReallyJoyfulFerret` (24 bits) and the digest
carries ten of them. `PeerLabels` no longer caps out at the short id: a label that still reads like another's
once the alias is on it grows by the next token, one round at a time, until every text in the index is
distinct. A blank-named peer, whose name already is the first token, grows a continuation
(`ReallyJoyfulFerret (QuietlyBoldCedar)`). Distinct ids have distinct digests, so any two read apart within
the ten tokens, and only a SHA-256 collision reaches the guard that stops the loop. The alternative a reader
reaches for first, a memory-hard KDF on the derivation, was rejected: the attacker's cost is dominated by key
generation, a 100 ms KDF buys about seven bits, and it would cost a phone some 200 s to index a 2,000-peer
universe. Salting per viewer was rejected because it kills the alias's one job, quoting it to each other.
Longer baselines (two or three tokens) were weighed and the single token kept for the rows; growth supplies
the extra bits only when they are needed.

Why exactly 256 per list: a byte is an index with no modulo bias, there is nothing to re-roll (the `BLOCKED`
safety net is gone; the lists are curated pairwise-clean and `AliasTest` cross-checks every word and every
adjacent concatenation against the shipped profanity list), and an iOS port reproduces every alias from the
salt and the lists alone. The lists are therefore a spec: sorted, pinned by a fingerprint test, never
reordered or edited after release without a new ADR.

**What it costs and does not cover.** Every alias changed with this release; the changelog says so. This is
collision-*evident*, not anti-impersonation. Twenty-four bits is minutes of grinding, and success makes both
labels grow to two tokens (48 bits, weeks on a GPU) and then three (72 bits, out of reach) but never
coincide; the growth is the tell. A plain adoption of a contact's name, with no grinding, still shows two
Alices with different aliases and nothing says which is real: that is the roadmap's impersonation warning,
unchanged by this decision. A grown label also grows the mention token it inserts (`Mention.name`, as in
ADR 058) and the notification title, by a token. The trap: a label grows on the *other* person's phone,
where the look-alike is, so the owner's own directory may never contain it. Settings therefore shows the
owner's second token muted beside the bold alias, on one line (`profile_alias`), or Alice could not answer
"which one are you?". `NodeId.shortForm` stays, display-only, and is no longer part of any label. Kept true by `AliasTest`
(goldens computed from the salt and the lists with Python rather than from the code, the byte-exact mapping,
the 256/sorted/disjoint/fingerprint assertions, the profanity cross-check) and `PeerLabelsTest` (a real
ground pair, `jiuqkhusaqt3u25svz7rbvvwje` / `p3ve2zdqk6ecz5dfofpywoobxa`, whose digests share four bytes
under the alias salt).
