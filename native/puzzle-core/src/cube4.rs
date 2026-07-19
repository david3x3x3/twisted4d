// 4D generalization of cube3.rs: a 3^4 hypercube ("MagicCube4D"-style) piece-based model.
//
// Pieces sit at integer coordinates in {-1, 0, 1}^4 minus the hidden core (0,0,0,0), giving
// 3^4 - 1 = 80 pieces. A "cell" (8 of them: U/D/L/R/F/B along X/Y/Z, plus I/O along the 4th
// axis W, matching MagicCube4D's naming) selects a 3D "layer" by fixing one axis at +-1. A
// twist rotates that layer by a 3D rotation confined to the other 3 axes -- concretely, pick
// one of the *other* 3 axes to also hold fixed (`fix_axis2`); the remaining 2 axes rotate into
// each other by 90 degrees. That gives 8 cells x 3 fix_axis2 choices x 2 directions = 48 twists.

pub type Vec4i = (i32, i32, i32, i32);
pub type Mat4i = [[i32; 4]; 4];

const IDENTITY4: Mat4i = [
    [1, 0, 0, 0],
    [0, 1, 0, 0],
    [0, 0, 1, 0],
    [0, 0, 0, 1],
];

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Axis4 {
    X = 0,
    Y = 1,
    Z = 2,
    W = 3,
}

impl Axis4 {
    pub const ALL: [Axis4; 4] = [Axis4::X, Axis4::Y, Axis4::Z, Axis4::W];

    pub fn index(self) -> usize {
        self as usize
    }

    pub fn from_index(i: i32) -> Option<Axis4> {
        match i {
            0 => Some(Axis4::X),
            1 => Some(Axis4::Y),
            2 => Some(Axis4::Z),
            3 => Some(Axis4::W),
            _ => None,
        }
    }

    fn get(self, v: Vec4i) -> i32 {
        match self {
            Axis4::X => v.0,
            Axis4::Y => v.1,
            Axis4::Z => v.2,
            Axis4::W => v.3,
        }
    }
}

/// One of the 8 cells of a 3^4 hypercube. Naming follows MagicCube4D: U/D/L/R/F/B for the
/// X/Y/Z axes (matching cube3's Face), and I(nner)/O(uter) for the 4th axis, W.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Cell4 {
    U = 0,
    D = 1,
    L = 2,
    R = 3,
    F = 4,
    B = 5,
    I = 6,
    O = 7,
}

impl Cell4 {
    pub const ALL: [Cell4; 8] = [
        Cell4::U,
        Cell4::D,
        Cell4::L,
        Cell4::R,
        Cell4::F,
        Cell4::B,
        Cell4::I,
        Cell4::O,
    ];

    pub fn from_index(i: i32) -> Option<Cell4> {
        match i {
            0 => Some(Cell4::U),
            1 => Some(Cell4::D),
            2 => Some(Cell4::L),
            3 => Some(Cell4::R),
            4 => Some(Cell4::F),
            5 => Some(Cell4::B),
            6 => Some(Cell4::I),
            7 => Some(Cell4::O),
            _ => None,
        }
    }

    fn axis(self) -> Axis4 {
        match self {
            Cell4::U | Cell4::D => Axis4::Y,
            Cell4::L | Cell4::R => Axis4::X,
            Cell4::F | Cell4::B => Axis4::Z,
            Cell4::I | Cell4::O => Axis4::W,
        }
    }

    fn sign(self) -> i32 {
        match self {
            Cell4::U | Cell4::R | Cell4::F | Cell4::O => 1,
            Cell4::D | Cell4::L | Cell4::B | Cell4::I => -1,
        }
    }

    fn selects(self, p: Vec4i) -> bool {
        self.axis().get(p) == self.sign()
    }
}

#[derive(Clone, Copy, PartialEq)]
pub struct Piece4 {
    pub pos: Vec4i,
    pub orient: Mat4i,
}

pub struct Cube4 {
    pub pieces: Vec<Piece4>,
}

fn mat_vec_mul(m: &Mat4i, v: Vec4i) -> Vec4i {
    let x = [v.0, v.1, v.2, v.3];
    let mut out = [0; 4];
    for row in 0..4 {
        out[row] = m[row][0] * x[0] + m[row][1] * x[1] + m[row][2] * x[2] + m[row][3] * x[3];
    }
    (out[0], out[1], out[2], out[3])
}

