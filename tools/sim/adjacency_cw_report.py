"""
Adjacency/CW-order report generator for twisted4d's 4D room, built on top of cube4_sim.py.

For every one of the 8 room cells (U, D, L, R, F, B, I, O) and every valid "fixAxis2" choice (the
second fixed axis, matching community 2-letter notation like "LU"), this reports the CW cycle of
the 4 room cells that rotate around that (cell, fixAxis2) pair -- i.e. "press this virtual button
and the affected cells rotate in this order, when the label carries no apostrophe."

Room O's own face is never rendered (see HypercubeRenderer's doc comment) -- but O *is* a real,
selectable room slot (STICK mode can aim the left stick at it, see updateCell4Selection's `deg <
202.5 && deg >= 157.5` branch), and twisting the O layer visibly rotates the other 4 affected
cells, all of which *are* rendered. So O's row can still be verified on a real device: select room
O via STICK mode, press the rotation button for the fixAxis2 you want, and watch the 4 named cells
cycle -- you just can't watch an "O wall" directly the way you can for the other 7.

Ground truth (UNREORIENTED baseline): Notation.kt's PRIME_FLIP_TWISTS is native-keyed and was
empirically confirmed by a real-controller, real-screen survey (see NotationTest.kt) -- when
unreoriented, room == native, so this table directly gives a trustworthy CW order for every
(cell, fixAxis2) pair. This part of the report is ground truth, not theory.

Because ROOM cell positions are physically fixed (L is always the same wall, regardless of which
native piece currently sits there -- that's the entire point of the room abstraction), the CW
order for a given (room_cell, room_fixAxis2) pair should be *the same* no matter how the room has
been reoriented. This script's main job is checking that invariant against the app's *actual*
current logic (Notation.correctedPrimeForDisplay, room-keyed PRIME_FLIP_TWISTS lookup) for any
reorientation state, by literally running the twist the app would run and reading off where
stickers move -- not by re-deriving a handedness formula that could have its own bugs.

Confirmed finding (2026-07-19 repro: select room R -> reorient to I, select room U -> reorient to
I x3, select room L, press the button that labels "LU"): the app's current room-keyed
correctedPrimeForDisplay gives the *reverse* CW order for reoriented "L fixed with U" compared to
the unreoriented, hardware-confirmed baseline (O->F->I->B vs the true F->O->B->I, i.e. actually
CCW on screen despite the "no apostrophe"/CW label). This means the room-keyed apostrophe fix
from earlier this session (see mc4d_log_compatibility / NotationTest.kt) fixed *which letters*
get shown but did not fully fix the apostrophe's physical correctness after reorientation --
that needs more work; the fix is not yet in Notation.kt.

A candidate general correction ("extra_flip" below, comparing the native rotating-axis pair's
image in room space against the reference orientation via a 2x2 submatrix determinant) is kept
here for future iteration, but is NOT validated -- a first attempt at using it to override the
prime for "L fixed with U" produced a DIFFERENT reoriented order than the unreoriented baseline,
which can't be right (the room-level order must be orientation-invariant), so something about
that formula or its interaction with PRIME_FLIP_TWISTS is still wrong. Don't trust extra_flip's
output without rechecking the math; it's included so the next iteration doesn't start from zero.

Fixed bug (2026-07-22, caught during on-screen review of the U cell): every "fixed with I" row
(fixAxis2 = W) was mislabeled -- fix_axis2_cell_name special-cased axis W to pick 'I' (sign -1)
instead of 'O' (sign +1), when Notation.axisRepresentativeCell picks the SAME sign (+1) uniformly
for every axis (R, U, F, O for X, Y, Z, W -- no special case). Confirmed directly against a real
device: pressing the button for native (cell=U, fixAxis2=W, prime=false) actually shows label
"UO", not "UI", and the DEBUGTEST twist log + a before/after scene-dump comparison both confirmed
the underlying CYCLE was already correct (R->F->L->B, matching this report) -- the bug was purely
cosmetic (the printed name), not a physics/order bug, and it affected all six cells that have a
"fixed with I"-labeled row (U, D, L, R, F, B), not just U.

Usage: run directly for (1) the unreoriented ground-truth report, and (2) a live comparison for
the confirmed repro. Import `report(sim, use_current_app_logic=True/False)` and `cw_cycle_for(...)`
to check other specific scenarios.
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from cube4_sim import (
    Cube4Sim, mat_vec_mul, mat_mat_mul, plane_rotation_native,
    X, Y, Z, W, AXIS_NAMES, CELL_AXIS_SIGN, AXIS_SIGN_TO_CELL, HOME_POSITIONS,
)
import copy

# Native-keyed, matches Notation.PRIME_FLIP_TWISTS exactly (see NotationTest.kt's doc for how
# each entry was confirmed: 11 by exhaustive on-screen survey, 3 by MC4D round-trip export).
PRIME_FLIP_TWISTS = {
    ('U', Z), ('D', X), ('L', Z), ('R', Y), ('F', X), ('B', Y), ('I', X), ('I', Z),
    ('R', W), ('D', W), ('F', W), ('O', Y),
}

REPORTED_CELLS = ['U', 'D', 'L', 'R', 'F', 'B', 'I', 'O']  # O's own face is unrendered, but its
# row is still verifiable by watching the other 4 affected (rendered) cells cycle -- see the
# module docstring.


def rotating_axes(cell_axis, fix_axis2):
    return sorted(a for a in (X, Y, Z, W) if a not in (cell_axis, fix_axis2))


def room_axis_of(cube_orientation4, native_axis):
    for j in range(4):
        if abs(cube_orientation4[j][native_axis]) > 0.5:
            return j
    raise Exception("no room axis found")


def extra_flip(cube_orientation4, cell_axis, fix_axis2):
    """UNVALIDATED candidate correction -- see module docstring. Do not trust without rechecking."""
    r0, r1 = rotating_axes(cell_axis, fix_axis2)
    room_ca = room_axis_of(cube_orientation4, cell_axis)
    room_fa = room_axis_of(cube_orientation4, fix_axis2)
    q0, q1 = sorted(a for a in (X, Y, Z, W) if a not in (room_ca, room_fa))
    m = cube_orientation4
    det2 = m[q0][r0] * m[q1][r1] - m[q0][r1] * m[q1][r0]
    return det2 < 0


def deep_copy_sim(sim):
    new = Cube4Sim.__new__(Cube4Sim)
    new.pieces = [copy.deepcopy(p) for p in sim.pieces]
    new.cube_orientation4 = [row[:] for row in sim.cube_orientation4]
    new.selected_room_axis = sim.selected_room_axis
    new.selected_room_sign = sim.selected_room_sign
    return new


def cw_cycle_for(sim, room_cell, room_fix_axis2_axis, use_current_app_logic=False):
    """Returns the 4-cycle of room cells (e.g. ['F','O','B','I']) that rotate for the "no
    apostrophe" twist around (room_cell, room_fix_axis2_axis), by actually applying that twist
    to a scratch copy and tracking where marked stickers move.

    use_current_app_logic=False (default): ground truth, valid when sim is unreoriented (room ==
    native) -- uses the native-keyed PRIME_FLIP_TWISTS directly on the resolved native pair.
    use_current_app_logic=True: mirrors what the app *actually* does today (Notation.
    correctedPrimeForDisplay), i.e. looks up PRIME_FLIP_TWISTS keyed by the ROOM cell/axis
    instead -- use this to check the app's real behavior against the ground-truth baseline for
    any reorientation."""
    room_ca_axis, room_ca_sign = CELL_AXIS_SIGN[room_cell]
    native_cell = sim.native_cell_in_room_slot(room_ca_axis, room_ca_sign)
    native_fix_axis2 = sim.native_axis_at_room_axis(room_fix_axis2_axis)

    if use_current_app_logic:
        is_flip = (room_cell, room_fix_axis2_axis) in PRIME_FLIP_TWISTS
    else:
        is_flip = (native_cell, native_fix_axis2) in PRIME_FLIP_TWISTS
    prime = is_flip  # "no apostrophe" native prime, per correctedPrime's own formula

    q0, q1 = sorted(a for a in (X, Y, Z, W) if a not in (room_ca_axis, room_fix_axis2_axis))
    starts = [(q0, 1), (q0, -1), (q1, 1), (q1, -1)]

    native_cell_axis, native_cell_sign = CELL_AXIS_SIGN[native_cell]

    trackers = {}
    for axis_idx, sign in starts:
        for i, home in enumerate(HOME_POSITIONS):
            p = sim.pieces[i]
            if p.pos[native_cell_axis] != native_cell_sign:
                continue
            for ha in range(4):
                hc = home[ha]
                if hc == 0:
                    continue
                homedir = [0, 0, 0, 0]
                homedir[ha] = hc
                cur = mat_vec_mul(p.orient, tuple(homedir))
                cam = mat_vec_mul(sim.cube_orientation4, cur)
                for j in range(4):
                    if abs(cam[j]) > 0.5:
                        if j == axis_idx and (1 if cam[j] > 0 else -1) == sign:
                            trackers[(axis_idx, sign)] = (i, ha, hc)
                        break
            if (axis_idx, sign) in trackers:
                break

    temp = deep_copy_sim(sim)
    cell_axis = CELL_AXIS_SIGN[native_cell][0]
    r0, r1 = rotating_axes(cell_axis, native_fix_axis2)
    rot = plane_rotation_native(r0, r1, not prime)
    for p in temp.pieces:
        if p.pos[cell_axis] == CELL_AXIS_SIGN[native_cell][1]:
            p.pos = mat_vec_mul(rot, p.pos)
            p.orient = mat_mat_mul(rot, p.orient)

    dest_of = {}
    for axis_idx, sign in starts:
        i, ha, hc = trackers[(axis_idx, sign)]
        p = temp.pieces[i]
        homedir = [0, 0, 0, 0]
        homedir[ha] = hc
        cur = mat_vec_mul(p.orient, tuple(homedir))
        cam = mat_vec_mul(temp.cube_orientation4, cur)
        for j in range(4):
            if abs(cam[j]) > 0.5:
                dest = (j, 1 if cam[j] > 0 else -1)
                break
        dest_of[AXIS_SIGN_TO_CELL[(axis_idx, sign)]] = AXIS_SIGN_TO_CELL[dest]

    cycle = [AXIS_SIGN_TO_CELL[starts[0]]]
    for _ in range(3):
        cycle.append(dest_of[cycle[-1]])
    return cycle


def fix_axis2_cell_name(fix_axis2):
    """Matches Notation.axisRepresentativeCell exactly: R, U, F, O for X, Y, Z, W -- an earlier
    version of this function special-cased W to pick 'I' (sign -1) instead of 'O' (sign +1),
    which was simply wrong (confirmed against a real device: the app labels this "UO", not "UI"
    -- see the 2026-07-22 repro below). The cycle math itself was never affected by this, since
    cw_cycle_for takes the raw axis, not this display name -- only the printed label was wrong,
    for all six cells' "fixed with I" row (should read "fixed with O")."""
    for c, (ax, sg) in CELL_AXIS_SIGN.items():
        if ax == fix_axis2 and sg == 1:
            return c


