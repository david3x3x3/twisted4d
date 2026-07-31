// 3x3x3 cubie model: piece-based representation (position + orientation per cubie),
// generalizes cleanly to NxNxN and later to 4D by swapping the position/rotation types.

use rand::Rng;

pub type Vec3i = (i32, i32, i32);
pub type Mat3i = [[i32; 3]; 3];

const IDENTITY: Mat3i = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];

/// One of the 6 faces of a 3x3x3. Rotation matrices below are each a 90-degree turn of that
/// face, clockwise as viewed from outside the face looking at the cube (standard cube
/// notation). `nativeIndex` in the Kotlin `Face` enum must stay in this same U/D/L/R/F/B
/// order, since it's passed across JNI as a plain int.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Face {
    U = 0,
    D = 1,
    L = 2,
    R = 3,
    F = 4,
    B = 5,
}

impl Face {
    pub const ALL: [Face; 6] = [Face::U, Face::D, Face::L, Face::R, Face::F, Face::B];

    pub fn from_index(i: i32) -> Option<Face> {
        match i {
            0 => Some(Face::U),
            1 => Some(Face::D),
            2 => Some(Face::L),
            3 => Some(Face::R),
            4 => Some(Face::F),
            5 => Some(Face::B),
            _ => None,
        }
    }

    /// A clockwise-from-outside 90-degree turn about the +Y/+X/+Z axis is a NEGATIVE angle
    /// (right-hand rule), so the two faces sharing an axis get opposite-signed matrices.
    fn clockwise_matrix(self) -> Mat3i {
        match self {
            Face::U => [[0, 0, -1], [0, 1, 0], [1, 0, 0]],
            Face::D => [[0, 0, 1], [0, 1, 0], [-1, 0, 0]],
            Face::L => [[1, 0, 0], [0, 0, -1], [0, 1, 0]],
            Face::R => [[1, 0, 0], [0, 0, 1], [0, -1, 0]],
            Face::F => [[0, 1, 0], [-1, 0, 0], [0, 0, 1]],
            Face::B => [[0, -1, 0], [1, 0, 0], [0, 0, 1]],
        }
    }

    fn selects(self, p: Vec3i) -> bool {
        match self {
            Face::U => p.1 == 1,
            Face::D => p.1 == -1,
            Face::L => p.0 == -1,
            Face::R => p.0 == 1,
            Face::F => p.2 == 1,
            Face::B => p.2 == -1,
        }
    }
}

#[derive(Clone, Copy, PartialEq)]
pub struct Cubie {
    pub pos: Vec3i,
    pub orient: Mat3i,
}

pub struct Cube3 {
    pub cubies: Vec<Cubie>,
}

fn mat_vec_mul(m: &Mat3i, v: Vec3i) -> Vec3i {
    let (x, y, z) = v;
    (
        m[0][0] * x + m[0][1] * y + m[0][2] * z,
        m[1][0] * x + m[1][1] * y + m[1][2] * z,
        m[2][0] * x + m[2][1] * y + m[2][2] * z,
    )
}

fn mat_mat_mul(a: &Mat3i, b: &Mat3i) -> Mat3i {
    let mut out = [[0; 3]; 3];
    for row in 0..3 {
        for col in 0..3 {
            out[row][col] =
                a[row][0] * b[0][col] + a[row][1] * b[1][col] + a[row][2] * b[2][col];
        }
    }
    out
}

/// Same reasoning and mechanism as Cube4's identically-named fn (see that doc for the full
/// explanation, including the real report this fixed): compares `current`'s orientation against
/// solved only on the columns this cubie actually has a sticker on (`home.pos`'s nonzero
/// coordinates), so an axis nobody's using (e.g. a face center's other two axes) can't fail the
/// check just because *some* rotation is hiding there that no player could ever see.
fn visible_orientation_matches(current: &Cubie, home: &Cubie) -> bool {
    let home_coords = [home.pos.0, home.pos.1, home.pos.2];
    for axis in 0..3 {
        if home_coords[axis] == 0 {
            continue;
        }
        for row in 0..3 {
            if current.orient[row][axis] != home.orient[row][axis] {
                return false;
            }
        }
    }
    true
}

fn transpose(m: &Mat3i) -> Mat3i {
    let mut out = [[0; 3]; 3];
    for r in 0..3 {
        for c in 0..3 {
            out[r][c] = m[c][r];
        }
    }
    out
}

