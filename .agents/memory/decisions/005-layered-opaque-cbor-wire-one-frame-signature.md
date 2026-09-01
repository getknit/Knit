---
id: "005"
slug: layered-opaque-cbor-wire-one-frame-signature
title: "Layered opaque-CBOR wire + one frame signature"
date: 2026-07-07
topics: [wire, protocol, crypto]
---

# ADR 005 — Layered opaque-CBOR wire + one frame signature

Status: Accepted

Three layers (`WireEnvelope` / `RelayEnvelope` / per-type content) of opaque `@ByteString` CBOR so a relay
rewrites only ttl/hops and passes `signed`+`sig` byte-for-byte — one Ed25519 signature authenticates every
type. Evolves additively. Detail: `context/wire-format.md`; break rules: `docs/WIRE_COMPAT.md`.
