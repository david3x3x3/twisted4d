# To-Do: Controller Input Scheme

Running list of feature/change requests for the puzzle app's controller
controls. Add new items to the bottom as they come up. Ask Claude Code to
mark an item `[x]` once it's implemented, and leave it in place (don't
delete) so there's a record of what's done.

## Cell selection (left stick)

- [x] Left stick selects one of the 8 cells (front, back, up, down, left,
      right, in, out) by direction, and holding it highlights the
      currently selected cell.
- [x] Cardinal directions select 4 cells directly:
  - Up → up cell
  - Down → down cell
  - Left → in cell
  - Right → out cell
- [x] Diagonal directions select the other 4 cells:
  - Up-left / down-right diagonal → left / right cells
  - Up-right / down-left diagonal → front / back cells
- [x] Exact diagonal-to-specific-cell assignment (e.g. does up-left mean
      "left" or "right" specifically) is not yet locked in — flexible for
      now, can be decided during implementation.

      Decided (as of 2026-07-20, after trying it and reversing a couple):
      up-left → L, down-right → R, up-right → B, down-left → F, right → I,
      left → O (see HypercubeRenderer.updateCell4Selection).

- [x] ~~Selection should be screen-relative~~ (tried, reverted 2026-07-20:
      approximating the stick's fixed 45-degree wedges against each wall's
      actual on-screen angle broke down badly, e.g. R/L structurally
      always sit at exactly 0/180 degrees on screen for this camera's
      pitch-then-yaw, no-roll rotation order — never near the 315/135
      degree diagonal targets — so R and L could never be selected via
      the down-right/up-left wedges at all). Selection is a fixed mapping
      instead: each 45-degree stick wedge always means the same *room
      slot* (e.g. down-right = the room's own +X slot), resolved to
      whichever native cell currently occupies that slot via
      [cubeOrientation4] alone — see
      HypercubeRenderer.updateCell4Selection/nativeCellInRoomSlot. This
      still means the mapping isn't static forever: rotating the room via
      the XW/YW/ZW buttons (which is what actually changes
      cubeOrientation4) changes which cell a wedge selects. Dragging the
      view around does not.
- [x] Snap the view to the nearest "nice" cardinal-ish angle (mirroring
      CubeRenderer.snapToNearestCardinalOrientation) as soon as the left
      stick moves significantly off-center, and also whenever a twist is
      applied via a rotation button (even without actively re-selecting
      via the stick) — purely a visual realignment now (see above), no
      longer tied to what a stick direction selects. See
      HypercubeRenderer.snapViewToNearestCardinalOrientation.

## Rotation controls (right-side buttons, once a cell is selected)

- [x] Left/Right face buttons → Y-axis rotation
- [x] Up/Down face buttons → X-axis rotation
- [x] Right bumper / right trigger → Z-axis rotation
- [x] Button pressed (not a separate modifier) determines twist direction
      (CW vs CCW) — no extra "direction" input needed.

      Implementation note: for the 6 cells whose own axis is X/Y/Z
      (U/D/L/R/F/B), the button-pair matching that axis would otherwise
      request an invalid twist (fixAxis2 == cell's own axis). Per
      clarification, this is dynamically remapped to fixAxis2 = W instead
      of being disabled — which happens to reproduce the same physical
      rotation as an ordinary 3D twist of that cell, since the two
      *other* spatial axes end up rotating either way. See
      RotationButton/GamepadInputHandler.on4DRotationButton and
      MainActivity's 4D gamepad wiring.

### Rotation direction convention (all counterclockwise-based)

- [x] Right button: rotates so the positive X axis moves away from the
      user (i.e., positive X → negative Z direction).
- [x] Up button: rotates negative Z axis toward positive Y (i.e.,
      negative Z → positive Y direction).
- [x] Primary trigger (RT): rotates positive Y axis toward negative X
      (i.e., positive Y → negative X direction).
- [x] Left/Down/other buttons in each pair should follow the same
      rotational convention in the opposite direction (implementation
      should derive these consistently rather than special-casing each
      one).

      Implementation: RotationButton's literalAxis + primaryPrime
      constants alone reproduce these three examples via the existing
      twist-animation math, and generalize consistently to the W-axis
      cases above without special-casing.

## Open items / follow-ups

- [x] Decide final diagonal→cell assignment (see above).
- [ ] Confirm bumper vs. trigger mapping once physically testing on the
      Retroid Pocket 3, since button layouts can vary by device.

## Follow-ups from this implementation pass

- [ ] Now that the left stick selects a cell in 4D mode, the right stick
      drives camera orbit there instead (per clarification during
      implementation) — confirm this feels right once tested on-device,
      since touch-drag also still orbits the camera independently.
- [ ] No on-screen (touch) UI equivalent of the new gamepad rotation
      scheme was added — the existing cell/axis row buttons are
      unchanged and still use the old direct-twist behavior. Decide if
      the on-screen UI should also be updated to match.
