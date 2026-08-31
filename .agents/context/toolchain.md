# Toolchain (bleeding-edge — do not "fix" these without reading why)

This project intentionally runs on very new tooling (AGP 9.3.2, Gradle 9.7.1, Kotlin 2.4.10,
Compose BOM 2026.08.00, compileSdk 37.1). That forces several non-obvious choices. **Read this before
changing build config, dependencies, or the DI graph.**

## compileSdk is what gates AAR upgrades — check `minCompileSdk`, not the version number

`compileSdk` is `release(37) { minorApiLevel = 1 }` (37.2 is beta). An AAR whose
`aar-metadata.properties` declares a *higher* `minCompileSdk` **cannot** be consumed at all — the build
fails in `checkDebugAarMetadata`, not at compile. androidx now moves this gate aggressively (the whole
API-37 wave — core-ktx 1.19.0, lifecycle 2.11.0, Compose UI 1.12.0, okhttp-android 5.5.0 — is exactly
why compileSdk moved off 36.1). Before bumping any AAR dependency, read its metadata:

```sh
curl -sO "https://dl.google.com/dl/android/maven2/<group/path>/<artifact>/<ver>/<artifact>-<ver>.aar"
unzip -p <artifact>-<ver>.aar META-INF/com/android/build/gradle/aar-metadata.properties
```

Two traps that version numbers hide:

- **Check the artifacts this app actually uses, not the group's headline one.** lifecycle 2.11.0's
  `lifecycle-runtime` is minCompileSdk 34; its `lifecycle-viewmodel-compose` is 37. The strictest one wins.
- **A JVM-looking coordinate can still resolve to an AAR.** `com.squareup.okhttp3:okhttp` publishes an
  `androidRuntimeElements` variant whose `available-at` redirects to `okhttp-android`, so an Android
  consumer gets that AAR's `minCompileSdk`. Inspect `<artifact>-<ver>.module`, not just the `.pom`.

`compileSdk` sets only which APIs the compiler *sees*. Runtime behavior is `targetSdk`, deliberately held
at **36** — moving it opts into a new release's behavior changes and starts a Play policy clock, and is a
separate decision from taking a dependency. Lint's `NewApi` keeps guarding every call site against
minSdk 29 regardless.

Bumping `compileSdk` means installing that exact platform everywhere the build runs. The minor is part
of the package name (`platforms;android-37.1` ≠ `platforms;android-37`), and two CI files name it
literally: `.gitlab-ci.yml`'s `ANDROID_COMPILE_SDK` and the F-Droid-image reproducibility job in
`.github/workflows/release.yml`. Build-tools is *not* coupled to it — that tracks AGP's default revision
(36.0.0 for AGP 9.3.2).

## Why these choices

