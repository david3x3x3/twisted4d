# Google Play listing text (draft)

Working draft for the Play Store listing. Not final — review/edit before submitting.

## App name

Twisted 4D

## Short description (max 80 characters)

A true 4D twisty puzzle you can actually solve -- gamepad or touch controls.

(78 characters)

## Full description (max 4000 characters)

Twisted 4D is a 4D twisty-puzzle simulator for Android -- a fully interactive 3^4 hypercube
(tesseract) you can scramble and solve.

CONTROLS
Works great with a Bluetooth or USB game controller -- more precise and comfortable for serious
solving -- or with on-screen touch controls if you don't have one handy. Supports both portrait
and landscape orientations.

Two control modes:
- Stick Select: hold a direction to pick a cell, then twist it -- the general-purpose way to solve
- RKT: a streamlined scheme for the last phase of a solve, once every remaining twist targets the
  same one or two cells

SOLVING AIDS
- Piece-type filters (centers, ridges, edges, corners) fade out pieces you don't need to look at
  yet, so you can focus on one stage of a solve at a time
- A solve timer, undo/redo, scramble, and reset
- Export your solve in standard community twist notation, or as a MagicCube4D-compatible log file,
  for sharing or analysis in other tools

Twisted 4D was inspired by Hyperspeedcube and MagicCube4D -- see the in-app Help screen for full
credits.

---

## Notes for whoever submits this

- Word "Rubik" is deliberately avoided throughout (trademark) -- see the conversation that
  produced this draft, 2026-08-02.
- No mention of a 3D cube mode -- not reachable from the UI (David's call, 2026-08-01/confirmed
  again 2026-09-15 as staying that way for 1.0), so it's not a feature to advertise. The 3D
  renderer code still exists internally, just unused.
- Full description is ~1450 characters as of this draft -- well under the 4000 limit, room to
  expand if needed.
- Screenshots live in screenshots/ -- see that folder's own notes; refreshed 2026-09-15 for the
  0.9.0 layout/rendering changes (reserved puzzle viewport, single-spaced status lines, recentered
  default view -- see the app/build.gradle.kts versionName comment for the full list).
- The Play Store listing is already live and public as of this draft (not just open testing) --
  this file may already be somewhat behind whatever text is actually live; treat it as a base to
  reconcile against the live listing, not as ground truth of what's currently published.
