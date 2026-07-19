package dev.twisted4d.app

/**
 * Builds the static per-cubie mesh data for a 3x3x3 cube: vertex positions/colors and a
 * shared index buffer. Positions/colors never change after construction -- twists only
 * change each cubie's model matrix (position + orientation), supplied at render time by
 * [NativeLib.cubeGetTransforms].
 */
object CubeGeometry {

    /** Half-extent of one cubie; spacing between cubie centers is 1.0, leaving a visible gap. */
    const val CUBIE_HALF = 0.475f

    const val FLOATS_PER_VERTEX = 6 // x, y, z, r, g, b
    const val VERTICES_PER_CUBIE = 24 // 4 per face x 6 faces

    // Canonical cubie order: x outer, y middle, z inner, skipping the hidden core (0,0,0).
    // Must match Cube3::solved()'s iteration order in cube3.rs exactly, since transforms
    // from native code are matched to these home positions purely by index.
    val HOME_POSITIONS: List<Triple<Int, Int, Int>> = buildList {
        for (x in -1..1) {
            for (y in -1..1) {
                for (z in -1..1) {
                    if (x == 0 && y == 0 && z == 0) continue
                    add(Triple(x, y, z))
                }
            }
        }
    }

    // Standard color scheme: +Y white, -Y yellow, +Z green, -Z blue, +X red, -X orange.
    private val COLOR_PLUS_X = floatArrayOf(0.80f, 0.15f, 0.15f)
    private val COLOR_MINUS_X = floatArrayOf(0.90f, 0.45f, 0.05f)
    private val COLOR_PLUS_Y = floatArrayOf(0.92f, 0.92f, 0.92f)
    private val COLOR_MINUS_Y = floatArrayOf(0.95f, 0.85f, 0.10f)
    private val COLOR_PLUS_Z = floatArrayOf(0.10f, 0.60f, 0.20f)
    private val COLOR_MINUS_Z = floatArrayOf(0.10f, 0.35f, 0.80f)
    private val COLOR_INTERIOR = floatArrayOf(0.08f, 0.08f, 0.09f)

    /** Shared index buffer: 6 faces x 2 triangles x 3 indices, reused by every cubie. */
    val INDICES: ShortArray = ShortArray(36).also { idx ->
        for (face in 0 until 6) {
            val v0 = (face * 4).toShort()
            val base = face * 6
            idx[base + 0] = v0
            idx[base + 1] = (v0 + 1).toShort()
            idx[base + 2] = (v0 + 2).toShort()
            idx[base + 3] = v0
            idx[base + 4] = (v0 + 2).toShort()
            idx[base + 5] = (v0 + 3).toShort()
        }
    }

    /** Interleaved [x,y,z,r,g,b] x 24 vertices for the cubie whose home position is given. */
    fun buildCubieVertices(home: Triple<Int, Int, Int>): FloatArray {
        val h = CUBIE_HALF
        val (hx, hy, hz) = home

        val out = FloatArray(VERTICES_PER_CUBIE * FLOATS_PER_VERTEX)
        var o = 0

        fun face(color: FloatArray, vararg corners: FloatArray) {
            for (c in corners) {
                out[o++] = c[0]; out[o++] = c[1]; out[o++] = c[2]
                out[o++] = color[0]; out[o++] = color[1]; out[o++] = color[2]
            }
        }

        // +X
        face(
            if (hx == 1) COLOR_PLUS_X else COLOR_INTERIOR,
            floatArrayOf(h, -h, -h), floatArrayOf(h, h, -h), floatArrayOf(h, h, h), floatArrayOf(h, -h, h),
        )
        // -X
        face(
            if (hx == -1) COLOR_MINUS_X else COLOR_INTERIOR,
            floatArrayOf(-h, -h, h), floatArrayOf(-h, h, h), floatArrayOf(-h, h, -h), floatArrayOf(-h, -h, -h),
        )
        // +Y
        face(
            if (hy == 1) COLOR_PLUS_Y else COLOR_INTERIOR,
            floatArrayOf(-h, h, -h), floatArrayOf(-h, h, h), floatArrayOf(h, h, h), floatArrayOf(h, h, -h),
        )
        // -Y
        face(
            if (hy == -1) COLOR_MINUS_Y else COLOR_INTERIOR,
            floatArrayOf(-h, -h, h), floatArrayOf(-h, -h, -h), floatArrayOf(h, -h, -h), floatArrayOf(h, -h, h),
        )
        // +Z
        face(
            if (hz == 1) COLOR_PLUS_Z else COLOR_INTERIOR,
            floatArrayOf(-h, -h, h), floatArrayOf(h, -h, h), floatArrayOf(h, h, h), floatArrayOf(-h, h, h),
        )
        // -Z
        face(
            if (hz == -1) COLOR_MINUS_Z else COLOR_INTERIOR,
            floatArrayOf(h, -h, -h), floatArrayOf(-h, -h, -h), floatArrayOf(-h, h, -h), floatArrayOf(h, h, -h),
        )

        return out
    }
}
