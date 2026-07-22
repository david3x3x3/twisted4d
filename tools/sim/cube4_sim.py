"""
Full, self-contained simulation of twisted4d's 4D puzzle: piece-level state (mirroring
native/puzzle-core/src/cube4.rs's Cube4 exactly) plus room orientation and on-screen rendering
(mirroring HypercubeRenderer.kt). This is a ground-truth oracle independent of the running app --
lets us compute "what should be visible" from first principles instead of reading device output
(screenshots or scene-dump broadcasts), which both require trusting + correctly interpreting the
app's own rendering. Cross-check periodically against the real app/real Rust tests; don't treat
this as infallible on its own (bugs in this file are exactly as possible as bugs anywhere else).

Usage: import Cube4Sim, drive it with .twist()/.reorient_room_slot_to_i(), then read
.visible_stickers() for a ground-truth list of (wall, position, color) exactly like the app's own
currentSceneColors(), but computed independently.
"""

import math

X, Y, Z, W = 0, 1, 2, 3
AXIS_NAMES = ['X', 'Y', 'Z', 'W']

CELL_AXIS_SIGN = {
    'U': (Y, 1), 'D': (Y, -1),
    'L': (X, -1), 'R': (X, 1),
    'F': (Z, 1), 'B': (Z, -1),
    'I': (W, -1), 'O': (W, 1),
}
AXIS_SIGN_TO_CELL = {v: k for k, v in CELL_AXIS_SIGN.items()}


def identity4():
    return [[1 if i == j else 0 for j in range(4)] for i in range(4)]


def mat_vec_mul(m, v):
    return tuple(sum(m[r][c] * v[c] for c in range(4)) for r in range(4))


def mat_mat_mul(a, b):
    return [[sum(a[r][k] * b[k][c] for k in range(4)) for c in range(4)] for r in range(4)]


def plane_rotation_native(a, b, clockwise):
    """Exactly matches cube4.rs's plane_rotation: used for actual twists (integer, exact)."""
    m = identity4()
    m[a][a] = 0
    m[b][b] = 0
    if clockwise:
        m[a][b] = -1
        m[b][a] = 1
    else:
        m[a][b] = 1
        m[b][a] = -1
    return m


def plane_rotation_float(a, b, angle_deg):
    """Matches HypercubeRenderer.kt's setPlaneRotation4: used for room reorientation (float,
    but always exact since only +-90 degree steps are used)."""
    m = identity4()
    c = round(math.cos(math.radians(angle_deg)))
    s = round(math.sin(math.radians(angle_deg)))
    m[a][a] = c
    m[a][b] = -s
    m[b][a] = s
    m[b][b] = c
    return m


class Piece:
    __slots__ = ('pos', 'orient')

    def __init__(self, pos, orient):
        self.pos = pos      # Vec4i, native position
        self.orient = orient  # Mat4i, native orientation


def solved_pieces():
    """Matches Cube4::solved's exact canonical order: nested x,y,z,w loops over {-1,0,1},
    skipping the hidden core (0,0,0,0). HypercubeGeometry.HOME_POSITIONS must match this order
    (per that Rust doc comment) -- so piece index i here corresponds to home position i there."""
    pieces = []
    for x in (-1, 0, 1):
        for y in (-1, 0, 1):
            for z in (-1, 0, 1):
                for w in (-1, 0, 1):
                    if x == 0 and y == 0 and z == 0 and w == 0:
                        continue
                    pieces.append(Piece((x, y, z, w), identity4()))
    return pieces


def cell_selects(cell, pos):
    axis, sign = CELL_AXIS_SIGN[cell]
    return pos[axis] == sign


