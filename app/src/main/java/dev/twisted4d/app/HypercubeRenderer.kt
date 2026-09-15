package dev.twisted4d.app

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Renders a 3^4 hypercube using a genuine 4D->3D perspective projection, the same style
 * MagicCube4D/Hyperspeedcube use: 6 of the cells (U/D/L/R/F/B) form separate, non-overlapping
 * 3x3x3 blocks anchored like the walls of a room, a 7th (I) is a 3x3x3 block floating at the
 * center, and the 8th (O) sits behind I -- naturally occluded by the depth buffer from the
 * default orientation, not specially skipped. Each cell's own 27 pieces taper into a frustum
 * shape (see [onDrawFrame]'s perspective divide) because a piece's real depth *within* its
 * current cell (how close to I vs. O it sits) varies piece-to-piece and scales its projected
 * position/size accordingly.
 *
 * Every sticker's own geometry is real per-vertex 4D data, not a rigid mesh placed at a resolved
 * point: a sticker is a genuine 3-dimensional facet of its piece (see
 * [HypercubeGeometry]'s class doc for the dimensional-analogy reasoning), and each of its 24
 * corners (see [HypercubeGeometry.LOCAL_OFFSETS_BY_AXIS]) is individually rotated by the piece's
 * current orientation, shifted to the piece's true room position, face-shrunk, and perspective-
 * divided -- exactly like every other 4D point in the scene, just 24 of them per sticker instead
 * of one. This is what lets a sticker itself come out subtly wedge-shaped near a cell's tapering
 * edge, not just correctly positioned. Per-face lighting normals are computed the same way MC4D's
 * own pipeline does it (`PipelineUtils.computeFrame`'s brightness step): from the cross product of
 * two edges of the *already-projected* face, not a baked constant -- correct regardless of how
 * much a given face ends up distorted. Ordinary 3D rotation (touch-drag/right-stick -- the left
 * stick is reserved for cell selection, see [updateCell4Selection]) orbits the whole assembly so
 * you can see each cell in turn, same feel as [CubeRenderer]. Which native cell currently occupies
 * which of these 8 slots is controlled by [cubeOrientation4], a 4D rotation kept restricted to
 * exact 90-degree increments via [requestCameraRotate90] (a full continuous 4D trackball isn't
 * needed: the existing continuous 3D orbit already plays the role MagicCube4D's own mouse-drag
 * does, per its own FAQ on understanding projected 4D objects) -- e.g. rotating the Z-W plane
 * cycles F->I->B->O->F, matching Hyperspeedcube's "send this cell to the center" shortcut.
 *
 * Every one of a piece's 1-4 stickers is rendered independently (see [onDrawFrame]): its color
 * is fixed (the cell that sticker was originally part of), but which of the 8 slots it's
 * currently drawn in is resolved fresh every frame from `cubeOrientation4 * pieceOrientation *
 * homeDir`, so stickers visually relocate -- and smoothly re-taper mid-flight, since the same
 * true-projection math applies whether a twist/room-rotation is settled or still animating -- as
 * the camera is rotated in 90-degree steps or the piece itself is twisted.
 *
 * [cubeOrientation4] only ever changes in exact 90-degree steps (via [requestCameraRotate90]),
 * so it never needs to move continuously. The *continuous* touch-drag/right-stick "look around
 * the room" feel is a separate, ordinary 3D rotation, [viewOrientation3] -- applied once per
 * frame via a single shared MVP matrix now that every vertex position is already baked into room
 * space (see [onDrawFrame]), rather than a per-sticker model matrix. Keeping [viewOrientation3]
 * and [cubeOrientation4] separate still matters: composing continuous rotation into
 * [cubeOrientation4] would make the discrete "which wall is this sticker on" threshold below flip
 * abruptly mid-drag.
 */

/** The 4D screen's 2 gamepad left-hand input schemes -- see [HypercubeRenderer.setInputMode].
 * [STICK]: continuous stick-angle cell selection ([HypercubeRenderer.updateCell4Selection]).
 * [RKT]: no selection at all -- the left-hand controls twist the room's current I slot directly
 * (see [HypercubeRenderer.requestRktITwist]), for executing a fixed, memorized last-phase-of-solve
 * algorithm (hypercube OLL/PLL equivalent) without needing to reselect a cell between twists.
 * (A third mode, PAD -- discrete dpad/L1/L2 step navigation -- existed until 2026-07-26, removed
 * once STICK mode's d-pad-as-stick support (see GamepadInputHandler.onDpadStick) made it
 * redundant: everything PAD could do, STICK mode's d-pad now does too, more directly.) */
enum class GamepadInputMode { STICK, RKT }
class HypercubeRenderer : GLSurfaceView.Renderer {

    // Retuned for the true-4D-projection geometry's much smaller natural scale (cells now sit
    // within roughly +-1.5 units of the room's center, vs. the old flat net's ROOM_HALF=6) --
    // see MIN_DISTANCE/MAX_DISTANCE below, retuned to match.
    //
    // Was 8.5f -- tightened 2026-09-15 alongside the reserved-dedicated-area viewport rework
    // (onSurfaceChanged's doc): 8.5 left the puzzle filling only ~68% of its square viewport
    // (confirmed via a real screenshot on the Retroid Pocket 3 Plus), well short of "maximize the
    // filled percentage of its dedicated area" once that area became an exact, guaranteed square
    // instead of whatever incidental space a wide aspect ratio happened to leave free.
    @Volatile private var distance = 6.4f

    // Ordinary 3D-feeling rotation input (touch-drag / left stick) -> XZ/YZ planes.
    @Volatile var stickX: Float = 0f
    @Volatile var stickY: Float = 0f
    private val dragLock = Any()
    private var pendingDragYawDeg = 0f
    private var pendingDragPitchDeg = 0f

    /** Called (on the GL thread) right after a twist/scramble/reset with the new solved state. */
    @Volatile var onStateChanged: ((Boolean) -> Unit)? = null

    /** Called (on the GL thread) right after a twist is applied via [requestTwist] or
     * [requestEdgeTwist] -- not fired by [requestUndo]/[requestRedo], so a caller (MainActivity)
     * using this to build an undo/log history doesn't see its own undo/redo moves recorded back
     * into that same history. Takes the already-resolved [TwistRecord]
     * directly (changed 2026-08-10 from 6 flat args, to accommodate [TwistRecord.Edge] alongside
     * [TwistRecord.Ridge] without a second callback or a growing arg list) -- [TwistRecord.Ridge]'s
     * `roomCell`/`roomFixAxis2` are room-relative context for community-notation labeling,
     * alongside the native `cell`/`fixAxis2`/`prime` that [Cube4.twist]/undo/MC4D export need (see
     * MainActivity.communityNotation's doc for why both are necessary and different); its
     * `displayApostrophe` is passed straight through from twist-resolution time -- see
     * [TwistRecord.Ridge.displayApostrophe]'s doc for why it can't just be re-derived from `prime`
     * here. */
    @Volatile var onTwistApplied: ((TwistRecord) -> Unit)? = null

    /** Called (on the GL thread) whenever [drainTwistQueueIfIdle] finds [twistQueue] empty --
     * i.e. every pending twist/undo/redo/edge action has now actually been applied to native
     * state, not just requested. Unlike [onTwistApplied] (fired the instant *any* new twist is
     * applied, always safe to trust immediately -- native mutation happens synchronously inside
     * [applyTwistInternal]/[applyEdgeTwistInternal]), undo/redo don't get an equivalent per-action
     * callback: [MainActivity]'s `performUndo`/`performRedo` update their own history index
     * *optimistically*, on whichever thread calls them, before the corresponding
     * [requestUndo]/[requestRedo] has necessarily reached the GL thread or drained off
     * [twistQueue] -- so mid-burst, that history index can briefly be ahead of what's actually
     * been applied. This callback is the moment that gap is guaranteed closed again: exists for
     * [MainActivity]'s export round-trip checker, so it can compare its own recorded history
     * against live native state only once there's nothing left in flight to make that comparison
     * premature. */
    @Volatile var onQueueIdle: (() -> Unit)? = null

    /** Called (on the GL thread) at the exact moment [applyTwistInternal]/[applyEdgeTwistInternal]
     * actually mutates native state -- i.e. when a queued or immediate twist/undo/redo/edge action
     * *really* happens, as opposed to when it was merely requested. [onTwistApplied]/[MainActivity]
     * 's own request-time logging (`performUndo`/`performRedo`) only records *when a button press
     * updated the bookkeeping*, not when the corresponding native mutation actually landed -- added
     * specifically because a real trace (2026-08-17) of that request-side logging alone, while
     * internally self-consistent, couldn't rule out a request/application ordering mismatch (e.g.
     * queued actions applying out of order) since it had no visibility into the application side at
     * all. See the mc4d_export_bug_investigation memory. */
    @Volatile var onNativeApply: ((String) -> Unit)? = null

    /** Called (on the GL thread) specifically when an undo/redo actually lands on native state --
     * unlike [onTwistApplied] (new twists only) or [onNativeApply] (every kind, no way to tell
     * which), this exists so [MainActivity] can track "an undo/redo I submitted hasn't landed yet"
     * precisely, to close a real race confirmed 2026-08-17 via a debug-log trace: [onQueueIdle]
     * fires whenever [twistQueue] is empty, but a just-submitted `surfaceView.queueEvent{...}`
     * Runnable (from `performUndo`/`performRedo`, running on whichever thread called them) lives in
     * *GLSurfaceView's own* separate event queue until the GL thread picks it up -- if an unrelated
     * animation finishes and [onDrawFrame] calls `drainTwistQueueIfIdle` in the same frame, just
     * before that Runnable is transferred in, [twistQueue] looks empty and [onQueueIdle] fires one
     * frame early, while `historyIndex4D` (updated optimistically by `performUndo`/`performRedo`,
     * see its own doc) has already moved past what's truly on the live puzzle. New twists don't
     * have this problem (their own history bookkeeping only updates *after* [onTwistApplied]
     * confirms landing), so only undo/redo needs this separate signal. */
    @Volatile var onUndoRedoApplied: (() -> Unit)? = null

    /** Called (on the GL thread) exactly when a requested undo is actually about to be applied --
     * whether immediately ([requestUndo]'s idle branch) or after draining ahead of it in
     * [twistQueue] ([drainTwistQueueIfIdle]'s [QueuedAction.UndoRequest] branch) -- to resolve
     * which [TwistRecord] to reverse right now. Deliberately resolved this late rather than back
     * when the button was pressed (contrast [requestTwist], which takes fully-resolved params):
     * closes a real bug (2026-09-11, see the mc4d_export_bug_investigation-adjacent memory) where
     * MainActivity used to decrement its own historyIndex4D optimistically at press time, which
     * could go stale the instant a twist requested *earlier* but still sitting in [twistQueue]
     * finally landed -- that twist's own bookkeeping would then run against a historyIndex4D the
     * not-yet-applied undo had already rewritten out from under it, permanently diverging the live
     * puzzle from the recorded history by exactly that twist's worth. Resolving here instead --
     * called at the exact moment this undo is really about to apply, in the same guaranteed FIFO
     * order [twistQueue] already applies everything else in -- means MainActivity always resolves
     * against its own *current*, fully-caught-up history state, never a stale one. Returns null if
     * there's nothing left to undo (e.g. already back at the scramble boundary); [onUndoRedoApplied]
     * still fires in that case, since a `pendingUndoRedoCount` increment from the original request
     * needs exactly one matching decrement regardless of outcome. */
    @Volatile var resolveUndo: (() -> TwistRecord?)? = null

    /** Redo counterpart to [resolveUndo] -- see its doc for why resolution is deferred to apply
     * time here too. Returns null once caught back up to the end of history. */
    @Volatile var resolveRedo: (() -> TwistRecord?)? = null

    /** If set (by MainActivity, *before* `setRenderer` is called -- see build4DScreen), consumed
     * by [onSurfaceCreated] instead of its usual [NativeLib.cube4Reset] -- restores a puzzle
     * saved before the process died. See [CubeRenderer.pendingRestoreState]'s doc for why this
     * must be a plain field set before [setRenderer], not a `queueEvent` call afterward. */
    @Volatile var pendingRestoreState: IntArray? = null

    // Whether onSurfaceCreated has already run once for *this* renderer instance -- see that
    // method's doc for why this matters: without it, onSurfaceCreated can't tell "genuinely new
    // instance, no restore pending" (a mode switch or fresh launch, which should reset to solved)
    // apart from "this same instance's surface got recreated mid-session" (e.g. a share-sheet
    // Intent covering the activity, which should leave the puzzle state alone).
    private var hasCreatedSurfaceBefore = false

    // Piece filtering: dims whole pieces (all their stickers) that the active filter-set's
    // current step hasn't revealed yet -- see PieceFilterSet's class doc. activeFilterSet null
    // means no filter-set is active (nothing dimmed); otherwise a piece is dimmed unless
    // PieceFilterSet.isPieceVisible(activeFilterIndex, activeFilterStep, home) is true for the
    // piece's own *home* position (its permanent identity, not its current one -- see
    // onDrawFrame). Whether crossing into a different filter within the set carries an earlier
    // filter's pieces forward or resets is decided per-subfilter by the filter text's own "+"
    // grammar, not hardcoded here (see PieceFilter's doc). MainActivity's Filters submenu sets
    // activeFilterSet (always starting both indices at 0), and Select+L1/L2 in STICK mode steps
    // activeFilterStep, rolling over into activeFilterIndex++/-- at a filter's start/end.
    @Volatile var activeFilterSet: PieceFilterSet? = null
    @Volatile var activeFilterIndex: Int = 0
    @Volatile var activeFilterStep: Int = 0

    // Persistent left-stick selection state: which room *slot* (axis+sign), not which resolved
    // cell, is selected -- see selectedCell4's doc for why. GL-thread-only (updateCell4Selection
    // is only ever called via queueEvent; so is MainActivity's read of selectedCell4). Stays
    // stale once hasSelection goes false (nothing re-clears it) -- harmless, since every reader
    // that cares about staleness (selectedCell4, selectedRoomCell, effectiveCell4/RoomCell,
    // roomFixAxis2For) already goes through hasSelection/hasEffectiveSelection first.
    private var selectedRoomAxis = AXIS_Y
    private var selectedRoomSign = 1

    // Whether STICK mode's left stick/d-pad currently has a selection actively held -- false
    // the instant it returns to center (see updateCell4Selection), *forgetting* the last
    // selection entirely rather than just hiding its highlight, per 2026-08-02 design: twisting
    // with nothing selected should no longer silently reuse whatever was last touched (that was
    // the confirmed bug -- the highlight already disappeared on release, but selectedCell4/
    // selectedRoomCell kept resolving to the stale slot regardless). RKT mode never touches this
    // (its selection is permanently pinned to R by setInputMode) -- see hasEffectiveSelection.
    private var hasSelection = false

    // hasSelection, plus RKT's permanent pin -- RKT's cell selection is never "released" the way
    // STICK's is (there's no stick input driving it in RKT), so treating it as always-selected
    // here is what keeps requestTwist/resolveRotationButtonFixAxis2 resolving to the pinned R
    // slot exactly as before, unaffected by this feature.
    private val hasEffectiveSelection: Boolean get() = hasSelection || inputMode == GamepadInputMode.RKT

    /** UI-thread-safe mirror of [hasEffectiveSelection]/[selectedRoomAxis]/[selectedRoomSign], for
     * VirtualClusterView's per-frame function-label computation (see MainActivity's
     * rotationButtonContent and friends). Those three fields are deliberately GL-thread-only (see
     * [selectedRoomAxis]'s doc); publishing just this snapshot as a single @Volatile reference (one
     * atomic read, no torn state) is enough to make [hasEffectiveSelectionForLabel]/
     * [selectedRoomAxisForLabel]/[selectedRoomSignForLabel] below safe to call from the UI thread's
     * repaint loop. Worst case a label is one frame stale (~33ms) until the next publish --
     * cosmetic only, self-correcting. */
    private data class LabelSelectionSnapshot(val hasEffectiveSelection: Boolean, val roomAxis: Int, val roomSign: Int)
    @Volatile private var labelSelectionSnapshot =
        LabelSelectionSnapshot(hasEffectiveSelection, selectedRoomAxis, selectedRoomSign)
    private fun publishLabelSnapshot() {
        labelSelectionSnapshot = LabelSelectionSnapshot(hasEffectiveSelection, selectedRoomAxis, selectedRoomSign)
    }

    /** Which of the 2 left-hand input schemes is active -- see [GamepadInputMode]. Read from the
     * UI thread (MainActivity's on4DNavigate/onLeftStick closures, both running inside their own
     * queueEvent already) and written only via [setInputMode], also GL-thread-only. */
    @Volatile var inputMode: GamepadInputMode = GamepadInputMode.STICK
        private set

    // STICK's selected room slot, parked here while RKT is active (RKT always pins to R -- see
    // setInputMode -- so switching back to STICK restores whatever was selected before, instead
    // of losing it to RKT's fixed R pin).
    private var parkedStickAxis = AXIS_Y
    private var parkedStickSign = 1

    /** Swaps the outgoing mode's room slot into [parkedStickAxis]/[parkedStickSign] (STICK only
     * -- RKT has no parked slot of its own) and sets up the incoming mode's slot: STICK restores
     * its parked slot, RKT always pins to R (AXIS_X, +1) -- see [GamepadInputMode]'s doc for why
     * R specifically. Must run on the GL thread (those fields aren't volatile) -- call via
     * queueEvent, same as [updateCell4Selection]. */
    fun setInputMode(mode: GamepadInputMode) {
        if (mode == inputMode) return
        if (inputMode == GamepadInputMode.STICK) {
            parkedStickAxis = selectedRoomAxis
            parkedStickSign = selectedRoomSign
        }
        when (mode) {
            GamepadInputMode.STICK -> { selectedRoomAxis = parkedStickAxis; selectedRoomSign = parkedStickSign }
            GamepadInputMode.RKT -> { selectedRoomAxis = AXIS_X; selectedRoomSign = 1 }
        }
        inputMode = mode
        publishLabelSnapshot()
    }

    /** Which [Cell4] the gamepad's left stick currently has selected for the next twist, or
     * `null` if nothing is ([hasEffectiveSelection] is false) -- see [updateCell4Selection]. A
     * computed property, re-resolved against the *current* [cubeOrientation4] on every read,
     * rather than a cached value -- otherwise, since a physical stick held perfectly steady
     * fires no new motion events, rotating the room (e.g. via [requestCameraRotate90]) while a
     * selection is being held wouldn't update it until the next stick nudge: the highlight and
     * any subsequent twist would silently act on whichever cell used to be in that slot, not
     * whichever cell is actually there now. This is the *native* occupant's identity (via
     * [nativeCellInRoomSlot]), which is what [requestTwist] needs -- for the on-screen
     * highlight, see [emphasizedCell] instead (a deliberately different computation, though it
     * agrees with this one whenever both are non-null). For what a twist button should actually
     * act on -- which still needs an answer even with nothing selected -- see [effectiveCell4]
     * instead.
     */
    val selectedCell4: Cell4? get() = if (hasEffectiveSelection) nativeCellInRoomSlot(selectedRoomAxis, selectedRoomSign) else null

    /** The currently selected room slot's own fixed label (e.g. "the wall at +X is always R"),
     * *not* whichever native cell currently occupies it -- same distinction [emphasizedCell]
     * makes via [cellFor], and the one [MainActivity]'s `rotationInvertedForCell` needs: that
     * table corrects for a rendering property of the *wall* ("each wall's depth axis is always
     * W" -- see [onDrawFrame]'s per-corner projection), not of the native cell sitting in it, so
     * looking it up by [selectedCell4] (native identity) silently breaks once the room's been
     * rotated -- e.g. after moving some other cell to I, pressing a rotation button on a cell
     * that's now sitting in a *different* wall than its own name got the wrong on-screen
     * direction, because the correction table was consulted for the cell's native identity
     * instead of the wall it's actually rendering in. `null` under the same condition
     * [selectedCell4] is -- see [effectiveRoomCell] for the twist-resolution equivalent that's
     * never null. */
    val selectedRoomCell: Cell4? get() = if (hasEffectiveSelection) cellFor(selectedRoomAxis, selectedRoomSign) else null

    /** Which room axis a twist on [buttonLiteralAxis] should actually act relative to right now:
     * the actively selected slot if there is one ([hasEffectiveSelection]), else
     * [buttonLiteralAxis]'s own axis -- i.e. with nothing selected, an X-axis button (Up/Down)
     * behaves as if R were selected, Y (Left/Right) as if U were, Z (bumper/trigger) as if F
     * were, matching [Cell4]'s own axis pairing (R/U/F are each their axis's `+1`-sign cell --
     * see [cellFor]). This is *why* [roomFixAxis2For]'s collision check ends up unconditionally
     * true whenever nothing is selected: the default slot's axis is by construction always the
     * same as the button's own, same as it would be if you'd actually selected R/U/F yourself. */
    private fun effectiveRoomAxis(buttonLiteralAxis: Axis4): Int =
        if (hasEffectiveSelection) selectedRoomAxis else buttonLiteralAxis.nativeIndex

    /** The sign half of [effectiveRoomAxis] -- the actively selected slot's sign if there is
     * one, else `+1` (R/U/F) normally, or `-1` (L/D/B, the *opposite* cell on that axis) when
     * [defaultOpposite] is set -- see [MainActivity]'s THUMB_L/BUTTON_C-held modifier, which asks
     * for exactly that: with nothing selected, holding that button flips a twist to act on the
     * far side of the axis instead, the same cell holding the stick to the opposite compass
     * wedge would have selected. Ignored entirely once something's actually selected -- the
     * modifier only exists to pick a default, not to override a real selection. */
    private fun effectiveRoomSign(defaultOpposite: Boolean): Int =
        if (hasEffectiveSelection) selectedRoomSign else if (defaultOpposite) -1 else 1

    /** The non-nullable twist-resolution counterpart to [selectedCell4]: whichever cell a twist
     * on [buttonLiteralAxis] should actually act on right now, falling back to
     * [effectiveRoomAxis]/[effectiveRoomSign] (R/U/F, or L/D/B if [defaultOpposite]) when nothing
     * is selected instead of ever being null. */
    fun effectiveCell4(buttonLiteralAxis: Axis4, defaultOpposite: Boolean = false): Cell4 =
        nativeCellInRoomSlot(effectiveRoomAxis(buttonLiteralAxis), effectiveRoomSign(defaultOpposite))

    /** The non-nullable twist-resolution counterpart to [selectedRoomCell] -- see
     * [effectiveCell4]'s doc, same fallback, room-label instead of native identity. */
    fun effectiveRoomCell(buttonLiteralAxis: Axis4, defaultOpposite: Boolean = false): Cell4 =
        cellFor(effectiveRoomAxis(buttonLiteralAxis), effectiveRoomSign(defaultOpposite))

    /**
     * Resolves a rotation button's screen/room-relative axis (e.g. "Up" always means room axis X)
     * to the actual *native* axis [requestTwist] needs for `fixAxis2`, given the currently
     * selected room slot and however [cubeOrientation4] has been rotated so far.
     *
     * Two steps: first, resolve entirely in room terms -- if [buttonLiteralAxis] collides with
     * the selected room slot's own axis ([selectedRoomAxis], *not* [selectedCell4]'s native axis
     * -- see below for why that distinction matters), fall back to the room's W axis, matching
     * how a cell's own screen wall never doubles as its own fixAxis2; otherwise use
     * [buttonLiteralAxis] directly. Second, translate that *room* axis to whichever *native* axis
     * currently occupies it.
     *
     * Both steps matter, and both must stay room-relative until the final translation -- confirmed
     * via real testing: selecting F, moving it to I (room-rotating the Z/W pair), then selecting L
     * and twisting should give a screen-relative "LO", but resolving fixAxis2 from
     * [selectedCell4]'s *native* axis (as this used to) gave a twist that visually behaved like
     * "LF" instead, because native W had by then rotated to sit at the room's F/B wall -- L's own
     * *native* axis (X) happened to still equal its *room slot's* axis in that specific case (L's
     * slot was untouched by the F/I rotation), so the collision check alone wasn't the bug; the
     * fixAxis2 *value itself* (a bare native `Axis4.W`) was being used as if it always meant "the
     * room's I/O direction," which stops being true the moment the room's been rotated. Using
     * [selectedRoomAxis] for the collision check too (not just the translation) is required in
     * general, though: it and [selectedCell4]'s native axis can differ once the room's been
     * rotated enough that the *selected slot itself* holds a cell whose native axis isn't the
     * slot's own -- e.g. selecting I right after the same F-to-I rotation above.
     */
    fun resolveRotationButtonFixAxis2(buttonLiteralAxis: Axis4): Axis4 {
        val roomFixAxis2 = roomFixAxis2For(buttonLiteralAxis)
        return Axis4.entries.first { it.nativeIndex == nativeAxisAtRoomAxis(roomFixAxis2) }
    }

    /** The first (room-only) step of [resolveRotationButtonFixAxis2] -- [buttonLiteralAxis]'s
     * *room* axis, before translating to native. [resolveRotationButtonFixAxis2] needs the native
     * result for the actual twist; [MainActivity]'s room-relative community-notation labeling
     * needs *this* instead, since the representative letter a human reads off the screen is
     * "whichever wall this axis currently is," not whichever native axis happens to be there --
     * see the mc4d_log_compatibility memory for why native identity is still exactly right for
     * the MC4D file export, just not for what's shown on screen. */
    fun roomFixAxis2For(buttonLiteralAxis: Axis4): Int =
        if (buttonLiteralAxis.nativeIndex == effectiveRoomAxis(buttonLiteralAxis)) AXIS_W else buttonLiteralAxis.nativeIndex

    /** UI-thread-safe counterpart to `selectedRoomCell != null` -- reads [labelSelectionSnapshot]
     * instead of the live GL-thread-only fields (see that snapshot's doc). Label use only (the
     * precedence check for "is a cell selected right now" -- MainActivity.rotationButtonContent's
     * Select-held/cell-selected/config-mapped precedence, and l3CLabel's STICK-mode check). */
    val hasEffectiveSelectionForLabel: Boolean get() = labelSelectionSnapshot.hasEffectiveSelection

    /** UI-thread-safe counterparts to the raw [selectedRoomAxis]/[selectedRoomSign] -- label use
     * only (MainActivity's l3CLabel, which needs the actual selected plane to preview the
     * Select-not-held "move selected cell to I" tap -- see [requestMoveSelectedCellToI], itself
     * just [requestCameraRotate90] in the (selectedRoomAxis, AXIS_W) plane, so the label reuses
     * [Notation.roomRotationPlaneLabel] the same way the Select-held room-rotation labels do). */
    val selectedRoomAxisForLabel: Int get() = labelSelectionSnapshot.roomAxis
    val selectedRoomSignForLabel: Int get() = labelSelectionSnapshot.roomSign

    /** One rendered sticker's current room position and color, as computed by [currentSceneColors]
     * -- [roomAxis]/[roomSign] is which wall it's on (or [AXIS_W]/`-1` for the I slot; the O slot
     * is never included, matching [onDrawFrame]'s own skip), [x]/[y]/[z] is its room-space position
     * (raw room-space units, before [onDrawFrame]'s `FACE_SHRINK`/`EYE_W_DIST` projection, so two
     * stickers on the same wall with the same x/y/z-minus-the-wall's-own-axis share a piece), and
     * [colorCell] is the sticker's own fixed color (which cell it was originally part of --
     * doesn't change with rotation, only its position does). */
    data class VisibleSticker(val roomAxis: Int, val roomSign: Int, val colorCell: Cell4, val x: Float, val y: Float, val z: Float)

    /** A queryable snapshot of every sticker currently visible in the room, computed the same way
     * [onDrawFrame] positions them for rendering (piece transform -> [cubeOrientation4] -> which
     * wall/I-slot -> that wall's local position) but returned as plain data instead of drawn --
     * for verifying test results directly against native+orientation state instead of reading
     * screenshots (see the "trackable state of what's visible" ask that motivated this). Ignores
     * any in-progress twist/room-rotation animation -- always reflects the settled, post-twist
     * state, same as [currentTransforms]/[cubeOrientation4] themselves. */
    fun currentSceneColors(): List<VisibleSticker> {
        val out = mutableListOf<VisibleSticker>()
        for (i in HypercubeGeometry.HOME_POSITIONS.indices) {
            val home = HypercubeGeometry.HOME_POSITIONS[i]
            val base = i * 20
            for (k in 0 until 4) pos4[k] = currentTransforms[base + k]
            for (k in 0 until 16) pieceOrient4[k] = currentTransforms[base + 4 + k]
            mat4VecMul(cameraPos4, cubeOrientation4, pos4)

            val homeCoords = intArrayOf(home.x, home.y, home.z, home.w)
            for (axisIdx in 0 until 4) {
                val homeCoord = homeCoords[axisIdx]
                if (homeCoord == 0) continue

                homeDir4[0] = 0f; homeDir4[1] = 0f; homeDir4[2] = 0f; homeDir4[3] = 0f
                homeDir4[axisIdx] = homeCoord.toFloat()
                mat4VecMul(currentDir4, pieceOrient4, homeDir4)
                mat4VecMul(cameraDir4, cubeOrientation4, currentDir4)

                var slotAxis = -1
                var slotSign = 0
                for (j in 0 until 4) {
                    if (abs(cameraDir4[j]) > 0.5f) {
                        slotAxis = j
                        slotSign = if (cameraDir4[j] > 0) 1 else -1
                        break
                    }
                }
                if (slotAxis == AXIS_W && slotSign > 0) continue // O slot: never rendered -- see onDrawFrame's doc
                val colorCell = Cell4.entries.first { it.axis.nativeIndex == axisIdx && it.sign == homeCoord }
                out.add(VisibleSticker(slotAxis, slotSign, colorCell, cameraPos4[0], cameraPos4[1], cameraPos4[2]))
            }
        }
        return out
    }

    /** Which native axis currently occupies room axis [roomAxis] (sign-agnostic) -- e.g. if the
     * room's been rotated so native F now displays at the I/O wall, this returns F's own axis (Z)
     * for `roomAxis = AXIS_W`, not W itself. `cubeOrientation4[roomAxis][nativeAxis]` is nonzero
     * for exactly one nativeAxis, since it's always a signed permutation matrix (see the class
     * doc) -- same reasoning as [nativeCellInRoomSlot], just axis-only (no sign/cell lookup). */
    private fun nativeAxisAtRoomAxis(roomAxis: Int): Int {
        for (nativeAxis in 0 until 4) {
            if (abs(cubeOrientation4[roomAxis * 4 + nativeAxis]) > 0.5f) return nativeAxis
        }
        error("cubeOrientation4 should always map every room axis to exactly one native axis")
    }

    /** The reverse of [nativeAxisAtRoomAxis]: which room axis + sign [nativeAxis] currently maps
     * to, via [cubeOrientation4] -- e.g. if native F now displays at the I/O wall with reversed
     * polarity, this returns (AXIS_W, -1). Needed by [correctedNativePrimeForRoomTwist] to work
     * out how a native rotation's handedness reads once conjugated into room space. */
    private fun roomAxisAndSignForNative(nativeAxis: Int): Pair<Int, Int> {
        for (roomAxis in 0 until 4) {
            val v = cubeOrientation4[roomAxis * 4 + nativeAxis]
            if (abs(v) > 0.5f) return roomAxis to (if (v > 0f) 1 else -1)
        }
        error("cubeOrientation4 should always map every native axis to exactly one room axis")
    }

    /** Forward counterpart to [roomAxisAndSignForNative]: which native axis + sign room axis
     * [roomAxis]/[roomSign] currently maps to, via [cubeOrientation4] -- e.g. (AXIS_Y, 1) for "U"
     * normally returns (Y, 1), but once the room's been reoriented could return any other native
     * (axis, sign) pair. [nativeAxisAtRoomAxis] alone is sign-agnostic and not enough for
     * [requestI180TwistUFDB]/[requestI180TwistURDL]'s room-relative resolution: an edge twist's
     * two axes' *relative* sign is exactly what distinguishes one diagonal from another (e.g.
     * UF/DB from UB/DF), so losing sign here would lose that distinction. */
    private fun nativeAxisAndSignAtRoomAxis(roomAxis: Int, roomSign: Int): Pair<Int, Int> {
        val nativeAxis = nativeAxisAtRoomAxis(roomAxis)
        val mapSign = cubeOrientation4[roomAxis * 4 + nativeAxis]
        return nativeAxis to (if (mapSign > 0f) roomSign else -roomSign)
    }

    /**
     * The native `prime` bit that, applied to [nativeCell]/[nativeFixAxis2] (the physical layer a
     * twist actually acts on, already resolved for the *current* orientation), renders on screen
     * as [desiredApostrophe] for the room-relative ([roomCell], [roomFixAxis2]) grip -- the
     * reorientation-aware replacement for applying a button's raw per-cell-corrected prime
     * directly, which is what caused the confirmed "reoriented LU renders CCW while labeled CW"
     * bug (see [[mc4d_log_compatibility]] memory and `tools/sim/adjacency_cw_report.py`'s doc for
     * the full repro).
     *
     * Why a simple lookup isn't enough: `Cube4::twist`'s "clockwise" flag has no inherent
     * real-world handedness (see `plane_rotation`'s doc in `cube4.rs`) -- [Notation.
     * PRIME_FLIP_TWISTS] only tells us, for a given (cell, fixAxis2) pair, which native prime
     * looks correct *when unreoriented* (native == room). Once reoriented, the same room slot can
     * be reached via a *different* native axis pair, and reusing the room-keyed table as-is (what
     * the old apostrophe-only fix did) silently assumes that relationship is orientation-
     * independent -- it isn't: conjugating a native rotation through [cubeOrientation4] can flip
     * its rendered handedness even though [cubeOrientation4] is always a proper (determinant +1)
     * rotation, because a proper 4D rotation can still reverse one 2-plane's orientation as long
     * as it compensates in the complementary plane.
     *
     * This is just the plumbing (resolving the rotating native axis pair and where
     * [cubeOrientation4] currently maps each of them) -- see [Notation.correctedNativePrime] for
     * the actual math, kept there so it's unit-testable without any Android/GL dependency.
     */
    fun correctedNativePrimeForRoomTwist(
        nativeCell: Cell4,
        nativeFixAxis2: Axis4,
        roomCell: Cell4,
        roomFixAxis2: Int,
        desiredApostrophe: Boolean,
    ): Boolean {
        val cellAxis = nativeCell.axis.nativeIndex
        val rotating = Axis4.entries
            .map { it.nativeIndex }
            .filter { it != cellAxis && it != nativeFixAxis2.nativeIndex }
            .sorted()
        val (roomR0, s0) = roomAxisAndSignForNative(rotating[0])
        val (roomR1, s1) = roomAxisAndSignForNative(rotating[1])
        val roomFixAxis2Axis = Axis4.entries.first { it.nativeIndex == roomFixAxis2 }
        return Notation.correctedNativePrime(roomR0, s0, roomR1, s1, roomCell, roomFixAxis2Axis, desiredApostrophe)
    }

    /**
     * Which [Cell4] should stay fully visible while everything else on the puzzle fades toward
     * translucent (see [onDrawFrame]'s per-piece dimming) -- the selected room slot, while the
     * left stick is held significantly deflected in [GamepadInputMode.STICK]; always [Cell4.I] in
     * [GamepadInputMode.RKT] (permanently, regardless of stick position -- RKT's whole point is a
     * fixed last-phase algorithm centered on I, so the emphasis should stay on I even though R's
     * face buttons are what's actually twistable; see [GamepadInputMode]'s doc); null otherwise
     * (nothing selected, no emphasis, every piece renders at full opacity). A computed property
     * for the same live-resolution reason as [selectedCell4] -- but deliberately computed via
     * [cellFor], *not* [selectedCell4]/[nativeCellInRoomSlot], even though they usually agree:
     * [onDrawFrame] labels each sticker's *current* room position with `cellFor(slotAxis,
     * slotSign)`, a fixed axis+sign -> label convention (the wall at +X is always "R"), not that
     * sticker's own native identity. Once the room's been rotated away from its default
     * arrangement (cubeOrientation4 != identity), a native cell's own identity and its current
     * position's label are different things -- e.g. after rotating native I into the "R" wall,
     * that sticker's currentCell is `R` (the wall it's now on), not `I` (what it natively is).
     * Comparing against [selectedCell4] (I's own native identity) would never match anything
     * actually sitting in the selected wall; comparing against this property (the wall's label)
     * does.
     */
    val emphasizedCell: Cell4?
        get() = when {
            inputMode == GamepadInputMode.RKT -> Cell4.I
            else -> selectedRoomCell
        }

    /** [GamepadInputMode.RKT]'s left-hand controls -- twists whichever native cell the room's I
     * slot *currently* holds (not necessarily literal [Cell4.I], if the room's been rotated
     * earlier -- same room-slot-relative treatment [selectedCell4] gives R for RKT's right-hand
     * buttons, since a memorized algorithm should act on "whatever's in I/R right now", not a
     * specific native cell identity). [roomFixAxis2] is a *room* axis (e.g. `AXIS_Y` for the "U"
     * in "IU"), translated to the native axis [requestTwist] needs the same way
     * [resolveRotationButtonFixAxis2] does for on4DRotationButton -- without this, "IU" would only
     * actually mean IU when the room happens to be at its default orientation, drifting to some
     * other twist entirely once it's been rotated (e.g. via STICK mode's "move to I"), the same
     * bug that on4DRotationButton had. [desiredApostrophe] is given directly by the caller, already
     * resolved to the exact community-notation twist wanted (e.g. IU vs IU') -- unlike
     * on4DRotationButton, this doesn't go through [MainActivity]'s screen-consistency correction
     * table, since these are fixed, explicit moves for a known algorithm rather than a "make this
     * button feel the same on every cell" mapping. Still needs [correctedNativePrimeForRoomTwist]
     * though: the room's I slot can be reached via any native cell, same reorientation-dependent
     * handedness issue as on4DRotationButton. */
    fun requestRktITwist(roomFixAxis2: Int, desiredApostrophe: Boolean) {
        snapViewToNearestCardinalOrientation()
        val fixAxis2 = Axis4.entries.first { it.nativeIndex == nativeAxisAtRoomAxis(roomFixAxis2) }
        val cell = nativeCellInRoomSlot(AXIS_W, -1)
        val prime = correctedNativePrimeForRoomTwist(cell, fixAxis2, Cell4.I, roomFixAxis2, desiredApostrophe)
        requestTwist(cell, fixAxis2, prime, Cell4.I, roomFixAxis2, desiredApostrophe)
    }

    // The old STICK-mode Button-C+X/B fixed 180-degree I-twist shortcuts (requestI180TwistUFDB/
    // requestI180TwistURDL) were removed 2026-08-13 once the configurable button grid
    // (ButtonConfigs/requestButtonAction below) subsumed them -- button_config.txt's default
    // buttonC:X/buttonC:B slots ("IUF"/"IRU") now reach the exact same room-relative-resolved
    // requestEdgeTwist call generically, so the two hardcoded wrappers were pure duplication. See
    // requestEdgeTwist's own doc for the brute-force-verified decomposition math they both relied
    // on -- unchanged, still exactly as correct as before.

    /** Resolves and fires a configured button slot's [ButtonAction] (see [ButtonConfigs]' class
     * doc) -- the same room-relative-letters-resolved-to-native-at-press-time pattern
     * [requestRktITwist] and [requestI180TwistUFDB]/[requestI180TwistURDL] already use, just
     * generalized to whichever cell/axes the config names instead of a single fixed shortcut.
     * Unlike the old hardcoded per-button mapping this replaces, no [Notation.
     * rotationInvertedForCell]-style "make this feel natural" correction is needed here: a
     * configured token *is* the exact community-notation twist the user chose, and
     * [correctedNativePrimeForRoomTwist] (already validated for all 8 cells/all 3 fixAxis2 choices
     * each -- see its own doc) guarantees whatever native twist gets applied renders as exactly
     * that label, regardless of current orientation. [ButtonAction.Edge] needs no equivalent
     * correction at all -- a 180-degree edge twist has no apostrophe/direction to get wrong (see
     * [TwistRecord.Edge]'s doc), and [EDGE_TWIST_DECOMPOSITIONS] is itself a complete table across
     * all 4 possible cell axes, not just the single diagonal each hardcoded shortcut used. */
    fun requestButtonAction(action: ButtonAction) {
        snapViewToNearestCardinalOrientation()
        when (action) {
            is ButtonAction.Ridge -> {
                val roomAxis = action.roomCell.axis.nativeIndex
                val roomSign = action.roomCell.sign
                val roomFixAxis2 = action.roomFixAxis2Cell.axis.nativeIndex
                val cell = nativeCellInRoomSlot(roomAxis, roomSign)
                val fixAxis2 = Axis4.entries.first { it.nativeIndex == nativeAxisAtRoomAxis(roomFixAxis2) }
                val prime = correctedNativePrimeForRoomTwist(cell, fixAxis2, action.roomCell, roomFixAxis2, action.prime)
                requestTwist(cell, fixAxis2, prime, action.roomCell, roomFixAxis2, action.prime)
            }
            is ButtonAction.Edge -> {
                val cell = nativeCellInRoomSlot(action.roomCell.axis.nativeIndex, action.roomCell.sign)
                val (axis1, sign1) = nativeAxisAndSignAtRoomAxis(action.axis1Cell.axis.nativeIndex, action.axis1Cell.sign)
                val (axis2, sign2) = nativeAxisAndSignAtRoomAxis(action.axis2Cell.axis.nativeIndex, action.axis2Cell.sign)
                requestEdgeTwist(
                    cell,
                    Axis4.entries.first { it.nativeIndex == axis1 }, sign1,
                    Axis4.entries.first { it.nativeIndex == axis2 }, sign2,
                )
            }
        }
    }

    // GL-thread-only edge-detection state for updateCell4Selection's snap-on-deflect behavior --
    // -1 while the stick isn't significantly deflected, else which of the 8 compass wedges
    // (0=right/I, going clockwise... see updateCell4Selection) the *previous* significant motion
    // event landed in, so a wedge *change* (not just "is the stick still held") can trigger a
    // fresh snap -- see updateCell4Selection's doc.
    private var lastStickWedge = -1
    private val roomDirScratch4 = FloatArray(4)

    private var program = 0
    private var uMvpLoc = 0
    private var uNormalMatrixLoc = 0
    private var uAlphaLoc = 0

    // The 3x3 rotation part of viewOrientation3, column-major, re-extracted once per frame (not
    // per-sticker) -- every per-face normal onDrawFrame computes is already in the same room-space
    // frame as vertex positions before this rotation, so one shared matrix correctly transforms
    // every sticker's normals for the whole frame, same as it always has. See onDrawFrame and
    // ROTATION_PART_INDICES.
    private val normalMat3 = FloatArray(9)

    private val indexBuffer: ShortBuffer = ByteBuffer
        .allocateDirect(HypercubeGeometry.INDICES.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply { put(HypercubeGeometry.INDICES); position(0) }

    // Reused every frame to upload dynamicVertexData -- see that field's doc.
    private val dynamicVertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(MAX_STICKERS * HypercubeGeometry.VERTICES_PER_STICKER * HypercubeGeometry.FLOATS_PER_VERTEX * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var indexBufferId = 0

    // Real per-vertex 4D projection (see onDrawFrame): every sticker's 24 corners are computed
    // fresh each frame -- true 4D position, face-shrunk, perspective-divided -- into this one
    // shared dynamic buffer, replacing the old static-mesh-per-color + per-sticker model-matrix
    // approach (which could only translate/uniformly-scale a rigid cube, never actually distort
    // one). MAX_STICKERS is the true combinatorial total (8 cells x 27 pieces each, since every
    // piece contributes exactly one sticker instance per cell it touches) -- O's 27 are always
    // culled (see the O-skip below) but sized for the full count anyway, simpler than computing
    // the exact post-cull maximum.
    private var dynamicVboId = 0
    private val dynamicVertexData =
        FloatArray(MAX_STICKERS * HypercubeGeometry.VERTICES_PER_STICKER * HypercubeGeometry.FLOATS_PER_VERTEX)
    private val stickerByteOffsets = IntArray(MAX_STICKERS)
    private val stickerAlphas = FloatArray(MAX_STICKERS)
    private val stickerCornerPositions = FloatArray(HypercubeGeometry.VERTICES_PER_STICKER * 3)
    private val pieceToRoom4 = FloatArray(16)
    private val localCorner4 = FloatArray(4)
    private val roomCorner4 = FloatArray(4)

    // Cached per-piece, once per frame (see onDrawFrame's pre-pass): which room slot each of a
    // piece's up to 4 stickers currently sits in, resolved once and reused both to decide whether
    // the whole piece counts as "touching" emphasizedCell and to build that sticker's geometry --
    // avoids resolving cameraDir4 twice per sticker.
    private val axisSlotAxis = IntArray(4)
    private val axisSlotSign = IntArray(4)

    // Only populated/used while roomAnimating or while a twist is animating a given piece (see
    // onDrawFrame's pre-pass and faceCenter4 below): this sticker's room slot resolved against
    // the animation's *pre*-rotation orientation alone (roomAnimBefore for a room rotation,
    // pieceOrientBefore4 for a twist) -- only one endpoint is needed (unlike an earlier version
    // of this fix, which also resolved a *post*-rotation endpoint to linearly blend toward):
    // faceCenter4 is instead rotated *forward* from this pre-rotation value by the exact same
    // real rotation matrix (roomAnimDeltaRot4 / animRot4) the piece's own position already uses,
    // so the discrete "which cell does this belong to" decision faceCenter4 depends on traces a
    // true rotational arc across the animation, not a hard snap OR a linear blend between two
    // separately-resolved endpoints (a linear blend of two unit vectors doesn't trace the same
    // arc a true rotation does). roomAnimating and a twist's animating can never both be true at
    // once (see applyTwistInternal/requestCameraRotate90's guards), so these scratch buffers are
    // safe to share between the two cases.
    private val axisSlotAxisBefore = IntArray(4)
    private val axisSlotSignBefore = IntArray(4)
    private val cameraDirBefore4 = FloatArray(4)
    private val faceCenterBefore4 = FloatArray(4)
    private val faceCenterRoomNow4 = FloatArray(4)
    // This piece's pre-twist orientation (distinct from pieceOrient4, which holds the current
    // *interpolated* orientation) -- only meaningful while animating && animAffected, used to
    // resolve a twisting piece's "side" stickers' pre-twist slot, the one endpoint faceCenter4
    // needs to then rotate forward from (see the scratch buffers' doc above).
    private val pieceOrientBefore4 = FloatArray(16)
    private val currentDirTwistBefore4 = FloatArray(4)
    private val shrunkCorner4 = FloatArray(4)
    private val faceEdge1 = FloatArray(3)
    private val faceEdge2 = FloatArray(3)
    private val faceNormalScratch = FloatArray(3)

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjMatrix = FloatArray(16)

    // Row-major 4x4 matrices for pure 4D math (distinct from GL's column-major convention,
    // used only for the final per-sticker model matrix). cubeOrientation4 is kept to exact
    // 90-degree-step rotations (only ever changed by requestCameraRotate90), so transforming a
    // native axis vector through it always lands exactly on another axis vector -- no
    // nearest-match/snapping needed, unlike CubeRenderer.
    private val cubeOrientation4 = FloatArray(16)
    private val deltaRot4A = FloatArray(16)
    private val newOrientation4 = FloatArray(16)

    // In-flight requestCameraRotate90 animation state -- cubeOrientation4 itself updates
    // immediately (same "apply to logical state now, animate the visual separately" pattern as
    // requestTwist), so onDrawFrame instead renders from effectiveCubeOrientation4: interpolated
    // from roomAnimBefore (a snapshot from just before the rotation) toward the now-already-final
    // cubeOrientation4 over ROOM_ANIM_DURATION_NANOS.
    private var roomAnimating = false
    private var roomAnimStartNanos = 0L
    private var roomAnimPlaneA = 0
    private var roomAnimPlaneB = 0
    private var roomAnimAngleDeg = 0f
    private val roomAnimBefore = FloatArray(16)
    private val roomAnimDeltaRot4 = FloatArray(16)
    private val effectiveCubeOrientation4 = FloatArray(16)
    private val pieceOrient4 = FloatArray(16)
    private val animRot4 = FloatArray(16)
    private val animatedPos4 = FloatArray(4)
    private val animatedOrient4 = FloatArray(16)
    private val pos4 = FloatArray(4)
    private val cameraPos4 = FloatArray(4)
    private val homeDir4 = FloatArray(4)
    private val currentDir4 = FloatArray(4)
    private val cameraDir4 = FloatArray(4)
    private val faceCenter4 = FloatArray(4)

    // Column-major (GL layout) continuous "look around the room" rotation -- see class doc.
    // Rebuilt only by drag/stick input, applied uniformly to every sticker's position and mesh.
    private val viewOrientation3 = FloatArray(16)
    private val deltaRotX3 = FloatArray(16)
    private val deltaRotY3 = FloatArray(16)
    private val deltaCombined3 = FloatArray(16)
    private val newOrientation3 = FloatArray(16)

    // In-flight snapViewToNearestCardinalOrientation animation state -- see that function's doc.
    private var snapAnimating = false
    private var snapAnimStartNanos = 0L
    private val snapAnimFrom = FloatArray(16)
    private val snapAnimTo = FloatArray(16)

    // Held effectiveCubeOrientation4 value shown throughout an in-flight snapAnimating hop -- see
    // snapViewToNearestCardinalOrientation's doc. cubeOrientation4 itself is always mutated
    // synchronously (never deferred, so selection/twist resolution is instant and correct); this
    // snapshot is what keeps the *visual* room frozen at its pre-compensation appearance until the
    // camera's small hop finishes, so the two never show a partially-compensated, inconsistent
    // in-between state.
    private val snapEffectiveHold = FloatArray(16)
    private val cardinalRotationRowMajorScratch = FloatArray(16)

    // Shared for the whole frame now (no more per-sticker model matrix): positions are baked
    // directly into vertex data already in room space (post-4D-projection), so the only thing
    // left for the GPU to do uniformly is the ordinary 3D orbit (viewOrientation3) + camera
    // projection (viewProjMatrix) -- computed once per frame instead of once per sticker.
    private val mvpMatrix = FloatArray(16)

    // Current (settled) per-piece transforms, refreshed after every twist/scramble/reset.
    private var currentTransforms: FloatArray = FloatArray(HypercubeGeometry.HOME_POSITIONS.size * 20)

    // In-flight twist animation state. animKind picks which of the two mutually-exclusive groups
    // below onDrawFrame's per-piece rotation actually reads: RIDGE uses animPlaneA/animPlaneB/
    // animAngleDeg (a coordinate-plane rotation, via setPlaneRotation4); EDGE uses
    // animEdgeAxis1/Sign1/Axis2/Sign2 (an arbitrary diagonal-axis rotation fixed at 180 degrees,
    // via setEdgeRotation4) -- see TwistRecord.Edge's doc for why a genuine single edge twist
    // needs different rotation math than a ridge twist's coordinate-plane one.
    private enum class AnimKind { RIDGE, EDGE }
    private var animating = false
    private var animKind = AnimKind.RIDGE
    private var animStartNanos = 0L
    private var animPlaneA = 0
    private var animPlaneB = 0
    private var animAngleDeg = 0f
    private var animEdgeExcludedAxis = AXIS_W
    private var animEdgeAxis1 = 0
    private var animEdgeSign1 = 1
    private var animEdgeAxis2 = 0
    private var animEdgeSign2 = 1
    private var animBefore: FloatArray? = null
    private var animAfter: FloatArray? = null
    private var animAffected: BooleanArray? = null

    /** [Twist]/[Edge]: a [requestTwist]/[requestEdgeTwist] call's fully-resolved parameters --
     * captured at the moment the button was pressed (see [requestTwist]'s doc), not re-derived
     * when this eventually leaves [twistQueue], since live selection/stick/orientation state may
     * have moved on by then. [UndoRequest]/[RedoRequest] carry no parameters at all, by contrast
     * -- see [resolveUndo]'s doc for why undo/redo are resolved fresh via callback at the moment
     * they actually apply instead. All four share one queue (and one [twistQueueMax] high-water
     * mark, see its doc) so a rapid burst of any mix of twists/undos/redos gets the same dynamic
     * speed-up -- added 2026-08-09 for undo/redo specifically (rapid clicks used to be simply
     * dropped by [applyTwistInternal]'s animating guard instead of queuing at all). [UndoRequest]/
     * [RedoRequest] don't carry the room-context fields [Twist] does, since undo/redo never notify
     * [onTwistApplied] (the caller -- MainActivity -- already owns its own history bookkeeping for
     * a replay, unlike a genuinely new twist). */
    private sealed class QueuedAction {
        data class Twist(
            val cell: Cell4,
            val fixAxis2: Axis4,
            val prime: Boolean,
            val roomCell: Cell4,
            val roomFixAxis2: Int,
            val displayApostrophe: Boolean,
        ) : QueuedAction()

        object UndoRequest : QueuedAction()
        object RedoRequest : QueuedAction()

        /** Genuine new edge twist -- notifies [onTwistApplied], unlike [UndoRequest]/[RedoRequest]
         * (which also cover undoing/redoing a [TwistRecord.Edge], resolved generically via
         * [resolveUndo]/[resolveRedo] instead of a separate edge-shaped queue entry). */
        data class Edge(val cell: Cell4, val axis1: Axis4, val sign1: Int, val axis2: Axis4, val sign2: Int) : QueuedAction()
    }

    /** Key for [EDGE_TWIST_DECOMPOSITIONS]. [cellAxis] is the twisted cell's own native axis
     * (the one that can never appear in [axis1]/[axis2] -- see the class doc's `fix_axis2 must
     * differ from the cell's own axis` invariant, same one ordinary ridge twists have); [axis1]/
     * [axis2] are normalized two ways so lookup doesn't care how a caller happened to name the
     * edge: axis order (lower [Axis4.nativeIndex] first), and overall sign (axis1's sign is
     * always forced to +1, flipping axis2's sign along with it if needed) -- the sign
     * normalization matters because a 180-degree rotation's axis and its negation are the *same*
     * rotation (`R(v,180°) = R(-v,180°)`, since Rodrigues' formula only depends on `sin` -- zero
     * at 180° -- and `k⊗k`, unchanged by negating `k`), so e.g. `(Y,-1,Z,-1)` (from
     * [requestI180TwistUFDB] after some reorientation moved a different native axis into a room
     * slot with flipped polarity) must resolve to the exact same table entry as `(Y,1,Z,1)`.
     *
     * [cellAxis] itself needs no normalization -- it's a fixed fact about which cell is being
     * twisted, not a caller naming choice. Added 2026-08-10 alongside the rest of the table's
     * generalization to every possible twisted cell (see [EDGE_TWIST_DECOMPOSITIONS]'s doc):
     * before this, the table (and [setEdgeRotation4]) silently assumed the twisted cell's own
     * axis was always W, true only while native I itself sat in the room's I slot -- David caught
     * a real crash from this the first time a *different* cell (U, via "move to I") occupied it,
     * since axis1/axis2 can then resolve to a pair that includes W (the room's I/O axis is no
     * longer the excluded one once some other cell's *own* axis has taken that role instead). */
    private data class EdgeKey(val cellAxis: Axis4, val axis1: Axis4, val sign1: Int, val axis2: Axis4, val sign2: Int)

    private fun edgeKey(cellAxis: Axis4, a: Axis4, signA: Int, b: Axis4, signB: Int): EdgeKey {
        val axis1: Axis4
        val sign1: Int
        val axis2: Axis4
        val sign2: Int
        if (a.nativeIndex <= b.nativeIndex) {
            axis1 = a; sign1 = signA; axis2 = b; sign2 = signB
        } else {
            axis1 = b; sign1 = signB; axis2 = a; sign2 = signA
        }
        return if (sign1 > 0) {
            EdgeKey(cellAxis, axis1, sign1, axis2, sign2)
        } else {
            EdgeKey(cellAxis, axis1, -sign1, axis2, -sign2)
        }
    }

    /** Verified 3-move native `(fixAxis2, prime)` decompositions for [applyEdgeTwistInternal] --
     * see [requestEdgeTwist]'s doc for how these were found (brute-force search over all 216
     * three-move combinations of the twisted cell's own 6 possible ridge twists, filtering for
     * genuine order-2 rotations that fix both diagonal endpoints, e.g. UF and DB, and nothing
     * else) rather than derived by hand/analogy. All 16 matches per target diagonal compose to the
     * exact same 4x4 matrix (independently verified), so the specific sequence picked here is
     * arbitrary among equally-correct options.
     *
     * All 4 possible [EdgeKey.cellAxis] values x their 6 possible edge diagonals = 24 entries
     * (extended 2026-08-10 from an original 6 that only covered `cellAxis = W`, i.e. only ever
     * correct while native I sat in the room's own I slot -- see [EdgeKey]'s doc for the crash
     * that exposed this). For a given [EdgeKey.cellAxis], the other 3 native axes are always
     * available, giving 3 axis-pairs x 2 diagonals = 6 entries -- same shape regardless of which
     * axis is excluded, just relabeled. */
    private val EDGE_TWIST_DECOMPOSITIONS: Map<EdgeKey, List<Pair<Axis4, Boolean>>> = mapOf(
        // cellAxis = X (available Y, Z, W)
        edgeKey(Axis4.X, Axis4.Y, 1, Axis4.Z, 1) to listOf(Axis4.Y to true, Axis4.Y to true, Axis4.W to false),
        edgeKey(Axis4.X, Axis4.Y, 1, Axis4.Z, -1) to listOf(Axis4.Y to true, Axis4.Y to true, Axis4.W to true),
        edgeKey(Axis4.X, Axis4.Y, 1, Axis4.W, 1) to listOf(Axis4.Y to true, Axis4.Y to true, Axis4.Z to false),
        edgeKey(Axis4.X, Axis4.Y, 1, Axis4.W, -1) to listOf(Axis4.Y to true, Axis4.Y to true, Axis4.Z to true),
        edgeKey(Axis4.X, Axis4.Z, 1, Axis4.W, 1) to listOf(Axis4.Y to true, Axis4.Z to true, Axis4.Z to true),
        edgeKey(Axis4.X, Axis4.Z, 1, Axis4.W, -1) to listOf(Axis4.Y to true, Axis4.W to true, Axis4.W to true),
        // cellAxis = Y (available X, Z, W)
        edgeKey(Axis4.Y, Axis4.X, 1, Axis4.Z, 1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.W to false),
        edgeKey(Axis4.Y, Axis4.X, 1, Axis4.Z, -1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.W to true),
        edgeKey(Axis4.Y, Axis4.X, 1, Axis4.W, 1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.Z to false),
        edgeKey(Axis4.Y, Axis4.X, 1, Axis4.W, -1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.Z to true),
        edgeKey(Axis4.Y, Axis4.Z, 1, Axis4.W, 1) to listOf(Axis4.X to true, Axis4.Z to true, Axis4.Z to true),
        edgeKey(Axis4.Y, Axis4.Z, 1, Axis4.W, -1) to listOf(Axis4.X to true, Axis4.W to true, Axis4.W to true),
        // cellAxis = Z (available X, Y, W)
        edgeKey(Axis4.Z, Axis4.X, 1, Axis4.Y, 1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.W to false),
        edgeKey(Axis4.Z, Axis4.X, 1, Axis4.Y, -1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.W to true),
        edgeKey(Axis4.Z, Axis4.X, 1, Axis4.W, 1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.Y to false),
        edgeKey(Axis4.Z, Axis4.X, 1, Axis4.W, -1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.Y to true),
        edgeKey(Axis4.Z, Axis4.Y, 1, Axis4.W, 1) to listOf(Axis4.X to true, Axis4.Y to true, Axis4.Y to true),
        edgeKey(Axis4.Z, Axis4.Y, 1, Axis4.W, -1) to listOf(Axis4.X to true, Axis4.W to true, Axis4.W to true),
        // cellAxis = W (available X, Y, Z) -- UF/DB, UR/DL etc., I's own home-orientation case
        edgeKey(Axis4.W, Axis4.X, 1, Axis4.Y, 1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.Z to false), // UR/DL
        edgeKey(Axis4.W, Axis4.X, 1, Axis4.Y, -1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.Z to true), // UL/DR
        edgeKey(Axis4.W, Axis4.X, 1, Axis4.Z, 1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.Y to false), // FR/BL
        edgeKey(Axis4.W, Axis4.X, 1, Axis4.Z, -1) to listOf(Axis4.X to true, Axis4.X to true, Axis4.Y to true), // FL/BR
        edgeKey(Axis4.W, Axis4.Y, 1, Axis4.Z, 1) to listOf(Axis4.X to true, Axis4.Y to true, Axis4.Y to true), // UF/DB
        edgeKey(Axis4.W, Axis4.Y, 1, Axis4.Z, -1) to listOf(Axis4.X to true, Axis4.Z to true, Axis4.Z to true), // UB/DF
    )

    // Twists/undos/redos requested while a twist/room-rotation was already animating -- queued
    // instead of dropped (see requestTwist's doc), drained one at a time as each animation finishes
    // (drainTwistQueueIfIdle). twistQueueMax is a high-water mark of how deep the queue got during
    // the current unbroken burst -- not the live size -- reset to 0 once the queue fully drains;
    // ANIM_DURATION_NANOS's dynamic speed-up in onDrawFrame reads it so a big backlog blows
    // through fast instead of playing out one full-speed animation per queued twist.
    private val twistQueue = ArrayDeque<QueuedAction>()
    private var twistQueueMax = 0

    private val vertexShaderSrc = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec3 aColor;
        uniform mat4 uMVP;
        uniform mat3 uNormalMatrix;
        out vec3 vNormal;
        out vec3 vColor;
        void main() {
            gl_Position = uMVP * vec4(aPosition, 1.0);
            vNormal = uNormalMatrix * aNormal;
            vColor = aColor;
        }
    """.trimIndent()

    private val fragmentShaderSrc = """
        #version 300 es
        precision mediump float;
        in vec3 vNormal;
        in vec3 vColor;
        uniform float uAlpha;
        out vec4 fragColor;
        void main() {
            vec3 n = normalize(vNormal);
            // Key light from upper-front-right plus a dimmer fill from the opposite side, so
            // every face of a sticker cube reads as a distinct shade instead of one flat color --
            // faces exactly perpendicular to both lights (fully shadowed) still get the ambient
            // floor so nothing goes pure black.
            vec3 keyDir = normalize(vec3(0.45, 0.85, 0.55));
            vec3 fillDir = normalize(vec3(-0.35, -0.2, -0.6));
            float key = max(dot(n, keyDir), 0.0);
            float fill = max(dot(n, fillDir), 0.0);
            float lighting = 0.38 + key * 0.5 + fill * 0.16;
            vec3 c = vColor * lighting;
            fragColor = vec4(c, uAlpha);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.05f, 0.05f, 0.07f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // De-emphasized stickers (see onDrawFrame's emphasizedCell/filter-dim handling) are drawn
        // translucent rather than skipped or wireframed -- ordinary alpha blending, with per-draw
        // depth-mask toggling (see the draw loop) so a translucent sticker doesn't wrongly occlude
        // whatever's behind it in the depth buffer while still reading as "in front" visually.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        program = buildProgram(vertexShaderSrc, fragmentShaderSrc)
        uMvpLoc = GLES30.glGetUniformLocation(program, "uMVP")
        uNormalMatrixLoc = GLES30.glGetUniformLocation(program, "uNormalMatrix")
        uAlphaLoc = GLES30.glGetUniformLocation(program, "uAlpha")

        val ibo = IntArray(1)
        GLES30.glGenBuffers(1, ibo, 0)
        indexBufferId = ibo[0]
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indexBuffer.capacity() * 2,
            indexBuffer,
            GLES30.GL_STATIC_DRAW,
        )

        // One shared dynamic buffer, sized up front for the worst case (every sticker visible),
        // re-filled every frame in onDrawFrame -- see dynamicVertexData's doc for why this
        // replaced the old one-static-mesh-per-color approach.
        val dynamicVbo = IntArray(1)
        GLES30.glGenBuffers(1, dynamicVbo, 0)
        dynamicVboId = dynamicVbo[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dynamicVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, dynamicVertexBuffer.capacity() * 4, null, GLES30.GL_DYNAMIC_DRAW)

        // The native puzzle state lives in a process-lifetime Rust singleton (see cube4() in
        // lib.rs), not anything tied to this GL surface -- so a reset is only correct the first
        // time *this instance* creates a surface (a fresh launch or a mode switch, both of which
        // construct a brand new HypercubeRenderer and expect a solved start, matching
        // MainActivity.build4DScreen's own moveHistory4D.clear()), not on a later re-creation of
        // the same instance's surface (e.g. the GL context getting torn down while an
        // Intent.createChooser share sheet covers the activity -- confirmed via real-device
        // testing: exporting a scrambled puzzle was silently resetting it to solved, because this
        // used to reset unconditionally whenever no restore was pending, which is also true on
        // that second, spurious call).
        val restore = pendingRestoreState
        if (restore != null) {
            NativeLib.cube4SetState(restore)
            pendingRestoreState = null
        } else if (!hasCreatedSurfaceBefore) {
            NativeLib.cube4Reset()
        }
        hasCreatedSurfaceBefore = true
        currentTransforms = NativeLib.cube4GetTransforms()

        setIdentity4(cubeOrientation4)
        Matrix.setIdentityM(viewOrientation3, 0)
        // Two separate calls, not one combined applyScreenRelativeRotation(yaw, pitch) -- yaw
        // alone first, then pitch alone, so pitch rotates about the *original* (still-horizontal)
        // X axis rather than one already tilted by yaw. That's what keeps U/D exactly vertical on
        // screen for any yaw (rotating around Y can never move a vector that's still purely along
        // Y), which a single combined call can't guarantee. See INITIAL_VIEW_ORIENTATION, built
        // the same way, for why snapping back to this default must match this exact order.
        applyScreenRelativeRotation(INITIAL_YAW_DEG, 0f)
        applyScreenRelativeRotation(0f, INITIAL_PITCH_DEG)
    }

    /** The puzzle always renders into a *square* GL viewport, centered within whatever
     * "dedicated area" is left over once the virtual controller (and its above/below-cluster
     * status lines -- see MainActivity.statusLineParams' doc) reserve their own real estate --
     * squareSize = min(dedicated width, dedicated height), so the puzzle fills that area as much
     * as it can without ever needing to know the actual device's aspect ratio. This replaced a
     * landscape-only "fill the whole surface, let the wide aspect leave side margins for free"
     * approach that only worked by *coincidence*: on a squarer/less-stretched display than
     * whatever this was tuned on (confirmed via a real Retroid Pocket 3 Plus, 2026-09-15 -- see
     * the mc4d_export_bug-style investigation notes) that incidental margin shrank enough for the
     * puzzle to actually collide with the status text sitting in the corner. Reserving real,
     * guaranteed space up front (mirroring how portrait already reserved a bottom strip for the
     * controller) fixes that for any aspect ratio, not just the ones actually tested.
     *
     * Portrait reserves a *bottom* strip (full width) for the bottom-anchored control bar plus
     * its now-above-it status lines; landscape reserves *left/right* strips (full height) for the
     * two edge-anchored clusters plus their own headers. Either way the numbers below must exactly
     * match VirtualClusterView.WIDTH_FRACTION_OF_SCREEN/STACK_GAP_FRACTION/
     * EDGE_MARGIN_SAFETY_FRACTION and MainActivity's *Params functions built on them, or the two
     * sides drift out of sync again. Portrait vs. landscape is decided purely from the surface's
     * own reported dimensions (taller-than-wide), not a separate orientation flag threaded in from
     * MainActivity, so this adapts correctly on its own the moment the device actually rotates and
     * onSurfaceChanged fires again.
     */
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val clusterWidth = minOf(width, height) * VirtualClusterView.WIDTH_FRACTION_OF_SCREEN
        val clusterHeight = VirtualClusterView.heightForWidth(clusterWidth)
        val gapPx = clusterWidth * VirtualClusterView.STACK_GAP_FRACTION
        val edgeMargin = clusterWidth * VirtualClusterView.EDGE_MARGIN_SAFETY_FRACTION

        val dedicatedX: Float
        val dedicatedY: Float
        val dedicatedW: Float
        val dedicatedH: Float
        if (height > width) {
            // Bottom-anchored stack, top to bottom: status header (2 lines) / cluster. Both
            // clusters share the same reserved strip height. The Stick/RKT mode label isn't
            // budgeted here at all -- it draws inside the left cluster's own stick control (see
            // VirtualClusterView.drawStick's stickCornerLabel doc), adding no extra height.
            //
            // The header is 2 *separately positioned* single-line TextViews (MainActivity.
            // statusLineParams stacks each at statusBlockHeightForWidth(width, 1)), not one
            // statusBlockHeightForWidth(width, 2) block -- that 2-line formula bakes in only one
            // shared top/bottom padding pair, not each line's own. Must exactly mirror
            // statusLineParams' own margin math: one STACK_GAP_FRACTION gap from the cluster up to
            // the near line, then the near and far lines separated by the smaller
            // LINE_GAP_FRACTION (single-spaced per David's 2026-09-15 ask) -- getting this out of
            // sync previously let the far (filter/turns) line sit UNDER the puzzle's own edge
            // (confirmed via a real screenshot, 2026-09-15).
            val lineHeight = VirtualClusterView.statusBlockHeightForWidth(clusterWidth, 1)
            val lineGapPx = clusterWidth * VirtualClusterView.LINE_GAP_FRACTION
            val reservedBottom = clusterHeight + gapPx + 2 * lineHeight + lineGapPx + edgeMargin
            dedicatedX = 0f
            dedicatedY = reservedBottom
            dedicatedW = width.toFloat()
            dedicatedH = (height - reservedBottom).coerceAtLeast(1f)
        } else {
            val reservedSide = clusterWidth + edgeMargin
            dedicatedX = reservedSide
            dedicatedY = 0f
            dedicatedW = (width - reservedSide * 2f).coerceAtLeast(1f)
            dedicatedH = height.toFloat()
        }
        val squareSize = minOf(dedicatedW, dedicatedH)
        val viewportX = dedicatedX + (dedicatedW - squareSize) / 2f
        val viewportY = dedicatedY + (dedicatedH - squareSize) / 2f
        GLES30.glViewport(viewportX.toInt(), viewportY.toInt(), squareSize.toInt(), squareSize.toInt())
        // A narrower FOV (was 40f) than CubeRenderer's, paired with a proportionally longer
        // camera distance below, flattens the perspective -- less size difference between the
        // near and far walls (e.g. F/R vs. their opposite B/L) -- closer to an isometric look
        // without going fully orthographic. Built via frustumM (an asymmetric frustum), not the
        // simpler perspectiveM, purely to apply VERTICAL_CONTENT_SHIFT_FRACTION below -- see its
        // own doc.
        val near = 0.1f
        val halfHeight = near * tan(Math.toRadians(24.0 / 2.0)).toFloat()
        // Small vertical "lens shift" -- at the app's fixed default view angle (INITIAL_YAW_DEG/
        // INITIAL_PITCH_DEG), the U cell (top of the unfolded net) sits measurably closer to the
        // camera than the D cell (bottom) does, so it renders larger and the room reads as top-
        // heavy: U was touching the square viewport's own top edge while D still had real
        // clearance at the bottom (confirmed via a real screenshot, 2026-09-15). The camera itself
        // (Matrix.setLookAtM below) stays perfectly level -- shearing the frustum's near-plane
        // window up by a small amount shifts every cell's on-screen position down by the same NDC
        // amount regardless of its depth (unlike translating the scene in view-space, which would
        // shift near/far cells by different amounts) -- exactly the lens-shift technique real
        // cameras/architectural photography use to reframe without moving the camera. Positive
        // shifts the window *up* in camera space, which reads as the rendered content moving
        // *down* on screen -- tuned empirically against that screenshot's ~0px top / ~26px bottom
        // clearance (on a 752px-tall square viewport) to split the difference evenly.
        val shift = halfHeight * VERTICAL_CONTENT_SHIFT_FRACTION
        Matrix.frustumM(projMatrix, 0, -halfHeight, halfHeight, -halfHeight + shift, halfHeight + shift, near, 150f)
    }

    /** Accumulates an "ordinary" 3D-feeling touch-drag delta (degrees), applied next frame. */
    fun addDragDelta(dYawDeg: Float, dPitchDeg: Float) {
        synchronized(dragLock) {
            pendingDragYawDeg += dYawDeg
            pendingDragPitchDeg += dPitchDeg
        }
    }

    private fun drainDragDelta(): Pair<Float, Float> {
        synchronized(dragLock) {
            val delta = pendingDragYawDeg to pendingDragPitchDeg
            pendingDragYawDeg = 0f
            pendingDragPitchDeg = 0f
            return delta
        }
    }

    /**
     * Rotates [viewOrientation3] by [dYawDeg]/[dPitchDeg] about the *screen's current* vertical/
     * horizontal axes -- the plain 3D "orbit the room" feel, composed on the left exactly like
     * [CubeRenderer.applyScreenRelativeRotation] so it has no gimbal-lock pole. This never
     * touches [cubeOrientation4]: which native cell sits on which wall is a separate, purely
     * discrete concern (see class doc), so dragging can never trigger the wall-reassignment
     * jump that would happen if the two were the same matrix.
     */
    private fun applyScreenRelativeRotation(dYawDeg: Float, dPitchDeg: Float) {
        Matrix.setRotateM(deltaRotX3, 0, dPitchDeg, 1f, 0f, 0f)
        Matrix.setRotateM(deltaRotY3, 0, dYawDeg, 0f, 1f, 0f)
        Matrix.multiplyMM(deltaCombined3, 0, deltaRotY3, 0, deltaRotX3, 0)
        Matrix.multiplyMM(newOrientation3, 0, deltaCombined3, 0, viewOrientation3, 0)
        System.arraycopy(newOrientation3, 0, viewOrientation3, 0, 16)
    }

    /**
     * The actual "4D camera" control: rotates [cubeOrientation4] by exactly 90 degrees (or -90
     * if [reverse]) in the ([axisA], [axisB]) plane, e.g. Z-W cycles F->I->B->O->F. Since this
     * only ever composes 90-degree rotations, cubeOrientation4 always stays a signed permutation
     * matrix, keeping the per-sticker slot resolution in [onDrawFrame] exact. Ignored while a
     * twist or another room rotation is still animating, same as [requestTwist]'s own guard --
     * keeps at most one animation driving the room/pieces at a time.
     */
    fun requestCameraRotate90(axisA: Int, axisB: Int, reverse: Boolean) {
        if (animating || roomAnimating) return
        // See resolvePendingSnapHopImmediately's doc: the SELECT/"move to I" wiring always snaps
        // immediately before this, so this collapses that snap's hop right now instead of this
        // room-rotate animation starting from a stale held value.
        resolvePendingSnapHopImmediately()
        System.arraycopy(cubeOrientation4, 0, roomAnimBefore, 0, 16)
        roomAnimPlaneA = axisA
        roomAnimPlaneB = axisB
        roomAnimAngleDeg = if (reverse) -90f else 90f
        roomAnimStartNanos = System.nanoTime()
        roomAnimating = true

        setPlaneRotation4(deltaRot4A, axisA, axisB, roomAnimAngleDeg)
        mat4MatMul(newOrientation4, deltaRot4A, cubeOrientation4)
        System.arraycopy(newOrientation4, 0, cubeOrientation4, 0, 16)
    }

    /**
     * Rotates the room by exactly one 90-degree step so whichever cell the left stick currently
     * has selected ([selectedRoomAxis]/[selectedRoomSign] -- its *current* room slot, not its
     * native identity) ends up in the I slot -- for the gamepad's left trigger. A no-op if
     * nothing is actively selected ([hasSelection] false -- there's no longer a "last selection"
     * to fall back on once the stick's released, see [hasSelection]'s doc) or if the selection is
     * already on the W axis (I or O), since there's no spatial axis left to pair with W for this:
     * the two cells on the W axis can only swap via a *180*-degree turn, not a single quarter
     * turn, and this only ever does quarter turns like every other rotation here.
     *
     * The rotation direction (`reverse`) is derived, not looked up: [requestCameraRotate90]'s
     * `(axis, W)` quarter turn sends the room's own +axis direction to +W when [reverse] is
     * false, and to -W when true (see [setPlaneRotation4]) -- I is -W, so the selected slot's
     * sign alone picks the direction that lands there.
     */
    fun requestMoveSelectedCellToI() {
        if (!hasSelection || selectedRoomAxis == AXIS_W) return
        requestCameraRotate90(selectedRoomAxis, AXIS_W, reverse = selectedRoomSign > 0)
    }

    /** Multiplies the camera distance by [factor] (>1 zooms in, <1 zooms out), clamped so the
     * room can't be zoomed inside-out or pushed arbitrarily far away -- mirrors
     * [CubeRenderer.zoomBy] exactly, just with a range scaled up for this room's larger extent. */
    fun zoomBy(factor: Float) {
        distance = (distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    /**
     * Realigns the view so the camera ends up at the app's one true default orientation
     * ([INITIAL_VIEW_ORIENTATION]), compensating [cubeOrientation4] by whichever of the cube's 24
     * rotational symmetries (see [CARDINAL_TARGETS]) the camera's *current* orientation happens to
     * be closest to -- so the net on-screen appearance settles into the exact same tidy view a
     * camera-only "snap to nearest symmetry" would have shown, but the camera itself always ends
     * up at true default, never one of the other 23. Called (see [updateCell4Selection] and
     * [MainActivity]'s rotation-button/RKT wiring) whenever the puzzle is about to be interacted
     * with via the gamepad, so the view never stays at an arbitrary, ugly continuous drag angle.
     *
     * Why the camera always returns to true default now, rather than "nearest of 24" (this
     * function's original behavior): confirmed via a real device repro and live instrumented
     * logging (2026-07-22) that dragging the camera to an arbitrary angle and then engaging the
     * stick/a button almost never actually lands the camera back on true identity -- with 24
     * roughly-equal "nearest" zones, landing near enough to default is the unlikely outcome, not
     * the norm. Every one of the 24 shares the same corner-on silhouette (only the color-to-slot
     * assignment differs), so a non-identity symmetry is very easy to mistake for "back at
     * default" -- which made the room-letter labeling that followed look like a bug even when it
     * was correct. Keeping the camera itself always at true default removes that whole class of
     * confusion at the source: "room U" is now always the literal fixed top-of-screen slot, full
     * stop, and which *native* cell currently sits there is answered entirely by
     * [cubeOrientation4] -- the same mechanism [requestCameraRotate90] already uses for "move to
     * I", not a second, parallel "which of 24 symmetries is the camera at" bookkeeping system
     * (the old `cameraSnapSymmetry`/`holdSymmetry`/`applyInverseSnapSymmetry`, all removed; see
     * [updateCell4Selection] for how much simpler the wedge resolution became without them).
     *
     * The math this relies on: for any of the 24 symmetries S, rotating the *camera* by S while
     * leaving [cubeOrientation4] alone produces the exact same visible result as leaving the
     * camera at identity and instead setting `cubeOrientation4' = S * cubeOrientation4` -- both
     * are just conjugations of the same underlying transform, and S is itself a signed
     * permutation (the same species of matrix [cubeOrientation4] already is). Validated against
     * 50 random (prior-reorientation, S) combinations via `tools/sim/cube4_sim.py` before
     * implementing here: the two approaches produced bit-identical visible-sticker layouts in
     * every case, and a separate 300-combination check confirmed room-letter resolution matches
     * too (see that script for both).
     *
     * Why this is a two-phase, *not* a single lerp (found on-device 2026-07-22, right after the
     * above first shipped): animating [viewOrientation3] all the way from wherever it was dragged
     * to straight to [INITIAL_VIEW_ORIENTATION], *simultaneously* lerping [cubeOrientation4] from
     * its old value to its compensated one, only guarantees the start and end frames look right --
     * the two independent lerp-then-orthonormalize curves don't cancel at intermediate t, so
     * whenever the dragged-to angle was far from default (i.e. S was a large symmetry, common per
     * the same instrumentation above) this was a visibly huge spin. Worse, restarting that
     * simultaneous pair on every stick wedge change (as fast re-selection does) meant each restart
     * re-snapshotted the room mid-lerp, popping [cubeOrientation4] from its actual on-screen value
     * to a fresh "from" every time -- the noticeable flicker on every selection change. The fix:
     * only ever *animate* the small hop from the current angle to [CARDINAL_TARGETS] of the
     * nearest S -- a motion just as small as the pre-2026-07-22 "snap to nearest symmetry" always
     * was, since S is by construction the *closest* symmetry. [cubeOrientation4] itself is mutated
     * immediately (synchronously, right here) the moment this is called -- correctness for
     * [selectedCell4]/twist resolution can never wait on an animation -- but its *visual* reveal
     * ([effectiveCubeOrientation4], see [onDrawFrame]) is held frozen at the pre-compensation
     * snapshot for as long as the hop is still in flight, then swapped to the (already-updated)
     * real value in one uncontested cut the instant the hop finishes -- camera jumps from
     * "default*S" to "default" at the exact same moment the room jumps from "uncompensated" to
     * "compensated", so the two exactly cancel with no intermediate frame in between to look wrong.
     * A burst of rapid re-snaps (repeated wedge changes) never re-snapshots the hold mid-hop (see
     * below), so the room stays visually frozen through the whole burst and only reveals once,
     * cleanly, when it actually settles -- no per-restart pop, no flicker.
     */
    fun snapViewToNearestCardinalOrientation() {
        var bestSymmetry = CARDINAL_ROTATIONS[0]
        var bestIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in CARDINAL_TARGETS.indices) {
            val candidate = CARDINAL_TARGETS[i]
            var score = 0f
            for (idx in ROTATION_PART_INDICES) {
                score += candidate[idx] * viewOrientation3[idx]
            }
            if (score > bestScore) {
                bestScore = score
                bestSymmetry = CARDINAL_ROTATIONS[i]
                bestIndex = i
            }
        }

        // Only the small hop to the nearest cardinal orientation is ever animated -- see doc. If a
        // hop is already in flight, don't re-snapshot the hold: keep showing whatever the room
        // looked like before this whole rapid re-snap sequence began, so it stays frozen until it
        // actually settles instead of popping on every restart.
        if (!snapAnimating) {
            System.arraycopy(cubeOrientation4, 0, snapEffectiveHold, 0, 16)
        }
        System.arraycopy(viewOrientation3, 0, snapAnimFrom, 0, 16)
        System.arraycopy(CARDINAL_TARGETS[bestIndex], 0, snapAnimTo, 0, 16)
        snapAnimStartNanos = System.nanoTime()
        snapAnimating = true

        // Compensate cubeOrientation4 by the same symmetry S the camera would otherwise have
        // shown, so the net appearance is unchanged once revealed -- see the math note above.
        // Mutated synchronously, right now -- selectedCell4/twist resolution must never wait on
        // the hop above; only its visual reveal is deferred (see onDrawFrame). Deliberately NOT
        // skipped while a twist or room rotation is animating (unlike before 2026-08-04): a button
        // press mid-animation now queues its already-resolved twist (see requestTwist/twistQueue)
        // instead of being dropped, so this compensation must stay in sync on every single press,
        // not just the ones lucky enough to land while nothing else was animating -- a skipped
        // call here would let effectiveCell4/resolveRotationButtonFixAxis2 resolve the *next*
        // queued twist against a stale cubeOrientation4. Safe to run unconditionally: when
        // viewOrientation3 hasn't moved since it last settled (the common case, no camera drag
        // between presses), bestSymmetry always resolves to the identity and this is a no-op: see
        // resolvePendingSnapHopImmediately's doc for why a twist/room-rotation about to start
        // always collapses viewOrientation3 back to true default first.
        //
        // bestSymmetry is column-major (GL layout, matching CARDINAL_ROTATIONS/viewOrientation3);
        // cubeOrientation4 is row-major (matching the rest of this class's pure-4D-math
        // matrices), so convert before combining them.
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                cardinalRotationRowMajorScratch[r * 4 + c] = bestSymmetry[c * 4 + r]
            }
        }
        mat4MatMul(newOrientation4, cardinalRotationRowMajorScratch, cubeOrientation4)
        System.arraycopy(newOrientation4, 0, cubeOrientation4, 0, 16)
    }

    /**
     * If [snapViewToNearestCardinalOrientation]'s small camera hop is still in flight, immediately
     * collapses it: jumps the camera the rest of the way to true default and reveals the
     * (already-committed) compensated [cubeOrientation4] right now, into [effectiveCubeOrientation4]
     * too, rather than letting a twist or room-rotate animation that's about to start read a stale
     * held value as its own starting point (see [onDrawFrame]'s snapAnimating branch). Called from
     * [applyTwistInternal] and [requestCameraRotate90] for exactly this reason -- both are always
     * immediately preceded by a snap in [MainActivity]'s wiring, so without this their own
     * animation would begin from a visual that was never actually on screen, popping at the start.
     * Deliberately NOT called from [snapViewToNearestCardinalOrientation] itself: a fresh re-snap
     * (e.g. the next stick wedge) should let the hop continue/adjust smoothly, not collapse -- see
     * that function's flicker note.
     */
    private fun resolvePendingSnapHopImmediately() {
        if (!snapAnimating) return
        System.arraycopy(INITIAL_VIEW_ORIENTATION, 0, viewOrientation3, 0, 16)
        System.arraycopy(cubeOrientation4, 0, effectiveCubeOrientation4, 0, 16)
        snapAnimating = false
    }

    /**
     * Left-stick cell selection for 4D mode (see `todo-controller-input.md`), called via
     * `queueEvent` on every left-stick motion update -- [x]/[y] are the deadzoned stick axes as
     * reported by [GamepadInputHandler]. An 8-way compass (evenly split into 45-degree wedges by
     * the stick's raw angle alone) picks one of the 6 walls or 2 special (I/O) slots, each wedge
     * with a *fixed* room-slot meaning (e.g. down-right always means the room's own +X slot) --
     * used directly, no camera-symmetry correction needed, since the camera always sits at true
     * default (see [snapViewToNearestCardinalOrientation]'s doc for why) -- "down-right" and "the
     * room's +X slot" are simply the same screen position, always. [nativeCellInRoomSlot] then
     * resolves whichever slot was landed on to its current native occupant via [cubeOrientation4].
     *
     * As soon as the stick crosses [SIGNIFICANT_STICK_MAGNITUDE] from centered, this also snaps
     * the view (see [snapViewToNearestCardinalOrientation]) -- but only when the stick's *wedge*
     * actually changes (tracked via [lastStickWedge]), not on every motion event, and not just at
     * the start of a fresh hold. This deliberately does NOT re-snap while the stick sits
     * significantly deflected in the same wedge and the camera is dragged (touch or right stick)
     * at the same time -- holding the left stick for selection while simultaneously dragging for
     * rotation isn't a supported combo, and whatever it resolves to during that overlap is
     * unspecified. What *is* supported: dragging to a new camera angle, then moving the stick
     * (even without fully releasing it first) to point at a new wedge -- that wedge change
     * triggers the re-snap, so the correction catches up to the drag. This also still guards
     * against an earlier incidental-touchscreen-drift bug, since noise wouldn't move the stick to
     * a genuinely different 45-degree wedge.
     *
     * Below [SIGNIFICANT_STICK_MAGNITUDE], a nonzero-but-small deflection is ignored entirely
     * rather than updating the selected slot -- a real analog stick (especially wireless)
     * rarely settles at exactly (0,0) once released, and without this, that residual noise
     * would silently reassign the selected cell out from under the user between presses (e.g.
     * selecting R, then having a twist button unexpectedly act on a totally different cell the
     * stick never intentionally pointed at). This is also where the selection itself is forgotten
     * (clears [hasSelection], not just [lastStickWedge]) -- releasing the stick/d-pad all the way
     * to center clears the last selection rather than leaving it live-but-unhighlighted, so a
     * twist right after release falls back to [effectiveCell4]'s per-axis default instead of
     * silently reusing whatever was last selected (2026-08-02 design change).
     */
    fun updateCell4Selection(x: Float, y: Float) {
        val isSignificant = hypot(x, y) > SIGNIFICANT_STICK_MAGNITUDE
        if (!isSignificant) {
            lastStickWedge = -1
            hasSelection = false
            publishLabelSnapshot()
            return
        }
        hasSelection = true

        // AXIS_Y is negative when pushed up, so negate it to get a standard math angle (0 deg
        // = right, 90 deg = up, increasing counterclockwise), then bucket it into one of 8
        // 45-degree compass wedges, centered on 0/45/90/.../315 (so e.g. wedge 0 spans
        // [-22.5, 22.5) -- matches the boundaries the old direct deg comparisons used exactly).
        val deg = (Math.toDegrees(atan2(-y.toDouble(), x.toDouble())) + 360.0) % 360.0
        val wedge = (((deg + 22.5) / 45.0).toInt()) % 8
        if (wedge != lastStickWedge) {
            snapViewToNearestCardinalOrientation()
            lastStickWedge = wedge
        }

        val (roomAxis, roomSign) = when (wedge) {
            0 -> AXIS_W to -1 // right: I's slot
            1 -> AXIS_Z to -1 // up-right: B's slot
            2 -> AXIS_Y to 1 // up: U's slot
            3 -> AXIS_X to -1 // up-left: L's slot
            4 -> AXIS_W to 1 // left: O's slot
            5 -> AXIS_Z to 1 // down-left: F's slot
            6 -> AXIS_Y to -1 // down: D's slot
            else -> AXIS_X to 1 // down-right: R's slot (wedge 7)
        }
        selectedRoomAxis = roomAxis
        selectedRoomSign = roomSign
        publishLabelSnapshot()
    }

    /** Which native [Cell4] currently occupies the room slot at ([roomAxis], [roomSign]) --
     * the inverse of the forward `cubeOrientation4 * cell.outwardNormal()` mapping [onDrawFrame]
     * uses per-sticker, found here by just checking all 8 cells (cheap; only called on stick
     * input, never per-frame-per-sticker). */
    private fun nativeCellInRoomSlot(roomAxis: Int, roomSign: Int): Cell4 {
        for (cell in Cell4.entries) {
            mat4VecMul(roomDirScratch4, cubeOrientation4, cell.outwardNormal())
            if (roomDirScratch4[roomAxis] * roomSign > 0.5f) return cell
        }
        error("cubeOrientation4 should always map every cell to exactly one room slot")
    }

    /**
     * Applies [cell]/[fixAxis2]/[prime] to native puzzle state immediately, then animates the
     * affected cell's pieces from their pre-twist transforms to the new ones -- or, if another
     * twist/room-rotation is still animating, queues these exact already-resolved parameters
     * instead of dropping them (see [twistQueue]/[drainTwistQueueIfIdle]): [onDrawFrame] applies
     * it the moment the current animation finishes, running progressively faster the deeper the
     * queue got (see [twistQueueMax]'s use in [onDrawFrame]) so a burst of rapid input drains
     * quickly instead of playing out one full-speed animation per press.
     *
     * Every argument must be resolved by the caller *before* calling this -- e.g. [MainActivity]'s
     * on4DRotationButton resolves `cell`/`fixAxis2`/`roomCell` from live stick-selection and
     * room-orientation state -- never lazily re-derived once this leaves the queue, since that
     * live state (which cell is selected, which way the room's been rotated) can have moved on by
     * the time a queued entry's turn comes up; queuing must capture a decision, not a recipe for
     * re-deriving one later. [fixAxis2] equal to [cell]'s own axis is invalid and silently no-ops,
     * whether applied immediately or dequeued later.
     *
     * [roomCell]/[roomFixAxis2]/[displayApostrophe] are purely passed through to [onTwistApplied],
     * not used by the twist itself -- so callers that already resolved a room context (and the
     * community-notation apostrophe it implies) when deciding what to twist (e.g. [MainActivity]'s
     * on4DRotationButton, or [requestRktITwist] above) don't have to re-derive it later just for
     * notation labeling -- see [TwistRecord.Ridge.displayApostrophe]'s doc for why it can't safely
     * be re-derived later anyway.
     */
    fun requestTwist(
        cell: Cell4,
        fixAxis2: Axis4,
        prime: Boolean,
        roomCell: Cell4,
        roomFixAxis2: Int,
        displayApostrophe: Boolean,
    ) {
        if (animating || roomAnimating) {
            twistQueue.addLast(QueuedAction.Twist(cell, fixAxis2, prime, roomCell, roomFixAxis2, displayApostrophe))
            twistQueueMax = max(twistQueueMax, twistQueue.size)
            return
        }
        if (!applyTwistInternal(cell, fixAxis2, prime)) return
        onTwistApplied?.invoke(TwistRecord.Ridge(cell, fixAxis2, prime, roomCell, roomFixAxis2, displayApostrophe))
    }

    /** Called from [onDrawFrame] the instant [animating] or [roomAnimating] clears -- applies the
     * next [twistQueue] entry, if any, exactly as [requestTwist]/[requestUndo]/[requestRedo] would
     * have applied it immediately (including notifying [onTwistApplied] for a [QueuedAction.Twist]
     * entry's history). Loops past any entry [applyTwistInternal] rejects (only the
     * `fixAxis2 == cell.axis` invalid case in practice, since animating/roomAnimating are already
     * known clear here) rather than getting stuck on it. Resets [twistQueueMax] back to 0 once the
     * queue is empty, matching Hyperspeedcube's own dynamic-speed queue (see the conversation that
     * produced this): a fresh burst should start at normal speed, not inherit a previous burst's
     * high-water mark. */
    private fun drainTwistQueueIfIdle() {
        if (animating || roomAnimating) return
        while (twistQueue.isNotEmpty()) {
            when (val next = twistQueue.removeFirst()) {
                is QueuedAction.Twist -> {
                    if (applyTwistInternal(next.cell, next.fixAxis2, next.prime)) {
                        onTwistApplied?.invoke(
                            TwistRecord.Ridge(next.cell, next.fixAxis2, next.prime, next.roomCell, next.roomFixAxis2, next.displayApostrophe),
                        )
                        return
                    }
                }
                is QueuedAction.Edge -> {
                    if (applyEdgeTwistInternal(next.cell, next.axis1, next.sign1, next.axis2, next.sign2)) {
                        onTwistApplied?.invoke(TwistRecord.Edge(next.cell, next.axis1, next.sign1, next.axis2, next.sign2))
                        return
                    }
                }
                QueuedAction.UndoRequest -> if (applyResolvedUndoRedo(resolveUndo)) return
                QueuedAction.RedoRequest -> if (applyResolvedUndoRedo(resolveRedo)) return
            }
        }
        twistQueueMax = 0
        onQueueIdle?.invoke()
    }

    /** Shared by [requestUndo]/[requestRedo]'s immediate-apply branch and [drainTwistQueueIfIdle]'s
     * queued one -- resolves via [resolver] right now (see [resolveUndo]'s doc for why this has to
     * happen this late) and applies whichever [TwistRecord] kind comes back, or does nothing if
     * null (nothing left to undo/redo). [onUndoRedoApplied] always fires once resolution is
     * attempted, even on a null/rejected outcome, since MainActivity incremented
     * `pendingUndoRedoCount` unconditionally when this was first requested and needs exactly one
     * matching decrement regardless of how it resolves. Returns whether an animation actually
     * started, so [drainTwistQueueIfIdle] knows whether to stop draining (matches every other
     * branch there). */
    private fun applyResolvedUndoRedo(resolver: (() -> TwistRecord?)?): Boolean {
        val applied = when (val record = resolver?.invoke()) {
            is TwistRecord.Ridge -> applyTwistInternal(record.cell, record.fixAxis2, record.prime)
            is TwistRecord.Edge -> applyEdgeTwistInternal(record.cell, record.axis1, record.sign1, record.axis2, record.sign2)
            null -> false
        }
        onUndoRedoApplied?.invoke()
        return applied
    }

    /** Requests undoing whatever [resolveUndo] says is currently last -- deliberately not told
     * which move that is by the caller (contrast [requestTwist], which takes fully-resolved
     * params): see [resolveUndo]'s doc for why resolving it this late, rather than back when the
     * button was pressed, is what closes a real bookkeeping race. Applies immediately if idle, or
     * queues behind [twistQueue] exactly like [requestTwist] otherwise -- see [QueuedAction]'s doc
     * for why undo/redo share that same queue (and its dynamic speed-up) rather than a separate
     * immediate-or-dropped path. Never notifies [onTwistApplied]: nothing here needs to append to
     * history, just replay it (MainActivity's [resolveUndo] callback does its own bookkeeping). */
    fun requestUndo() {
        if (animating || roomAnimating) {
            twistQueue.addLast(QueuedAction.UndoRequest)
            twistQueueMax = max(twistQueueMax, twistQueue.size)
            return
        }
        applyResolvedUndoRedo(resolveUndo)
    }

    /** Redo counterpart to [requestUndo] -- see its doc and [resolveRedo]'s for why resolution is
     * deferred to apply time here too. */
    fun requestRedo() {
        if (animating || roomAnimating) {
            twistQueue.addLast(QueuedAction.RedoRequest)
            twistQueueMax = max(twistQueueMax, twistQueue.size)
            return
        }
        applyResolvedUndoRedo(resolveRedo)
    }

    private fun applyTwistInternal(cell: Cell4, fixAxis2: Axis4, prime: Boolean): Boolean {
        if (animating || roomAnimating || fixAxis2 == cell.axis) return false
        // See resolvePendingSnapHopImmediately's doc: on4DRotationButton always snaps immediately
        // before every twist, so this collapses that snap's hop right now instead of letting the
        // twist animation's pieces render against a stale held room orientation.
        resolvePendingSnapHopImmediately()

        val before = currentTransforms
        NativeLib.cube4Twist(cell.nativeIndex, fixAxis2.nativeIndex, prime)
        val after = NativeLib.cube4GetTransforms()
        onNativeApply?.invoke("ridge $cell/$fixAxis2/prime=$prime")

        val cellAxisIdx = cell.axis.nativeIndex
        animAffected = BooleanArray(HypercubeGeometry.HOME_POSITIONS.size) { i ->
            val base = i * 20
            before[base + cellAxisIdx].roundToInt() == cell.sign
        }
        val rotating = (0 until 4).filter { it != cellAxisIdx && it != fixAxis2.nativeIndex }
        animKind = AnimKind.RIDGE
        animPlaneA = rotating[0]
        animPlaneB = rotating[1]
        animAngleDeg = if (prime) -90f else 90f
        animBefore = before
        animAfter = after
        animStartNanos = System.nanoTime()
        animating = true

        onStateChanged?.invoke(NativeLib.cube4IsSolved())
        return true
    }

    /** Requests a genuine single 180-degree edge twist of [cell] around the diagonal axis through
     * native spatial axes ([axis1],[sign1]) and ([axis2],[sign2]) -- e.g. Y/+1, Z/+1 for the
     * UF/DB diagonal (added 2026-08-10 for [requestI180TwistUFDB]/[requestI180TwistURDL], see
     * their docs). This is what MagicCube4D itself does when you click an *edge* sticker (2
     * nonzero local axes) rather than an ordinary *ridge* sticker's single-axis 90-degree twist --
     * David caught that an earlier version faked this by chaining 3 ordinary [requestTwist]-style
     * quarter turns, which showed up as 3 separate animations *and* 3 separate history entries
     * instead of a single move, and also had the wrong diagonal (a hand-derived-by-analogy formula
     * that turned out backwards).
     *
     * This version still computes the resulting *native puzzle state* via [EDGE_TWIST_DECOMPOSITIONS]
     * -- 3 chained [NativeLib.cube4Twist] calls done back-to-back with no render/animation/history
     * between them, purely to get the correct permutation without new Rust-level rotation math --
     * but the *decomposition itself* (which exact 3 quarter-turns, and their order/prime) was found
     * by brute-force search over all 216 three-move combinations of native (axis, prime) pairs,
     * filtering for ones that leave both diagonal endpoints (e.g. UF and DB) fixed while being a
     * genuine order-2 (180-degree) rotation and nothing else -- not another hand-derived guess.
     * All 16 matches per target diagonal compose to the exact same 4x4 matrix (verified directly),
     * so which one is picked doesn't matter; see [EDGE_TWIST_DECOMPOSITIONS]'s doc for the picked
     * ones. The *animation* is unrelated to this decomposition -- see [setEdgeRotation4] for the
     * real single-rotation math the same verified end matrix confirmed.
     *
     * Native-only, unlike [requestRktITwist] -- doesn't resolve room-relative "whichever cell is
     * currently in I's slot" the way RKT's I-twists do. A deliberate scope cut (David's shortcut
     * always fires from [Cell4.I] directly in STICK mode): correct at default/untouched
     * orientation, not yet guaranteed correct after a room reorientation (e.g. via "move to I")
     * the way [correctedNativePrimeForRoomTwist] makes ridge twists -- that would need the same
     * per-sub-twist reorientation correction extended to a 3-move decomposition, not attempted
     * yet. */
    fun requestEdgeTwist(cell: Cell4, axis1: Axis4, sign1: Int, axis2: Axis4, sign2: Int) {
        if (animating || roomAnimating) {
            twistQueue.addLast(QueuedAction.Edge(cell, axis1, sign1, axis2, sign2))
            twistQueueMax = max(twistQueueMax, twistQueue.size)
            return
        }
        if (!applyEdgeTwistInternal(cell, axis1, sign1, axis2, sign2)) return
        onTwistApplied?.invoke(TwistRecord.Edge(cell, axis1, sign1, axis2, sign2))
    }

    private fun applyEdgeTwistInternal(cell: Cell4, axis1: Axis4, sign1: Int, axis2: Axis4, sign2: Int): Boolean {
        if (animating || roomAnimating) return false
        resolvePendingSnapHopImmediately()

        val before = currentTransforms
        val key = edgeKey(cell.axis, axis1, sign1, axis2, sign2)
        val decomposition = EDGE_TWIST_DECOMPOSITIONS[key]
            ?: error("No verified 3-twist decomposition for edge $axis1/$sign1, $axis2/$sign2 on cell axis ${cell.axis} -- see EDGE_TWIST_DECOMPOSITIONS's doc")
        for ((fixAxis2, prime) in decomposition) {
            NativeLib.cube4Twist(cell.nativeIndex, fixAxis2.nativeIndex, prime)
        }
        val after = NativeLib.cube4GetTransforms()
        onNativeApply?.invoke("edge $cell/$axis1,$sign1/$axis2,$sign2")

        val cellAxisIdx = cell.axis.nativeIndex
        animAffected = BooleanArray(HypercubeGeometry.HOME_POSITIONS.size) { i ->
            val base = i * 20
            before[base + cellAxisIdx].roundToInt() == cell.sign
        }
        animKind = AnimKind.EDGE
        animEdgeExcludedAxis = cellAxisIdx
        animEdgeAxis1 = axis1.nativeIndex
        animEdgeSign1 = sign1
        animEdgeAxis2 = axis2.nativeIndex
        animEdgeSign2 = sign2
        animBefore = before
        animAfter = after
        animStartNanos = System.nanoTime()
        animating = true

        onStateChanged?.invoke(NativeLib.cube4IsSolved())
        return true
    }

    /** Instantly resets to solved, then re-randomizes the puzzle from there (no animation),
     * refreshes solved state, and returns the moves actually applied -- MainActivity records
     * these into its own twist history (see [NativeLib.cube4Scramble]'s doc) so an exported MC4D
     * log can mark where the scramble ends, matching real MagicCube4D's own "m|" convention. The
     * reset-first is load-bearing, not defensive padding: [NativeLib.cube4Scramble] itself
     * scrambles from *whatever state the native cube is already in*, and callers only ever record
     * the returned moves as "the whole history so far," implicitly assuming that starting point
     * was solved -- confirmed as a real bug (2026-08-17, via MainActivity's export round-trip
     * checker) via the simplest possible repro: press Scramble twice in a row with no Reset
     * in between. The second scramble silently scrambled from the first scramble's *already-
     * scrambled* state, while MainActivity's history/export only ever recorded the second
     * scramble's 250 moves as if they'd started from solved -- an export taken at that point would
     * never replay back to solved in real MC4D, because the recorded "start" wasn't the true one. */
    fun requestScramble(moveCount: Int): List<Triple<Cell4, Axis4, Boolean>> {
        if (animating) return emptyList()
        // Any twist still queued (e.g. from a room-rotation in flight -- this check, like
        // requestReset's below, only guards animating, not roomAnimating) was resolved against
        // the pre-scramble puzzle/orientation and would be nonsense applied afterward.
        twistQueue.clear()
        twistQueueMax = 0
        NativeLib.cube4Reset()
        val raw = NativeLib.cube4Scramble(moveCount)
        currentTransforms = NativeLib.cube4GetTransforms()
        onStateChanged?.invoke(NativeLib.cube4IsSolved())
        return (raw.indices step 3).map { i ->
            Triple(
                Cell4.entries.first { it.nativeIndex == raw[i] },
                Axis4.entries.first { it.nativeIndex == raw[i + 1] },
                raw[i + 2] != 0,
            )
        }
    }

    /** Instantly resets to solved (no animation) and refreshes solved state. */
    fun requestReset() {
        if (animating) return
        // See requestScramble's identical clear above for why.
        twistQueue.clear()
        twistQueueMax = 0
        NativeLib.cube4Reset()
        currentTransforms = NativeLib.cube4GetTransforms()
        onStateChanged?.invoke(NativeLib.cube4IsSolved())
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // Resolved once per frame (not once per sticker below) since emphasizedCell is a
        // computed property now -- see its doc for why it needs to be live rather than cached.
        val frameEmphasizedCell = emphasizedCell

        if (snapAnimating) {
            // Only the small hop itself animates; reaching the end here means it's time for the
            // big, un-animated jump the rest of the way to true default -- see
            // snapViewToNearestCardinalOrientation's doc for why this must be a single cut, not a
            // lerp.
            val snapT = ((System.nanoTime() - snapAnimStartNanos).toFloat() / SNAP_ANIM_DURATION_NANOS).coerceIn(0f, 1f)
            if (snapT >= 1f) {
                System.arraycopy(INITIAL_VIEW_ORIENTATION, 0, viewOrientation3, 0, 16)
                snapAnimating = false
            } else {
                lerpAndOrthonormalizeRotation(viewOrientation3, snapAnimFrom, snapAnimTo, snapT)
            }
        }

        if (roomAnimating) {
            val roomAnimT = ((System.nanoTime() - roomAnimStartNanos).toFloat() / ROOM_ANIM_DURATION_NANOS).coerceIn(0f, 1f)
            if (roomAnimT >= 1f) {
                System.arraycopy(cubeOrientation4, 0, effectiveCubeOrientation4, 0, 16)
                roomAnimating = false
                drainTwistQueueIfIdle()
            } else {
                setPlaneRotation4(roomAnimDeltaRot4, roomAnimPlaneA, roomAnimPlaneB, roomAnimAngleDeg * roomAnimT)
                mat4MatMul(effectiveCubeOrientation4, roomAnimDeltaRot4, roomAnimBefore)
            }
        } else if (snapAnimating) {
            // Room stays visually frozen at its pre-compensation appearance for the whole hop --
            // see snapViewToNearestCardinalOrientation's doc. cubeOrientation4 has already been
            // mutated (synchronously, at call time); this just delays *revealing* that until the
            // hop above finishes, at which point this branch stops running and the plain else
            // below takes over, showing the already-current cubeOrientation4.
            System.arraycopy(snapEffectiveHold, 0, effectiveCubeOrientation4, 0, 16)
        } else {
            System.arraycopy(cubeOrientation4, 0, effectiveCubeOrientation4, 0, 16)
        }

        val (dragYaw, dragPitch) = drainDragDelta()
        var dYaw = dragYaw
        var dPitch = dragPitch
        if (stickX != 0f || stickY != 0f) {
            dYaw += stickX * STICK_DEG_PER_FRAME
            dPitch += stickY * STICK_DEG_PER_FRAME
        }
        if (dYaw != 0f || dPitch != 0f) applyScreenRelativeRotation(dYaw, dPitch)

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // Same for every sticker this frame (see normalMat3's doc) -- upload once here rather
        // than inside the per-sticker loop below.
        for (k in ROTATION_PART_INDICES.indices) normalMat3[k] = viewOrientation3[ROTATION_PART_INDICES[k]]
        GLES30.glUniformMatrix3fv(uNormalMatrixLoc, 1, false, normalMat3, 0)

        var animT = 0f
        var animDone = false
        if (animating) {
            // Dynamic twist speed (added 2026-08-04, same formula Hyperspeedcube uses for its own
            // twist queue): twistQueueMax is how deep the backlog got during this unbroken burst
            // of queued twists, not the live queue size, so speed doesn't dip back down as entries
            // drain -- a burst blows through at a consistently fast, compounding rate instead of
            // slowing down again after the first couple catch up. 0 (no backlog ever) keeps
            // speedMod at exactly 1x -- a solo twist always plays at the normal, tuned
            // ANIM_DURATION_NANOS pace. coerceIn below already handles an overshooting speedMod by
            // just completing the twist instantly this frame, same as a huge delta would.
            val speedMod = if (twistQueueMax > 0) exp(twistQueueMax * EXP_TWIST_SPEED_FACTOR) else 1f
            animT = (((System.nanoTime() - animStartNanos).toFloat() / ANIM_DURATION_NANOS) * speedMod).coerceIn(0f, 1f)
            animDone = animT >= 1f
        }

        // --- Pass 1: compute every visible sticker's real per-vertex 4D geometry into
        // dynamicVertexData (pure CPU math, no GL calls) -- see that field's doc and
        // HypercubeGeometry's class doc for why each corner is projected individually now
        // instead of translating/scaling a rigid mesh.
        var numStickersToDraw = 0
        var writeIndex = 0
        val after = animAfter
        for (i in HypercubeGeometry.HOME_POSITIONS.indices) {
            val home = HypercubeGeometry.HOME_POSITIONS[i]
            // Filtered-out pieces are no longer skipped outright -- they're dimmed the same way
            // an unselected piece is (see frameEmphasizedCell below), so a filter still lets you
            // see roughly where hidden pieces are instead of punching a hole in the puzzle.
            val filterDimmed = activeFilterSet
                ?.let { !it.isPieceVisible(activeFilterIndex, activeFilterStep, home) } ?: false

            val base = i * 20
            val src = when {
                !animating -> currentTransforms
                animAffected!![i] -> animBefore!!
                else -> after!!
            }

            for (k in 0 until 4) pos4[k] = src[base + k]
            for (k in 0 until 16) pieceOrient4[k] = src[base + 4 + k]

            if (animating && animAffected!![i]) {
                // src above is animBefore for an affected piece, so pieceOrient4 at this point is
                // exactly its pre-twist orientation -- capture it before the interpolated rotation
                // below overwrites it, needed by the pre-pass to resolve a twisting piece's "side"
                // stickers' pre-twist slot (see pieceOrientBefore4's doc).
                System.arraycopy(pieceOrient4, 0, pieceOrientBefore4, 0, 16)

                if (animKind == AnimKind.EDGE) {
                    setEdgeRotation4(animRot4, animEdgeExcludedAxis, animEdgeAxis1, animEdgeSign1, animEdgeAxis2, animEdgeSign2, 180f * animT)
                } else {
                    setPlaneRotation4(animRot4, animPlaneA, animPlaneB, animAngleDeg * animT)
                }
                mat4VecMul(animatedPos4, animRot4, pos4)
                mat4MatMul(animatedOrient4, animRot4, pieceOrient4)
                System.arraycopy(animatedPos4, 0, pos4, 0, 4)
                System.arraycopy(animatedOrient4, 0, pieceOrient4, 0, 16)
            }

            mat4VecMul(cameraPos4, effectiveCubeOrientation4, pos4)
            // This piece's full current-orientation-to-room-space rotation, reused for every one
            // of its (up to 4) stickers' 24 corners below -- computed once per piece, not per
            // corner, since it doesn't depend on which sticker/corner is being resolved.
            mat4MatMul(pieceToRoom4, effectiveCubeOrientation4, pieceOrient4)

            val homeCoords = intArrayOf(home.x, home.y, home.z, home.w)

            // Pre-pass: resolve each of this piece's (up to 4) stickers' current room slot once,
            // both to know whether this whole piece counts as "touching" frameEmphasizedCell (see
            // emphasizedCell's doc -- a selected cell's neighboring stickers on the *same* piece
            // stay fully visible too, not just the stickers literally sitting in that cell) and to
            // avoid resolving cameraDir4 a second time in the geometry loop below.
            //
            // While a room rotation is in flight, "touching" is checked against each sticker's
            // *pre*-rotation slot (via axisSlotAxisBefore/SignBefore, resolved below using
            // roomAnimBefore) instead of its live, currently-animating slot -- re-resolving live
            // is unstable specifically during this animation, since re-resolving *which native
            // cell is at frameEmphasizedCell's slot* is exactly what the rotation is in the middle
            // of changing. An earlier attempt compared each sticker's *home*-position identity
            // (cellFor(axisIdx, homeCoord), i.e. that sticker's permanent color) against "which
            // color is at that slot" -- two different concepts that only happen to agree on a
            // freshly-solved puzzle; on any scrambled puzzle they're unrelated, which is why that
            // attempt lit up scattered, unrelated stickers across many different cells instead of
            // just the selected one. Freezing the *slot resolution itself* to its pre-rotation
            // value (rather than switching to a native-identity comparison) keeps this a purely
            // positional "was this sticker actually at the selected wall right before the
            // rotation started" question, stable for the whole animation regardless of scramble
            // state.
            var pieceTouchesEmphasized = frameEmphasizedCell == null
            for (axisIdx in 0 until 4) {
                val homeCoord = homeCoords[axisIdx]
                if (homeCoord == 0) {
                    axisSlotAxis[axisIdx] = -1
                    continue
                }
                homeDir4[0] = 0f; homeDir4[1] = 0f; homeDir4[2] = 0f; homeDir4[3] = 0f
                homeDir4[axisIdx] = homeCoord.toFloat()
                mat4VecMul(currentDir4, pieceOrient4, homeDir4)
                mat4VecMul(cameraDir4, effectiveCubeOrientation4, currentDir4)
                var slotAxis = -1
                var slotSign = 0
                for (j in 0 until 4) {
                    if (abs(cameraDir4[j]) > 0.5f) {
                        slotAxis = j
                        slotSign = if (cameraDir4[j] > 0) 1 else -1
                        break
                    }
                }
                axisSlotAxis[axisIdx] = slotAxis
                axisSlotSign[axisIdx] = slotSign

                // A whole-puzzle rotation (Select+face button) changes cubeOrientation4 in a
                // plane that can flip *any* piece's dominant axis partway through, and an ordinary
                // twist does the same to a twisting piece's "side" stickers (any sticker whose own
                // identity axis isn't the twist's own axis -- only the sticker aligned with the
                // twist axis itself keeps a constant dominant axis, e.g. R's own sticker stays "R"
                // throughout an R-twist; every other sticker on a twisting piece genuinely swings
                // from one neighboring cell to another as part of the twist). Both cases resolve
                // this sticker's slot against the animation's *pre*-rotation orientation alone
                // (roomAnimBefore / pieceOrientBefore4) -- reused below both for faceCenter4 (which
                // rotates forward from that single endpoint by the same real rotation matrix the
                // piece's own position already uses, rather than snapping to whichever slot is
                // instantaneously dominant) and, for the roomAnimating case, for the emphasis check
                // above. roomAnimating and a twist's animating can never both be true (see the
                // scratch buffers' shared doc above), so this is a plain else-if, not a nested/
                // combined case.
                if (roomAnimating) {
                    mat4VecMul(cameraDirBefore4, roomAnimBefore, currentDir4)
                    var slotAxisBefore = -1
                    var slotSignBefore = 0
                    for (j in 0 until 4) {
                        if (abs(cameraDirBefore4[j]) > 0.5f) {
                            slotAxisBefore = j
                            slotSignBefore = if (cameraDirBefore4[j] > 0) 1 else -1
                            break
                        }
                    }
                    axisSlotAxisBefore[axisIdx] = slotAxisBefore
                    axisSlotSignBefore[axisIdx] = slotSignBefore
                    if (frameEmphasizedCell != null && cellFor(slotAxisBefore, slotSignBefore) == frameEmphasizedCell) {
                        pieceTouchesEmphasized = true
                    }
                } else {
                    if (frameEmphasizedCell != null && cellFor(slotAxis, slotSign) == frameEmphasizedCell) {
                        pieceTouchesEmphasized = true
                    }
                }
                if (animating && animAffected!![i]) {
                    // Deliberately resolved directly from currentDirTwistBefore4 (room space, not
                    // camera space) -- unlike the roomAnimating branch above, this needs to stay
                    // in the same room-space basis animRot4 itself operates in, so the render loop
                    // can rotate it forward with animRot4 first and only apply
                    // effectiveCubeOrientation4 (constant for the whole twist) at the very end,
                    // mirroring cameraPos4's own two-step derivation exactly.
                    mat4VecMul(currentDirTwistBefore4, pieceOrientBefore4, homeDir4)
                    var slotAxisBefore = -1
                    var slotSignBefore = 0
                    for (j in 0 until 4) {
                        if (abs(currentDirTwistBefore4[j]) > 0.5f) {
                            slotAxisBefore = j
                            slotSignBefore = if (currentDirTwistBefore4[j] > 0) 1 else -1
                            break
                        }
                    }
                    axisSlotAxisBefore[axisIdx] = slotAxisBefore
                    axisSlotSignBefore[axisIdx] = slotSignBefore
                }
            }
            val pieceAlpha = (if (pieceTouchesEmphasized) 1f else SELECTION_DIM_ALPHA) *
                (if (filterDimmed) FILTER_DIM_ALPHA else 1f)

            for (axisIdx in 0 until 4) {
                val homeCoord = homeCoords[axisIdx]
                if (homeCoord == 0) continue
                val slotAxis = axisSlotAxis[axisIdx]
                val slotSign = axisSlotSign[axisIdx]
                // O slot: never rendered -- restored after a mistaken removal. This isn't a net-
                // only concern: MC4D's own pipeline explicitly culls the analogous "front" cell
                // (PipelineUtils.computeFrame's front-cell-cull step) before it ever reaches
                // rendering, because it's the one cell whose W coordinate approaches (and can
                // exceed) EYE_W_DIST, blowing its `stickerScale` up toward/through infinity. O is
                // genuinely "outside of everything" under a true perspective projection too, not
                // just under the flat net.
                if (slotAxis == AXIS_W && slotSign > 0) continue
                // faceCenter4: a plain unit vector along this sticker's *current* cell's own
                // defining axis (e.g. (1,0,0,0) for R, (0,0,0,-1) for I) -- matching MagicCube4D's
                // actual algorithm (PolytopePuzzleDescription.computeStickerVertsAtRest): shrink
                // this piece's true room position toward that fixed point by FACE_SHRINK, rather
                // than pushing cells out to an arbitrary anchor distance.
                //
                // During a whole-puzzle rotation, or while this piece is mid-twist, faceCenter4 is
                // rotated *forward* from its pre-animation assignment (see the pre-pass above) by
                // the exact same real rotation matrix the piece's own position/orientation already
                // uses, rather than snapped to whichever slot is instantaneously dominant, or (an
                // earlier version of this fix) linearly blended toward a separately-resolved
                // endpoint -- a linear blend of two unit vectors doesn't trace the same arc a true
                // rotation does, which read as subtly "not quite 4D" motion even after the
                // discrete-snap bug was fixed (reported by a Hyperspeedcube community member after
                // the v0.6.1 release).
                if (roomAnimating) {
                    val sab = axisSlotAxisBefore[axisIdx]
                    val ssb = axisSlotSignBefore[axisIdx]
                    faceCenterBefore4[0] = if (sab == AXIS_X) ssb.toFloat() else 0f
                    faceCenterBefore4[1] = if (sab == AXIS_Y) ssb.toFloat() else 0f
                    faceCenterBefore4[2] = if (sab == AXIS_Z) ssb.toFloat() else 0f
                    faceCenterBefore4[3] = if (sab == AXIS_W) ssb.toFloat() else 0f
                    // roomAnimDeltaRot4 rotates *from* the pre-rotation camera space
                    // (roomAnimBefore, the same space faceCenterBefore4 above was resolved in) *to*
                    // the current partial-rotation camera space -- exactly the same transform
                    // effectiveCubeOrientation4 = roomAnimDeltaRot4 * roomAnimBefore applies to
                    // every piece's own position.
                    mat4VecMul(faceCenter4, roomAnimDeltaRot4, faceCenterBefore4)
                } else if (animating && animAffected!![i]) {
                    val sab = axisSlotAxisBefore[axisIdx]
                    val ssb = axisSlotSignBefore[axisIdx]
                    faceCenterBefore4[0] = if (sab == AXIS_X) ssb.toFloat() else 0f
                    faceCenterBefore4[1] = if (sab == AXIS_Y) ssb.toFloat() else 0f
                    faceCenterBefore4[2] = if (sab == AXIS_Z) ssb.toFloat() else 0f
                    faceCenterBefore4[3] = if (sab == AXIS_W) ssb.toFloat() else 0f
                    // animRot4 (the piece's own real partial-twist rotation, already computed
                    // above for pos4/pieceOrient4) rotates faceCenterBefore4 the same way in room
                    // space first, then effectiveCubeOrientation4 (constant for the whole twist)
                    // carries it into camera space -- the same two-step pipeline cameraPos4 itself
                    // goes through.
                    mat4VecMul(faceCenterRoomNow4, animRot4, faceCenterBefore4)
                    mat4VecMul(faceCenter4, effectiveCubeOrientation4, faceCenterRoomNow4)
                } else {
                    faceCenter4[0] = if (slotAxis == AXIS_X) slotSign.toFloat() else 0f
                    faceCenter4[1] = if (slotAxis == AXIS_Y) slotSign.toFloat() else 0f
                    faceCenter4[2] = if (slotAxis == AXIS_Z) slotSign.toFloat() else 0f
                    faceCenter4[3] = if (slotAxis == AXIS_W) slotSign.toFloat() else 0f
                }

                val colorCell = cellFor(axisIdx, homeCoord)
                val color = HypercubeGeometry.CELL_COLORS[colorCell.ordinal]
                val localOffsets = HypercubeGeometry.LOCAL_OFFSETS_BY_AXIS[axisIdx]

                // Every one of this sticker's 24 corners (6 faces x 4, unshared) is a real 4D
                // point: rotate its local body-frame offset into room space, add the piece's true
                // position, face-shrink, then perspective-divide -- the same pipeline every other
                // point in the scene goes through, just once per corner instead of once per
                // sticker-center.
                for (corner in 0 until HypercubeGeometry.VERTICES_PER_STICKER) {
                    val lo = corner * 4
                    localCorner4[0] = localOffsets[lo]; localCorner4[1] = localOffsets[lo + 1]
                    localCorner4[2] = localOffsets[lo + 2]; localCorner4[3] = localOffsets[lo + 3]
                    mat4VecMul(roomCorner4, pieceToRoom4, localCorner4)

                    shrunkCorner4[0] = faceCenter4[0] + (cameraPos4[0] + roomCorner4[0] - faceCenter4[0]) * FACE_SHRINK
                    shrunkCorner4[1] = faceCenter4[1] + (cameraPos4[1] + roomCorner4[1] - faceCenter4[1]) * FACE_SHRINK
                    shrunkCorner4[2] = faceCenter4[2] + (cameraPos4[2] + roomCorner4[2] - faceCenter4[2]) * FACE_SHRINK
                    shrunkCorner4[3] = faceCenter4[3] + (cameraPos4[3] + roomCorner4[3] - faceCenter4[3]) * FACE_SHRINK

                    // MagicCube4D's own formula (PipelineUtils.computeFrame): `w = eyeW - vert.w;
                    // vert.xyz *= eyeW/w` -- one shared divide for every corner of every slot.
                    val scale = EYE_W_DIST / (EYE_W_DIST - shrunkCorner4[3])
                    val po = corner * 3
                    stickerCornerPositions[po] = shrunkCorner4[0] * scale * SPACING
                    stickerCornerPositions[po + 1] = shrunkCorner4[1] * scale * SPACING
                    stickerCornerPositions[po + 2] = shrunkCorner4[2] * scale * SPACING
                }

                // Per-face normals computed from the cross product of two edges of the
                // *already-projected* face (MC4D's own PipelineUtils.computeFrame does the
                // equivalent for its brightness step) -- correct regardless of how much a given
                // face ends up perspective-distorted, unlike a baked constant normal.
                val stickerBase = writeIndex
                for (face in 0 until 6) {
                    val v0 = face * 4 * 3
                    val v1 = v0 + 3
                    val v2 = v0 + 6
                    for (k in 0 until 3) {
                        faceEdge1[k] = stickerCornerPositions[v1 + k] - stickerCornerPositions[v0 + k]
                        faceEdge2[k] = stickerCornerPositions[v2 + k] - stickerCornerPositions[v0 + k]
                    }
                    faceNormalScratch[0] = faceEdge1[1] * faceEdge2[2] - faceEdge1[2] * faceEdge2[1]
                    faceNormalScratch[1] = faceEdge1[2] * faceEdge2[0] - faceEdge1[0] * faceEdge2[2]
                    faceNormalScratch[2] = faceEdge1[0] * faceEdge2[1] - faceEdge1[1] * faceEdge2[0]
                    val len = sqrt(
                        faceNormalScratch[0] * faceNormalScratch[0] +
                            faceNormalScratch[1] * faceNormalScratch[1] +
                            faceNormalScratch[2] * faceNormalScratch[2],
                    ).let { if (it > 1e-8f) it else 1f }
                    faceNormalScratch[0] /= len; faceNormalScratch[1] /= len; faceNormalScratch[2] /= len

                    for (vertInFace in 0 until 4) {
                        val corner = face * 4 + vertInFace
                        val po = corner * 3
                        dynamicVertexData[writeIndex++] = stickerCornerPositions[po]
                        dynamicVertexData[writeIndex++] = stickerCornerPositions[po + 1]
                        dynamicVertexData[writeIndex++] = stickerCornerPositions[po + 2]
                        dynamicVertexData[writeIndex++] = faceNormalScratch[0]
                        dynamicVertexData[writeIndex++] = faceNormalScratch[1]
                        dynamicVertexData[writeIndex++] = faceNormalScratch[2]
                        dynamicVertexData[writeIndex++] = color[0]
                        dynamicVertexData[writeIndex++] = color[1]
                        dynamicVertexData[writeIndex++] = color[2]
                    }
                }

                stickerByteOffsets[numStickersToDraw] = stickerBase * 4
                stickerAlphas[numStickersToDraw] = pieceAlpha
                numStickersToDraw++
            }
        }

        // --- Upload once, then draw. ---
        dynamicVertexBuffer.position(0)
        dynamicVertexBuffer.put(dynamicVertexData, 0, writeIndex)
        dynamicVertexBuffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dynamicVboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, writeIndex * 4, dynamicVertexBuffer)

        // Shared for every sticker this frame -- positions are already baked into room space
        // above, so the only transform left is the ordinary 3D orbit + camera projection.
        Matrix.multiplyMM(mvpMatrix, 0, viewProjMatrix, 0, viewOrientation3, 0)
        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)

        // Opaque stickers first (normal depth write), then de-emphasized/translucent ones with
        // depth *write* off (test still on) -- so a translucent sticker never wrongly occludes
        // whatever's behind it in the depth buffer, while still being correctly hidden behind any
        // opaque one in front of it. Not a full back-to-front sort of the translucent stickers
        // among themselves -- acceptable for how this is actually used (mostly whole cells fading
        // together, not deeply overlapping layers); revisit if that turns out to look wrong.
        val stride = HypercubeGeometry.FLOATS_PER_VERTEX * 4
        for (pass in 0 until 2) {
            val wantOpaque = pass == 0
            GLES30.glDepthMask(wantOpaque)
            for (s in 0 until numStickersToDraw) {
                val alpha = stickerAlphas[s]
                if ((alpha >= 0.999f) != wantOpaque) continue
                val byteOffset = stickerByteOffsets[s]
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, byteOffset)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, byteOffset + 12)
                GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, byteOffset + 24)
                GLES30.glUniform1f(uAlphaLoc, alpha)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, HypercubeGeometry.INDICES.size, GLES30.GL_UNSIGNED_SHORT, 0)
            }
        }
        GLES30.glDepthMask(true)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)

        if (animDone) {
            currentTransforms = after!!
            animating = false
            animBefore = null
            animAfter = null
            animAffected = null
            drainTwistQueueIfIdle()
        }
    }

    /** Which [Cell4] a direction along [axisIdx] with sign [homeCoord] represents -- a pure
     * axis+sign lookup, so [onDrawFrame] reuses it for two different directions: the sticker's
     * fixed home direction (its permanent color identity) and its current camera-space slot
     * direction (which wall it's presently drawn on, for selection/highlighting). */
    private fun cellFor(axisIdx: Int, homeCoord: Int): Cell4 = when (axisIdx) {
        AXIS_X -> if (homeCoord > 0) Cell4.R else Cell4.L
        AXIS_Y -> if (homeCoord > 0) Cell4.U else Cell4.D
        AXIS_Z -> if (homeCoord > 0) Cell4.F else Cell4.B
        else -> if (homeCoord > 0) Cell4.O else Cell4.I
    }


    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)

        val programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vertexShader)
        GLES30.glAttachShader(programId, fragmentShader)
        GLES30.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linkStatus, 0)
        check(linkStatus[0] != 0) {
            "Program link failed: ${GLES30.glGetProgramInfoLog(programId)}"
        }
        return programId
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        check(compileStatus[0] != 0) {
            "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    companion object {
        /** True combinatorial max: 8 cells x 27 pieces each, since every piece contributes
         * exactly one sticker instance per cell it touches (see [HypercubeGeometry]'s class doc).
         * O's 27 are always culled at render time but this is sized for the full count anyway --
         * simpler than computing the exact post-cull maximum, and the memory cost is trivial. */
        const val MAX_STICKERS = 8 * 27

        // Doubled from the original 1.2 -- right-stick camera orbit felt much too slow relative to
        // touch-drag (which has no equivalent scaling knob); a stick is a variable control users
        // already expect to modulate with how far they push it, so speeding up the baseline is a
        // reasonable first try rather than something that needs to stay subtle. Revisit if it
        // turns out to feel too fast rather than "double" being exactly right.
        private const val STICK_DEG_PER_FRAME = 2.4f
        private const val ANIM_DURATION_NANOS = 220_000_000L // 220ms

        /** Higher = faster exponential speed-up per extra twist queued during a burst -- see
         * onDrawFrame's speedMod. Same value and formula Hyperspeedcube's own dynamic twist queue
         * uses (crates/hyperpuzzle_view/src/animations/twist.rs, EXP_TWIST_FACTOR), confirmed by
         * reading its source rather than guessing (2026-08-04): each extra queued twist multiplies
         * speed by e^0.5 (~1.65x), compounding, so a deep backlog drains fast rather than playing
         * out one full-speed animation per queued twist. */
        private const val EXP_TWIST_SPEED_FACTOR = 0.5f

        /** Duration of the eased view-realignment in [snapViewToNearestCardinalOrientation] --
         * quick enough to not feel laggy, but long enough (a handful of frames at 60fps) to
         * read as a motion rather than a jarring instant jump. */
        private const val SNAP_ANIM_DURATION_NANOS = 100_000_000L // 100ms

        /** Duration of [requestCameraRotate90]'s room-turn animation -- shorter than a twist's
         * [ANIM_DURATION_NANOS], since this is a quick "see it snap into place" motion rather
         * than a puzzle move to actually register mentally, and it shouldn't add drag to rapid
         * repeated presses (e.g. the left trigger). */
        private const val ROOM_ANIM_DURATION_NANOS = 130_000_000L // 130ms

        // -45 puts F and R (and their opposite mirrors, L and B) symmetrically either side of
        // center at equal depth; 35.264 (arctan(1/sqrt(2)), the standard "true isometric" tilt)
        // makes all 6 walls land on a regular hexagon around the center once combined with that
        // yaw -- see the two-call ordering in onSurfaceCreated and INITIAL_VIEW_ORIENTATION below,
        // both required for this to come out exactly symmetric rather than approximately so.
        private const val INITIAL_YAW_DEG = -45f
        private const val INITIAL_PITCH_DEG = 35.264f

        /** Spacing between adjacent stickers within one cell's 3x3x3 block. */
        private const val SPACING = 1.0f

        /** MagicCube4D's own "Face Shrink" -- how much each corner of each sticker is pulled
         * toward its cell's fixed face-center point (see [onDrawFrame]'s `faceCenter4`/
         * `shrunkCorner4`), 1.0 meaning no pull at all (cells touch their neighbors seamlessly)
         * and smaller values pulling cells inward, creating the gap between them. The *formula*
         * matches `PolytopePuzzleDescription.computeStickerVertsAtRest`'s real algorithm exactly
         * (confirmed via that class's actual source, not guessed), but this constant is tuned
         * lower than MC4D's own default (0.4) -- David wanted enough separation that some camera
         * angle always shows every cell fully unobscured by its neighbors, which 0.4 didn't quite
         * give in this app's geometry (confirmed 2026-07-29: 0.4 left far cells partly hidden
         * behind near ones at every rotation tried; 0.22 reliably has an unobscured angle). Not
         * user-adjustable yet -- a real Settings slider is a known future step, this is just a
         * static value David is happy with for now. */
        private const val FACE_SHRINK = 0.22f

        /** The 4D analog of camera distance/FOV -- MagicCube4D's "Eye W Scale" -- controlling how
         * strongly a piece's own W coordinate (its depth within whichever cell it's currently in,
         * after [FACE_SHRINK] pulls it toward that cell's face center) tapers its projected
         * size/position, via the same `eyeW / (eyeW - w)` formula MC4D's `PipelineUtils.
         * computeFrame` uses. */
        private const val EYE_W_DIST = 2.0f

        /** Opacity for a piece that doesn't touch [emphasizedCell] while something *is*
         * emphasized (a cell selected in STICK mode, or permanently I in RKT mode) -- "mostly
         * transparent, not gone," per David's spec (2026-07-29): low enough to clearly read as
         * de-emphasized, high enough to still see roughly where those pieces are. Starting guess,
         * meant to be tuned visually together with [FILTER_DIM_ALPHA]. */
        private const val SELECTION_DIM_ALPHA = 0.10f

        /** Opacity for a piece a filter series hasn't revealed yet (see [activeFilterSet]/
         * [activeFilterIndex]/[activeFilterStep]) -- a separate constant from [SELECTION_DIM_ALPHA] since David
         * explicitly wants to tune the two "cases" (selection-fade vs. filter-fade) independently,
         * even though they start at the same value. */
        private const val FILTER_DIM_ALPHA = 0.10f

        private const val MIN_DISTANCE = 3f
        private const val MAX_DISTANCE = 25f

        // See onSurfaceChanged's frustumM doc -- fraction of the near-plane half-height to shear
        // the projection window by, to visually recenter the default view's top-heavy rendering
        // (U cell bigger/closer than D). Tuned empirically against a real screenshot; revisit if
        // INITIAL_YAW_DEG/INITIAL_PITCH_DEG or the room's own geometry ever change, since either
        // could shift which cell ends up closer to the camera.
        private const val VERTICAL_CONTENT_SHIFT_FRACTION = 0.09f

        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_Z = 2
        const val AXIS_W = 3

        /** How far off-center (0..1 stick magnitude) counts as "moving significantly" for
         * [updateCell4Selection]'s snap-on-deflect trigger -- higher than the plain deadzone
         * [GamepadInputHandler] already applies, so a small/incidental deflection can still
         * select a cell without yanking the view around every time. */
        private const val SIGNIFICANT_STICK_MAGNITUDE = 0.5f

        /** Column-major indices of the 3x3 rotation part within a 4x4 GL matrix (see
         * [lerpAndOrthonormalizeRotation]). */
        private val ROTATION_PART_INDICES = intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)

        /** The app's default startup tilt for [viewOrientation3] -- must match the *order* of the
         * two separate applyScreenRelativeRotation calls in onSurfaceCreated (yaw about the fixed
         * Y axis, composed on the left, *then* pitch about the fixed X axis, composed on the left
         * of that) exactly, i.e. `rotX * rotY`, not `rotY * rotX`: rotating about Y can never move
         * a vector already lying exactly along Y, which is what keeps U/D vertical -- but only if
         * pitch is the outermost (last-applied, leftmost) rotation. Getting this backwards here
         * wouldn't change how the startup view actually looks (onSurfaceCreated doesn't use this
         * constant directly), only make [snapViewToNearestCardinalOrientation] snap to a visibly
         * different, unsymmetric orientation the instant it's triggered. */
        private val INITIAL_VIEW_ORIENTATION: FloatArray = run {
            val rotX = FloatArray(16)
            val rotY = FloatArray(16)
            val combined = FloatArray(16)
            Matrix.setRotateM(rotX, 0, INITIAL_PITCH_DEG, 1f, 0f, 0f)
            Matrix.setRotateM(rotY, 0, INITIAL_YAW_DEG, 0f, 1f, 0f)
            Matrix.multiplyMM(combined, 0, rotX, 0, rotY, 0)
            combined
        }

        /** The 24 orientation-preserving symmetries of a cube -- every signed permutation
         * matrix (one +-1 entry per row/column) with determinant +1 -- as 4x4 GL matrices.
         * Identical construction to [CubeRenderer.CARDINAL_ROTATIONS]; used by
         * [snapViewToNearestCardinalOrientation] both to find the closest axis-aligned view for
         * the camera to settle back from, and as the compensating rotation applied to
         * [cubeOrientation4] so the net appearance matches that view -- see that function's doc. */
        private val CARDINAL_ROTATIONS: List<FloatArray> = buildList {
            val permutations = listOf(
                intArrayOf(0, 1, 2), intArrayOf(0, 2, 1),
                intArrayOf(1, 0, 2), intArrayOf(1, 2, 0),
                intArrayOf(2, 0, 1), intArrayOf(2, 1, 0),
            )
            for (perm in permutations) {
                for (sx in intArrayOf(-1, 1)) {
                    for (sy in intArrayOf(-1, 1)) {
                        for (sz in intArrayOf(-1, 1)) {
                            val signs = intArrayOf(sx, sy, sz)
                            val m = Array(3) { FloatArray(3) }
                            for (row in 0..2) {
                                m[row][perm[row]] = signs[row].toFloat()
                            }
                            val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
                            if (det > 0f) {
                                val out = FloatArray(16)
                                out[0] = m[0][0]; out[1] = m[1][0]; out[2] = m[2][0]; out[3] = 0f
                                out[4] = m[0][1]; out[5] = m[1][1]; out[6] = m[2][1]; out[7] = 0f
                                out[8] = m[0][2]; out[9] = m[1][2]; out[10] = m[2][2]; out[11] = 0f
                                out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
                                add(out)
                            }
                        }
                    }
                }
            }
        }.also { check(it.size == 24) { "expected 24 cardinal rotations, got ${it.size}" } }

        /** Each [CARDINAL_ROTATIONS] symmetry re-expressed relative to
         * [INITIAL_VIEW_ORIENTATION] (i.e. INITIAL_VIEW_ORIENTATION * C), so snapping preserves
         * the app's nice corner-on tilt instead of flattening to a single wall viewed dead-on. */
        private val CARDINAL_TARGETS: List<FloatArray> = CARDINAL_ROTATIONS.map { c ->
            val out = FloatArray(16)
            Matrix.multiplyMM(out, 0, INITIAL_VIEW_ORIENTATION, 0, c, 0)
            out
        }

        private fun setIdentity4(m: FloatArray) {
            for (i in 0 until 16) m[i] = 0f
            for (i in 0 until 4) m[i * 4 + i] = 1f
        }

        /** Row-major 4x4 rotation in the (a,b) plane by [angleDeg], identity elsewhere. */
        private fun setPlaneRotation4(out: FloatArray, a: Int, b: Int, angleDeg: Float) {
            setIdentity4(out)
            val rad = Math.toRadians(angleDeg.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            out[a * 4 + a] = c; out[a * 4 + b] = -s
            out[b * 4 + a] = s; out[b * 4 + b] = c
        }

        /** Row-major 4x4 rotation by [angleDeg] around the 3D axis through spatial axes
         * ([axis1],[sign1])/([axis2],[sign2]) -- e.g. Y/+1, Z/+1 for the UF/DB diagonal -- identity
         * on [excludedAxis]'s row/column, the twisted cell's own native axis (see
         * [applyEdgeTwistInternal]'s doc for why this can be any of the 4 native axes, not always
         * W -- a real crash before this was fixed, 2026-08-10: this used to hardcode W, correct
         * only while native I itself sat in the room's I slot). [axis1]/[axis2]/[excludedAxis] are
         * always 3 distinct values; the 4th native axis (the "pivot" -- e.g. X for the UF/DB
         * example, whichever of the 3 available axes isn't part of the diagonal) still fully
         * participates in the rotation below, it's just not part of `k` -- see [applyEdgeTwistInternal]'s
         * verification notes for why the pivot ends up correctly rotated (negated at 180 degrees)
         * anyway, purely from the `-I` term, without needing a nonzero k-component of its own.
         *
         * Unlike [setPlaneRotation4] (confined to a single coordinate plane), this is the
         * general-purpose Rodrigues' rotation formula -- R = I*cos + sin*[k]_x + (1-cos)*(k outer
         * k) -- computed in a *local* 3-space (the 3 native axes other than [excludedAxis], in
         * ascending native-index order) using unit axis k = normalize(sign1 at axis1, sign2 at
         * axis2) in that local space, then scattered back into the appropriate rows/columns of
         * [out]. Used at [angleDeg] = 180*animT for [applyEdgeTwistInternal]'s single continuous
         * edge-twist animation (as opposed to 3 chained 90-degree ones) -- confirmed by hand at
         * 180 degrees (cos=-1, sin=0) to reduce to exactly the matrices [EDGE_TWIST_DECOMPOSITIONS]'
         * brute-force search independently verified. */
        private fun setEdgeRotation4(out: FloatArray, excludedAxis: Int, axis1: Int, sign1: Int, axis2: Int, sign2: Int, angleDeg: Float) {
            val localAxes = IntArray(3)
            var localCount = 0
            for (axis in 0 until 4) if (axis != excludedAxis) localAxes[localCount++] = axis
            val localIndexOf = IntArray(4) { -1 }
            for (i in 0 until 3) localIndexOf[localAxes[i]] = i

            val k = floatArrayOf(0f, 0f, 0f)
            k[localIndexOf[axis1]] += sign1.toFloat()
            k[localIndexOf[axis2]] += sign2.toFloat()
            val len = sqrt(k[0] * k[0] + k[1] * k[1] + k[2] * k[2])
            k[0] /= len; k[1] /= len; k[2] /= len
            val rad = Math.toRadians(angleDeg.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val omc = 1f - c
            val rLocal = arrayOf(
                floatArrayOf(c + k[0] * k[0] * omc, k[0] * k[1] * omc - k[2] * s, k[0] * k[2] * omc + k[1] * s),
                floatArrayOf(k[1] * k[0] * omc + k[2] * s, c + k[1] * k[1] * omc, k[1] * k[2] * omc - k[0] * s),
                floatArrayOf(k[2] * k[0] * omc - k[1] * s, k[2] * k[1] * omc + k[0] * s, c + k[2] * k[2] * omc),
            )

            setIdentity4(out)
            for (i in 0 until 3) {
                for (j in 0 until 3) {
                    out[localAxes[i] * 4 + localAxes[j]] = rLocal[i][j]
                }
            }
        }

        /** Row-major 4x4 matrix multiply: out = a * b. out must not alias a or b. */
        private fun mat4MatMul(out: FloatArray, a: FloatArray, b: FloatArray) {
            for (row in 0 until 4) {
                for (col in 0 until 4) {
                    var sum = 0f
                    for (k in 0 until 4) sum += a[row * 4 + k] * b[k * 4 + col]
                    out[row * 4 + col] = sum
                }
            }
        }

        /** Row-major 4x4 matrix times a 4-vector: out = m * v. out must not alias v. */
        private fun mat4VecMul(out: FloatArray, m: FloatArray, v: FloatArray) {
            for (row in 0 until 4) {
                var sum = 0f
                for (col in 0 until 4) sum += m[row * 4 + col] * v[col]
                out[row] = sum
            }
        }

        /**
         * Fills [out] (column-major GL layout) with an approximation of the rotation [t] of the
         * way from [from] to [to] (both column-major GL rotation matrices), for
         * [snapViewToNearestCardinalOrientation]'s eased snap. A plain per-entry lerp of two
         * rotation matrices isn't itself a rotation matrix (its columns won't stay unit length
         * or perpendicular), so this re-orthonormalizes afterward: normalize column 0, subtract
         * off column 1's projection onto it and normalize that, then take column 2 as their
         * cross product -- guarantees a clean right-handed rotation every frame rather than a
         * true constant-angular-velocity slerp, but for the small, quick snaps this is used for
         * (a few hundred ms at most) the difference isn't visible.
         */
        private fun lerpAndOrthonormalizeRotation(out: FloatArray, from: FloatArray, to: FloatArray, t: Float) {
            for (idx in ROTATION_PART_INDICES) {
                out[idx] = from[idx] + (to[idx] - from[idx]) * t
            }
            out[3] = 0f; out[7] = 0f; out[11] = 0f
            out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f

            var c0x = out[0]; var c0y = out[1]; var c0z = out[2]
            var len = sqrt(c0x * c0x + c0y * c0y + c0z * c0z)
            if (len > 1e-6f) { c0x /= len; c0y /= len; c0z /= len }

            var c1x = out[4]; var c1y = out[5]; var c1z = out[6]
            val dot01 = c1x * c0x + c1y * c0y + c1z * c0z
            c1x -= dot01 * c0x; c1y -= dot01 * c0y; c1z -= dot01 * c0z
            len = sqrt(c1x * c1x + c1y * c1y + c1z * c1z)
            if (len > 1e-6f) { c1x /= len; c1y /= len; c1z /= len }

            val c2x = c0y * c1z - c0z * c1y
            val c2y = c0z * c1x - c0x * c1z
            val c2z = c0x * c1y - c0y * c1x

            out[0] = c0x; out[1] = c0y; out[2] = c0z
            out[4] = c1x; out[5] = c1y; out[6] = c1z
            out[8] = c2x; out[9] = c2y; out[10] = c2z
        }
    }
}
