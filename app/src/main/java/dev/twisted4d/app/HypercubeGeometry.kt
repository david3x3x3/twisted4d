package dev.twisted4d.app

/** A point in {-1,0,1}^4; Kotlin has no built-in 4-tuple. */
data class Vec4i(val x: Int, val y: Int, val z: Int, val w: Int)

/**
 * Color palette and shared mesh for the 3^4 hypercube's "unfolded" rendering (see
 * [HypercubeRenderer]): 6 cells (U/D/L/R/F/B) arranged as separate 3x3x3 blocks around a
 * center, 1 more (I) as a 3x3x3 block at the center, and 1 (O) never rendered at all --
 * matching MagicCube4D/Hyperspeedcube's default view of 7 non-overlapping cells with the 8th
 * hidden. Every rendered sticker is a small solid-colored cube (all 6 faces the same color,
 * since only one sticker color is ever shown per cube); [HypercubeRenderer] positions instances
 * of this one shared mesh per color rather than building per-piece geometry.
 */
object HypercubeGeometry {

    const val STICKER_HALF = 0.28f // ~60% of the original 0.46 half-extent, per grid spacing 1.0
    const val FLOATS_PER_VERTEX = 9 // x, y, z, nx, ny, nz, r, g, b
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

    /** Shared GL_LINES index buffer tracing all 4 edges of each face's quad (6 faces x 4 edges
     * x 2 indices) -- reuses the same 24 vertices as [INDICES], just drawn as lines instead of
     * triangles, for the selected-cell wireframe outline (see [HypercubeRenderer]). Shared cube
     * edges get traced twice (once per adjacent face); harmless, just minor overdraw. */
    val WIREFRAME_INDICES: ShortArray = ShortArray(48).also { idx ->
        for (face in 0 until 6) {
            val v0 = (face * 4).toShort()
            val base = face * 8
            idx[base + 0] = v0; idx[base + 1] = (v0 + 1).toShort()
            idx[base + 2] = (v0 + 1).toShort(); idx[base + 3] = (v0 + 2).toShort()
            idx[base + 4] = (v0 + 2).toShort(); idx[base + 5] = (v0 + 3).toShort()
            idx[base + 6] = (v0 + 3).toShort(); idx[base + 7] = v0
        }
    }

    /** Interleaved [x,y,z,nx,ny,nz,r,g,b] x 24 vertices for a small solid-colored sticker cube --
     * each face gets its own constant outward normal (this is a cube, not a smooth surface, so
     * no per-vertex normal averaging) for [HypercubeRenderer]'s per-face diffuse lighting; without
     * it, every face renders as exactly the same flat color and the cube shape only reads from
     * its silhouette/gaps rather than actually looking three-dimensional. */
    fun buildStickerVertices(color: FloatArray): FloatArray {
        val h = STICKER_HALF
        val out = FloatArray(VERTICES_PER_STICKER * FLOATS_PER_VERTEX)
        var o = 0

        fun face(normal: FloatArray, vararg corners: FloatArray) {
            for (c in corners) {
                out[o++] = c[0]; out[o++] = c[1]; out[o++] = c[2]
                out[o++] = normal[0]; out[o++] = normal[1]; out[o++] = normal[2]
                out[o++] = color[0]; out[o++] = color[1]; out[o++] = color[2]
            }
        }

        face(floatArrayOf(1f, 0f, 0f), floatArrayOf(h, -h, -h), floatArrayOf(h, h, -h), floatArrayOf(h, h, h), floatArrayOf(h, -h, h))
        face(floatArrayOf(-1f, 0f, 0f), floatArrayOf(-h, -h, h), floatArrayOf(-h, h, h), floatArrayOf(-h, h, -h), floatArrayOf(-h, -h, -h))
        face(floatArrayOf(0f, 1f, 0f), floatArrayOf(-h, h, -h), floatArrayOf(-h, h, h), floatArrayOf(h, h, h), floatArrayOf(h, h, -h))
        face(floatArrayOf(0f, -1f, 0f), floatArrayOf(-h, -h, h), floatArrayOf(-h, -h, -h), floatArrayOf(h, -h, -h), floatArrayOf(h, -h, h))
        face(floatArrayOf(0f, 0f, 1f), floatArrayOf(-h, -h, h), floatArrayOf(h, -h, h), floatArrayOf(h, h, h), floatArrayOf(-h, h, h))
        face(floatArrayOf(0f, 0f, -1f), floatArrayOf(h, -h, -h), floatArrayOf(-h, -h, -h), floatArrayOf(-h, h, -h), floatArrayOf(h, h, -h))

        return out
    }
}
