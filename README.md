# twisted4d

A native Android app that simulates 3D and 4D Rubik's-cube-style twisty puzzles — a standard
3x3x3 cube, and a 3⁴ hypercube (tesseract) rendered as an unfolded room of six outer cells around
a floating center cell. The UI/app layer is Kotlin; the 4D puzzle math and twist logic live in a
native Rust core.

twisted4d treats gamepad play as a first-class citizen rather than an afterthought. Twisting,
cell selection, camera control, and undo/redo all work fully from a controller. Some utility
actions (scramble, reset, exporting a twist log, a few settings toggles) are still touch-only for
now — moving those onto the controller too is an ongoing goal.

The project was inspired by [Hyperspeedcube](https://github.com/HactarCE/Hyperspeedcube) and
[MagicCube4D](https://superliminal.com/cube/); see the in-app Help screen for full credits.

## Devices

twisted4d is actively being tested on:

- **Retroid Pocket 3** — a handheld Android gaming device with a built-in controller
- **8BitDo Micro** — a compact Bluetooth gamepad
- **Sony DualSense** (PS5 controller) — connected over Bluetooth

These are examples, not requirements — any Android device running API 26+ (Android 8.0 or later)
should work, with or without a gamepad attached.

## Controls

See the in-app Help screen (tap the Help button) for the full control scheme, covering touch,
3D-mode gamepad controls, and 4D mode's Stick/RKT input schemes.

## License

MIT — see [LICENSE](LICENSE).
