---
id: "2026-09.37ce"
slug: a-direct-transfer-s-bytes-are-sealed
title: "A direct transfer's bytes are sealed, not just sent over WPA2"
date: 2026-09-10
topics: [transfer, crypto]
---

# ADR 2026-09.37ce — A direct transfer's bytes are sealed, not just sent over WPA2

Status: Accepted (2026-09-10; `transfer/TransferStream` VERSION 2, `TransferStreamTest`)

The group a direct transfer runs over is WPA2-Personal with a 24-character passphrase minted for that one
transfer and delivered inside a sealed DM (ADR 2026-09.wtmz). That is real protection and it was, for one
release cycle, the only protection the file had: `TransferStream` v1 authenticated the stream with
HMAC-SHA256 and encrypted nothing. Everything else a Knit user sends is sealed by Knit, and a file that
crosses a link in the clear is out of step with that — the guarantee would rest entirely on both phones'
supplicants being free of KRACK-class bugs, which is not a guarantee this app gets to make on their behalf.

So the payload is sealed too, and WPA2 becomes the second lock rather than the only one.

Decisions worth not relitigating:

1. **Chunked AES-256-GCM over the existing 256 KiB chunks, not one seal over the stream.** A single GCM
   operation would hand the receiver its tag only at the very end, so every byte written before that point
   would be unverified. Per-chunk sealing means **the receiver writes only bytes it has already
   authenticated**, which is strictly better than v1, where unverified bytes reached the sink and the
   trailer caught them afterwards.
2. **The READY secret is split before use.** HKDF-SHA256 under `knit/xfer/v2/stream` expands the 32-byte
   secret into an `encKey` for the chunks and a `macKey` for the client proof and the trailer. Nothing uses
   the transfer secret directly, and the label is disjoint from `knit/scope/v1/…`, `knit/dm/v2/…` and
   `knit/group/v1/…`. A test forges a proof keyed by the raw secret and asserts it is refused, so the split
   cannot quietly regress.
3. **The nonce is the chunk index, and that is safe here for one specific reason** — `encKey` derives from a
   secret minted for a single transfer and never reused, so a counter can never collide. This would be
   indefensible under a long-lived key and is the first thing to re-examine if one ever appears.
4. **The AAD is `header ‖ index(u64be) ‖ final(u8)`, and the receiver derives it from its own arithmetic
   rather than reading it off the wire.** That is what makes reordering, replay into another transfer, and
   truncation all fail their tag instead of being detected afterwards. A stream cut at a chunk boundary is
   refused rather than accepted as a shorter file, because the receiver computes `final` from the size it
   already agreed to.
5. **The HMAC trailer stays, though GCM subsumes it.** It is what the verdict byte reports, so a corrupt
   transfer stays a *stated outcome* rather than an exception, and it covers the header as a unit. It costs
   one HMAC pass at a fraction of link speed. *Rejected:* dropping it as redundant, which would have changed
   the failure contract in the same commit that changed the crypto.
6. **The version rides in the header the receiver compares whole.** Two builds that disagree fail the
   transfer rather than misreading each other. The feature has never shipped, so there is no v1 to
   interoperate with and none is offered.

Sealing measures **1765 MB/s** on the host JVM, single-threaded, seal plus trailer — about 25× the 70 MB/s the
link itself does, so the codec is not the bottleneck and no chunk-size retuning was needed.

Cost and residuals (accepted): overhead is 20 bytes per 256 KiB chunk (a u32 length and a 16-byte tag), or
0.0076%. A version mismatch presents as "the file didn't arrive intact" rather than naming itself, which is
acceptable only while the feature is unreleased. The seal ends where the file lands: the receiver's copy is
written to Downloads as an ordinary file with no Knit encryption over it, and the consent sheet says so in as
many words. Throughput was measured on the host JVM, **not on a Pixel** — ARMv8 crypto extensions should put
it in the same order, but that is inference rather than measurement. Tests: `TransferStreamTest`, including
one that asserts a recognisable marker in the payload appears nowhere in the sealed wire bytes — a
tag-only test would pass just as happily with the file in the clear.
