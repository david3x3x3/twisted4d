plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.twisted4d.app"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.twisted4d.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
