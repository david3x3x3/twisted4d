import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.twisted4d.app"
        minSdk = 26
        targetSdk = 34
        // versionCode is the Play-Store-style monotonically-increasing build counter (never
        // shown to users, just what the package manager compares to decide "is this an
        // upgrade") -- bump it by 1 on every future release regardless of versionName.
        // versionName is the free-form user-visible string; semantic versioning
        // (MAJOR.MINOR.PATCH) is the usual convention. 0.x.y means "pre-1.0" -- no promise of
        // stability yet -- per the user's explicit choice (2026-07-26). 0.5.0, not 0.9.0: real
        // open questions remain before 1.0 (whether Fire TV is even a 1.0 target, on-screen
        // menu/button cleanup), so "nearly there" would overclaim.
        versionCode = 2
        versionName = "0.5.0"
        buildConfigField("String", "GIT_VERSION", "\"$gitVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
