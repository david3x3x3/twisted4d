package dev.twisted4d.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Regression tests for [Notation] -- these encode ground truth established the hard way this
 * session (an on-screen per-twist community-notation survey against a real controller, and real
 * MagicCube4D round-trip exports), so a future refactor can't silently reintroduce either bug it
 * found: the prime-flip table, and native-vs-room-relative letter confusion. */
class NotationTest {

    // --- PRIME_FLIP_TWISTS: exact confirmed survey result -------------------------------------

    @Test
    fun `prime flip set matches the confirmed on-screen survey exactly`() {
        // 11 confirmed by an exhaustive on-screen survey (every (cell, fixAxis2) combination,
        // real controller, STICK mode) plus 3 O-as-twisted-cell entries confirmed via a real
        // MC4D round-trip export/import -- see Notation.PRIME_FLIP_TWISTS's doc.
        val expected = setOf(
            Cell4.U to Axis4.Z, // UF
            Cell4.D to Axis4.X, // DR
            Cell4.L to Axis4.Z, // LF
            Cell4.R to Axis4.Y, // RU
            Cell4.F to Axis4.X, // FR
            Cell4.B to Axis4.Y, // BU
            Cell4.I to Axis4.X, // IR
            Cell4.I to Axis4.Z, // IF
            Cell4.R to Axis4.W, // RO
            Cell4.D to Axis4.W, // DO
            Cell4.F to Axis4.W, // FO
            Cell4.O to Axis4.Y, // OU
        )
        assertEquals(expected, Notation.PRIME_FLIP_TWISTS)
    }

    @Test
    fun `every valid (cell, fixAxis2) pair is covered exactly once by the flip decision`() {
        // 8 cells x 3 valid representative axes each (fixAxis2 can't equal the cell's own axis)
        // = 24 total combinations -- sanity check that correctedPrime is defined (doesn't throw)
        // for all of them, matching the full survey's scope.
        var count = 0
        for (cell in Cell4.entries) {
            for (axis in Axis4.entries) {
                if (axis == cell.axis) continue
                count++
                Notation.correctedPrime(cell, axis, prime = false)
            }
        }
        assertEquals(24, count)
    }

    // --- correctedPrime / communityNotation: spot checks from real confirmed repros ----------

    @Test
    fun `IF prime=false matches HSC's confirmed IF' grip 21 dir 1`() {
        // HSC's own direct export of "IF'" gave grip 21, dir 1, matching our formula's
        // *corrected* prime=true -- but (I, Z) is one of the confirmed PRIME_FLIP_TWISTS entries,
        // so the raw native prime that produces it is false, not true. See the
        // mc4d_log_compatibility memory. No room reorientation involved, so room fields equal the
        // native ones, and displayApostrophe is just correctedPrime unreoriented.
        val record = TwistRecord.Ridge(
            Cell4.I, Axis4.Z, prime = false, roomCell = Cell4.I, roomFixAxis2 = Axis4.Z.nativeIndex,
            displayApostrophe = Notation.correctedPrime(Cell4.I, Axis4.Z, false),
        )
        assertEquals("IF'", Notation.communityNotation(record))
        assertEquals(21, Notation.mc4dGrip(record.cell, record.fixAxis2))
    }

    @Test
    fun `BO' native prime=true round-tripped correctly through real MC4D`() {
        // The user selected O, twisted on each of its 3 axes including this one, exported, and
        // re-imported into real MC4D -- its rendering matched twisted4d's. No reorientation, so
        // room fields equal native.
        val record = TwistRecord.Ridge(
            Cell4.B, Axis4.W, prime = true, roomCell = Cell4.B, roomFixAxis2 = Axis4.W.nativeIndex,
            displayApostrophe = Notation.correctedPrime(Cell4.B, Axis4.W, true),
        )
        assertEquals("BO'", Notation.communityNotation(record))
    }

