package dev.twisted4d.app

/**
 * Pure-Kotlin reimplementation of `cube4.rs`'s `Cube4` -- exists solely as an independent
 * "shadow" oracle for [MainActivity]'s export round-trip checker, not for rendering or real
 * gameplay. Deliberately doesn't touch [NativeLib]/JNI: the checker needs a *second*, disposable
 * puzzle state to replay a move snapshot into and compare against the live one, and the native
 * side only exposes a single process-wide singleton (see `cube4()` in `lib.rs`) -- reimplementing
 * the small amount of pure integer-matrix math involved here is far simpler than adding a second
 * native instance just for this. See [MainActivity]'s export-round-trip-checker doc for the
 * overall design and why replaying raw [TwistRecord] fields (rather than round-tripping through
 * MC4D grip/dir numbers first) catches exactly the same class of bug with less code.
 */
class ShadowCube4 {
    private class Piece(var pos: Vec4i, var orient: Array<IntArray>)

    private val pieces: List<Piece> = HypercubeGeometry.HOME_POSITIONS.map { Piece(it, identity()) }

    private fun identity(): Array<IntArray> = Array(4) { r -> IntArray(4) { c -> if (r == c) 1 else 0 } }

    private fun matVec(m: Array<IntArray>, v: Vec4i): Vec4i {
        val x = intArrayOf(v.x, v.y, v.z, v.w)
        val out = IntArray(4) { r -> (0 until 4).sumOf { c -> m[r][c] * x[c] } }
        return Vec4i(out[0], out[1], out[2], out[3])
    }

    private fun matMat(a: Array<IntArray>, b: Array<IntArray>): Array<IntArray> =
        Array(4) { r -> IntArray(4) { c -> (0 until 4).sumOf { k -> a[r][k] * b[k][c] } } }

    /** Matches `plane_rotation` in cube4.rs exactly -- a 90-degree rotation in the plane spanned
     * by axes [a]/[b], holding the other two fixed. */
    private fun planeRotation(a: Int, b: Int, clockwise: Boolean): Array<IntArray> {
        val m = identity()
        m[a][a] = 0
        m[b][b] = 0
        if (clockwise) {
            m[a][b] = -1
            m[b][a] = 1
        } else {
            m[a][b] = 1
            m[b][a] = -1
        }
        return m
    }

    private fun cellSelects(cell: Cell4, pos: Vec4i): Boolean {
        val coord = when (cell.axis) {
            Axis4.X -> pos.x
            Axis4.Y -> pos.y
            Axis4.Z -> pos.z
            Axis4.W -> pos.w
        }
        return coord == cell.sign
    }

    /** Matches `Cube4::twist` exactly. */
    fun twist(cell: Cell4, fixAxis2: Axis4, prime: Boolean) {
        val cellAxis = cell.axis.nativeIndex
        val fixAxis = fixAxis2.nativeIndex
        val rotating = (0..3).filter { it != cellAxis && it != fixAxis }
        val rot = planeRotation(rotating[0], rotating[1], !prime)
        for (p in pieces) {
            if (cellSelects(cell, p.pos)) {
                p.pos = matVec(rot, p.pos)
                p.orient = matMat(rot, p.orient)
            }
        }
    }

    /** The single 180-degree rotation an edge twist through ([axis1],[sign1])/([axis2],[sign2])
     * must be: fixes [cell]'s own axis, negates the one remaining "third" free axis, and swaps
     * axis1/axis2 scaled by `sign1*sign2` -- proven identical (2026-08-15 diagnostic session) to
     * [HypercubeRenderer]'s `EDGE_TWIST_DECOMPOSITIONS` 3-move table for all 24 valid cases (both
     * by direct Rodrigues-formula derivation and by brute-force matrix comparison), so this closed
     * form is used directly here instead of duplicating that table. */
    fun edgeTwist(cell: Cell4, axis1: Axis4, sign1: Int, axis2: Axis4, sign2: Int) {
        val cellAxis = cell.axis.nativeIndex
        val a1 = axis1.nativeIndex
        val a2 = axis2.nativeIndex
        val third = (0..3).first { it != cellAxis && it != a1 && it != a2 }
        val m = identity()
        m[a1][a1] = 0
        m[a2][a2] = 0
        m[a1][a2] = sign1 * sign2
        m[a2][a1] = sign1 * sign2
        m[third][third] = -1
        for (p in pieces) {
            if (cellSelects(cell, p.pos)) {
                p.pos = matVec(m, p.pos)
                p.orient = matMat(m, p.orient)
            }
        }
    }

