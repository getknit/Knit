---
id: "002"
slug: built-in-kotlin-is-overridden-to-2-4-0
title: "Built-in Kotlin is overridden to 2.4.0 (not AGP's bundled 2.2.10)"
date: 2026-07-07
topics: [build, toolchain, kotlin]
---

# ADR 002 — Built-in Kotlin is overridden to 2.4.0 (not AGP's bundled 2.2.10)

Status: Accepted

The Kotlin-2.2 compiler can't read class metadata produced by Kotlin 2.4. KGP 2.4.0 goes on the root
buildscript classpath. Bumping AGP does not move Kotlin — the override is the lever. Detail:
`context/toolchain.md`.
