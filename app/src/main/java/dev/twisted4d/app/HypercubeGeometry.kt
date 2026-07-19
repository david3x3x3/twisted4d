package dev.twisted4d.app

/** A point in {-1,0,1}^4; Kotlin has no built-in 4-tuple. */
data class Vec4i(val x: Int, val y: Int, val z: Int, val w: Int)

/**
 * Builds the static per-piece mesh data for a 3^4 hypercube: vertex positions/colors and a
 * shared index buffer, mirroring [CubeGeometry] but for 80 pieces in 4D. Each piece still
 * renders as a simple 3D cube (colored by its X/Y/Z-extremity, same palette as [CubeGeometry])
 * -- the W axis isn't given its own sticker color; instead [HypercubeRenderer] conveys it via
 * perspective scale (pieces closer in W render bigger/closer, like a real 4D camera), except
 * for the 2 pieces with no X/Y/Z extremity at all (pure I/O pieces), which get a solid W color
 * so they aren't just plain dark cubes.
 */
object HypercubeGeometry {

    const val PIECE_HALF = 0.475f
    const val FLOATS_PER_VERTEX = 6 // x, y, z, r, g, b
    const val VERTICES_PER_PIECE = 24 // 4 per face x 6 faces

    // Canonical piece order: x, y, z, then w, over {-1,0,1}, skipping the hidden core
    // (0,0,0,0). Must match Cube4::solved()'s iteration order in cube4.rs exactly.
    val HOME_POSITIONS: List<Vec4i> = buildList {
        for (x in -1..1) {
            for (y in -1..1) {
                for (z in -1..1) {
                    for (w in -1..1) {
                        if (x == 0 && y == 0 && z == 0 && w == 0) continue
                        add(Vec4i(x, y, z, w))
                    }
                }
            }
        }
    }

    private val COLOR_PLUS_X = floatArrayOf(0.80f, 0.15f, 0.15f)
    private val COLOR_MINUS_X = floatArrayOf(0.90f, 0.45f, 0.05f)
    private val COLOR_PLUS_Y = floatArrayOf(0.92f, 0.92f, 0.92f)
    private val COLOR_MINUS_Y = floatArrayOf(0.95f, 0.85f, 0.10f)
    private val COLOR_PLUS_Z = floatArrayOf(0.10f, 0.60f, 0.20f)
    private val COLOR_MINUS_Z = floatArrayOf(0.10f, 0.35f, 0.80f)
    private val COLOR_PLUS_W = floatArrayOf(0.75f, 0.20f, 0.75f) // O: magenta
    private val COLOR_MINUS_W = floatArrayOf(0.85f, 0.65f, 0.85f) // I: pale magenta
    private val COLOR_INTERIOR = floatArrayOf(0.08f, 0.08f, 0.09f)

    /** Shared index buffer: 6 faces x 2 triangles x 3 indices, reused by every piece. */
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

    /** Interleaved [x,y,z,r,g,b] x 24 vertices for the piece whose home position is given. */
    fun buildPieceVertices(home: Vec4i): FloatArray {
        val h = PIECE_HALF
        val (hx, hy, hz, hw) = home
        val pureWPiece = hx == 0 && hy == 0 && hz == 0

        val out = FloatArray(VERTICES_PER_PIECE * FLOATS_PER_VERTEX)
        var o = 0

        fun face(color: FloatArray, vararg corners: FloatArray) {
            for (c in corners) {
                out[o++] = c[0]; out[o++] = c[1]; out[o++] = c[2]
                out[o++] = color[0]; out[o++] = color[1]; out[o++] = color[2]
            }
        }

        fun colorFor(extremityColor: FloatArray, isExtreme: Boolean): FloatArray = when {
            isExtreme -> extremityColor
            pureWPiece && hw == 1 -> COLOR_PLUS_W
            pureWPiece && hw == -1 -> COLOR_MINUS_W
            else -> COLOR_INTERIOR
        }

        // +X
        face(
            colorFor(COLOR_PLUS_X, hx == 1),
            floatArrayOf(h, -h, -h), floatArrayOf(h, h, -h), floatArrayOf(h, h, h), floatArrayOf(h, -h, h),
        )
        // -X
        face(
            colorFor(COLOR_MINUS_X, hx == -1),
            floatArrayOf(-h, -h, h), floatArrayOf(-h, h, h), floatArrayOf(-h, h, -h), floatArrayOf(-h, -h, -h),
        )
        // +Y
        face(
            colorFor(COLOR_PLUS_Y, hy == 1),
            floatArrayOf(-h, h, -h), floatArrayOf(-h, h, h), floatArrayOf(h, h, h), floatArrayOf(h, h, -h),
        )
        // -Y
        face(
            colorFor(COLOR_MINUS_Y, hy == -1),
            floatArrayOf(-h, -h, h), floatArrayOf(-h, -h, -h), floatArrayOf(h, -h, -h), floatArrayOf(h, -h, h),
        )
        // +Z
        face(
            colorFor(COLOR_PLUS_Z, hz == 1),
            floatArrayOf(-h, -h, h), floatArrayOf(h, -h, h), floatArrayOf(h, h, h), floatArrayOf(-h, h, h),
        )
        // -Z
        face(
            colorFor(COLOR_MINUS_Z, hz == -1),
            floatArrayOf(h, -h, -h), floatArrayOf(-h, -h, -h), floatArrayOf(-h, h, -h), floatArrayOf(h, h, -h),
        )

        return out
    }
}
