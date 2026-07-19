package dev.twisted4d.app

/**
 * JNI bridge into the `puzzle-core` Rust crate (native/puzzle-core/src/cube3.rs holds the
 * actual puzzle model; this object is just the JNI surface).
 */
object NativeLib {
    init {
        System.loadLibrary("puzzle_core")
    }

    external fun coreVersion(): String

    external fun cubeReset()
    external fun cubeTwistU()

    /**
     * 26 cubies x 12 floats: [px, py, pz, m00, m01, m02, m10, m11, m12, m20, m21, m22].
     * Cubie order matches [CubeGeometry.HOME_POSITIONS] by index -- see cube3.rs for why
     * no color/position data needs to cross the JNI boundary beyond this.
     */
    external fun cubeGetTransforms(): FloatArray
}