def report(sim, label="report", use_current_app_logic=False):
    print(f"=== {label} ===")
    for cell in REPORTED_CELLS:
        cell_axis, _ = CELL_AXIS_SIGN[cell]
        other_axes = [a for a in (X, Y, Z, W) if a != cell_axis]
        print(f"\n{cell}:")
        for fix_axis2 in other_axes:
            cycle = cw_cycle_for(sim, cell, fix_axis2, use_current_app_logic)
            print(f"  fixed with {fix_axis2_cell_name(fix_axis2)} ({AXIS_NAMES[fix_axis2]}): "
                  f"CW order = {' -> '.join(cycle)} -> (back to {cycle[0]})")


if __name__ == '__main__':
    baseline_sim = Cube4Sim()  # solved, unreoriented -- ground truth
    report(baseline_sim, "UNREORIENTED baseline (ground truth, hardware-confirmed)")

    print()
    print("=== L fixed with U: baseline vs. the confirmed repro's reoriented state ===")
    print("baseline (ground truth):        ", cw_cycle_for(baseline_sim, 'L', Y))

    repro_sim = Cube4Sim()
    repro_sim.select_room_slot(X, 1); repro_sim.reorient_selected_to_i()
    repro_sim.select_room_slot(Y, 1)
    repro_sim.reorient_selected_to_i(); repro_sim.reorient_selected_to_i(); repro_sim.reorient_selected_to_i()
    print("reoriented, current app logic:  ", cw_cycle_for(repro_sim, 'L', Y, use_current_app_logic=True))
    print("-> same cells, but check the order carefully: is it the same cycle, or reversed?")
    print()
    print("(A reversed cycle here means the app's 'no apostrophe' twist for reoriented LU")
    print(" renders CCW, contradicting the CW the label promises -- this is the confirmed bug.)")
