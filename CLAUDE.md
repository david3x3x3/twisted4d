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

## Current status (as of 2026-10-04)

- **Released:** `v1.0.0` is tagged and pushed to GitHub (with a signed release APK attached to
  the release). Note: `v0.9.0`'s tag/release were left as-is (historical) — they do NOT include
  the two fixes below; `v1.0.0` is a fresh tag off `master`, not a move of the old one.
  **Submitted to Play Store** (David, 2026-10-05) — pending Google's review. The last version
  actually live there is still `0.8.1` until this clears.
- **Folded into `v1.0.0`** (committed on `master` since `v0.9.0`, never previously tagged/released):
  - `604c851` — fix stale puzzle-cell selection surviving a Start Menu/Help round-trip
  - `021ccf7` — fix stale HUD stick-indicator + a scroll-suppression regression in Help
  - Together these close out a real chain of controller-input bugs found via screen recordings
    and live on-device log-watching (real gamepad interference, dialog window-focus stealing all
    generic motion events). All confirmed fixed on a real Retroid Pocket 3 Plus.
- **Investigated, no action taken:** a native SIGSEGV crash reported on the Retroid (2026-09-16,
  `dumpsys dropbox` tombstone), first occurrence. Backtrace is 100% inside ART's own
  ConcurrentCopying GC on the `HeapTaskDaemon` thread — no app or Rust/JNI frames present, and
  `native/puzzle-core/src/lib.rs`'s JNI boundary looks clean (safe `jni` crate array wrappers
  throughout, nothing held across calls). No actionable lead from this dump; treating as a
  possible vendor/GC-level one-off on this device (UNISOC ums512, Android 11) unless it recurs —
  if it does, re-pull the tombstone via `adb shell dumpsys dropbox --print` and compare.
- **1.0 scope, decided:** 3D mode stays hidden from the UI for 1.0 (code intact, just unreachable
  — not changing). Stats/timer-screen polish stays a deferred v2 feature per the original spec
  (not a 1.0 blocker). Explicitly deferred, not 1.0 blockers: settable rotation speed, right-stick
  4D rotation, Select+D-pad free bindings, Fire TV support, Linux build support, `.hsc` file
  import.
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
