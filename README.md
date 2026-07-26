# twisted4d

A native Android app that simulates 3D and 4D Rubik's-cube-style twisty puzzles — a standard
3x3x3 cube, and a 3⁴ hypercube (tesseract) rendered as an unfolded room of six outer cells around
a floating center cell. The UI/app layer is Kotlin; the 4D puzzle math and twist logic live in a
native Rust core.

twisted4d is built gamepad-first, and a gamepad is currently required for actual play — twisting
and cell selection in 4D mode (the app's core feature) only work from a controller right now.
Touch covers camera orbit/zoom and utility actions (scramble, reset, exporting a twist log, some
settings), but not twisting itself. Full touch-only play, with no gamepad needed at all, is a
future goal, not current behavior.

The project was inspired by [Hyperspeedcube](https://github.com/HactarCE/Hyperspeedcube) and
[MagicCube4D](https://superliminal.com/cube/); see the in-app Help screen for full credits.

## Devices

twisted4d is actively being tested on:

- **Retroid Pocket 3** — a handheld Android gaming device with a built-in controller
- **8BitDo Micro** — a compact Bluetooth gamepad
- **Sony DualSense** (PS5 controller) — connected over Bluetooth

These are examples, not requirements — any Android gamepad recognized by Android's standard
`InputDevice`/`KeyEvent` APIs should work. A gamepad is currently required for actual play (see
above); the app itself runs on any Android device running API 26+ (Android 8.0 or later), but
you'll need a controller attached to use it.

## Controls

See the in-app Help screen (tap the Help button) for the full control scheme, covering touch,
3D-mode gamepad controls, and 4D mode's Stick/RKT input schemes.

## Credits and originality

twisted4d is an independent, from-scratch implementation — not a port, fork, or copy of either
Hyperspeedcube or MagicCube4D. The Kotlin app layer, the Rust puzzle-core, the rendering, the
notation handling, and the gamepad control scheme are all original code written for this project.

What it *does* share with those two projects is the "unfolded room" way of visualizing a 3⁴
hypercube (six outer cells arranged around a floating center cell). That visualization was
pioneered by MagicCube4D, the original 4D twisty-puzzle simulator dating to 1988; Hyperspeedcube,
a much newer reimplementation for the same hypercubing community, continued the same convention,
and twisted4d does too, for the same reason both of them did — it's the way this community has
come to expect a 4D twisty puzzle to look, not a proprietary design either project can claim
sole ownership of using. Full credits — Hyperspeedcube (Andrew Farkas), MagicCube4D (Don Hatch,
Melinda Green, Jay Berkenbilt, Roice Nelson) and its Android port (Raymond Zhao) — are listed in
the in-app Help screen; they're given because those projects' ideas and public documentation were
genuinely useful references, not despite it.

## Development process

This project was built with AI-assisted coding tools (Claude Code), under direct human design
decisions, code review, and real-device testing (gamepad handedness, input timing, and rendering
correctness were all verified on physical hardware, not just assumed) at every step along the way.

## License

MIT — see [LICENSE](LICENSE).