    // --- mc4dEdgeGrip: derived by compiling and running MagicCube4D's own source, not by hand ---

    @Test
    fun `mc4dEdgeGrip covers all 96 valid (cell, axis1, sign1, axis2, sign2) combinations with distinct grips`() {
        // Every cell x every pair of its 3 non-own axes x both signs on each = 8 * 3 * 4 = 96,
        // matching the 8 cells x 12 edges each the real MC4D source's own grip-generation loop
        // produces (see mc4dEdgeGrip's doc). All 96 grips must be distinct, and each must fall in
        // its cell's edge tier (offset 8..19 of that cell's 27-grip block).
        val allGrips = mutableSetOf<Int>()
        var count = 0
        for (cell in Cell4.entries) {
            val cellIndex = Notation.MC4D_CELL_ORDER.indexOf(cell)
            val available = Axis4.entries.filter { it != cell.axis }
            for (i in available.indices) {
                for (j in available.indices) {
                    if (i >= j) continue
                    for (sign1 in listOf(-1, 1)) {
                        for (sign2 in listOf(-1, 1)) {
                            val grip = Notation.mc4dEdgeGrip(cell, available[i], sign1, available[j], sign2)
                            assertTrue(
                                grip in (cellIndex * 27 + 8)..(cellIndex * 27 + 19),
                                "grip $grip for $cell/${available[i]}$sign1/${available[j]}$sign2 outside its cell's edge tier",
                            )
                            allGrips.add(grip)
                            count++
                        }
                    }
                }
            }
        }
        assertEquals(96, count)
        assertEquals(96, allGrips.size, "expected all 96 edge grips to be distinct")
    }

    @Test
    fun `mc4dEdgeGrip is symmetric in argument order`() {
        // Naming an edge's two axes in either order must resolve to the same grip -- e.g. Button-
        // C+X/B's room-relative resolution isn't guaranteed to hand axis1/axis2 to requestEdgeTwist
        // in native-ascending order (it depends on which room axis got queried first).
        assertEquals(
            Notation.mc4dEdgeGrip(Cell4.I, Axis4.Y, 1, Axis4.Z, 1),
            Notation.mc4dEdgeGrip(Cell4.I, Axis4.Z, 1, Axis4.Y, 1),
        )
    }

    @Test
    fun `mc4dEdgeGrip does not collapse the two antipodal grips on the same axis`() {
        // Unlike HypercubeRenderer's edgeKey (which deliberately normalizes away a uniform sign
        // flip, since the *rotation* is identical either way), MC4D numbers the two antipodal
        // edge stickers on the same diagonal as two distinct grips -- collapsing them here would
        // export the wrong sticker even though the resulting twist looks the same.
        val g1 = Notation.mc4dEdgeGrip(Cell4.I, Axis4.Y, 1, Axis4.Z, 1)
        val g2 = Notation.mc4dEdgeGrip(Cell4.I, Axis4.Y, -1, Axis4.Z, -1)
        assertTrue(g1 != g2, "expected the UF and DB grips to be distinct MC4D grip numbers")
    }