class Cube4Sim:
    """Full simulation: native piece state (like Rust's Cube4) + room orientation (like
    HypercubeRenderer's cubeOrientation4/selectedRoomAxis/selectedRoomSign)."""

    def __init__(self):
        self.pieces = solved_pieces()
        self.cube_orientation4 = identity4()  # room orientation, native<->room signed permutation
        self.selected_room_axis = Y  # matches HypercubeRenderer's default (STICK's initial parked slot: U)
        self.selected_room_sign = 1

    # --- native twist, exactly matching Cube4::twist -----------------------------------------

    def twist(self, cell, fix_axis2_axis, prime):
        cell_axis, _ = CELL_AXIS_SIGN[cell]
        assert fix_axis2_axis != cell_axis, "fixAxis2 must differ from the cell's own axis"
        rotating = [a for a in (X, Y, Z, W) if a != cell_axis and a != fix_axis2_axis]
        rot = plane_rotation_native(rotating[0], rotating[1], not prime)
        for p in self.pieces:
            if cell_selects(cell, p.pos):
                p.pos = mat_vec_mul(rot, p.pos)
                p.orient = mat_mat_mul(rot, p.orient)

    # --- room orientation, exactly matching HypercubeRenderer --------------------------------

    def native_cell_in_room_slot(self, room_axis, room_sign):
        for cell, (axis, sign) in CELL_AXIS_SIGN.items():
            v = [0, 0, 0, 0]
            v[axis] = sign
            transformed = mat_vec_mul(self.cube_orientation4, tuple(v))
            if transformed[room_axis] * room_sign > 0.5:
                return cell
        raise Exception("no cell maps to that room slot")

    def native_axis_at_room_axis(self, room_axis):
        for native_axis in range(4):
            if abs(self.cube_orientation4[room_axis][native_axis]) > 0.5:
                return native_axis
        raise Exception("no native axis maps to that room axis")

    def selected_room_cell(self):
        """Matches HypercubeRenderer.selectedRoomCell: the room slot's own fixed label."""
        return AXIS_SIGN_TO_CELL[(self.selected_room_axis, self.selected_room_sign)]

    def selected_native_cell(self):
        """Matches HypercubeRenderer.selectedCell4."""
        return self.native_cell_in_room_slot(self.selected_room_axis, self.selected_room_sign)

    def select_room_slot(self, room_axis, room_sign):
        self.selected_room_axis = room_axis
        self.selected_room_sign = room_sign

    def reorient_selected_to_i(self):
        """Matches HypercubeRenderer.requestMoveSelectedCellToI. No-op if already on the W axis."""
        if self.selected_room_axis == W:
            return
        reverse = self.selected_room_sign > 0
        angle = -90 if reverse else 90
        delta = plane_rotation_float(self.selected_room_axis, W, angle)
        self.cube_orientation4 = mat_mat_mul(delta, self.cube_orientation4)

    def room_fix_axis2_for(self, button_literal_axis):
        """Matches HypercubeRenderer.roomFixAxis2For."""
        return W if button_literal_axis == self.selected_room_axis else button_literal_axis

    def resolve_fix_axis2(self, button_literal_axis):
        """Matches HypercubeRenderer.resolveRotationButtonFixAxis2 -- returns native axis."""
        return self.native_axis_at_room_axis(self.room_fix_axis2_for(button_literal_axis))

    # --- rendering: exactly matching HypercubeRenderer.currentSceneColors --------------------

    def visible_stickers(self):
        """Returns list of (room_axis, room_sign, color_cell, camera_pos) for every currently
        rendered sticker -- ground truth computed purely from piece state + cubeOrientation4,
        independent of the actual running app."""
        out = []
        for i, home in enumerate(HOME_POSITIONS):
            p = self.pieces[i]
            camera_pos = mat_vec_mul(self.cube_orientation4, p.pos)
            for axis_idx in range(4):
                home_coord = home[axis_idx]
                if home_coord == 0:
                    continue
                home_dir = [0, 0, 0, 0]
                home_dir[axis_idx] = home_coord
                current_dir = mat_vec_mul(p.orient, tuple(home_dir))
                camera_dir = mat_vec_mul(self.cube_orientation4, current_dir)
                slot_axis, slot_sign = -1, 0
                for j in range(4):
                    if abs(camera_dir[j]) > 0.5:
                        slot_axis, slot_sign = j, (1 if camera_dir[j] > 0 else -1)
                        break
                if slot_axis == W and slot_sign > 0:
                    continue  # O slot: never rendered
                color_cell = AXIS_SIGN_TO_CELL[(axis_idx, home_coord)]
                out.append((slot_axis, slot_sign, color_cell, camera_pos))
        return out

    def wall_colors(self, room_axis, room_sign):
        """Convenience: just the colors currently on one wall (or I-slot for room_axis=W,
        room_sign=-1), as a set (for a quick "is this wall still monochrome" check) or full list."""
        return [s for s in self.visible_stickers() if s[0] == room_axis and s[1] == room_sign]


# Matches Cube4::solved()'s exact piece order (see solved_pieces's doc) -- HOME_POSITIONS[i] is
# piece i's home/solved position, used to look up which axis/sign each of its stickers faces.
HOME_POSITIONS = [p.pos for p in solved_pieces()]


if __name__ == '__main__':
    # Sanity check: reproduces the user's exact repro from a fresh solved cube.
    sim = Cube4Sim()

    sim.select_room_slot(X, 1)  # select room R
    sim.reorient_selected_to_i()
    sim.select_room_slot(Y, 1)  # select room U
    sim.reorient_selected_to_i()
    sim.reorient_selected_to_i()
    sim.reorient_selected_to_i()
    sim.select_room_slot(X, -1)  # select room L

    print("room L holds native:", sim.selected_native_cell(), "(expect I)")

    cell = sim.selected_native_cell()
    fix_axis2 = sim.resolve_fix_axis2(Y)  # LEFT button's literalAxis is Y
    print(f"twisting native {cell} with fixAxis2={AXIS_NAMES[fix_axis2]}, prime=False")
    sim.twist(cell, fix_axis2, False)

    print()
    print("Room L wall after twist:")
    for s in sorted(sim.wall_colors(X, -1)):
        print(" ", s)


# --- RotationButton, matching GamepadInputHandler.kt exactly -------------------------------
ROTATION_BUTTONS = {
    'RIGHT': (Y, True), 'LEFT': (Y, False),
    'UP': (X, False), 'DOWN': (X, True),
    'TRIGGER_R': (Z, False), 'BUMPER_R': (Z, True),
}


def rotation_inverted_for_cell(button, room_cell):
    """Matches Notation.rotationInvertedForCell exactly."""
    if button in ('UP', 'DOWN'):
        return room_cell in {'D', 'L', 'R', 'F', 'I', 'O'}
    if button in ('LEFT', 'RIGHT'):
        return room_cell in {'R', 'B', 'O'}
    if button in ('TRIGGER_R', 'BUMPER_R'):
        return room_cell in {'U', 'L', 'F', 'B', 'I', 'O'}


def press_rotation_button(sim, button):
    """Matches MainActivity's on4DRotationButton closure exactly. Returns
    (cell, fixAxis2, prime, roomCell, roomFixAxis2) -- same shape as the app's TwistRecord."""
    literal_axis, primary_prime = ROTATION_BUTTONS[button]
    cell = sim.selected_native_cell()
    fix_axis2 = sim.resolve_fix_axis2(literal_axis)
    room_cell = sim.selected_room_cell()
    room_fix_axis2 = sim.room_fix_axis2_for(literal_axis)
    inverted = rotation_inverted_for_cell(button, room_cell)
    prime = primary_prime != inverted
    sim.twist(cell, fix_axis2, prime)
    return cell, fix_axis2, prime, room_cell, room_fix_axis2
