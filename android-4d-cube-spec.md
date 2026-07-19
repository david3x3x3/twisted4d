# Android 4D/3D Twisty-Puzzle Simulator — Project Spec

## Goal

A native Android app (Kotlin \+ native code) that simulates a 3D and 4D Rubik's-cube-style twisty puzzle, inspired by the open-source desktop app **Hyperspeedcube** ([https://github.com/HactarCE/Hyperspeedcube](https://github.com/HactarCE/Hyperspeedcube), by Andrew Farkas, MIT OR Apache-2.0 licensed). This is a from-scratch implementation, not a port — Hyperspeedcube's public source/docs can be referenced for the 4D math and puzzle-state logic, with credit given per its license terms.

Target devices include handheld Android gaming devices with built-in physical controllers (e.g. Retroid Pocket 3), so gamepad input support is a first-class requirement, not an afterthought.

## Tech stack

- **Language:** Kotlin for app/UI/lifecycle layer; native code (Rust or C++ via NDK/JNI) for the performance-critical puzzle math (4D rotation, projection, twist/permutation logic).  
- **Rendering:** OpenGL ES 3.x to start (Vulkan later if performance demands it). Rendering lives in native code or in Kotlin via GLSurfaceView, TBD based on how much logic stays native.  
- **Input:** Android `InputDevice` / `KeyEvent` / `MotionEvent` APIs for gamepad support, plus standard touch input for phones/tablets without controllers.  
- **Build:** Gradle \+ Android NDK (cmake or cargo-ndk if using Rust).

## Core architecture

1. **Puzzle model (native layer)**  
     
   - Represent an N-dimensional cube (start with 3D, then generalize to 4D) as a set of pieces/stickers with facelet coordinates.  
   - 4D rotations represented as rotors/bivectors (not naive 4x4 matrices) to avoid gimbal issues — mirror Hyperspeedcube's approach here.  
   - Twist logic: given a layer/axis selection, compute the resulting permutation and animate the transition.  
   - Expose a clean JNI/FFI boundary: puzzle state queries, twist commands, scramble, solve-check.

   

2. **Projection & rendering (native or Kotlin+GL)**  
     
   - 4D → 3D perspective or orthographic projection (configurable "4D camera" distance/angle), then standard 3D → 2D projection for screen.  
   - Piece/sticker mesh generation from the puzzle model.  
   - Smooth twist animations (easing, configurable speed).

   

3. **App/UI layer (Kotlin)**  
     
   - Puzzle selector (start with 3x3x3, then 3D NxNxN, then 4D 3^4).  
   - Touch controls: drag-to-rotate view, tap/drag-to-twist.  
   - **Gamepad controls:** map d-pad/sticks to view rotation, face buttons to axis/layer selection and twist direction, shoulder buttons for modifiers (e.g. wide turns, inverse twists). Should work automatically on Retroid-style devices via Android's generic gamepad recognition — but build an in-app button-remapping screen since face-button layouts vary by device.  
   - Timer/scramble/stats screen (optional v2 feature, Hyperspeedcube has this for speedsolving).

## Suggested milestones for Claude Code

1. **Scaffold:** Android project, Gradle config, NDK toolchain wired up, a blank GLSurfaceView rendering a triangle.  
2. **3D cube MVP:** Render a static 3x3x3 cube, implement one axis of rotation and one twist, touch-drag camera control.  
3. **Gamepad input MVP:** Detect a connected controller, log button/axis events, map sticks to camera rotation as a first test.  
4. **Full 3D puzzle:** All twists, smooth animation, scramble/solve state tracking, gamepad twist controls.  
5. **4D generalization:** Extend the piece/permutation model to 4D, add 4D-to-3D projection and a "4D camera" control, render a 4D puzzle (e.g. 3^4 hypercube) in cross-section or projected form.  
6. **Polish:** Puzzle selection menu, settings (colors, projection type, controller remapping), stats/timer.

## Licensing note for Claude Code

Do not copy Hyperspeedcube source code directly unless a specific file is explicitly pulled in and credited per its MIT/Apache-2.0 license (copyright notice retained). Prefer reimplementing the math from first principles/public documentation.

## Open questions to resolve during implementation

- Rust-via-JNI vs C++ via NDK for the native layer (Rust preferred if toolchain setup proves reasonable, since Hyperspeedcube's logic is already in Rust and easier to cross-reference).  
- Whether rendering lives fully native (via a native GL context) or in Kotlin with native code only for puzzle math.