impl Cube3 {
    /// Builds a solved cube. Cubie order is the canonical nested loop x, then y, then z,
    /// over {-1, 0, 1} skipping the hidden core (0, 0, 0) -- the Kotlin renderer generates
    /// its per-cubie sticker-color meshes with this exact same order so the two line up
    /// purely by index, with no need to pass colors/home-position across the JNI boundary.
    pub fn solved() -> Self {
        let mut cubies = Vec::with_capacity(26);
        for x in -1..=1 {
            for y in -1..=1 {
                for z in -1..=1 {
                    if x == 0 && y == 0 && z == 0 {
                        continue;
                    }
                    cubies.push(Cubie {
                        pos: (x, y, z),
                        orient: IDENTITY,
                    });
                }
            }
        }
        Cube3 { cubies }
    }

    fn twist_layer(&mut self, rot: &Mat3i, select: impl Fn(Vec3i) -> bool) {
        for cubie in self.cubies.iter_mut() {
            if select(cubie.pos) {
                cubie.pos = mat_vec_mul(rot, cubie.pos);
                cubie.orient = mat_mat_mul(rot, &cubie.orient);
            }
        }
    }

    /// Turns `face` 90 degrees; `prime` reverses the direction (counter-clockwise).
    pub fn twist(&mut self, face: Face, prime: bool) {
        let rot = if prime {
            transpose(&face.clockwise_matrix())
        } else {
            face.clockwise_matrix()
        };
        self.twist_layer(&rot, |p| face.selects(p));
    }

    pub fn is_solved(&self) -> bool {
        let solved = Cube3::solved();
        self.cubies
            .iter()
            .zip(solved.cubies.iter())
            .all(|(a, b)| a.pos == b.pos && visible_orientation_matches(a, b))
    }

    /// Applies `move_count` random quarter turns (never repeating the immediately preceding
    /// face, to avoid trivially-cancelling moves) and returns the moves applied.
    pub fn scramble(&mut self, move_count: u32) -> Vec<(Face, bool)> {
        let mut rng = rand::thread_rng();
        let mut moves = Vec::with_capacity(move_count as usize);
        let mut last_face: Option<Face> = None;
        for _ in 0..move_count {
            let mut face;
            loop {
                face = Face::ALL[rng.gen_range(0..6)];
                if Some(face) != last_face {
                    break;
                }
            }
            let prime = rng.gen_bool(0.5);
            self.twist(face, prime);
            moves.push((face, prime));
            last_face = Some(face);
        }
        moves
    }

    /// Flattened per-cubie transforms in solved-order: [px, py, pz, m00, m01, m02, m10, m11,
    /// m12, m20, m21, m22] repeated per cubie (12 floats x 26 cubies = 312 floats total).
    pub fn transforms(&self) -> Vec<f32> {
        let mut out = Vec::with_capacity(self.cubies.len() * 12);
        for cubie in &self.cubies {
            out.push(cubie.pos.0 as f32);
            out.push(cubie.pos.1 as f32);
            out.push(cubie.pos.2 as f32);
            for row in cubie.orient.iter() {
                for &v in row.iter() {
                    out.push(v as f32);
                }
            }
        }
        out
    }

    /// Flattened per-cubie [pos.0, pos.1, pos.2, orient rows...] in solved-order (12 ints x 26
    /// cubies = 312 ints total) -- an exact, lossless integer encoding (unlike [Cube3::transforms],
    /// which is meant for rendering) used to persist/restore puzzle state across process death.
    pub fn get_state(&self) -> Vec<i32> {
        let mut out = Vec::with_capacity(self.cubies.len() * 12);
        for cubie in &self.cubies {
            out.push(cubie.pos.0);
            out.push(cubie.pos.1);
            out.push(cubie.pos.2);
            for row in cubie.orient.iter() {
                for &v in row.iter() {
                    out.push(v);
                }
            }
        }
        out
    }