fn mat_mat_mul(a: &Mat4i, b: &Mat4i) -> Mat4i {
    let mut out = [[0; 4]; 4];
    for row in 0..4 {
        for col in 0..4 {
            out[row][col] = (0..4).map(|k| a[row][k] * b[k][col]).sum();
        }
    }
    out
}

/// A 90-degree rotation in the plane spanned by axes `a`/`b`, holding the other two axes
/// fixed. `clockwise` just needs to be consistent and self-inverse-under-negation; there's no
/// real-world chirality convention to match in 4D the way cube3 matches physical cubes.
fn plane_rotation(a: usize, b: usize, clockwise: bool) -> Mat4i {
    let mut m = IDENTITY4;
    m[a][a] = 0;
    m[b][b] = 0;
    if clockwise {
        m[a][b] = -1;
        m[b][a] = 1;
    } else {
        m[a][b] = 1;
        m[b][a] = -1;
    }
    m
}

impl Cube4 {
    /// Builds a solved cube. Piece order is the canonical nested loop x, y, z, then w, over
    /// {-1, 0, 1}, skipping the hidden core (0,0,0,0) -- the Kotlin renderer must generate its
    /// per-piece meshes in this exact same order.
    pub fn solved() -> Self {
        let mut pieces = Vec::with_capacity(80);
        for x in -1..=1 {
            for y in -1..=1 {
                for z in -1..=1 {
                    for w in -1..=1 {
                        if x == 0 && y == 0 && z == 0 && w == 0 {
                            continue;
                        }
                        pieces.push(Piece4 {
                            pos: (x, y, z, w),
                            orient: IDENTITY4,
                        });
                    }
                }
            }
        }
        Cube4 { pieces }
    }

    fn twist_layer(&mut self, rot: &Mat4i, select: impl Fn(Vec4i) -> bool) {
        for piece in self.pieces.iter_mut() {
            if select(piece.pos) {
                piece.pos = mat_vec_mul(rot, piece.pos);
                piece.orient = mat_mat_mul(rot, &piece.orient);
            }
        }
    }

    /// Twists `cell`'s layer, rotating the two axes that are neither `cell`'s own axis nor
    /// `fix_axis2`. Panics if `fix_axis2` is `cell`'s own axis (not a valid twist).
    pub fn twist(&mut self, cell: Cell4, fix_axis2: Axis4, prime: bool) {
        let cell_axis = cell.axis();
        assert!(fix_axis2 != cell_axis, "fix_axis2 must differ from the cell's own axis");

        let rotating: Vec<usize> = Axis4::ALL
            .iter()
            .map(|a| a.index())
            .filter(|&i| i != cell_axis.index() && i != fix_axis2.index())
            .collect();
        let rot = plane_rotation(rotating[0], rotating[1], !prime);
        self.twist_layer(&rot, |p| cell.selects(p));
    }

    pub fn is_solved(&self) -> bool {
        let solved = Cube4::solved();
        self.pieces
            .iter()
            .zip(solved.pieces.iter())
            .all(|(a, b)| a.pos == b.pos && a.orient == b.orient)
    }

    /// Applies `move_count` random quarter turns (never repeating the immediately preceding
    /// cell) and returns the moves applied.
    pub fn scramble(&mut self, move_count: u32) -> Vec<(Cell4, Axis4, bool)> {
        use rand::Rng;
        let mut rng = rand::thread_rng();
        let mut moves = Vec::with_capacity(move_count as usize);
        let mut last_cell: Option<Cell4> = None;
        for _ in 0..move_count {
            let mut cell;
            loop {
                cell = Cell4::ALL[rng.gen_range(0..8)];
                if Some(cell) != last_cell {
                    break;
                }
            }
            let others: Vec<Axis4> = Axis4::ALL
                .iter()
                .copied()
                .filter(|a| *a != cell.axis())
                .collect();
            let fix_axis2 = others[rng.gen_range(0..3)];
            let prime = rng.gen_bool(0.5);
            self.twist(cell, fix_axis2, prime);
            moves.push((cell, fix_axis2, prime));
            last_cell = Some(cell);
        }
        moves
    }

