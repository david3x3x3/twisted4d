package dev.twisted4d.app

/** A point in {-1,0,1}^4; Kotlin has no built-in 4-tuple. */
data class Vec4i(val x: Int, val y: Int, val z: Int, val w: Int)

/**
 * Builds the static per-piece mesh data for a 3^4 hypercube, mirroring [CubeGeometry] but for
 * 80 pieces in 4D and 8 cells (not 6): each piece shows one sticker per cell it belongs to
 * (1 to 4 of them), matching MagicCube4D/Hyperspeedcube's model. The U/D/L/R/F/B stickers (X/Y/Z
 * extremity) render as colored faces on the piece's main cube, same as [CubeGeometry]. The I/O
 * stickers (W extremity) can't reuse a cube face slot the same way -- a cube only has 6 faces,
 * and W is a genuinely separate axis, not a relabeling of one of the other three -- so they
 * render as a small separate marker cube-let, offset from the piece in whichever direction its
 * (possibly twisted) orientation currently points its home W-axis; see [HypercubeRenderer].
 */
object HypercubeGeometry {

    const val PIECE_HALF = 0.475f
    const val MARKER_HALF = 0.2f
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

    // 8 cell colors, matching Cell4's U/D/L/R/F/B/I/O.
    private val COLOR_U = floatArrayOf(0.92f, 0.92f, 0.92f)
    private val COLOR_D = floatArrayOf(0.95f, 0.85f, 0.10f)
    private val COLOR_L = floatArrayOf(0.90f, 0.45f, 0.05f)
    private val COLOR_R = floatArrayOf(0.80f, 0.15f, 0.15f)
    private val COLOR_F = floatArrayOf(0.10f, 0.60f, 0.20f)
    private val COLOR_B = floatArrayOf(0.10f, 0.35f, 0.80f)
    val COLOR_I = floatArrayOf(0.05f, 0.80f, 0.80f)
    val COLOR_O = floatArrayOf(0.65f, 0.10f, 0.85f)
    private val COLOR_INTERIOR = floatArrayOf(0.08f, 0.08f, 0.09f)

    /** Shared index buffer: 6 faces x 2 triangles x 3 indices, reused by every piece/marker. */
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

    /** Interleaved [x,y,z,r,g,b] x 24 vertices for the piece whose home position is given --
     * the main cube body, showing U/D/L/R/F/B stickers only (I/O render separately). */
    fun buildPieceVertices(home: Vec4i): FloatArray {
        val h = PIECE_HALF
        val (hx, hy, hz, _) = home

        val out = FloatArray(VERTICES_PER_PIECE * FLOATS_PER_VERTEX)
        var o = 0

        fun face(color: FloatArray, vararg corners: FloatArray) {
            for (c in corners) {
                out[o++] = c[0]; out[o++] = c[1]; out[o++] = c[2]
                out[o++] = color[0]; out[o++] = color[1]; out[o++] = color[2]
            }
        }

        fun colorFor(stickerColor: FloatArray, isExtreme: Boolean) = if (isExtreme) stickerColor else COLOR_INTERIOR

        // +X (R)
        face(
            colorFor(COLOR_R, hx == 1),
            floatArrayOf(h, -h, -h), floatArrayOf(h, h, -h), floatArrayOf(h, h, h), floatArrayOf(h, -h, h),
        )
        // -X (L)
        face(
            colorFor(COLOR_L, hx == -1),
            floatArrayOf(-h, -h, h), floatArrayOf(-h, h, h), floatArrayOf(-h, h, -h), floatArrayOf(-h, -h, -h),
        )
        // +Y (U)
        face(
            colorFor(COLOR_U, hy == 1),
            floatArrayOf(-h, h, -h), floatArrayOf(-h, h, h), floatArrayOf(h, h, h), floatArrayOf(h, h, -h),
        )
        // -Y (D)
        face(
            colorFor(COLOR_D, hy == -1),
            floatArrayOf(-h, -h, h), floatArrayOf(-h, -h, -h), floatArrayOf(h, -h, -h), floatArrayOf(h, -h, h),
        )
        // +Z (F)
        face(
            colorFor(COLOR_F, hz == 1),
            floatArrayOf(-h, -h, h), floatArrayOf(h, -h, h), floatArrayOf(h, h, h), floatArrayOf(-h, h, h),
        )
        // -Z (B)
        face(
            colorFor(COLOR_B, hz == -1),
            floatArrayOf(h, -h, -h), floatArrayOf(-h, -h, -h), floatArrayOf(-h, h, -h), floatArrayOf(h, h, -h),
        )

        return out
    }

    /** Interleaved [x,y,z,r,g,b] x 24 vertices for a small solid-colored I/O marker cube-let. */
    fun buildMarkerVertices(color: FloatArray): FloatArray {
        val h = MARKER_HALF
        val out = FloatArray(VERTICES_PER_PIECE * FLOATS_PER_VERTEX)
        var o = 0

        fun face(vararg corners: FloatArray) {
            for (c in corners) {
                out[o++] = c[0]; out[o++] = c[1]; out[o++] = c[2]
                out[o++] = color[0]; out[o++] = color[1]; out[o++] = color[2]
            }
        }

        face(floatArrayOf(h, -h, -h), floatArrayOf(h, h, -h), floatArrayOf(h, h, h), floatArrayOf(h, -h, h))
        face(floatArrayOf(-h, -h, h), floatArrayOf(-h, h, h), floatArrayOf(-h, h, -h), floatArrayOf(-h, -h, -h))
        face(floatArrayOf(-h, h, -h), floatArrayOf(-h, h, h), floatArrayOf(h, h, h), floatArrayOf(h, h, -h))
        face(floatArrayOf(-h, -h, h), floatArrayOf(-h, -h, -h), floatArrayOf(h, -h, -h), floatArrayOf(h, -h, h))
        face(floatArrayOf(-h, -h, h), floatArrayOf(h, -h, h), floatArrayOf(h, h, h), floatArrayOf(-h, h, h))
        face(floatArrayOf(h, -h, -h), floatArrayOf(-h, -h, -h), floatArrayOf(-h, h, -h), floatArrayOf(h, h, -h))

        return out
    }
}
