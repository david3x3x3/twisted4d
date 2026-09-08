package dev.twisted4d.app

/** One recorded 4D twist -- [Ridge] (the original/only kind until 2026-08-10) or [Edge] (new: a
 * genuine single 180-degree twist around the diagonal axis through 2 of a cell's spatial axes,
 * what MagicCube4D itself does when you click an edge sticker -- 2 nonzero local axes -- instead
 * of a ridge sticker's ordinary 1-axis 90-degree twist; see HypercubeRenderer.
 * requestI180TwistUFDB's doc for the full "why a sealed class" reasoning and how [Edge]'s exact
 * rotation was derived/verified). A plain flat data class with "meaningful only for ridge twists"
 * fields (the alternative) is exactly the kind of implicit invariant this codebase avoids
 * elsewhere -- a sealed class instead forces every consumer (undo, export, save/load) to
 * explicitly decide what an [Edge] means to it, via an exhaustive `when`, rather than silently
 * misreading a field that doesn't apply. [Edge] doesn't yet carry room-relative fields the way
 * [Ridge] does (see [Ridge.roomCell]'s doc) -- David's Button-C+X/B shortcut that produces it
 * always acts on native [Cell4.I] directly, not room-relative, a deliberate scope cut for now
 * (see HypercubeRenderer.requestEdgeTwist's doc). */
sealed class TwistRecord {
    /** [cell]/[fixAxis2]/[prime] are native identifiers -- what [Cube4.twist], undo, and the
     * MC4D file export all need, since MC4D's own grip encoding is piece-identity-based and
     * camera-independent (see the mc4d_log_compatibility memory). [roomCell]/[roomFixAxis2] are
     * the *room-relative* cell/axis in effect when this twist was made -- what a human reads off
     * the screen and what community notation (and this app's own on-screen label / clipboard
     * notation) should be built from instead, since those two can disagree once the room's been
     * reoriented away from default (see HypercubeRenderer.selectedRoomCell's doc). [roomFixAxis2]
     * is a raw room axis index (HypercubeRenderer.AXIS_X et al.), not yet converted to a
     * representative letter, so it can be combined with [Notation.communityNotation] at read
     * time.
     *
     * [displayApostrophe] is the room-level, orientation-independent community-notation
     * apostrophe intent -- computed *once*, at twist-resolution time (via [Notation.
     * correctedPrimeForDisplay], from the button's raw per-cell-corrected prime, before
     * [HypercubeRenderer.correctedNativePrimeForRoomTwist] adjusts [prime] itself for the current
     * reorientation) -- and stored directly rather than re-derived from [prime] later, because
     * that re-derivation is exactly what the reoriented-CCW-labeled-CW bug turned out to be:
     * [prime] is now the *true* native prime actually applied (needed by [Cube4.twist] and,
     * unchanged, by MC4D export), which depends on the current orientation -- reusing a room-only
     * table on it, after the fact, silently assumes an orientation-independence that isn't true.
     * See [correctedPrimeForDisplay]'s doc for the fuller history and
     * [[mc4d_log_compatibility]] memory for why the two primes must stay separate. */
    data class Ridge(
        val cell: Cell4,
        val fixAxis2: Axis4,
        val prime: Boolean,
        val roomCell: Cell4,
        val roomFixAxis2: Int,
        val displayApostrophe: Boolean,
    ) : TwistRecord()

    /** [cell]/[axis1]/[sign1]/[axis2]/[sign2] identify the edge sticker grabbed -- e.g.
     * `axis1=Y,sign1=+1,axis2=Z,sign2=+1` for the UF/DB diagonal (the U and F directions). No
     * `prime`/apostrophe: a 180-degree edge twist is its own inverse (undo and redo both just
     * replay it -- see MainActivity.performUndo/performRedo), unlike a ridge twist's two distinct
     * directions. Native-only for now (no `roomCell`/`roomAxis1`/`roomAxis2` -- see this sealed
     * class's own doc), so [Notation.communityNotation] and MC4D export both key off these native
     * fields directly rather than a room-relative label. */
    data class Edge(
        val cell: Cell4,
        val axis1: Axis4,
        val sign1: Int,
        val axis2: Axis4,
        val sign2: Int,
    ) : TwistRecord()
}

/** Pure, Android-independent 4D twist-notation logic (hypercubing.xyz community notation and
 * real MagicCube4D `.log` file compatibility) -- extracted from [MainActivity] so it can be unit
 * tested directly (see `app/src/test`), rather than only checkable by hand on a device. Nothing
 * in here touches rendering, gamepad input, or Android framework classes; [MainActivity] and
 * [HypercubeRenderer] are the only things that resolve a physical action down to the [Cell4]/
 * [Axis4]/[TwistRecord] values this object turns into notation. */
object Notation {

