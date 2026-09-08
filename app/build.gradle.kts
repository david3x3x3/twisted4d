import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing credentials -- deliberately never committed (see .gitignore and
// keystore.properties.example): storePassword/keyPassword there are what actually let someone
// sign an update to this app's Play Store identity, so a leaked copy is as sensitive as a leaked
// login. Loaded from a properties file, not hardcoded here, so this whole build.gradle.kts file
// stays safe to commit even though it references where the real secrets live. Absent on a fresh
// clone (or anyone else's checkout) -- releaseSigningConfig is null in that case, and the
// `release` build type below just skips attaching a signingConfig, so `assembleDebug` and other
// day-to-day tasks keep working; only `assembleRelease`/`bundleRelease` actually need this.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProps = if (keystorePropertiesFile.exists()) {
    Properties().apply { load(keystorePropertiesFile.inputStream()) }
} else {
    null
}

// Runs `git <args>` in the repo root and returns trimmed stdout -- used below to stamp each build
// with the exact commit it came from, so a bug report's on-screen build label ("Build a1b2c3d4",
// or "...-dirty-<timestamp>" for an uncommitted working tree) can be traced straight back to
// source instead of needing a hand-bumped counter kept in sync with commits by memory.
fun gitCommand(vararg args: String): String {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    return output
}

// A committed tree's hash alone already uniquely identifies the source, so no timestamp needed
// there -- but a *dirty* tree's hash stays the same across every build until the next commit, even
// across edit-rebuild-redeploy cycles with real (uncommitted) changes in between, which made the
// label useless for confirming "is this actually the build I just made" during iteration. A build
// timestamp, appended only in the dirty case, guarantees each build gets a distinct label.
val gitDirty = gitCommand("status", "--porcelain").isNotEmpty()
val gitVersion = gitCommand("rev-parse", "--short=8", "HEAD") +
    if (gitDirty) "-dirty-" + SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()) else ""