    /// Inverse of [Cube3::get_state]. Returns `None` if `data`'s length doesn't match the
    /// expected 312 ints, leaving the caller free to fall back to [Cube3::solved].
    pub fn from_state(data: &[i32]) -> Option<Cube3> {
        if data.len() != 26 * 12 {
            return None;
        }
        let mut cubies = Vec::with_capacity(26);
        for chunk in data.chunks_exact(12) {
            let pos = (chunk[0], chunk[1], chunk[2]);
            let mut orient = [[0; 3]; 3];
            for r in 0..3 {
                for c in 0..3 {
                    orient[r][c] = chunk[3 + r * 3 + c];
                }
            }
            cubies.push(Cubie { pos, orient });
        }
        Some(Cube3 { cubies })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn solved_has_26_cubies() {
        assert_eq!(Cube3::solved().cubies.len(), 26);
    }

    #[test]
    fn every_face_four_turns_returns_to_solved() {
        for &face in Face::ALL.iter() {
            let mut cube = Cube3::solved();
            for _ in 0..4 {
                cube.twist(face, false);
            }
            assert!(cube.is_solved(), "{face:?} x4 should return to solved");
        }
    }

    #[test]
    fn every_face_prime_cancels_clockwise() {
        for &face in Face::ALL.iter() {
            let mut cube = Cube3::solved();
            cube.twist(face, false);
            cube.twist(face, true);
            assert!(cube.is_solved(), "{face:?} then {face:?}' should cancel");
        }
    }

    #[test]
    fn is_solved_detects_unsolved_state() {
        let mut cube = Cube3::solved();
        assert!(cube.is_solved());
        cube.twist(Face::U, false);
        assert!(!cube.is_solved());
    }

    #[test]
    fn a_centers_own_rotation_does_not_prevent_is_solved() {
        // Same fix/reasoning as Cube4's identically-named test -- see visible_orientation_matches's
        // doc. Directly construct a cube where every cubie matches solved except U's own
        // face-center's orient; only column 1 (U's own axis) is checked for this cubie, and that
        // column happens to be unaffected by this particular rotation.
        let mut cube = Cube3::solved();
        let u_center = cube.cubies.iter().position(|c| c.pos == (0, 1, 0)).unwrap();
        cube.cubies[u_center].orient = Face::U.clockwise_matrix();
        assert!(cube.is_solved());
    }

    #[test]
    fn an_edges_own_stickers_out_of_orientation_still_fails_is_solved() {
        // An edge (2 stickers) has only 1 empty column in 3D -- not enough for any rotation to
        // hide in -- so this behaves exactly like before: an edge's actual sticker axes (0/1
        // here) going wrong is genuinely visible and must still fail.
        let mut cube = Cube3::solved();
        let edge = cube.cubies.iter().position(|c| c.pos == (1, 1, 0)).unwrap();
        cube.cubies[edge].orient = Face::U.clockwise_matrix();
        assert!(!cube.is_solved());
    }

    #[test]
    fn one_u_twist_only_moves_top_layer() {
        let before = Cube3::solved();
        let mut after = Cube3::solved();
        after.twist(Face::U, false);

        // 9 cubies make up the top layer: 4 corners, 4 edges, 1 face center.
        assert_eq!(after.cubies.iter().filter(|c| c.pos.1 == 1).count(), 9);

        for (b, a) in before.cubies.iter().zip(after.cubies.iter()) {
            if b.pos.1 == 1 {
                assert_eq!(a.pos.1, 1, "top-layer cubie should stay in the top layer");
                assert_ne!((b.pos, b.orient), (a.pos, a.orient), "top-layer cubie should have rotated");
            } else {
                assert_eq!(b.pos, a.pos, "non-top-layer cubie should be untouched");
                assert_eq!(b.orient, a.orient, "non-top-layer cubie should be untouched");
            }
        }
    }

    #[test]
    fn scramble_changes_state_with_nonzero_moves() {
        let mut cube = Cube3::solved();
        cube.scramble(25);
        assert!(!cube.is_solved());
    }

    #[test]
    fn scramble_never_repeats_the_same_face_twice_in_a_row() {
        let mut cube = Cube3::solved();
        let moves = cube.scramble(50);
        for pair in moves.windows(2) {
            assert_ne!(pair[0].0, pair[1].0);
        }
    }

    #[test]
    fn get_state_round_trips_through_from_state() {
        let mut cube = Cube3::solved();
        cube.scramble(15);
        let restored = Cube3::from_state(&cube.get_state()).expect("valid state");
        assert!(cube
            .cubies
            .iter()
            .zip(restored.cubies.iter())
            .all(|(a, b)| a.pos == b.pos && a.orient == b.orient));
    }

    #[test]
    fn from_state_rejects_wrong_length() {
        assert!(Cube3::from_state(&[0; 10]).is_none());
    }
}
