---
id: "028"
slug: crash-reports-are-captured-on-device
title: "Crash reports are captured on-device, redacted in two phases, and handed over only by the user"
date: 2026-08-20
topics: [privacy, crash-reports, support]
---

# ADR 028 — Crash reports are captured on-device, redacted in two phases, and handed over only by the user

Knit ships no crash reporting — no Crashlytics (it would drag GMS back in), no Sentry (F-Droid flags it,
and automatic egress is the one thing this app is built not to do). The cost lands on bug reports:
issue #9 arrived as the word "crash", and `.github/workflows/needs-info.yml` answers that by asking for
`adb logcat -b crash -d` — a computer, a cable, developer options, and a buffer that has usually rotated
by the time anyone reads the request. The app gave the reporter no way to produce what the bot asked for.

So: a `Thread.setDefaultUncaughtExceptionHandler` writes the trace to app-private storage, Diagnostics
grows a "Last crash" row, and the user reads it, copies it, shares it as a file, or opens a prefilled
GitHub bug form. There is no upload path anywhere in it.

**Plain files, not Room.** A crash report has no relational shape and no query needs, and going through
Room would engage ADR 008 — a `@Database` bump, a tested migration, a regenerated schema — for a rotating
directory of five text files. `data/crypto/AtomicFileWrite.kt` already provides the atomic write.

**`noBackupFilesDir`, not `filesDir` plus excludes.** Backup is allow-by-default across three sections of
`res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`. Under `filesDir` a report stays
private only while all three exclude entries exist and stay correct; one missed entry ships crash traces
to Google Drive backup and device-to-device transfer. `noBackupFilesDir` is excluded by construction.
`FileProvider` has no `no-backup-files-path` tag, so a stored report cannot be shared directly — which is
why sharing stages an explicit copy under `cacheDir/crash/` (one new `<cache-path>`, no new provider).
That is a feature: the provider can only ever hand out the one report the user picked.

**Redaction runs in two phases through one idempotent function.** Stack frames pass through
byte-identical — they are compiled-in constants and they are the whole diagnostic payload. Exception
*messages* are the risk, and not hypothetically: `BluetoothMeshTransport` puts two node ids in one
`IllegalStateException`. Phase 1, inside the dying handler, applies structural rules only (node/group/blob
/device ids, safety numbers, urls with their bearer tokens, on-device paths, non-ASCII runs, a 300-char
message cap). Phase 2 adds an exact-match pass over the contact names this device knows — and it *cannot*
run at crash time, because those names live in SQLCipher-backed Room and DataStore, which a dying process
with no Koin graph cannot reach. The stored file is therefore less redacted than the shared one; that gap
is closed by construction, since the store exposes no raw-read API and the phase-1 file has no
`FileProvider` path.

If the redactor itself throws, the fallback is frames-only — every message discarded, class names and line
numbers kept. **No code path writes an unredacted `stackTraceToString()` to disk.**

**Reporting is a link, never a post.** "Report on GitHub" opens the repo's own `bug_report.yml` form
prefilled from the report; the user submits it from their own browser. The app holds no token and calls no
API, so "contacts no servers" stays true. `steps` and `expected` are deliberately left blank: both are
`required` in the template, so GitHub blocks submission until the reporter writes them — which is exactly
what the needs-info bot found missing on issue #9. The form's field ids are now a contract with
`.github/ISSUE_TEMPLATE/bug_report.yml`, pinned by `CrashIssueUrlTest`.

Deliberately out of scope, and the UI says so rather than implying coverage it does not have: native
crashes (Tink, SQLCipher, the tflite moderator), ANRs, `WifiAwareTransport`'s deliberate
`Process.killProcess` on a NAN wedge, and `meshExceptionHandler`'s non-fatal swallow. The natural next
step for the first two is `ActivityManager.getHistoricalProcessExitReasons` (API 30+, and it returns an
ANR trace); the last two are a few lines each once someone wants them.

The `<!-- knit-crash-report -->` marker in every app-filed issue is consumed by
`.github/workflows/crash-report.yml`: it labels the report, links prior ones sharing a crash signature
(exception class plus top frame *locations*, which is what R8 preserves), flags a stale app version, and
names the `mapping-<version>.txt.gz` asset needed to deobfuscate release frames. It is separate from
`needs-info.yml` on purpose — an app-filed report satisfies that bot's trace/device/version rules by
construction, so the two never speak on the same thread.