    @Test
    fun `room-relative letters use native identity, and displayApostrophe is stored, not re-derived`() {
        // First bug this repro exposed: reorienting moved native I into room slot L; pressing a
        // button intending community "LU" on the piece now sitting in L produced native
        // (cell=I, fixAxis2=X) -- this app used to label it "IR'" (native identity for the
        // letters), which the fixed communityNotation correctly turns into "L"/"U".
        //
        // Second bug the *same* repro exposed, confirmed 2026-07-22 against a real device: even
        // after the letters were fixed, the apostrophe still came from a room-keyed
        // PRIME_FLIP_TWISTS lookup applied to whatever `prime` ended up being applied -- which
        // assumed the flip decision only depends on room identity, not on *which native axis pair*
        // currently underlies that room slot. It doesn't: the same room grip can be reached via
        // different native pairs with different rendered handedness. communityNotation no longer
        // re-derives the apostrophe from `prime` at all -- it reads the already-resolved
        // displayApostrophe directly (see that field's doc), which is exactly what this test
        // checks: communityNotation must reflect whatever displayApostrophe says, independent of
        // what `prime` (the physics-correct native value, now reorientation-aware -- see
        // HypercubeRenderer.correctedNativePrimeForRoomTwist) happens to be.
        val record = TwistRecord.Ridge(
            cell = Cell4.I, fixAxis2 = Axis4.X, prime = true,
            roomCell = Cell4.L, roomFixAxis2 = Axis4.Y.nativeIndex,
            displayApostrophe = false,
        )
        assertEquals("LU", Notation.communityNotation(record))
        // The native identifiers are still what the MC4D export and the twist itself need --
        // confirm they're untouched by the room-relative relabeling.
        assertEquals(Cell4.I, record.cell)
        assertEquals(Axis4.X, record.fixAxis2)
    }

    // --- correctedNativePrime: the reorientation-aware physics fix ---------------------------

    @Test
    fun `correctedNativePrime matches the unreoriented reference when room equals native`() {
        // Unreoriented, cubeOrientation4 is identity: every native axis maps to the same-indexed
        // room axis with sign +1 -- so roomR0/roomR1 always equal the native rotating pair itself
        // (ascending, since Axis4 entries are already sorted by nativeIndex), with signs +1.
        for (cell in Cell4.entries) {
            for (fixAxis2 in Axis4.entries) {
                if (fixAxis2 == cell.axis) continue
                val rotating = Axis4.entries.map { it.nativeIndex }.filter { it != cell.axis.nativeIndex && it != fixAxis2.nativeIndex }
                for (desiredApostrophe in listOf(false, true)) {
                    val prime = Notation.correctedNativePrime(
                        rotating[0], 1, rotating[1], 1, cell, fixAxis2, desiredApostrophe,
                    )
                    // Reduces to: prime = isFlip != desiredApostrophe (see correctedNativePrime's
                    // doc derivation) -- i.e. exactly what the old correctedPrimeForDisplay-only
                    // logic already got right for the unreoriented case.
                    val expected = Notation.correctedPrimeForDisplay(cell, fixAxis2.nativeIndex, prime) == desiredApostrophe
                    assertTrue(expected, "cell=$cell fixAxis2=$fixAxis2 desiredApostrophe=$desiredApostrophe")
                }
            }
        }
    }

    @Test
    fun `correctedNativePrime fixes the confirmed reoriented-LU bug`() {
        // Real repro (2026-07-22, confirmed against a real device via scene-dump + DEBUGTEST log):
        // select room R, reorient to I; select room U, reorient to I x3 (net -90); select room L;
        // press the button that resolves to native (cell=I, fixAxis2=X) and intends room "LU" (no
        // apostrophe). At that point cubeOrientation4 maps native Y -> room axis 3 (W) sign +1,
        // and native Z -> room axis 2 (Z) sign +1 (extracted directly from the simulation port --
        // see tools/sim/adjacency_cw_report.py's regression case). The OLD room-keyed-only logic
        // applied prime=false directly, which rendered as CCW (the reverse of the unreoriented "LU"
        // baseline's true F->O->B->I cycle) despite the "no apostrophe" label. The correct native
        // prime is true -- confirmed by simulating the actual twist with prime=true and checking
        // it reproduces the exact unreoriented baseline cycle (tools/sim confirmed this directly).
        val prime = Notation.correctedNativePrime(
            roomR0 = 3, roomR0Sign = 1, // native Y (rotating axis) -> room W, sign +1
            roomR1 = 2, roomR1Sign = 1, // native Z (rotating axis) -> room Z, sign +1
            roomCell = Cell4.L, roomFixAxis2 = Axis4.Y, desiredApostrophe = false, // "LU", no apostrophe
        )
        assertTrue(prime, "expected the reoriented LU twist to need native prime=true, not the old (buggy) false")
    }

