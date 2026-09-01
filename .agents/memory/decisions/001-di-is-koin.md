---
id: "001"
slug: di-is-koin
title: "DI is Koin, not Hilt"
date: 2026-07-07
topics: [build, toolchain, di]
---

# ADR 001 — DI is Koin, not Hilt

Status: Accepted

Hilt's Gradle plugin is broken on AGP 9.x in this window (dagger#5083 / #5099). Koin is pure-Kotlin
runtime DI with no Gradle plugin / annotation processor, so AGP can't break it. Detail:
`context/toolchain.md`.
