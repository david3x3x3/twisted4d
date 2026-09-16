# twisted4d — Claude Code project notes

Orientation for Claude Code sessions working in this repo — not user-facing (see README.md for
that). twisted4d is a native Android app (Kotlin app/UI layer, Rust puzzle-core via JNI) simulating
a 3⁴ hypercube (tesseract) twisty puzzle: gamepad-first, with a full on-screen touch controller as
a no-gamepad-required fallback.

## How to maintain this file

The **Current status** section below is a rolling recap, not a log — when it goes stale (a new
session wraps up meaningful work, a release ships, a decision changes), **overwrite it in place**.
Don't append a dated new copy underneath the old one; that just recreates the "pile of stale
entries" problem this file exists to avoid. Keep it short enough to read in one pass: what's
shipped, what's committed-but-unreleased, what's explicitly been decided as out of scope, and
anything a fresh session would otherwise waste a turn rediscovering. Prune finished items rather
than marking them done and leaving them.

## Current status (as of 2026-09-15)

- **Released:** `v0.9.0` is tagged and pushed to GitHub (with a signed release APK attached to the
  release). Not yet submitted to Play Store — the last version actually live there is `0.8.1`.
- **Committed on `master` but deliberately NOT yet folded into a release** (David asked to hold
  off on releasing further for now):
  - `604c851` — fix stale puzzle-cell selection surviving a Start Menu/Help round-trip
  - `021ccf7` — fix stale HUD stick-indicator + a scroll-suppression regression in Help
  - Together these close out a real chain of controller-input bugs found via screen recordings
    and live on-device log-watching (real gamepad interference, dialog window-focus stealing all
    generic motion events). All confirmed fixed on a real Retroid Pocket 3 Plus.
- **1.0 scope, decided:** 3D mode stays hidden from the UI for 1.0 (code intact, just unreachable
  — not changing). Explicitly deferred, not 1.0 blockers: settable rotation speed, right-stick 4D
  rotation, Select+D-pad free bindings, Fire TV support, Linux build support, `.hsc` file import.
- **Testing setup:** Retroid Pocket 3 Plus over adb (serial `85530475449896`) is the primary
  real-device target; it periodically needs re-authorizing ("Allow USB debugging?") after being
  idle — if `adb devices` shows it `offline` or missing, ask David to check the device screen. A
  `Medium_Phone_API_36.1` emulator covers wide-aspect-ratio (Pixel-class) checks. A DualSense is
  often connected too, which matters for gamepad-interference bugs (see idle-controller-clobber
  fix in `MainActivity.handleLeftStickInput`).

## Build / test / deploy

- `./gradlew assembleDebug` / `assembleRelease` / `bundleRelease` (AAB, for Play Store). The Rust
  puzzle-core build (`cargoNdkBuild`) shells out via `cmd /c` — Windows-only for now.
- Release artifacts (APK/AAB) get copied to `G:\My Drive\Twisted4D\` continuously during
  iteration, not just when asked — matches the existing workflow habit.
- A GitHub release always gets a signed release APK attached, not just the git tag.
- Confirm the version number with David before tagging any release; don't pick one unilaterally.
