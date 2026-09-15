# twisted4d

A native Android app that simulates a 4D Rubik's-cube-style twisty puzzle — a 3⁴ hypercube
(tesseract) rendered with a true 4D→3D perspective projection (tapered, MC4D-style cells, not a
flat unfolded net). The UI/app layer is Kotlin; the 4D puzzle math and twist logic live in a
native Rust core.

twisted4d is built gamepad-first — cell selection and twisting feel most precise with a physical
controller, and the in-app Help screen documents the full gamepad control scheme. A full on-screen
touch controller is also built in (portrait and landscape), so no gamepad is required to play — it
mirrors the physical control scheme directly, mode for mode and button for button.

(An ordinary 3D Rubik's cube mode also exists in the codebase — it predates the 4D puzzle and
isn't currently exposed in the UI.)

The project was inspired by [Hyperspeedcube](https://github.com/HactarCE/Hyperspeedcube) and its
predecessor, [MagicCube4D](https://superliminal.com/cube/); see the in-app Help screen for full
credits.

## Devices

twisted4d is actively being tested on:

- **Retroid Pocket 3** — a handheld Android gaming device with a built-in controller
- **8BitDo Micro** — a compact Bluetooth gamepad
- **Sony DualSense** (PS5 controller) — connected over Bluetooth
- **Joso BSP-D3** — a clip-on Bluetooth controller that clamps around the phone
- **Google Pixel** (phone) — touch-only, using the built-in virtual controller

These are examples, not requirements — any Android gamepad recognized by Android's standard
`InputDevice`/`KeyEvent` APIs should work, and any touchscreen device works with no gamepad at
all. The app runs on any Android device running API 26+ (Android 8.0 or later).

## Controls

See the in-app Help screen (tap the Help tile in the Start Menu, or the virtual controller's About
button for a short introduction) for the full control scheme, covering the on-screen virtual
controller and the gamepad's Stick/RKT input schemes.

## Credits and originality

twisted4d is an independent, from-scratch implementation — not a port, fork, or copy of either
Hyperspeedcube or MagicCube4D. The Kotlin app layer, the Rust puzzle-core, the rendering, the
notation handling, and the gamepad control scheme are all original code written for this project.

What it *does* share with those two projects is the "unfolded room" way of visualizing a 3⁴
hypercube (six outer cells arranged around a floating center cell). That visualization was
pioneered by MagicCube4D, dating to 1988; Hyperspeedcube, a much newer reimplementation for the
same hypercubing community, continued the same convention, and twisted4d does too, for the same
reason both of them did — it's the way this community has come to expect a 4D twisty puzzle to
look, not a proprietary design either project can claim sole ownership of using.

twisted4d was inspired by:

- **Hyperspeedcube**, by Andrew Farkas (HactarCE)
- **MagicCube4D**, Hyperspeedcube's predecessor, by Don Hatch, Melinda Green, Jay Berkenbilt, and
  Roice Nelson
- **MagicCube4D's Android port**, also by Melinda Green
- **MagicCube4D (Raynefork)**, Raymond Zhao's fork of that Android port adding features and
  modern-Android compatibility

Full credits are also listed in the in-app Help screen; they're given because those projects'
ideas and public documentation were genuinely useful references, not despite it.

## Building from source

Prerequisites:

- JDK 17
- Android SDK, with NDK 27.2.12479018 installed
- Rust, plus [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) with the
  `aarch64-linux-android`, `armv7-linux-androideabi`, and `x86_64-linux-android` targets added
  (`rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`)

Then build with:

```
./gradlew assembleDebug
```

**Windows only, for now:** the Gradle task that builds the Rust puzzle-core (`cargoNdkBuild` in
`app/build.gradle.kts`) currently shells out via `cmd /c cargo ndk ...`, so building from source
only works on Windows today. Getting this working on Linux (and macOS) is on the to-do list.

## Development process

This project was built with AI-assisted coding tools (Claude Code), under direct human design
decisions, code review, and real-device testing (gamepad handedness, input timing, and rendering
correctness were all verified on physical hardware, not just assumed) at every step along the way.

## License

MIT — see [LICENSE](LICENSE).