    /// Flattened per-piece transforms in solved-order: [x, y, z, w, m00..m33 (16 floats)] --
    /// 20 floats x 80 pieces = 1600 floats total.
    pub fn transforms(&self) -> Vec<f32> {
        let mut out = Vec::with_capacity(self.pieces.len() * 20);
        for piece in &self.pieces {
            out.push(piece.pos.0 as f32);
            out.push(piece.pos.1 as f32);
            out.push(piece.pos.2 as f32);
            out.push(piece.pos.3 as f32);
            for row in piece.orient.iter() {
                for &v in row.iter() {
                    out.push(v as f32);
                }
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn all_valid_twists() -> Vec<(Cell4, Axis4)> {
        let mut out = Vec::with_capacity(24);
        for &cell in Cell4::ALL.iter() {
            for &axis in Axis4::ALL.iter() {
                if axis != cell.axis() {
                    out.push((cell, axis));
                }
            }
        }
        out
    }

    #[test]
    fn solved_has_80_pieces() {
        assert_eq!(Cube4::solved().pieces.len(), 80);
    }

    #[test]
    fn there_are_24_valid_cell_axis_combinations() {
        assert_eq!(all_valid_twists().len(), 24);
    }

    #[test]
    fn every_twist_four_turns_returns_to_solved() {
        for (cell, fix_axis2) in all_valid_twists() {
            let mut cube = Cube4::solved();
            for _ in 0..4 {
                cube.twist(cell, fix_axis2, false);
            }
            assert!(cube.is_solved(), "{cell:?}/{fix_axis2:?} x4 should return to solved");
        }
    }

    #[test]
    fn every_twist_prime_cancels_clockwise() {
        for (cell, fix_axis2) in all_valid_twists() {
            let mut cube = Cube4::solved();
            cube.twist(cell, fix_axis2, false);
            cube.twist(cell, fix_axis2, true);
            assert!(cube.is_solved(), "{cell:?}/{fix_axis2:?} then prime should cancel");
        }
    }

    #[test]
    fn is_solved_detects_unsolved_state() {
        let mut cube = Cube4::solved();
        assert!(cube.is_solved());
        cube.twist(Cell4::U, Axis4::X, false);
        assert!(!cube.is_solved());
    }

    #[test]
    fn twist_only_moves_pieces_in_the_selected_cell() {
        for (cell, fix_axis2) in all_valid_twists() {
            let before = Cube4::solved();
            let mut after = Cube4::solved();
            after.twist(cell, fix_axis2, false);

            for (b, a) in before.pieces.iter().zip(after.pieces.iter()) {
                if cell.selects(b.pos) {
                    assert!(cell.selects(a.pos), "{cell:?}/{fix_axis2:?}: selected piece left the cell");
                } else {
                    assert_eq!(b.pos, a.pos, "{cell:?}/{fix_axis2:?}: non-selected piece moved");
                    assert_eq!(b.orient, a.orient, "{cell:?}/{fix_axis2:?}: non-selected piece rotated");
                }
            }
        }
    }

    #[test]
    fn an_io_cell_twist_matches_the_3d_face_permutation_pattern() {
        // Twisting the O cell (W=+1) around fix_axis2=Y should behave exactly like a 3D
        // R-style twist (rotating X/Z) confined to the W=1 slice: 27 pieces affected (a full
        // 3x3x3 sub-cube at w=1), all keeping w=1.
        let mut cube = Cube4::solved();
        cube.twist(Cell4::O, Axis4::Y, false);
        let affected = cube.pieces.iter().filter(|p| p.pos.3 == 1).count();
        assert_eq!(affected, 27, "the whole w=1 slice (27 pieces) should still be at w=1");
    }

    #[test]
    fn scramble_changes_state_with_nonzero_moves() {
        let mut cube = Cube4::solved();
        cube.scramble(25);
        assert!(!cube.is_solved());
    }

    #[test]
    fn scramble_never_repeats_the_same_cell_twice_in_a_row() {
        let mut cube = Cube4::solved();
        let moves = cube.scramble(50);
        for pair in moves.windows(2) {
            assert_ne!(pair[0].0, pair[1].0);
        }
    }
}