android {
    namespace = "dev.twisted4d.app"
    // 36, not 34 -- confirmed via developer.android.com/google/play/requirements/target-sdk
    // (2026-08-01) that Play requires a genuinely *new* app (this one's never been published)
    // to target API 36 starting Aug 31, 2026, which is essentially now; existing apps get one
    // level of grace (35) but there's no reason to under-shoot that for a first submission. Not
    // stopping at 35 -- would just mean bumping again almost immediately. minSdk stays 26
    // unchanged; targetSdk doesn't gate which devices can install the app, only which platform
    // behavior version the app opts into (see the release_signing_backup_locations-adjacent
    // conversation, 2026-08-01, for why this doesn't affect device compatibility, e.g. David's
    // Retroid).
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.twisted4d.app"
        minSdk = 26
        targetSdk = 36
        // versionCode is the Play-Store-style monotonically-increasing build counter (never
        // shown to users, just what the package manager compares to decide "is this an
        // upgrade") -- bump it by 1 on every future release regardless of versionName.
        // versionName is the free-form user-visible string; semantic versioning
        // (MAJOR.MINOR.PATCH) is the usual convention. 0.x.y means "pre-1.0" -- no promise of
        // stability yet -- per the user's explicit choice (2026-07-26). 0.7.0 (2026-08-01):
        // the portrait+landscape virtual touch controller -- 4D mode is now fully playable
        // without a physical gamepad, closing the biggest known gap before a Play Store listing
        // would make sense. 0.7.1 (2026-08-01): patch fix found minutes after 0.7.0 shipped --
        // connecting a real gamepad didn't hide the virtual controller or restore the old HUD.
        // 0.7.2 (2026-08-04): first Play Console open-testing upload -- bundles everything merged
        // since the 0.7.1 GitHub release (Stick Select forget-on-release + opposite-cell modifier,
        // API 36 target, privacy policy, Play listing draft, various Help/label fixes) that never
        // got its own tagged release. 0.7.3 (2026-08-04): rapid twist input no longer gets dropped
        // mid-animation -- it's queued and played back at a dynamically increasing speed (same
        // formula Hyperspeedcube's own twist queue uses) instead of silently discarded, fixing the
        // sluggish-feeling rapid-twisting reported right after 0.7.2 shipped. 0.7.4 (2026-08-06):
        // piece filters rebuilt as a filter-set/filter/subfilter hierarchy (built-in Pieces/CFOP/
        // David's own hand-authored 3block filter set, a new cell-or-type-letter union + "-"
        // subtraction token grammar, clipboard import/export/reset) plus a persistent "Time: M:SS"
        // solve timer. 0.8.0 (2026-08-19): configurable ABXY/R1/R2(+Button-C) button-twist grid,
        // MC4D log export fixes (edge-twist grip Z-axis sign, edge community notation, a live
        // export round-trip checker that caught two real bugs), a fix for cell O twisting
        // LEFT/RIGHT backwards, the "+" accumulate/reset subfilter grammar, and David's
        // hand-authored 3block filter set restructured/renamed into named F2L/OLC/PLC stages.
        // 0.8.1 (2026-09-07): virtual controls now stay on screen even with a real gamepad
        // connected (highlighting from real input too, retiring the old small status-HUD
        // swap-over), and show each button's live function -- text as before, or, for a
        // face-diamond/R1/R2 twist with a cell selected, a small isometric-cube rotation-direction
        // icon (TwistIcon) instead of RO/Rx-style notation. Z Dir Left/Right now only swaps which
        // native-name caption is shown at a fixed screen position, never the function itself. A
        // d-pad-driven cell selection now also moves the on-screen stick dot, matching the real
        // analog stick.
        // Still not 1.0: whether Fire TV is even a 1.0 target remains undecided, and Settings is
        // still missing its D-pad-assignment rows.
        versionCode = 12
        versionName = "0.8.1"
        buildConfigField("String", "GIT_VERSION", "\"$gitVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Only created when keystore.properties actually exists (see its doc above) -- Gradle's
        // signingConfigs block can't easily be skipped conditionally from the outside, so this
        // creates it either way but points storeFile at a file that simply won't exist without
        // the properties backing it, which is caught below rather than left to fail obscurely
        // deep in the signing step.
        if (releaseSigningProps != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProps.getProperty("storeFile"))
                storePassword = releaseSigningProps.getProperty("storePassword")
                keyAlias = releaseSigningProps.getProperty("keyAlias")
                keyPassword = releaseSigningProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Rust .so files are built by the `cargoNdkBuild` task below and copied
    // into src/main/jniLibs before the APK's native libs are packaged.
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// --- Native (Rust) build wiring -------------------------------------------
//
// The puzzle-math core lives in ../native/puzzle-core (a Rust crate compiled
// to a cdylib per Android ABI via cargo-ndk). Gradle just shells out to
// cargo-ndk and copies the resulting .so files into jniLibs; it does not
// try to model the Rust build graph itself.

val rustAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val cargoBinDir = file("${System.getProperty("user.home")}/.cargo/bin")

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds the Rust puzzle-core crate for all target Android ABIs via cargo-ndk."

    workingDir = file("${project.rootDir}/native/puzzle-core")
    val jniLibsDir = file("${project.projectDir}/src/main/jniLibs")

    val abiArgs = rustAbis.flatMap { listOf("-t", it) }
    commandLine(
        listOf("cmd", "/c", "cargo", "ndk") + abiArgs + listOf(
            "-o", jniLibsDir.absolutePath,
            "build", "--release",
        )
    )

    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    environment("PATH", "${cargoBinDir.absolutePath};${System.getenv("PATH")}")

    inputs.dir("${project.rootDir}/native/puzzle-core/src")
    inputs.file("${project.rootDir}/native/puzzle-core/Cargo.toml")
    outputs.dir(jniLibsDir)
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(cargoNdkBuild) }
