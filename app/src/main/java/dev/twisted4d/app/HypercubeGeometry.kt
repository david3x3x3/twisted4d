package dev.twisted4d.app

/** A point in {-1,0,1}^4; Kotlin has no built-in 4-tuple. */
data class Vec4i(val x: Int, val y: Int, val z: Int, val w: Int)

/**
 * Color palette and shared local geometry for the 3^4 hypercube's true-4D-projection rendering
 * (see [HypercubeRenderer]): 6 cells (U/D/L/R/F/B) form separate 3x3x3 blocks around a center, a
 * 7th (I) is a 3x3x3 block floating at the center, and the 8th (O) sits behind I -- naturally
 * occluded by the depth buffer, not specially hidden.
 *
 * A sticker in a 3^4 puzzle is a genuine 3-dimensional object, not a flat 2D square -- the same
 * dimensional step as a 3D Rubik's cube (whose piece is a 3D cube, and whose sticker is a flat 2D
 * facet of it) applies one dimension up here: a piece is a small 4D hypercube-shaped chunk, and
 * one of its stickers is a 3D cube-shaped *facet* of that chunk, flush against the piece's
 * boundary along exactly one native axis (the sticker's own "identity" axis) and spanning the
 * other 3 native axes freely. [LOCAL_OFFSETS_BY_AXIS] encodes exactly that: for a sticker whose
 * identity axis is `axisIdx`, its 24 corners (6 faces x 4, unshared, for flat per-face lighting)
 * get real extent in the *other* 3 native axes and none at all in `axisIdx` -- no separate
 * "thickness" hack needed, since the sticker's own 3 free axes already give it full cube volume.
 * [HypercubeRenderer] rotates each corner by the piece's own current 4D orientation, adds the
 * piece's true room position, and perspective-projects each corner individually -- real per-vertex
 * 4D geometry, not a rigid mesh translated/scaled to an already-resolved point.
 */
object HypercubeGeometry {

    const val STICKER_HALF = 0.28f // ~60% of the original 0.46 half-extent, per grid spacing 1.0
    const val FLOATS_PER_VERTEX = 9 // x, y, z, nx, ny, nz, r, g, b (the *rendered*, post-projection format)
    const val VERTICES_PER_STICKER = 24 // 4 per face x 6 faces

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

    // 8 cell colors, matching Cell4's U/D/L/R/F/B/I/O -- indexed by Cell4.ordinal.
    val CELL_COLORS: List<FloatArray> = listOf(
        floatArrayOf(0.92f, 0.92f, 0.92f), // U: white
        floatArrayOf(0.95f, 0.85f, 0.10f), // D: yellow
        floatArrayOf(0.90f, 0.45f, 0.05f), // L: orange
        floatArrayOf(0.80f, 0.15f, 0.15f), // R: red
        floatArrayOf(0.10f, 0.60f, 0.20f), // F: green
        floatArrayOf(0.10f, 0.35f, 0.80f), // B: blue
        floatArrayOf(0.55f, 0.15f, 0.75f), // I: purple
        floatArrayOf(0.95f, 0.45f, 0.70f), // O: pink (never actually rendered)
    )

    /** Shared index buffer: 6 faces x 2 triangles x 3 indices, reused by every sticker. */
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

    /** For each of the 4 possible "identity axes" a sticker can have, its 24 corners' (6 faces x
     * 4, unshared) local offset in the piece's own 4D body frame -- interleaved x,y,z,w per
     * corner (96 floats total per axis). The identity axis's own component is always 0 (the
     * sticker has no extent there); the other 3 native axes each get +-[STICKER_HALF], in the
     * same per-face corner order a plain 3D cube would use. [HypercubeRenderer] rotates these by
     * the piece's current orientation and adds its position -- see class doc. */
    val LOCAL_OFFSETS_BY_AXIS: Array<FloatArray> = Array(4) { axisIdx -> buildLocalOffsets(axisIdx) }

    private fun buildLocalOffsets(axisIdx: Int): FloatArray {
        val h = STICKER_HALF
        val axes = (0 until 4).filter { it != axisIdx }
        val out = FloatArray(VERTICES_PER_STICKER * 4)
        var o = 0

        fun corner(a: Float, b: Float, c: Float) {
            val local4 = FloatArray(4)
            local4[axes[0]] = a
            local4[axes[1]] = b
            local4[axes[2]] = c
            out[o++] = local4[0]; out[o++] = local4[1]; out[o++] = local4[2]; out[o++] = local4[3]
        }
        fun face(vararg corners: FloatArray) {
            for (c in corners) corner(c[0], c[1], c[2])
        }

        // Same 6 faces/corner-winding a plain isotropic cube would use, just written via the
        // a/b/c -> axes[0..2] mapping above instead of always native x/y/z.
        face(floatArrayOf(h, -h, -h), floatArrayOf(h, h, -h), floatArrayOf(h, h, h), floatArrayOf(h, -h, h))
        face(floatArrayOf(-h, -h, h), floatArrayOf(-h, h, h), floatArrayOf(-h, h, -h), floatArrayOf(-h, -h, -h))
        face(floatArrayOf(-h, h, -h), floatArrayOf(-h, h, h), floatArrayOf(h, h, h), floatArrayOf(h, h, -h))
        face(floatArrayOf(-h, -h, h), floatArrayOf(-h, -h, -h), floatArrayOf(h, -h, -h), floatArrayOf(h, -h, h))
        face(floatArrayOf(-h, -h, h), floatArrayOf(h, -h, h), floatArrayOf(h, h, h), floatArrayOf(-h, h, h))
        face(floatArrayOf(h, -h, -h), floatArrayOf(-h, -h, -h), floatArrayOf(-h, h, -h), floatArrayOf(h, h, -h))

        return out
    }
}
