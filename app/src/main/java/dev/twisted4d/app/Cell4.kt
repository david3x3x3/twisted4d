package dev.twisted4d.app

/**
 * Mirrors `cube4::Cell4` in the Rust puzzle-core crate -- one of the 8 cells of a 3^4
 * hypercube. Naming follows MagicCube4D: U/D/L/R/F/B for the X/Y/Z axes (matching [Face]),
 * and I(nner)/O(uter) for the 4th axis, W. [nativeIndex] must match the JNI cell parameter
 * expected by `NativeLib.cube4Twist`.
 */
enum class Cell4(val nativeIndex: Int, val axis: Axis4, val label: String) {
    U(0, Axis4.Y, "U"),
    D(1, Axis4.Y, "D"),
    L(2, Axis4.X, "L"),
    R(3, Axis4.X, "R"),
    F(4, Axis4.Z, "F"),
    B(5, Axis4.Z, "B"),
    I(6, Axis4.W, "I"),
    O(7, Axis4.W, "O");

    /** +1 for U/R/F/O, -1 for D/L/B/I -- matches `Cell4::sign` in Rust. */
    val sign: Int
        get() = when (this) {
            U, R, F, O -> 1
            D, L, B, I -> -1
        }

    /** This cell's outward-facing normal in native puzzle space, as a 4-component vector. */
    fun outwardNormal(): FloatArray {
        val out = FloatArray(4)
        out[axis.nativeIndex] = sign.toFloat()
        return out
    }
}