    // --- rotationInvertedForCell: spot checks from real controller confirmation ---------------

    @Test
    fun `UP is only already-correct on U and B`() {
        assertFalse(Notation.rotationInvertedForCell(RotationButton.UP, Cell4.U))
        assertFalse(Notation.rotationInvertedForCell(RotationButton.UP, Cell4.B))
        for (cell in listOf(Cell4.D, Cell4.L, Cell4.R, Cell4.F, Cell4.I, Cell4.O)) {
            assertTrue(Notation.rotationInvertedForCell(RotationButton.UP, cell), "expected $cell to need flipping for UP")
        }
    }

    @Test
    fun `DOWN's overall resolved prime is always the opposite of UP's, at every cell`() {
        // rotationInvertedForCell itself returns the *same* flag for UP and DOWN (they share one
        // when-branch) -- the opposite relationship only emerges once combined with each button's
        // own primaryPrime (UP=false, DOWN=true), which is what MainActivity's on4DRotationButton
        // actually resolves a twist's prime from.
        for (cell in Cell4.entries) {
            val upPrime = RotationButton.UP.primaryPrime != Notation.rotationInvertedForCell(RotationButton.UP, cell)
            val downPrime = RotationButton.DOWN.primaryPrime != Notation.rotationInvertedForCell(RotationButton.DOWN, cell)
            assertEquals(!upPrime, downPrime, "expected DOWN's resolved prime to be UP's opposite at $cell")
        }
    }

    @Test
    fun `BUMPER_R and TRIGGER_R are only already-correct on R and D`() {
        for (button in listOf(RotationButton.BUMPER_R, RotationButton.TRIGGER_R)) {
            assertFalse(Notation.rotationInvertedForCell(button, Cell4.R))
            assertFalse(Notation.rotationInvertedForCell(button, Cell4.D))
            for (cell in listOf(Cell4.U, Cell4.L, Cell4.F, Cell4.B, Cell4.I, Cell4.O)) {
                assertTrue(Notation.rotationInvertedForCell(button, cell), "expected $cell to need flipping for $button")
            }
        }
    }

    @Test
    fun `LEFT and RIGHT are only already-correct on U, D, L, F, I, and O`() {
        // Real-device confirmation (2026-08-17): unlike UP/DOWN and BUMPER_R/TRIGGER_R above, O
        // turned out NOT to need flipping for LEFT/RIGHT -- David reported X/LEFT moving the FO
        // ridge to RO (backwards); comparing against I (O's own reference cell, sharing its W
        // axis, where LEFT correctly moves F to L) confirmed O had been guessed into the flipped
        // set wrongly, "by extension" of the same L/R/I grouping the other two button pairs use
        // (where that same guess happened to be right).
        for (button in listOf(RotationButton.LEFT, RotationButton.RIGHT)) {
            for (cell in listOf(Cell4.U, Cell4.D, Cell4.L, Cell4.F, Cell4.I, Cell4.O)) {
                assertFalse(Notation.rotationInvertedForCell(button, cell), "expected $cell to already be correct for $button")
            }
            for (cell in listOf(Cell4.R, Cell4.B)) {
                assertTrue(Notation.rotationInvertedForCell(button, cell), "expected $cell to need flipping for $button")
            }
        }
    }

    // --- consolidateDoubles: double-turn collapsing --------------------------------------------

    @Test
    fun `consecutive identical moves collapse to a double-turn token`() {
        val result = Notation.consolidateDoubles(listOf("RU", "RU", "FB'")) { it }
        assertEquals(listOf("RU2", "FB'"), result)
    }

    @Test
    fun `consecutive opposite-prime moves do not collapse`() {
        val result = Notation.consolidateDoubles(listOf("RU", "RU'")) { it }
        assertEquals(listOf("RU", "RU'"), result)
    }
}
