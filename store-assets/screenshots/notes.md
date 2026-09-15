# Screenshots

Refreshed 2026-09-15 for 0.9.0's rendering/layout changes (reserved puzzle viewport, single-spaced
status lines, recentered default view -- see app/build.gradle.kts' versionName comment for the
full list). Previous set (captured 2026-08-02, for v0.6.0) is superseded.

All captured on the `Medium_Phone_API_36.1` emulator at real resolution (no upscaling): landscape
shots are 2400x1080, portrait is 1080x2400. App version shown: v0.9.0.

- `landscape-02-start-menu.png` -- the Start Menu grid, clean/idle state
- `landscape-03-scrambled.png` -- mid-solve, scrambled puzzle (8 turns), no menu open
- `landscape-04-settings.png` -- the 4D Settings submenu (Controller/Nintendo ABXY/Z Dir/Confirm
  Scramble-Reset/Export Format/Button Config -- Button Config is new since the previous set)
- `portrait-01-scrambled.png` -- same scrambled state as landscape-03, portrait orientation

`landscape-01-solved.png` (the original solved-state shot used for the feature graphic) was
removed from the previous set since it's superseded by the scrambled ones -- the feature graphic
itself (`../feature-graphic-01.png`) is unaffected, already generated and approved, and wasn't
regenerated this pass since the app icon/branding didn't change.

Still open: which of these actually go into the store listing, and whether a mid-scramble feature
graphic is wanted instead of the solved one already made.