- **DI is Koin, not Hilt.** Hilt's Gradle plugin is broken on AGP 9.x in this window
  (dagger#5083 / #5099). Koin is pure-Kotlin runtime DI with no Gradle plugin / no annotation
  processor, so it can't be broken by AGP. Koin is started in `KnitApplication`; modules live in
  `app/src/main/java/app/getknit/knit/di/`.
- **Built-in Kotlin is overridden to 2.4.10, not AGP's bundled 2.2.10.** AGP 9.3.2 ships KGP 2.2.10,
  whose Kotlin-2.2 compiler cannot read class metadata produced by Kotlin 2.4 (this is what used to
  pin Coil to 3.3.0). The root `build.gradle.kts` puts KGP 2.4.10 on the buildscript classpath
  (`classpath(libs.kotlin.gradle.plugin)`) so built-in Kotlin compiles with 2.4.10 — a supported combo
  (Kotlin 2.4 requires AGP 9.1+ per Google's AGP/Kotlin matrix). **Bumping AGP does not move Kotlin**:
  the 9.3 line we now build on (and 9.4) still bundle 2.2.10, so the override — not an AGP bump — is
  the lever. Keep KGP and the `ksp` version in lockstep with `kotlin`; KSP adopted independent (KSP2)
  versioning at 2.3.0 (decoupled, Kotlin 2.2+), so it no longer uses the old `<kotlin>-<ksp>` scheme.
- **`android.disallowKotlinSourceSets=false`** is set in `gradle.properties`. AGP 9's built-in
  Kotlin otherwise rejects the `kotlin.sourceSets` DSL that KSP (Room's processor) uses.
- **No explicit `kotlin-android` plugin.** AGP 9's built-in Kotlin handles compilation; only the
  `kotlin.plugin.compose`, `kotlin.plugin.serialization`, and `ksp` plugins are applied.
- Pin third-party versions in `gradle/libs.versions.toml` (version catalog); probe Maven before
  bumping anything that could pull in a newer Kotlin stdlib.
- **Stable releases only**, with one standing exception: `detekt` 2.0.0-alpha.x, because the 1.23.x
  stable line cannot run on Gradle 9 at all. So `cameraX` stays on 1.6.2 (1.7.0 is alpha), `datastore`
  on 1.2.1 (1.3.0 is alpha), `robolectric` on the 4.16.x line (4.17 is beta), `lifecycle` on 2.11.0
  (2.12.0 is alpha), `activity-compose` on 1.13.0 (1.14.0 is alpha), and AGP on 9.3.2 (9.5.0 is alpha;
  9.4.0 is rc). `navigation-compose` was held at 2.9.8 by this rule until 2.10.0 went stable.

## Static analysis: detekt / ktlint Gradle plugins

`detekt` runs via the **`dev.detekt` Gradle plugin** (detekt 2.0.x — the first line that supports
Gradle 9; 1.23.x capped at Gradle 8.12.1, and `dev.detekt` is the new plugin/group id). Applied on
`:app`, it analyzes `src/main/java` + `src/test/java` **without type resolution** (no compile classpath is
wired — same scope/behavior as the old CLI), overlaying `config/detekt/detekt.yml` on detekt's defaults
(`buildUponDefaultConfig = true`). The `detekt` task exits non-zero on findings; reports land in
`app/build/reports/detekt/`. CI's `verify:detekt` job runs `./gradlew detekt`. (Config note: detekt 2.0
renamed several config keys — `LongParameterList.constructorThreshold/functionThreshold` →
`allowedConstructorParameters/allowedFunctionParameters`, `TooManyFunctions.thresholdIn*` →
`allowedFunctionsPer*`, and the style rule `UnusedImports` → singular `UnusedImport`.)

`ktlint` runs via the **`org.jlleitschuh.gradle.ktlint` plugin**, applied on the root project (which
lints the `*.gradle.kts` scripts) and on `:app` (which lints the Kotlin sources). The ktlint *tool*
version is pinned to `libs.versions.ktlint` — independent of the plugin version (`ktlintPlugin`) — so rule
behavior stays fixed. Rules are the ktlint standard ruleset, configured via the repo-root **`.editorconfig`**
(auto-discovered), including the `@Composable` function-naming opt-out
(`ktlint_function_naming_ignore_when_annotated_with = Composable`). `./gradlew ktlintCheck` verifies (reports
in `build/reports/ktlint/`); **`./gradlew ktlintFormat` autocorrects** — a capability the old CLI lacked.
`detekt` and `ktlint` both run as `Stop` hooks (`.claude/hooks/gradle-{detekt,ktlint}-stop.sh`) alongside
`./gradlew lint`.

These are ordinary Gradle plugins now (this reverses the old "standalone CLIs" doctrine — ADR 007 is
Superseded by ADR 011). They still add nothing to `:app`'s compile/runtime classpath — detekt and ktlint
each run analysis in their own isolated task classpath — so the Kotlin-2.4-metadata hazard that motivated
the CLI approach doesn't apply (verified: `assembleDebug` + `lint` unaffected). Applying them locked new
tool configurations into `app/gradle.lockfile` — regenerate per the lockfile rule in
`rules/build-and-test.md` after any detekt/ktlint version bump.

## Coverage: Kover

`kover` (test coverage) runs as a Gradle plugin — coverage **must** instrument bytecode and hook the test
run, which only the plugin does cleanly (a CLI would have to swap offline-instrumented classes into AGP's
unit-test task). It's low-risk on this toolchain — unlike Hilt it does no compile-time
codegen; it hooks `testDebugUnitTest` *post-compile* and only adds Java-only agent/offline-runtime jars to
test-scope configs, so it never touches `:app`'s Kotlin-2.4 compile/runtime classpath (verified:
`assembleDebug` + `lint` unaffected). Note: **Kover 0.9.1 applied but silently failed to detect AGP 9.2.1's
build variants** (no per-variant tasks, empty report) — **0.9.8+** fixes it, so don't downgrade below it.
Coverage is measured from the debug unit tests (`koverHtmlReportDebug` / `koverXmlReportDebug` — the
per-*variant* tasks; the un-suffixed `koverHtmlReport` aggregates all variants). Only *generated* code is
excluded (Room `*_Impl`, Compose `ComposableSingletons`, `BuildConfig`) via `kover { reports { filters } }`
in `app/build.gradle.kts` — everything hand-written stays measured. **In Kover class globs `*` does NOT
cross the package `.` — use `**` to span packages** (a bare `*_Impl` matches nothing). CI runs it as the
advisory `test:coverage` job (mirrors `verify:detekt`), which archives the HTML/XML and scrapes the % from
`koverLogDebug`. Adding the plugin changed the lockfile (`kover-jvm-agent`) — regenerate per the lockfile
rule in `rules/build-and-test.md` after any Kover bump.

## Room 3 (`androidx.room3`) and its Gradle plugin

Room is **`androidx.room3:room3-*` 3.0.2**, not `androidx.room` — Room 3 is a new group and a new package,
not a version bump. Imports are `androidx.room3.*`; `androidx.sqlite.*` did **not** move. Consequences worth
knowing before you touch the data layer:

- **There is no `openHelperFactory`.** Room 3 deletes the SupportSQLite layer from the core API, so
  `setDriver(SQLiteDriver)` is the only seam for a custom engine. SQLCipher rides in as
  `SQLCipherDriver` (`net.zetetic:sqlcipher-android` 4.18.0, the release that added it) — see ADR 065.
  That is why Room 3 and SQLCipher move together; neither can be bumped past the other alone.
- **There is no `room3-ktx`.** `withWriteTransaction` / `useWriterConnection` / `immediateTransaction` are in
  `room3-runtime`. `androidx.room.withTransaction` is gone; `withWriteTransaction` is its replacement and is
  reentrant the same way (the connection rides in the coroutine context).
- **`Migration.migrate` and the `MigrationTestHelper` methods are `suspend`.** Migrations declare
  `override suspend fun migrate(connection: SQLiteConnection)`, and every `KnitDatabaseMigrationTest` case
  runs inside `runTest { }`.
- Room 3 does **not** force the KMP builder form: `Room.databaseBuilder(context, Klass::class.java, name)`
  and `inMemoryDatabaseBuilder(context, Klass::class.java)` still exist, and the Android builder defaults to
  `AndroidSQLiteDriver` when no driver is set — which is what the Robolectric DAO tests rely on.

The schema JSON is exported by the **`androidx.room3` Gradle plugin**, whose extension is `room3 { }` (not
`room { }`): `room3 { schemaDirectory("$projectDir/schemas") }` in `app/build.gradle.kts`, not the raw
`ksp { arg("room.schemaLocation", …) }` — the plugin *rejects* an explicit `room.schemaLocation` arg, so
don't add one back. It requires `exportSchema = true` on `KnitDatabase`. With only build types (no product
flavors) it writes to the flat `app/schemas/<db-class>/<version>.json`, so the debug-asset wiring and
`KnitDatabaseMigrationTest` are unchanged.