    /** Collapses exactly-two-consecutive-*identical* moves (same move, same prime) into a single
     * "&lt;base&gt;2" token -- e.g. two "RU" moves in a row become "RU2" -- matching standard
     * twisty-puzzle double-turn notation. Two consecutive *opposite*-prime moves on the same
     * axis aren't a double turn (they're most of a cancellation), so those are deliberately left
     * alone: only exact repeats consolidate. */
    fun <T> consolidateDoubles(moves: List<T>, baseNotation: (T) -> String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < moves.size) {
            if (i + 1 < moves.size && moves[i] == moves[i + 1]) {
                out.add(baseNotation(moves[i]).trimEnd('\'') + "2")
                i += 2
            } else {
                out.add(baseNotation(moves[i]))
                i += 1
            }
        }
        return out
    }

    /** Whether [button]'s resolved twist needs its prime flipped to look screen-consistent for
     * [roomCell] -- [RotationButton]'s literalAxis/primaryPrime scheme picks a single native
     * rotation formula per button and applies it uniformly to whichever cell is selected, but the
     * room rendering isn't symmetric across walls (each wall's "depth" axis is always W,
     * regardless of that wall's own axis -- see HypercubeRenderer.onDrawFrame's screenPos
     * computation), so the *same* formula produces opposite on-screen rotation senses at
     * different walls. [roomCell] must be the selected *room slot's* label
     * (HypercubeRenderer.selectedRoomCell), not the native cell occupying it -- the correction is
     * about which wall is rendering the twist, not which native cell it happens to be right now,
     * so once the room's been rotated away from default those two can disagree (confirmed via a
     * real repro: reorienting the puzzle, then twisting a cell that no longer sits in its own
     * wall, got the wrong on-screen direction until this was keyed off the wall instead). There's no
     * clean closed-form correction (checked: doesn't reduce to a simple function of axis, sign, or
     * fixAxis2 alone) -- both sets below were derived by simulating HypercubeRenderer's exact
     * projection math (piece rotation -> room slot -> screenPos -> the isometric default view3
     * matrix) for all 8 cells and comparing against a reference convention, then flipping prime for
     * whichever cells didn't already match it.
     *
     * UP's reference is real-device-confirmed: the user reported `UP` already looks correct on `U`
     * and `B`, backwards on the rest. The simulation reproduced that split exactly (U/B alone came
     * out clockwise; D/L/R/F/I -- and O by the same rotating-axis-pair grouping as L/R/I, though O
     * itself is never rendered to check directly -- all came out counterclockwise), and flipping
     * prime for that same set made all 8 clockwise. DOWN shares the set for a structural reason,
     * not a separately-confirmed one: it has the same literalAxis as UP and only flips
     * primaryPrime, so it's *defined* as UP's exact opposite for every cell already -- simulated
     * confirmation that DOWN is the opposite of UP's sense at all 7 checkable cells, both before
     * and after applying this same correction, so reusing it keeps that relationship intact rather
     * than re-deriving it from scratch.
     *
     * RIGHT/LEFT's reference was originally read off the `todo-controller-input.md` spec's stated
     * target ("Right button: rotates so the positive X axis moves away from the user, i.e.
     * positive X -> negative Z") rather than live-tested. U and D are RIGHT's "collision" cells
     * (literalAxis=Y collides with their own axis, forcing fixAxis2=W, the clean case), and
     * simulating RIGHT's *current, unmodified* formula on them produces exactly that +X->-Z
     * rotation -- so U/D (plus L/F/I, which already independently matched U/D's resulting screen
     * sense) were treated as the reference, and R/B were flipped to match. O was *guessed* into
     * the flipped set too, "by extension" of the same rotating-axis-pair grouping L/R/I used --
     * confirmed wrong by real-device testing (2026-08-17, David: X/left moved the FO ridge to RO,
     * when comparing against I -- O's own reference cell, sharing its W axis -- shows LEFT should
     * move F to L, not R). O is excluded here now, unlike UP/DOWN and BUMPER_R/TRIGGER_R below,
     * where the same "by extension" guess for O was independently confirmed *correct* (O's
     * inverted flag already matches I's for both those button pairs -- see NotationTest). LEFT
     * reuses RIGHT's (corrected) set for the same structural reason DOWN reuses UP's.
     *
     * BUMPER_R/TRIGGER_R's reference is real-device-confirmed, like UP's: the user reported both
     * already look correct on `R` and `D`, backwards on the rest. Simulated confirmation matched
     * that split exactly (R/D alone were already internally consistent between TRIGGER_R and
     * BUMPER_R as an opposite pair; U/L/F/B/I -- and O by extension -- all came out backwards on
     * both), and flipping prime for that set made all 8 cells consistent for both buttons. */
    fun rotationInvertedForCell(button: RotationButton, roomCell: Cell4): Boolean = when (button) {
        RotationButton.UP, RotationButton.DOWN -> roomCell in setOf(Cell4.D, Cell4.L, Cell4.R, Cell4.F, Cell4.I, Cell4.O)
        RotationButton.LEFT, RotationButton.RIGHT -> roomCell in setOf(Cell4.R, Cell4.B)
        RotationButton.TRIGGER_R, RotationButton.BUMPER_R -> roomCell in setOf(Cell4.U, Cell4.L, Cell4.F, Cell4.B, Cell4.I, Cell4.O)
    }

    /** Canonical single-cell representative for each axis, used to name fixAxis2 in
     * hypercubing.xyz notation -- an arbitrary but consistent choice, since either of an axis's
     * two cells names the same physical twist (just with the prime flipped). Native-axis version,
     * for [mc4dLogFile]'s MC4D export -- see [roomAxisRepresentativeCell] for the room-relative
     * version [communityNotation] needs instead. */
    fun axisRepresentativeCell(axis: Axis4): Cell4 = when (axis) {
        Axis4.X -> Cell4.R
        Axis4.Y -> Cell4.U
        Axis4.Z -> Cell4.F
        Axis4.W -> Cell4.O
    }

    /** Same mapping as [axisRepresentativeCell], applied to a *room* axis
     * (HypercubeRenderer.AXIS_X et al., which share [Axis4.nativeIndex]'s numbering) instead of a
     * native one -- the letter-per-index convention doesn't care which kind of axis it's fed, only
     * [axisRepresentativeCell]'s callers do. */
    fun roomAxisRepresentativeCell(roomAxis: Int): Cell4 =
        axisRepresentativeCell(Axis4.entries.first { it.nativeIndex == roomAxis })

    /** The exact cell at ([axis], [sign]) -- e.g. (Y,-1) -> D, (Y,+1) -> U. Unlike
     * [axisRepresentativeCell] (which deliberately ignores sign, since a ridge twist's `fixAxis2`
     * has no inherent sign of its own -- the apostrophe carries direction instead), a
     * [TwistRecord.Edge]'s two axis signs are exactly what distinguish one diagonal from another
     * (e.g. UF/DB from UB/DF) -- reusing the sign-blind representative for edge notation would
     * make different diagonals print identically (confirmed: "ROU" turned out to be ambiguous
     * between two different real diagonals of cell R, which is what led to this function). */
    fun signedAxisCell(axis: Axis4, sign: Int): Cell4 =
        Cell4.entries.first { it.axis == axis && it.sign == sign }

    /** e.g. "RU'" -- a ridge twist's community notation, given its already-resolved room-relative
     * fields. Extracted out of [communityNotation]'s [TwistRecord.Ridge] branch so the same formula
     * can also build a *live* (not-yet-applied) label from [HypercubeRenderer]'s label-safe
     * accessors, without needing a full [TwistRecord]. */
    fun ridgeNotation(roomCell: Cell4, roomFixAxis2: Int, displayApostrophe: Boolean): String =
        roomCell.label + roomAxisRepresentativeCell(roomFixAxis2).label + (if (displayApostrophe) "'" else "")

    /** MC4D's own cell order, empirically reverse-engineered (not documented in MC4D's source --
     * it comes from an external geometry library's traversal order): both the "which 27-grip
     * block" index for a cell *and* the within-block ordering of ridge-piece grips (see
     * [mc4dGrip]) are positions in this exact sequence. */
    val MC4D_CELL_ORDER = listOf(Cell4.I, Cell4.D, Cell4.F, Cell4.L, Cell4.R, Cell4.B, Cell4.U, Cell4.O)

    fun mc4dOpposite(cell: Cell4): Cell4 = Cell4.entries.first { it.axis == cell.axis && it.sign == -cell.sign }

    /** MC4D's grip index for the "2c ridge" piece straddling [cell] and [axisRepresentativeCell]
     * of [fixAxis2] -- reverse-engineered from real MC4D log files (see the mc4d_log_compatibility
     * memory for the full derivation): `cellIndex*27 + 20 + position`, where 20 is the fixed
     * offset to the 6-slot "ridge" tier within a cell's 27-grip block, and position is where the
     * representative cell falls in [MC4D_CELL_ORDER] once [cell] and its own opposite are
     * removed (both cell index and ridge position use that same master order). */
    fun mc4dGrip(cell: Cell4, fixAxis2: Axis4): Int {
        val cellIndex = MC4D_CELL_ORDER.indexOf(cell)
        val opposite = mc4dOpposite(cell)
        val remaining = MC4D_CELL_ORDER.filter { it != cell && it != opposite }
        val position = remaining.indexOf(axisRepresentativeCell(fixAxis2))
        return cellIndex * 27 + 20 + position
    }

    /** Key for [MC4D_EDGE_POSITIONS], normalized only by axis *order* (lower [Axis4.nativeIndex]
     * first, its own sign carried along with it) -- unlike [HypercubeRenderer]'s `edgeKey`, sign
     * is NOT further normalized away here: that normalization was valid there because a 180-degree
     * *rotation matrix* doesn't care about its axis's overall sign, but here each of the two
     * antipodal-axis grips (e.g. `(Y,-1,Z,-1)` vs `(Y,1,Z,1)` for the same physical UF/DB axis on
     * cell I) is a *different, real, individually-numbered* MC4D grip -- collapsing them would
     * silently export the wrong sticker. [TwistRecord.Edge]'s stored `sign1`/`sign2` already
     * unambiguously pick one specific grip (whichever one [HypercubeRenderer.
     * nativeAxisAndSignAtRoomAxis] actually resolved), so the lookup must preserve that, not
     * collapse it. */
    private data class Mc4dEdgeKey(val cell: Cell4, val axis1: Axis4, val sign1: Int, val axis2: Axis4, val sign2: Int)

    private fun mc4dEdgeKey(cell: Cell4, a: Axis4, signA: Int, b: Axis4, signB: Int): Mc4dEdgeKey =
        if (a.nativeIndex <= b.nativeIndex) Mc4dEdgeKey(cell, a, signA, b, signB) else Mc4dEdgeKey(cell, b, signB, a, signA)

    /** MC4D's within-tier position (0-11) for the "3c edge" grip at [cell]'s local diagonal through
     * ([axis1],[sign1])/([axis2],[sign2]) -- e.g. Y,+1/Z,+1 for the UF corner-ish edge sticker.
     * Unlike [mc4dGrip]'s ridge formula (a closed-form function of [MC4D_CELL_ORDER]), this tier's
     * within-cell ordering isn't a formula at all -- confirmed by actually compiling and running
     * MagicCube4D's own `PolytopePuzzleDescription`/CSG source (2026-08-10, `cutelyaware/
     * magiccube4d` on GitHub) and reading the real grip coordinates back out, since the ordering
     * comes from `CSG.Polytope.id`, a plain creation-order counter from the general n-dimensional
     * polytope-construction algorithm (see the mc4d_log_compatibility memory) -- there's no pattern
     * to extract, just 96 individually-confirmed values (8 cells x 12 edges each). Cross-validated
     * against [mc4dGrip] itself while deriving this: for every one of the 8 cells, the *ridge* tier
     * grip [mc4dGrip] predicts for each of that cell's 3 valid `fixAxis2` choices was checked
     * against the real dumped coordinates and found to have exactly one nonzero "local" coordinate
     * at the expected position -- i.e. this table and the already-shipped, MC4D-confirmed ridge
     * formula agree on every cell, not just asserted independently.
     *
     * **Correction (2026-08-11):** the original 2026-08-10 table had every entry involving the Z
     * axis sign-flipped (48 of 96 entries) -- a real bug, found via a scramble+solve+reimport
     * round trip that came back unsolved in real MC4D (David's repro). The cross-validation above
     * only ever checked *which* coordinate index was nonzero (axis identity), never its *sign* --
     * so it silently passed even though the raw-coordinate-index-to-[Axis4] mapping used to build
     * this table was wrong for one axis. The real mapping (confirmed by checking the sign of each
     * cell's own constant coordinate against that cell's already-known [Cell4.sign] for all 8
     * cells, which pins down all 4 axes unambiguously): raw index 0 = `+X`, raw index 1 = `-Z`,
     * raw index 2 = `+Y`, raw index 3 = `+W` -- indices 1/2 are swapped from the naive `X,Y,Z,W`
     * assumption *and* Z is sign-inverted. X, Y, and W were always correct, which is exactly why
     * cells whose own axis is X or W (L/R/I/O) or whose edge axes never include Z (F/B) partly or
     * fully checked out before, while D/U/L/R/I/O entries that *did* involve Z were wrong. See
     * `tools/sim` methodology notes / mc4d_log_compatibility memory for the verification script. */
    fun mc4dEdgeGrip(cell: Cell4, axis1: Axis4, sign1: Int, axis2: Axis4, sign2: Int): Int {
        val cellIndex = MC4D_CELL_ORDER.indexOf(cell)
        val position = MC4D_EDGE_POSITIONS.getValue(mc4dEdgeKey(cell, axis1, sign1, axis2, sign2))
        return cellIndex * 27 + 8 + position
    }

    private val MC4D_EDGE_POSITIONS: Map<Mc4dEdgeKey, Int> = mapOf(
        mc4dEdgeKey(Cell4.I, Axis4.Y, -1, Axis4.Z, 1) to 0,
        mc4dEdgeKey(Cell4.I, Axis4.X, -1, Axis4.Y, -1) to 1,
        mc4dEdgeKey(Cell4.I, Axis4.X, 1, Axis4.Y, -1) to 2,
        mc4dEdgeKey(Cell4.I, Axis4.Y, -1, Axis4.Z, -1) to 3,
        mc4dEdgeKey(Cell4.I, Axis4.X, -1, Axis4.Z, 1) to 4,
        mc4dEdgeKey(Cell4.I, Axis4.X, 1, Axis4.Z, 1) to 5,
        mc4dEdgeKey(Cell4.I, Axis4.X, -1, Axis4.Z, -1) to 6,
        mc4dEdgeKey(Cell4.I, Axis4.X, 1, Axis4.Z, -1) to 7,
        mc4dEdgeKey(Cell4.I, Axis4.Y, 1, Axis4.Z, 1) to 8,
        mc4dEdgeKey(Cell4.I, Axis4.X, -1, Axis4.Y, 1) to 9,
        mc4dEdgeKey(Cell4.I, Axis4.X, 1, Axis4.Y, 1) to 10,
        mc4dEdgeKey(Cell4.I, Axis4.Y, 1, Axis4.Z, -1) to 11,
        mc4dEdgeKey(Cell4.D, Axis4.Z, 1, Axis4.W, -1) to 0,
        mc4dEdgeKey(Cell4.D, Axis4.X, -1, Axis4.W, -1) to 1,
        mc4dEdgeKey(Cell4.D, Axis4.X, 1, Axis4.W, -1) to 2,
        mc4dEdgeKey(Cell4.D, Axis4.Z, -1, Axis4.W, -1) to 3,
        mc4dEdgeKey(Cell4.D, Axis4.X, -1, Axis4.Z, 1) to 4,
        mc4dEdgeKey(Cell4.D, Axis4.X, 1, Axis4.Z, 1) to 5,
        mc4dEdgeKey(Cell4.D, Axis4.X, -1, Axis4.Z, -1) to 6,
        mc4dEdgeKey(Cell4.D, Axis4.X, 1, Axis4.Z, -1) to 7,
        mc4dEdgeKey(Cell4.D, Axis4.Z, 1, Axis4.W, 1) to 8,
        mc4dEdgeKey(Cell4.D, Axis4.X, -1, Axis4.W, 1) to 9,
        mc4dEdgeKey(Cell4.D, Axis4.X, 1, Axis4.W, 1) to 10,
        mc4dEdgeKey(Cell4.D, Axis4.Z, -1, Axis4.W, 1) to 11,
        mc4dEdgeKey(Cell4.F, Axis4.Y, -1, Axis4.W, -1) to 0,
        mc4dEdgeKey(Cell4.F, Axis4.X, -1, Axis4.W, -1) to 1,
        mc4dEdgeKey(Cell4.F, Axis4.X, 1, Axis4.W, -1) to 2,
        mc4dEdgeKey(Cell4.F, Axis4.Y, 1, Axis4.W, -1) to 3,
        mc4dEdgeKey(Cell4.F, Axis4.X, -1, Axis4.Y, -1) to 4,
        mc4dEdgeKey(Cell4.F, Axis4.X, 1, Axis4.Y, -1) to 5,
        mc4dEdgeKey(Cell4.F, Axis4.X, -1, Axis4.Y, 1) to 6,
        mc4dEdgeKey(Cell4.F, Axis4.X, 1, Axis4.Y, 1) to 7,
        mc4dEdgeKey(Cell4.F, Axis4.Y, -1, Axis4.W, 1) to 8,
        mc4dEdgeKey(Cell4.F, Axis4.X, -1, Axis4.W, 1) to 9,
        mc4dEdgeKey(Cell4.F, Axis4.X, 1, Axis4.W, 1) to 10,
        mc4dEdgeKey(Cell4.F, Axis4.Y, 1, Axis4.W, 1) to 11,
        mc4dEdgeKey(Cell4.L, Axis4.Y, -1, Axis4.W, -1) to 0,
        mc4dEdgeKey(Cell4.L, Axis4.Z, 1, Axis4.W, -1) to 1,
        mc4dEdgeKey(Cell4.L, Axis4.Z, -1, Axis4.W, -1) to 2,
        mc4dEdgeKey(Cell4.L, Axis4.Y, 1, Axis4.W, -1) to 3,
        mc4dEdgeKey(Cell4.L, Axis4.Y, -1, Axis4.Z, 1) to 4,
        mc4dEdgeKey(Cell4.L, Axis4.Y, -1, Axis4.Z, -1) to 5,
        mc4dEdgeKey(Cell4.L, Axis4.Y, 1, Axis4.Z, 1) to 6,
        mc4dEdgeKey(Cell4.L, Axis4.Y, 1, Axis4.Z, -1) to 7,
        mc4dEdgeKey(Cell4.L, Axis4.Y, -1, Axis4.W, 1) to 8,
        mc4dEdgeKey(Cell4.L, Axis4.Z, 1, Axis4.W, 1) to 9,
        mc4dEdgeKey(Cell4.L, Axis4.Z, -1, Axis4.W, 1) to 10,
        mc4dEdgeKey(Cell4.L, Axis4.Y, 1, Axis4.W, 1) to 11,
        mc4dEdgeKey(Cell4.R, Axis4.Y, -1, Axis4.W, -1) to 0,
        mc4dEdgeKey(Cell4.R, Axis4.Z, 1, Axis4.W, -1) to 1,
        mc4dEdgeKey(Cell4.R, Axis4.Z, -1, Axis4.W, -1) to 2,
        mc4dEdgeKey(Cell4.R, Axis4.Y, 1, Axis4.W, -1) to 3,
        mc4dEdgeKey(Cell4.R, Axis4.Y, -1, Axis4.Z, 1) to 4,
        mc4dEdgeKey(Cell4.R, Axis4.Y, -1, Axis4.Z, -1) to 5,
        mc4dEdgeKey(Cell4.R, Axis4.Y, 1, Axis4.Z, 1) to 6,
        mc4dEdgeKey(Cell4.R, Axis4.Y, 1, Axis4.Z, -1) to 7,
        mc4dEdgeKey(Cell4.R, Axis4.Y, -1, Axis4.W, 1) to 8,
        mc4dEdgeKey(Cell4.R, Axis4.Z, 1, Axis4.W, 1) to 9,
        mc4dEdgeKey(Cell4.R, Axis4.Z, -1, Axis4.W, 1) to 10,
        mc4dEdgeKey(Cell4.R, Axis4.Y, 1, Axis4.W, 1) to 11,
        mc4dEdgeKey(Cell4.B, Axis4.Y, -1, Axis4.W, -1) to 0,
        mc4dEdgeKey(Cell4.B, Axis4.X, -1, Axis4.W, -1) to 1,
        mc4dEdgeKey(Cell4.B, Axis4.X, 1, Axis4.W, -1) to 2,
        mc4dEdgeKey(Cell4.B, Axis4.Y, 1, Axis4.W, -1) to 3,
        mc4dEdgeKey(Cell4.B, Axis4.X, -1, Axis4.Y, -1) to 4,
        mc4dEdgeKey(Cell4.B, Axis4.X, 1, Axis4.Y, -1) to 5,
        mc4dEdgeKey(Cell4.B, Axis4.X, -1, Axis4.Y, 1) to 6,
        mc4dEdgeKey(Cell4.B, Axis4.X, 1, Axis4.Y, 1) to 7,
        mc4dEdgeKey(Cell4.B, Axis4.Y, -1, Axis4.W, 1) to 8,
        mc4dEdgeKey(Cell4.B, Axis4.X, -1, Axis4.W, 1) to 9,
        mc4dEdgeKey(Cell4.B, Axis4.X, 1, Axis4.W, 1) to 10,
        mc4dEdgeKey(Cell4.B, Axis4.Y, 1, Axis4.W, 1) to 11,
        mc4dEdgeKey(Cell4.U, Axis4.Z, 1, Axis4.W, -1) to 0,
        mc4dEdgeKey(Cell4.U, Axis4.X, -1, Axis4.W, -1) to 1,
        mc4dEdgeKey(Cell4.U, Axis4.X, 1, Axis4.W, -1) to 2,
        mc4dEdgeKey(Cell4.U, Axis4.Z, -1, Axis4.W, -1) to 3,
        mc4dEdgeKey(Cell4.U, Axis4.X, -1, Axis4.Z, 1) to 4,
        mc4dEdgeKey(Cell4.U, Axis4.X, 1, Axis4.Z, 1) to 5,
        mc4dEdgeKey(Cell4.U, Axis4.X, -1, Axis4.Z, -1) to 6,
        mc4dEdgeKey(Cell4.U, Axis4.X, 1, Axis4.Z, -1) to 7,
        mc4dEdgeKey(Cell4.U, Axis4.Z, 1, Axis4.W, 1) to 8,
        mc4dEdgeKey(Cell4.U, Axis4.X, -1, Axis4.W, 1) to 9,
        mc4dEdgeKey(Cell4.U, Axis4.X, 1, Axis4.W, 1) to 10,
        mc4dEdgeKey(Cell4.U, Axis4.Z, -1, Axis4.W, 1) to 11,
        mc4dEdgeKey(Cell4.O, Axis4.Y, -1, Axis4.Z, 1) to 0,
        mc4dEdgeKey(Cell4.O, Axis4.X, -1, Axis4.Y, -1) to 1,
        mc4dEdgeKey(Cell4.O, Axis4.X, 1, Axis4.Y, -1) to 2,
        mc4dEdgeKey(Cell4.O, Axis4.Y, -1, Axis4.Z, -1) to 3,
        mc4dEdgeKey(Cell4.O, Axis4.X, -1, Axis4.Z, 1) to 4,
        mc4dEdgeKey(Cell4.O, Axis4.X, 1, Axis4.Z, 1) to 5,
        mc4dEdgeKey(Cell4.O, Axis4.X, -1, Axis4.Z, -1) to 6,
        mc4dEdgeKey(Cell4.O, Axis4.X, 1, Axis4.Z, -1) to 7,
        mc4dEdgeKey(Cell4.O, Axis4.Y, 1, Axis4.Z, 1) to 8,
        mc4dEdgeKey(Cell4.O, Axis4.X, -1, Axis4.Y, 1) to 9,
        mc4dEdgeKey(Cell4.O, Axis4.X, 1, Axis4.Y, 1) to 10,
        mc4dEdgeKey(Cell4.O, Axis4.Y, 1, Axis4.Z, -1) to 11,
    )

    /** `(cell, fixAxis2)` pairs where the raw native `prime` bit needs flipping before it means
     * what [communityNotation] and [mc4dLogFile] need it to mean -- `Cube4::twist`'s
     * rotating-axis-pair handedness is a mechanical function of native axis index order (see
     * `plane_rotation` in `cube4.rs`), not something that adapts to match hypercubing.xyz's or
     * MC4D's community/grip conventions, so this doesn't reduce to a formula (checked: not sign
     * alone, not representative-vs-opposite alone, not axis-ascending-vs-descending alone -- same
     * kind of irreducibility as the on-screen rotation-button screen-consistency tables elsewhere
     * in this file, which is a separate, unrelated correction -- see [[discuss_theories_before_acting]]
     * memory for why, and don't conflate the two).
     *
     * Confirmed 2026-07-21 via an on-screen per-twist community-notation survey covering every
     * (cell, fixAxis2) combination (real controller, STICK mode): every wrong case was purely a
     * flipped prime on the correct cell+representative, never a wrong cell or grip. The three
     * O-as-twisted-cell entries (`OR`/`OU`/`OF`) were derived by a symmetry guess first (O shares
     * I's axis, W, with the opposite sign, and every other axis pair has exactly one of its two
     * signed cells flip while the other doesn't -- I flips for R/F reps not U, so O was guessed to
     * flip for U not R/F) and then confirmed for real: O selected, one twist on each of its three
     * axes (X/Y/Z), exported and re-imported into real MC4D, whose rendering matched twisted4d's.
     *
     * The `(R,W)`/`(D,W)`/`(F,W)` entries (O as *representative*, not as the twisted cell) are a
     * separate subset with their own independent real-MC4D confirmation from earlier this session
     * (see the mc4d_log_compatibility memory): from a solved puzzle, `RO`/`DO`/`FO` (non-prime)
     * replay correctly, but `UO`/`BO`/`LO` (non-prime) replay as their prime -- consistent with the
     * on-screen survey redone here. */
    val PRIME_FLIP_TWISTS: Set<Pair<Cell4, Axis4>> = setOf(
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

    /** [prime] corrected so it means what [mc4dLogFile] needs -- see [PRIME_FLIP_TWISTS]'s doc.
     * Keyed by *native* cell/axis: MC4D's own grip encoding is piece-identity-based and
     * camera-independent (see the mc4d_log_compatibility memory), so the file export needs the
     * native pair regardless of room orientation. For the on-screen/human-facing label, see
     * [correctedPrimeForDisplay] instead -- don't reuse this one there, they're not
     * interchangeable (see that function's doc for why). */
    fun correctedPrime(cell: Cell4, fixAxis2: Axis4, prime: Boolean): Boolean =
        prime != ((cell to fixAxis2) in PRIME_FLIP_TWISTS)

    /** The room-level, orientation-independent community-notation apostrophe intent for a button
     * press already resolved to ([roomCell], [roomFixAxis2]) with raw per-cell-corrected [prime]
     * (i.e. `button.primaryPrime != rotationInvertedForCell(button, roomCell)`, computed *before*
     * any reorientation-awareness) -- called *once*, at twist-resolution time, to produce the
     * value stored as [TwistRecord.Ridge.displayApostrophe]. Same [PRIME_FLIP_TWISTS] table as
     * [correctedPrime], but keyed by *room* cell/axis instead of native.
     *
     * The original on-screen survey that produced [PRIME_FLIP_TWISTS] was done entirely from
     * unreoriented starting points, where native and room identity are always identical -- so it
     * never actually distinguished "this flip is a fact about the native pair" from "this flip is
     * a fact about the room pair," since during that survey those were always the same pair. A
     * real repro exposed which one it actually is: reorienting so native I ends up sitting in
     * room slot L, then pressing a button that resolves to native (I, X) but room (L, Y) --
     * [rotationInvertedForCell] (already room-keyed, from an earlier fix) correctly made the
     * button feel screen-consistent with pressing the same button on native L directly, which
     * unreoriented gives "LU" (no flip, since (L, Y) isn't in [PRIME_FLIP_TWISTS]) -- but the
     * native-keyed lookup used (I, X), which *is* in the set, so it wrongly flipped to "LU'".
     * Community notation describes what's on screen from the current viewer's perspective (the
     * same reason [TwistRecord] carries room fields at all -- see its own doc), so the *intent*
     * needs the room-keyed decision, not the native one.
     *
     * This alone is NOT enough to make the *actual twist* render correctly once reoriented --
     * that's [HypercubeRenderer.correctedNativePrimeForRoomTwist]'s job, using this function's
     * result as its target. Don't call this a second time on an already-applied
     * [TwistRecord.Ridge]'s `prime` to re-derive the apostrophe -- see
     * [TwistRecord.Ridge.displayApostrophe]'s doc for why that reintroduces the exact bug this
     * two-step split fixes. */
    fun correctedPrimeForDisplay(roomCell: Cell4, roomFixAxis2: Int, prime: Boolean): Boolean {
        val roomAxis = Axis4.entries.first { it.nativeIndex == roomFixAxis2 }
        return prime != ((roomCell to roomAxis) in PRIME_FLIP_TWISTS)
    }

    /** Whether native ascending-axis-order `clockwise = true` (see `plane_rotation` in
     * `cube4.rs`) renders as "no apostrophe" for ([cell], [fixAxis2]) *when unreoriented* (native
     * == room) -- the fixed, verified reference [correctedNativePrime] conjugates through the
     * current orientation to find the matching native prime for any reorientation state. Directly
     * derived from [PRIME_FLIP_TWISTS]: `correctedPrime`'s own definition says `prime = isFlip`
     * renders as "no apostrophe", and `Cube4::twist` passes `clockwise = !prime` -- so
     * `clockwise = !isFlip`. */
    fun targetClockwiseForNoApostrophe(cell: Cell4, fixAxis2: Axis4): Boolean =
        (cell to fixAxis2) !in PRIME_FLIP_TWISTS

    /**
     * The native `prime` bit that, applied to a twist whose two rotating native axes currently
     * map (via `cubeOrientation4`) to room axis [roomR0] with sign [roomR0Sign] and room axis
     * [roomR1] with sign [roomR1Sign] respectively, renders on screen as [desiredApostrophe] for
     * the room-relative ([roomCell], [roomFixAxis2]) grip. Pure core of
     * [HypercubeRenderer.correctedNativePrimeForRoomTwist] -- see that function's doc for the full
     * "why" (a reorientation can flip a native rotation's rendered handedness even though
     * `cubeOrientation4` is always a proper rotation) and for how [roomR0]/[roomR1] and their
     * signs get extracted from the live orientation matrix. Kept here, taking that extraction's
     * *result* as plain data instead of reading `cubeOrientation4` directly, so it stays testable
     * without any Android/GL dependency, same as everything else in this file.
     *
     * Validated (2026-07-22) against 1440 cases (60 random reorientations x all 24 valid
     * (cell, fixAxis2) pairs) by brute-force simulation with zero mismatches -- see
     * `tools/sim/adjacency_cw_report.py`.
     */
    fun correctedNativePrime(
        roomR0: Int,
        roomR0Sign: Int,
        roomR1: Int,
        roomR1Sign: Int,
        roomCell: Cell4,
        roomFixAxis2: Axis4,
        desiredApostrophe: Boolean,
    ): Boolean {
        val wantClockwise = targetClockwiseForNoApostrophe(roomCell, roomFixAxis2) != desiredApostrophe
        for (nativeClockwise in listOf(true, false)) {
            val renderedClockwise = if (roomR0 < roomR1) {
                if (roomR0Sign * roomR1Sign == 1) nativeClockwise else !nativeClockwise
            } else {
                val signedFlag = (if (nativeClockwise) 1 else -1) * roomR0Sign * roomR1Sign
                signedFlag == -1
            }
            if (renderedClockwise == wantClockwise) return !nativeClockwise
        }
        error("no matching native prime found -- shouldn't happen (exhaustively validated)")
    }

    /** A real MagicCube4D `.log` file for [history], byte-for-byte in the format MC4D itself
     * reads/writes (confirmed against real MC4D output) -- unlike [formatTwistLog4D], this is
     * meant to be opened directly in MagicCube4D, not read by a person. Always uses the same
     * canonical representative cell per axis ([axisRepresentativeCell]) for every twist, since
     * `dir`'s sign is relative to *which grip* was clicked, not a universal CW/CCW -- e.g. `RD`
     * (non-prime) is `RU`'s (non-prime) inverse, confirmed against real MC4D, so consistently
     * using the same representative (never switching between an axis's two cells) is what keeps
     * this app's own `prime` flag mapping to a consistent `dir` sign throughout, *after* correcting
     * `prime` via [correctedPrime] -- see [PRIME_FLIP_TWISTS]'s doc for which twists need that and
     * why. `slicemask` is always 1 (a single outer-layer twist, this app's only twist granularity).
     * A 180-degree double twist is two separate identical
     * triples, not a special encoding -- confirmed real MC4D does the same and doesn't consolidate
     * them, even though its own turn counter and this app's [formatTwistLog4D] both display
     * doubled moves as a single "X2" for readability. The view-orientation lines MC4D's header
     * expects are filled with a fixed identity matrix -- they only restore the camera angle on
     * load, not puzzle state, so any valid orientation works.
     *
     * [scrambleCount] leading entries of [history] are the scramble, not moves the player made --
     * real MC4D marks that boundary inline with a literal "m|" token in the move list, which is
     * where its Edit > Go to Beginning / Redo lands, and the header's move-count field counts only
     * the post-mark moves, not the file's full replay -- confirmed two ways: reading a real MC4D
     * log (`f2l.log`) that had this shape already, and round-tripping our own scramble+twists
     * export back through real MC4D (`retroid.log`), where Edit > Go to Beginning and single-step
     * Redo both worked as expected. The header's second field is `2` whenever a mark is present
     * (vs. `0` with none), also confirmed by both files.
     *
     * [TwistRecord.Edge] entries export via [mc4dEdgeGrip] with `dir` always `1` -- confirmed safe
     * (2026-08-10, by reading MC4D's own `getTwistMat`: `angle = dir * (2*PI/order) * frac`, and an
     * edge grip's rotation `order` is 2, so `dir=1` and `dir=-1` both give a `180°` twist, the only
     * nontrivial one an order-2 grip has -- there's no direction to get wrong). See
     * [mc4dEdgeGrip]'s doc for how its grip numbers were found (running MC4D's actual source, not
     * another reverse-engineering-from-hand-written-log-files round). */
    fun mc4dLogFile(history: List<TwistRecord>, scrambleCount: Int): String {
        val solveCount = history.size - scrambleCount
        val header = "MagicCube4D 3 ${if (scrambleCount > 0) 2 else 0} $solveCount {4,3,3} 3"
        val identityViewMatrix = listOf(
            "1.0 0.0 0.0 0.0",
            "0.0 1.0 0.0 0.0",
            "0.0 0.0 1.0 0.0",
            "0.0 0.0 0.0 1.0",
        )
        val tokens = history.map { record ->
            when (record) {
                is TwistRecord.Ridge -> {
                    val dir = if (correctedPrime(record.cell, record.fixAxis2, record.prime)) 1 else -1
                    "${mc4dGrip(record.cell, record.fixAxis2)},$dir,1"
                }
                is TwistRecord.Edge ->
                    "${mc4dEdgeGrip(record.cell, record.axis1, record.sign1, record.axis2, record.sign2)},1,1"
            }
        }.toMutableList()
        if (scrambleCount > 0) tokens.add(scrambleCount, "m|")
        return (listOf(header) + identityViewMatrix + listOf("*", "${tokens.joinToString(" ")}.")).joinToString("\n")
    }

    /** e.g. "RU'" for a [TwistRecord.Ridge] -- the twisted cell, then a representative cell for
     * the fixed second axis, prime for counterclockwise. Letters come from the record's
     * *room-relative* fields (`roomCell`/`roomFixAxis2`) -- matching how physical cube notation
     * works after a whole-puzzle reorientation (a WCA `x`/`y`/`z` cube rotation redefines what "R"
     * means for every move that follows; this app's "move cell to I" reorientation is the 4D
     * equivalent). The apostrophe comes directly from [TwistRecord.Ridge.displayApostrophe] -- see
     * that field's doc for why it's stored rather than re-derived from `prime` here. Also drives
     * the on-screen "last move" indicator (see MainActivity.build4DScreen).
     *
     * e.g. "IUF" for a [TwistRecord.Edge] -- the twisted cell, then the *exact* (sign-aware) cell
     * for each of the two edge axes, via [signedAxisCell] rather than [axisRepresentativeCell] --
     * unlike a ridge twist's `fixAxis2`, an edge twist's two axis signs are exactly what picks
     * which of a cell's 6 diagonals was grabbed (e.g. "IUF" vs "IUB" are genuinely different
     * twists), so collapsing sign the way ridge notation does would make different diagonals print
     * identically (found 2026-08-11: "ROU" turned out ambiguous between two distinct real
     * diagonals of cell R). No apostrophe (edge twists have no direction -- see
     * [TwistRecord.Edge]'s doc) and native rather than room-relative (see that same doc for why).
     * A provisional notation, not an established hypercubing.xyz convention -- there isn't one
     * documented for edge twists yet, unlike [TwistRecord.Ridge]'s. */
    fun communityNotation(record: TwistRecord): String = when (record) {
        is TwistRecord.Ridge -> ridgeNotation(record.roomCell, record.roomFixAxis2, record.displayApostrophe)
        is TwistRecord.Edge ->
            record.cell.label + signedAxisCell(record.axis1, record.sign1).label +
                signedAxisCell(record.axis2, record.sign2).label
    }

    /** Hyperspeedcube/hypercubing.xyz-style whole-room rotation label, e.g. "xy"/"yx" for a
     * 90-degree rotation in the X/Y plane -- direction is encoded purely by letter order (no prime
     * symbol, per hypercubing.xyz/notation/), matching [HypercubeRenderer.requestCameraRotate90]'s
     * `(axisA, axisB, reverse)` parameters exactly.
     *
     * The `reverse == false` -> `axisA+axisB` assignment (rather than `axisB+axisA`) is confirmed
     * correct, not guessed -- derived from [HypercubeRenderer.requestMoveSelectedCellToI], which is
     * exactly `requestCameraRotate90(selectedRoomAxis, AXIS_W, reverse = selectedRoomSign > 0)` and
     * must (by its own already-relied-upon contract, unrelated to this label) send the selected
     * cell to [Cell4.I], never [Cell4.O]. Worked example: selecting [Cell4.U] (`Axis4.Y`, sign
     * `+1`) calls this with `reverse=true`; hypercubing.xyz's own notation page states "yw: bring
     * +y to +w" and that "wy is the inverse of yw", i.e. "wy" sends +y to *-w* -- exactly [Cell4.I]
     * (`Axis4.W`, sign `-1`), matching `reverse=true` -> `"w"+"y"` (letters swapped) below. The same
     * `(axisA, axisB, reverse)` parameters, with the identical meaning, drive the Select-held
     * room-rotation labels too, so this one worked example confirms both use sites at once. */
    fun roomRotationPlaneLabel(axisA: Int, axisB: Int, reverse: Boolean): String {
        val a = Axis4.entries.first { it.nativeIndex == axisA }.label.lowercase()
        val b = Axis4.entries.first { it.nativeIndex == axisB }.label.lowercase()
        return if (reverse) b + a else a + b
    }

    /** e.g. "RU' RF RU2" -- a whole move sequence in [communityNotation], doubled moves collapsed
     * via [consolidateDoubles]. Our own notation for undo/clipboard/share purposes -- see
     * [mc4dLogFile] for actual MagicCube4D file compatibility, which needs MC4D's own internal
     * grip numbering, not this. */
    fun formatTwistLog4D(history: List<TwistRecord>): String =
        consolidateDoubles(history) { record -> communityNotation(record) }.joinToString(" ")
}
