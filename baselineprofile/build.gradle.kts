plugins {
    // No version: AGP is already on the build classpath from the root project, and re-declaring it
    // here is what Gradle refuses ("already on the classpath with an unknown version").
    id("com.android.test")
    // No version, same reason as above — the root project applies ktlint, so the plugin is on the
    // classpath. Worth having despite the module being conditional: without it nothing ever lints this
    // source, because CI never passes the flag that puts the module in the build.
    id("org.jlleitschuh.gradle.ktlint")
}

// Maintainer-only generator for `app/src/main/baseline-prof.txt`. Nothing here ships: this module is in the
// build only under `-Pknit.baselineProfile=true` (settings.gradle.kts), and its output is a text file that
// is committed and consumed by AGP with no plugin on :app at all. See .agents/context/baseline-profile.md.
android {
    namespace = "app.getknit.knit.baselineprofile"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 0
            }
    }

    defaultConfig {
        // Baseline profiles are collected through ART's profile dump, which needs API 28+. That is above
        // :app's minSdk 29 floor anyway, and the generated rules apply to every API level the app supports.
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Only the release-shaped, unminified variant exists here — it is the only one worth profiling, and
    // pairing it with :app's build type of the same name is what makes AGP build and install the right APK.
    buildTypes {
        create("nonMinifiedRelease") {
            isDebuggable = false
            // Without this the test APK is built unsigned and the install dies on
            // INSTALL_PARSE_FAILED_NO_CERTIFICATES. AGP auto-creates the debug config; it holds no secrets.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    targetProjectPath = ":app"

    // The test APK drives the app rather than living inside its process: macrobenchmark has to be able to
    // stop, cold-start and re-launch the target, which is impossible from within it.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit)
}
