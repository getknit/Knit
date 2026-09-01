---
id: "008"
slug: db-v1-is-the-frozen-launch-baseline
title: "DB v1 is the frozen launch baseline — migrations mandatory from v1"
date: 2026-07-07
topics: [data, room, migrations]
---

# ADR 008 — DB v1 is the frozen launch baseline — migrations mandatory from v1

Status: Accepted

No destructive fallback: every `@Database` bump adds a tested `Migration` + a migration-test case; a
missing migration throws at open time (caught in CI). Pre-1.0 destructive v2…v22 history is collapsed.
Detail: `context/testing.md`; break record: `docs/WIRE_COMPAT.md`.
