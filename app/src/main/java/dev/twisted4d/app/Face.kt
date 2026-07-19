package dev.twisted4d.app

/**
 * Mirrors `cube3::Face` in the Rust puzzle-core crate -- [nativeIndex] must match the JNI
 * face parameter expected by [NativeLib.cubeTwist], and [axisX]/[axisY]/[axisZ]/[clockwiseDeg]
 * must match the corresponding Rust rotation matrix so the Kotlin-side twist animation ends
 * up exactly where the native state lands once the animation finishes.
 */
enum class Face(
    val nativeIndex: Int,
    val axisX: Float,
    val axisY: Float,
    val axisZ: Float,
    /** Degrees for a clockwise (non-prime) turn of this face, viewed from outside it. */
    val clockwiseDeg: Float,
    val label: String,
) {
    U(0, 0f, 1f, 0f, -90f, "U"),
    D(1, 0f, 1f, 0f, 90f, "D"),
    L(2, 1f, 0f, 0f, 90f, "L"),
    R(3, 1f, 0f, 0f, -90f, "R"),
    F(4, 0f, 0f, 1f, -90f, "F"),
    B(5, 0f, 0f, 1f, 90f, "B");

    /** Whether a cubie currently at this integer grid position belongs to this face's layer. */
    fun selects(x: Int, y: Int, z: Int): Boolean = when (this) {
        U -> y == 1
        D -> y == -1
        L -> x == -1
        R -> x == 1
        F -> z == 1
        B -> z == -1
    }
}
