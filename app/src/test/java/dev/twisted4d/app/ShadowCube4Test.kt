package dev.twisted4d.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Regression tests for [ShadowCube4] -- mirrors the same invariants cube4.rs's own `#[cfg(test)]`
 * module checks for the real `Cube4`, since this is a from-scratch reimplementation meant to be
 * an independent, trustworthy oracle for [MainActivity]'s export round-trip checker. */
class ShadowCube4Test {

    private fun solvedTransforms(): FloatArray = ShadowCube4().transforms()

    @Test
    fun `a fresh shadow cube matches a fresh live-format solved state`() {
        assertNull(ShadowCube4().firstDivergence(solvedTransforms()))
    }

    @Test
    fun `four quarter turns of the same twist return to solved`() {
        val shadow = ShadowCube4()
        repeat(4) { shadow.twist(Cell4.U, Axis4.Z, false) }
        assertNull(shadow.firstDivergence(solvedTransforms()))
    }

    @Test
    fun `a twist then its prime cancels`() {
        val shadow = ShadowCube4()
        shadow.twist(Cell4.R, Axis4.Y, false)
        shadow.twist(Cell4.R, Axis4.Y, true)
        assertNull(shadow.firstDivergence(solvedTransforms()))
    }

    @Test
    fun `a single twist is detected as diverging from solved`() {
        val shadow = ShadowCube4()
        shadow.twist(Cell4.U, Axis4.X, false)
        assert(shadow.firstDivergence(solvedTransforms()) != null)
        assert(shadow.mismatchCount(solvedTransforms()) > 0)
    }

    @Test
    fun `two independently-built shadows replaying the same moves match exactly`() {
        val moves = listOf(
            TwistRecord.Ridge(Cell4.U, Axis4.Z, false, Cell4.U, Axis4.Z.nativeIndex, false),
            TwistRecord.Ridge(Cell4.R, Axis4.Y, true, Cell4.R, Axis4.Y.nativeIndex, true),
            TwistRecord.Edge(Cell4.I, Axis4.Y, 1, Axis4.Z, 1),
        )
        val a = ShadowCube4()
        val b = ShadowCube4()
        moves.forEach { a.apply(it) }
        moves.forEach { b.apply(it) }
        assertNull(a.firstDivergence(b.transforms()))
    }

    @Test
    fun `a genuinely different move sequence is caught as a mismatch`() {
        val a = ShadowCube4()
        val b = ShadowCube4()
        a.twist(Cell4.U, Axis4.Z, false)
        b.twist(Cell4.U, Axis4.Z, true) // opposite prime -- a different resulting state
        assert(a.firstDivergence(b.transforms()) != null)
        assert(a.mismatchCount(b.transforms()) > 0)
    }

    @Test
    fun `an edge twist applied twice returns to solved (order-2)`() {
        val shadow = ShadowCube4()
        shadow.edgeTwist(Cell4.I, Axis4.Y, 1, Axis4.Z, 1)
        shadow.edgeTwist(Cell4.I, Axis4.Y, 1, Axis4.Z, 1)
        assertNull(shadow.firstDivergence(solvedTransforms()))
    }

    // --- Richer diagnostics (divergentPieceIndices/describeDivergence) -------------------------

    @Test
    fun `a single twist's worth of mismatch reports exactly 27 pieces, all in one cell's layer`() {
        val a = ShadowCube4()
        val b = ShadowCube4()
        a.twist(Cell4.U, Axis4.Z, false) // a is missing this one twist that b has
        val indices = b.divergentPieceIndices(a.transforms())
        assertEquals(27, indices.size)
        assert(b.describeDivergence(a.transforms()).contains("a single twist's worth"))
    }

    @Test
    fun `a mismatch scattered across two different cells' twists is reported as scattered`() {
        val a = ShadowCube4()
        val b = ShadowCube4()
        b.twist(Cell4.U, Axis4.Z, false)
        b.twist(Cell4.R, Axis4.Y, false) // two different cells' worth of difference from a
        assert(b.describeDivergence(a.transforms()).contains("scattered"))
    }

    @Test
    fun `divergentPieceIndices is empty when states match`() {
        assert(ShadowCube4().divergentPieceIndices(solvedTransforms()).isEmpty())
    }
}