    fun apply(record: TwistRecord) {
        when (record) {
            is TwistRecord.Ridge -> twist(record.cell, record.fixAxis2, record.prime)
            is TwistRecord.Edge -> edgeTwist(record.cell, record.axis1, record.sign1, record.axis2, record.sign2)
        }
    }

    /** Same 20-floats-per-piece layout as [NativeLib.cube4GetTransforms] (x,y,z,w, then the 16
     * orient entries row-major), so it can be diffed directly against the live native state. */
    fun transforms(): FloatArray {
        val out = FloatArray(pieces.size * 20)
        pieces.forEachIndexed { i, p ->
            val base = i * 20
            out[base] = p.pos.x.toFloat()
            out[base + 1] = p.pos.y.toFloat()
            out[base + 2] = p.pos.z.toFloat()
            out[base + 3] = p.pos.w.toFloat()
            for (r in 0..3) for (c in 0..3) out[base + 4 + r * 4 + c] = p.orient[r][c].toFloat()
        }
        return out
    }

    /** First piece index (in [HypercubeGeometry.HOME_POSITIONS] order) whose position or
     * orientation differs from [liveTransforms] (same layout, e.g. from
     * [NativeLib.cube4GetTransforms]), or null if they match exactly -- a strict, non-lax
     * comparison (unlike `Cube4::is_solved`'s hidden-axis leniency): this is checking "did the
     * exact same sequence of twists produce the exact same state," not "is it solved," so even an
     * invisible-orientation difference is worth flagging as a real divergence. */
    fun firstDivergence(liveTransforms: FloatArray): Int? {
        val shadow = transforms()
        if (liveTransforms.size != shadow.size) return 0
        for (i in pieces.indices) {
            val base = i * 20
            for (j in 0 until 20) {
                if (shadow[base + j] != liveTransforms[base + j]) return i
            }
        }
        return null
    }

    /** Total number of pieces (position or orientation) that differ from [liveTransforms] -- for
     * a richer diagnostic message than just the first divergence's index. */
    fun mismatchCount(liveTransforms: FloatArray): Int = divergentPieceIndices(liveTransforms).size

    /** Every piece index (see [firstDivergence]) that differs from [liveTransforms]. */
    fun divergentPieceIndices(liveTransforms: FloatArray): List<Int> {
        val shadow = transforms()
        if (liveTransforms.size != shadow.size) return pieces.indices.toList()
        return pieces.indices.filter { i ->
            val base = i * 20
            (0 until 20).any { j -> shadow[base + j] != liveTransforms[base + j] }
        }
    }

    /** This piece's permanent home-position identity as cell letters (e.g. `"UF"` for a ridge
     * whose home touches U and F) -- for log messages only, so a mismatch report names pieces the
     * way a person would describe them rather than a bare array index. */
    private fun homeLabel(index: Int): String {
        val home = HypercubeGeometry.HOME_POSITIONS[index]
        val coords = intArrayOf(home.x, home.y, home.z, home.w)
        return Axis4.entries.mapNotNull { axis ->
            val c = coords[axis.nativeIndex]
            if (c == 0) null else Cell4.entries.first { it.axis == axis && it.sign == c }.label
        }.joinToString("")
    }

    /** Human-readable summary of a mismatch against [liveTransforms], for [MainActivity]'s log
     * line: which pieces (by home identity) differ, and -- if every divergent piece's *current*
     * (shadow-replayed) position shares one cell's layer -- which single cell that is. A clean
     * single-cell-layer mismatch (as opposed to pieces scattered across several cells) is the
     * signature of exactly one twist's worth of difference between the recorded history and the
     * live puzzle (at most 27 of the 80 pieces belong to any one cell's layer), as opposed to a
     * more systemic bug (e.g. the scramble-didn't-reset-first bug, which mismatched all 80). */
    fun describeDivergence(liveTransforms: FloatArray): String {
        val indices = divergentPieceIndices(liveTransforms)
        if (indices.isEmpty()) return "no divergence"
        val commonCell = Cell4.entries.firstOrNull { cell -> indices.all { cellSelects(cell, pieces[it].pos) } }
        val labels = indices.map { homeLabel(it) }.sorted()
        val shape = if (commonCell != null) {
            "all in $commonCell's current layer (a single twist's worth)"
        } else {
            "scattered across more than one cell's layer"
        }
        return "${indices.size} pieces -- $shape -- home identities: $labels"
    }
}
