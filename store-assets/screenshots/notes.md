# Screenshots

All captured on the emulator at real resolution (no upscaling): landscape shots are 2400x1080,
portrait is 1080x2400.

- `landscape-02-start-menu.png` -- the Start Menu grid, clean/idle state
- `landscape-03-scrambled.png` -- mid-solve, scrambled cube, no menu open
- `landscape-04-settings.png` -- the 4D Settings submenu (Controller/Nintendo ABXY/Confirm
  Scramble-Reset/Z Dir/Export Format)
- `portrait-01-scrambled.png` -- same scrambled state as landscape-03, portrait orientation

`landscape-01-solved.png` (the original solved-state shot used for the feature graphic) was
removed from here since it's superseded by the scrambled ones -- the feature graphic itself
(`../feature-graphic-01.png`) is unaffected, already generated and approved.

Along the way, found and fixed a real bug while framing the Settings screenshot: the "Confirm
Scramble/Reset" tile's label overflowed its cell and got clipped by the neighboring tile drawn
after it (StartMenuView.kt's tile text draws with no width check). Fixed by breaking the label
onto its own line ("Confirm\nScramble/Reset\n..." in MainActivity.kt) rather than changing the
rendering logic -- see `landscape-04-settings.png`, renders cleanly now.

Still open: which of these actually go into the store listing, and whether a mid-scramble
feature graphic is wanted instead of the solved one already made.
