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
     * RIGHT/LEFT's reference is weaker -- not live-tested like UP was, but read off the original
     * `todo-controller-input.md` spec's stated target ("Right button: rotates so the positive X
     * axis moves away from the user, i.e. positive X -> negative Z"). U and D are RIGHT's
     * "collision" cells (literalAxis=Y collides with their own axis, forcing fixAxis2=W, the clean
     * case), and simulating RIGHT's *current, unmodified* formula on them produces exactly that
     * +X->-Z rotation -- so U/D (plus L/F/I, which already independently matched U/D's resulting
     * screen sense) were treated as the reference, and only R/B (and O by extension) get flipped.
     * LEFT reuses the same set for the same structural reason DOWN reuses UP's. **Needs real
     * controller confirmation** -- unlike UP, nobody has tested RIGHT/LEFT on hardware yet, this is
     * only as good as the old spec doc's wording and the simulation.
     *
     * BUMPER_R/TRIGGER_R's reference is real-device-confirmed, like UP's: the user reported both
     * already look correct on `R` and `D`, backwards on the rest. Simulated confirmation matched
     * that split exactly (R/D alone were already internally consistent between TRIGGER_R and
     * BUMPER_R as an opposite pair; U/L/F/B/I -- and O by extension -- all came out backwards on
     * both), and flipping prime for that set made all 8 cells consistent for both buttons. */
    fun rotationInvertedForCell(button: RotationButton, roomCell: Cell4): Boolean = when (button) {
        RotationButton.UP, RotationButton.DOWN -> roomCell in setOf(Cell4.D, Cell4.L, Cell4.R, Cell4.F, Cell4.I, Cell4.O)
        RotationButton.LEFT, RotationButton.RIGHT -> roomCell in setOf(Cell4.R, Cell4.B, Cell4.O)
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
     * Throws [UnsupportedOperationException] if [history] contains any [TwistRecord.Edge] --
     * MC4D's grip encoding for an edge-sticker twist hasn't been reverse-engineered yet (unlike
     * [TwistRecord.Ridge]'s, see [mc4dGrip]'s doc), so there's no correct grip/dir to emit; failing
     * loudly here beats silently writing a `.log` MC4D would misinterpret or reject. Callers
     * (MainActivity's doExportMC4D) should catch this and tell the player, not let it crash the
     * export flow. */
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
                is TwistRecord.Edge -> throw UnsupportedOperationException(
                    "MC4D log export doesn't support edge twists (${record.cell.label} " +
                        "${record.axis1}/${record.axis2}) yet",
                )
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
     * e.g. "IUF" for a [TwistRecord.Edge] -- the twisted cell, then a representative cell for each
     * of the two edge axes. No apostrophe (edge twists have no direction -- see
     * [TwistRecord.Edge]'s doc) and native rather than room-relative (see that same doc for why).
     * A provisional notation, not an established hypercubing.xyz convention -- there isn't one
     * documented for edge twists yet, unlike [TwistRecord.Ridge]'s. */
    fun communityNotation(record: TwistRecord): String = when (record) {
        is TwistRecord.Ridge ->
            record.roomCell.label + roomAxisRepresentativeCell(record.roomFixAxis2).label +
                (if (record.displayApostrophe) "'" else "")
        is TwistRecord.Edge ->
            record.cell.label + axisRepresentativeCell(record.axis1).label + axisRepresentativeCell(record.axis2).label
    }

    /** e.g. "RU' RF RU2" -- a whole move sequence in [communityNotation], doubled moves collapsed
     * via [consolidateDoubles]. Our own notation for undo/clipboard/share purposes -- see
     * [mc4dLogFile] for actual MagicCube4D file compatibility, which needs MC4D's own internal
     * grip numbering, not this. */
    fun formatTwistLog4D(history: List<TwistRecord>): String =
        consolidateDoubles(history) { record -> communityNotation(record) }.joinToString(" ")
}
