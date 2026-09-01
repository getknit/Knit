---
id: "007"
slug: static-analysis-via-standalone-cli
title: "Static analysis via standalone CLI; Kover is the one plugin exception"
date: 2026-07-07
topics: [build, toolchain, static-analysis]
---

# ADR 007 — Static analysis via standalone CLI; Kover is the one plugin exception

Status: Superseded by 011

detekt/ktlint run as standalone CLIs in isolated root-build configs so they can't perturb `:app`'s
Kotlin-2.4 classpath. Coverage must instrument bytecode, so Kover is the deliberate plugin exception
(low-risk, no codegen; keep ≥ 0.9.8). Detail: `context/toolchain.md`.
