// 3x3x3 cubie model: piece-based representation (position + orientation per cubie),
// generalizes cleanly to NxNxN and later to 4D by swapping the position/rotation types.

pub type Vec3i = (i32, i32, i32);
pub type Mat3i = [[i32; 3]; 3];

const IDENTITY: Mat3i = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];

/// Clockwise (viewed from the +Y looking toward the origin) 90-degree rotation about Y,
/// used for the U-layer twist.
const ROT_U: Mat3i = [[0, 0, 1], [0, 1, 0], [-1, 0, 0]];

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

    /// The single twist milestone 2 needs: rotate the top (y = 1) layer clockwise.
    pub fn twist_u(&mut self) {
        self.twist_layer(&ROT_U, |p| p.1 == 1);
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
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn solved_has_26_cubies() {
        assert_eq!(Cube3::solved().cubies.len(), 26);
    }

    #[test]
    fn four_u_twists_return_to_solved() {
        let mut cube = Cube3::solved();
        let before = cube.transforms();
        for _ in 0..4 {
            cube.twist_u();
        }
        assert_eq!(cube.transforms(), before);
    }

    #[test]
    fn one_u_twist_only_moves_top_layer() {
        let before = Cube3::solved();
        let mut after = Cube3::solved();
        after.twist_u();

        // 9 cubies make up the top layer: 4 corners, 4 edges, 1 face center.
        assert_eq!(after.cubies.iter().filter(|c| c.pos.1 == 1).count(), 9);

        for (b, a) in before.cubies.iter().zip(after.cubies.iter()) {
            if b.pos.1 == 1 {
                assert_eq!(a.pos.1, 1, "top-layer cubie should stay in the top layer");
                assert_ne!(
                    (b.pos, b.orient),
                    (a.pos, a.orient),
                    "top-layer cubie should have rotated"
                );
            } else {
                assert_eq!(b.pos, a.pos, "non-top-layer cubie should be untouched");
                assert_eq!(b.orient, a.orient, "non-top-layer cubie should be untouched");
            }
        }
    }
}
