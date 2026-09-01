---
id: "003"
slug: two-radios-behind-one-meshtransport-seam
title: "Two radios behind one `MeshTransport` seam, no GMS/Nearby"
date: 2026-07-07
topics: [mesh, architecture, radios]
---

# ADR 003 — Two radios behind one `MeshTransport` seam, no GMS/Nearby

Status: Accepted

Wi-Fi Aware (NAN) + Bluetooth LE run simultaneously behind `CompositeMeshTransport` (Bluetooth
preferred). Direct `android.*` radio APIs, no Google Nearby / GMS, so a device with only one radio still
meshes. Import boundary is enforced as a rule (`rules/mesh.md`); detail: `context/mesh-transport.md`.