**Gotcha:** KSP incremental caching can skip re-export when the committed schema is unchanged
(`copyRoomSchemas` shows `NO-SOURCE`); to force a fresh export after a `@Database` bump, clear
`app/schemas/` and rebuild — then **`git checkout -- app/schemas/` to bring back versions 1..N-1**, which
Room does not regenerate. Only the *current* version is ever exported; the older files are history the
migration tests read, and clearing the directory deletes them.

## The debug APK packages two ABIs, not four

`buildTypes.debug` sets `ndk { abiFilters }` to **`arm64-v8a` + `x86_64`** (`val debugAbis` above the
`android` block). Debug is unminified and carries both tflite models, so it starts around 150 MB; the
default four-ABI packaging adds ~28 MB of `lib/`, and the two 32-bit slices are dead weight — every lab
Pixel is `arm64-v8a` and every Gradle-managed emulator image is `x86_64`, so nothing we build for runs
`x86` or `armeabi-v7a`. Measured: 150 MB → 138 MB, and `-Pknit.debugAbis=arm64-v8a` → 130 MB for a
device-only reflash. `-Pknit.debugAbis=` (empty) restores unfiltered four-ABI packaging.

Two things not to get wrong:

- **Never drop `x86_64`.** `pixel7api33`/`pixel8api34` are x86_64 system images and SQLCipher loads through
  `System.loadLibrary` at DB open, so an x86_64-less debug APK fails every instrumented test at the first
  Room access — not at build time. (Keeping `arm64-v8a` matters too: AGP warns that the managed-device
  image ABI default flips from `x86_64` to `arm64-v8a` in **AGP 10.0**, and this APK already carries both.)
- **Never lift it to `defaultConfig`.** `abiFilters` is a packaging filter and needs no NDK installed
  (unlike `ndk { debugSymbolLevel }`, see `context/distribution.md`), but release-APK bytes are
  byte-compared by F-Droid — release and staging keep all four ABIs. Verified after the change: a plain
  `assembleRelease` still packages `x86`, `x86_64`, `arm64-v8a` and `armeabi-v7a`.

Why it is worth caring about at all: the lab devices are on network adb, and throughput varies ~80× with
the band a phone happens to be associated to (~0.5 MB/s on 2.4 GHz vs ~40 MB/s on 5 GHz). Trimming the APK
helps at the margin; **a slow `installDebug` is usually the phone sitting on 2.4 GHz**, which is the thing
to check first — `adb -s <dev> shell cmd wifi status` reports the frequency. Never toggle Wi-Fi over adb
on a network-adb device (`rules/devices.md`).
