package dev.twisted4d.app

/** Mirrors `cube4::Axis4` in Rust -- [nativeIndex] must match the JNI fixAxis2 parameter. */
enum class Axis4(val nativeIndex: Int, val label: String) {
    X(0, "X"),
    Y(1, "Y"),
    Z(2, "Z"),
    W(3, "W"),
}
