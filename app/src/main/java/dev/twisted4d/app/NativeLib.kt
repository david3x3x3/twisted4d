package dev.twisted4d.app

/**
 * JNI bridge into the `puzzle-core` Rust crate (native/puzzle-core/src/cube3.rs and cube4.rs
 * hold the actual puzzle models; this object is just the JNI surface).
 */
object NativeLib {
    init {
        System.loadLibrary("puzzle_core")
    }

    external fun coreVersion(): String

    external fun cubeReset()
    external fun cubeTwist(face: Int, prime: Boolean)
    external fun cubeIsSolved(): Boolean
    external fun cubeScramble(moveCount: Int)

    /**
     * 26 cubies x 12 floats: [px, py, pz, m00, m01, m02, m10, m11, m12, m20, m21, m22].
     * Cubie order matches [CubeGeometry.HOME_POSITIONS] by index -- see cube3.rs for why
     * no color/position data needs to cross the JNI boundary beyond this.
     */
    external fun cubeGetTransforms(): FloatArray

    external fun cube4Reset()
    external fun cube4Twist(cell: Int, fixAxis2: Int, prime: Boolean)
    external fun cube4IsSolved(): Boolean
    external fun cube4Scramble(moveCount: Int)

    /**
     * 80 pieces x 20 floats: [x, y, z, w, m00..m33 (16 floats, row-major)]. Piece order matches
     * [HypercubeGeometry.HOME_POSITIONS] by index -- see cube4.rs.
     */
    external fun cube4GetTransforms(): FloatArray
}
