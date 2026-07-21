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
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders a 3^4 hypercube using the same "unfolded" layout MagicCube4D/Hyperspeedcube default
 * to: 6 of the cells (U/D/L/R/F/B) sit as separate, non-overlapping 3x3x3 blocks arranged like
 * the walls of a room, a 7th (I) is a 3x3x3 block floating at the center, and the 8th (O) is
 * never rendered at all -- from this viewpoint it's the "outside of everything," which has no
 * meaningful position to draw. Ordinary 3D rotation (touch-drag/right-stick -- the left stick
 * is reserved for cell selection, see [updateCell4Selection]) orbits the whole room so you can
 * see each wall in turn, same feel as [CubeRenderer]. Which native cell
 * currently occupies which of these 8 fixed positions is controlled by [cubeOrientation4], a
 * 4D rotation kept restricted to exact 90-degree increments via [requestCameraRotate90] (a full
 * continuous 4D trackball is both hard to use and unnecessary here) -- e.g. rotating the Z-W
 * plane cycles F->I->B->O->F, matching Hyperspeedcube's "send this cell to the center" shortcut.
 *
 * Every one of a piece's 1-4 stickers is rendered independently (see [onDrawFrame]): its color
 * is fixed (the cell that sticker was originally part of), but which of the 8 positions it's
 * drawn at is resolved fresh every frame from `cubeOrientation4 * pieceOrientation * homeDir`,
 * so stickers visually relocate as the camera is rotated in 90-degree steps or the piece itself
 * is twisted.
 *
 * [cubeOrientation4] only ever changes in exact 90-degree steps (via [requestCameraRotate90]),
 * so it never needs to move continuously. The *continuous* touch-drag/right-stick "look around
 * the room" feel is a separate, ordinary 3D rotation, [viewOrientation3], applied uniformly to
 * the whole assembled room (both each sticker's position and its mesh, exactly like
 * [CubeRenderer.cubeOrientation]) after room-local positions are resolved. Keeping these two
 * rotations separate matters: composing continuous rotation into [cubeOrientation4] would (a)
 * make the discrete "which wall is this sticker on" threshold below flip abruptly mid-drag, and
 * (b) never rotate the sticker meshes themselves, since only their positions depended on it.
 */

/** The 4D screen's 3 gamepad left-hand input schemes -- see [HypercubeRenderer.setInputMode].
 * [STICK]: continuous stick-angle cell selection ([HypercubeRenderer.updateCell4Selection]).
 * [PAD]: discrete dpad/L1/L2 step navigation ([HypercubeRenderer.navigateCell4Selection]).
 * [RKT]: no selection at all -- the left-hand controls twist the room's current I slot directly
 * (see [HypercubeRenderer.requestRktITwist]), for executing a fixed, memorized last-phase-of-solve
 * algorithm (hypercube OLL/PLL equivalent) without needing to reselect a cell between twists. */
enum class GamepadInputMode { STICK, PAD, RKT }
class HypercubeRenderer : GLSurfaceView.Renderer {

    // Scaled up from the room's plain size to compensate for the narrower FOV in
    // onSurfaceChanged (a telephoto-style flatter perspective needs a proportionally longer
    // distance to keep the same on-screen framing) -- see that FOV's own doc comment.
    @Volatile private var distance = 38.0f

    // Ordinary 3D-feeling rotation input (touch-drag / left stick) -> XZ/YZ planes.
    @Volatile var stickX: Float = 0f
    @Volatile var stickY: Float = 0f
    private val dragLock = Any()
    private var pendingDragYawDeg = 0f
    private var pendingDragPitchDeg = 0f

    /** Called (on the GL thread) right after a twist/scramble/reset with the new solved state. */
    @Volatile var onStateChanged: ((Boolean) -> Unit)? = null

    /** Called (on the GL thread) right after a twist is applied via [requestTwist] -- not fired
     * by [undoTwist], so a caller (MainActivity) using this to build an undo/log history doesn't
     * see its own undo moves recorded back into that same history. */
    @Volatile var onTwistApplied: ((Cell4, Axis4, Boolean) -> Unit)? = null

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

    // Piece-type filtering: hides whole pieces (all their stickers) by how many of the piece's
    // HOME_POSITIONS coordinates are nonzero -- 4 = corner, 3 = "edge" -- a solve aid for early
    // stages. Sticker count is a permanent piece-type identity (see onDrawFrame), so this is a
    // safe, cheap per-piece check against each piece's *home* position, not its current one.
    @Volatile var hideCorners: Boolean = false
    @Volatile var hideEdges: Boolean = false

    // Persistent left-stick selection state: which room *slot* (axis+sign), not which resolved
    // cell, is selected -- see selectedCell4's doc for why. GL-thread-only (updateCell4Selection
    // is only ever called via queueEvent; so is MainActivity's read of selectedCell4).
    private var selectedRoomAxis = AXIS_Y
    private var selectedRoomSign = 1
    private var stickHeld = false

    /** Which of the 3 left-hand input schemes is active -- see [GamepadInputMode]. Read from the
     * UI thread (MainActivity's on4DNavigate/onLeftStick closures, both running inside their own
     * queueEvent already) and written only via [setInputMode], also GL-thread-only. */
    @Volatile var inputMode: GamepadInputMode = GamepadInputMode.STICK
        private set

    // STICK and PAD each remember their own selected room slot independently -- these hold
    // whichever of those two modes *isn't* currently active's slot, swapped into/out of the
    // single "live" selectedRoomAxis/selectedRoomSign pair above by setInputMode, so switching
    // modes never disturbs the other mode's last selection. PAD defaults to I the first time it
    // activates. RKT doesn't need a parked slot of its own -- its selection is always pinned to R
    // (see setInputMode), never remembered/restored.
    private var parkedStickAxis = AXIS_Y
    private var parkedStickSign = 1
    private var parkedPadAxis = AXIS_W
    private var parkedPadSign = -1

    /** Swaps the outgoing mode's room slot into its own parked field (STICK/PAD only -- RKT has
     * none, see the parked-state fields' doc) and sets up the incoming mode's slot: STICK/PAD
     * restore their own parked slot, RKT always pins to R (AXIS_X, +1) -- see [GamepadInputMode]'s
     * doc for why R specifically. Must run on the GL thread (those fields aren't volatile) --
     * call via queueEvent, same as [updateCell4Selection]/[navigateCell4Selection]. */
    fun setInputMode(mode: GamepadInputMode) {
        if (mode == inputMode) return
        when (inputMode) {
            GamepadInputMode.STICK -> { parkedStickAxis = selectedRoomAxis; parkedStickSign = selectedRoomSign }
            GamepadInputMode.PAD -> { parkedPadAxis = selectedRoomAxis; parkedPadSign = selectedRoomSign }
            GamepadInputMode.RKT -> Unit
        }
        when (mode) {
            GamepadInputMode.STICK -> { selectedRoomAxis = parkedStickAxis; selectedRoomSign = parkedStickSign }
            GamepadInputMode.PAD -> { selectedRoomAxis = parkedPadAxis; selectedRoomSign = parkedPadSign }
            GamepadInputMode.RKT -> { selectedRoomAxis = AXIS_X; selectedRoomSign = 1 }
        }
        inputMode = mode
    }

    /**
     * Mode 2's step-based selection navigation (dpad/L1/L2, see [NavigationButton]), as opposed
     * to mode 1's continuous stick-angle math in [updateCell4Selection]. [targetAxis]/
     * [targetSign] is the pressed button's own room-slot endpoint (e.g. dpad-left -> AXIS_X,-1
     * for L). Always steps toward that endpoint *through* I, one press at a time: already there
     * -> no-op (no wrapping past an endpoint); currently at I -> jump straight to the endpoint;
     * anywhere else (including that endpoint's own opposite, or a different track entirely) ->
     * step back to I first. O is never reachable, since no button's endpoint is ever O.
     */
    fun navigateCell4Selection(targetAxis: Int, targetSign: Int) {
        val atI = selectedRoomAxis == AXIS_W && selectedRoomSign < 0
        when {
            selectedRoomAxis == targetAxis && selectedRoomSign == targetSign -> return
            atI -> { selectedRoomAxis = targetAxis; selectedRoomSign = targetSign }
            else -> { selectedRoomAxis = AXIS_W; selectedRoomSign = -1 }
        }
    }

    /**
     * The actual entry point mode 2's dpad/L1/L2 use (see [MainActivity]'s on4DNavigate wiring)
     * -- [defaultAxis]/[defaultSign] is the button's meaning under the *default*, un-rotated
     * camera tilt (e.g. dpad-left -> AXIS_X,-1 for L), corrected for whichever of the 24 cardinal
     * symmetries the view is *currently* snapped to before handing off to [navigateCell4Selection]
     * -- the same correction mode 1's stick wedges apply (see [applyInverseSnapSymmetry]), just
     * against the live [cameraSnapSymmetry] rather than a per-hold-frozen copy, since a discrete
     * button press has no "hold" to freeze it at the start of. Without this, mode 2 wouldn't
     * respect a camera rotation made earlier (via touch-drag + XW/YW/ZW, or mode 1's stick) --
     * dpad-left would always target room slot L exactly, not whatever's actually on the left
     * visually right now. Also snaps the view first, same as [MainActivity]'s on4DRotationButton
     * wiring, so navigating keeps the puzzle visually tidy even without an active twist.
     */
    fun navigateMode2Selection(defaultAxis: Int, defaultSign: Int) {
        snapViewToNearestCardinalOrientation()
        val (targetAxis, targetSign) = inverseSnapSymmetryTarget(cameraSnapSymmetry, defaultAxis, defaultSign)
        navigateCell4Selection(targetAxis, targetSign)
    }

    /** Which [Cell4] the gamepad's left stick currently has selected for the next twist -- see
     * [updateCell4Selection]. A computed property, re-resolved against the *current*
     * [cubeOrientation4] on every read, rather than a cached value -- otherwise, since a
     * physical stick held perfectly steady fires no new motion events, rotating the room (e.g.
     * via [requestCameraRotate90]) while a selection is being held wouldn't update it until the
     * next stick nudge: the highlight and any subsequent twist would silently act on whichever
     * cell used to be in that slot, not whichever cell is actually there now. This is the
     * *native* occupant's identity (via [nativeCellInRoomSlot]), which is what [requestTwist]
     * needs -- for the on-screen highlight, see [highlightedCell] instead, which is a
     * deliberately different computation.
     */
    val selectedCell4: Cell4 get() = nativeCellInRoomSlot(selectedRoomAxis, selectedRoomSign)

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
        val roomFixAxis2 = if (buttonLiteralAxis.nativeIndex == selectedRoomAxis) AXIS_W else buttonLiteralAxis.nativeIndex
        return Axis4.entries.first { it.nativeIndex == nativeAxisAtRoomAxis(roomFixAxis2) }
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

    /**
     * Which [Cell4] to render brightened -- the selected room slot, while the left stick is
     * held significantly deflected (see `todo-controller-input.md`), null otherwise. Also a
     * computed property for the same live-resolution reason as [selectedCell4] -- but
     * deliberately computed via [cellFor], *not* [selectedCell4]/[nativeCellInRoomSlot], even
     * though they usually agree: [onDrawFrame] labels each sticker's *current* room position
     * with `cellFor(slotAxis, slotSign)`, a fixed axis+sign -> label convention (the wall at
     * +X is always "R"), not that sticker's own native identity. Once the room's been rotated
     * away from its default arrangement (cubeOrientation4 != identity), a native cell's own
     * identity and its current position's label are different things -- e.g. after rotating
     * native I into the "R" wall, that sticker's currentCell is `R` (the wall it's now on), not
     * `I` (what it natively is). Comparing against [selectedCell4] (I's own native identity)
     * would never match anything actually sitting in the selected wall; comparing against this
     * property (the wall's label) does. Always null in [GamepadInputMode.RKT] -- its selection is
     * fixed and never meant to draw attention to itself (see [GamepadInputMode]'s doc).
     */
    val highlightedCell: Cell4?
        get() = when {
            inputMode == GamepadInputMode.RKT -> null
            stickHeld || inputMode == GamepadInputMode.PAD -> cellFor(selectedRoomAxis, selectedRoomSign)
            else -> null
        }

    /** [GamepadInputMode.RKT]'s left-hand controls -- twists whichever native cell the room's I
     * slot *currently* holds (not necessarily literal [Cell4.I], if the room's been rotated
     * earlier -- same room-slot-relative treatment [selectedCell4] gives R for RKT's right-hand
     * buttons, since a memorized algorithm should act on "whatever's in I/R right now", not a
     * specific native cell identity). [roomFixAxis2] is a *room* axis (e.g. `AXIS_Y` for the "U"
     * in "IU"), translated to the native axis [requestTwist] needs the same way
     * [resolveRotationButtonFixAxis2] does for on4DRotationButton -- without this, "IU" would only
     * actually mean IU when the room happens to be at its default orientation, drifting to some
     * other twist entirely once it's been rotated (e.g. via mode 1/2's "move to I"), the same bug
     * that on4DRotationButton had. [prime] is given directly by the caller, already resolved to
     * the exact community-notation twist wanted (e.g. IU vs IU') -- unlike on4DRotationButton,
     * this doesn't go through [MainActivity]'s screen-consistency correction table, since these
     * are fixed, explicit moves for a known algorithm rather than a "make this button feel the
     * same on every cell" mapping. */
    fun requestRktITwist(roomFixAxis2: Int, prime: Boolean) {
        snapViewToNearestCardinalOrientation()
        val fixAxis2 = Axis4.entries.first { it.nativeIndex == nativeAxisAtRoomAxis(roomFixAxis2) }
        requestTwist(nativeCellInRoomSlot(AXIS_W, -1), fixAxis2, prime)
    }

    // GL-thread-only edge-detection state for updateCell4Selection's snap-on-deflect behavior.
    private var stickWasSignificant = false
    private val roomDirScratch4 = FloatArray(4)

    private var program = 0
    private var uMvpLoc = 0
    private var uNormalMatrixLoc = 0
    private var uHighlightLoc = 0
    private var uForceBlackLoc = 0

    // The 3x3 rotation part of viewOrientation3, column-major, re-extracted once per frame (not
    // per-sticker -- every sticker's mesh is always axis-aligned, only its position varies, even
    // mid-twist-animation, so this one matrix correctly transforms every sticker's normals for
    // the whole frame). See onDrawFrame and ROTATION_PART_INDICES.
    private val normalMat3 = FloatArray(9)

    private val indexBuffer: ShortBuffer = ByteBuffer
        .allocateDirect(HypercubeGeometry.INDICES.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply { put(HypercubeGeometry.INDICES); position(0) }

    private val wireIndexBuffer: ShortBuffer = ByteBuffer
        .allocateDirect(HypercubeGeometry.WIREFRAME_INDICES.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply { put(HypercubeGeometry.WIREFRAME_INDICES); position(0) }

    private var indexBufferId = 0
    private var wireIndexBufferId = 0
    private lateinit var stickerVboIds: IntArray // one shared mesh per Cell4 color

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
    private val screenPos = FloatArray(3)

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

    // The one of CARDINAL_ROTATIONS chosen by the most recent snap (identity until the first
    // snap ever happens) -- see snapViewToNearestCardinalOrientation's doc. Updated by *every*
    // snap, including the ones on4DRotationButton triggers on every press purely to keep the
    // view visually tidy -- so this can change without the left stick having moved at all.
    private val cameraSnapSymmetry = FloatArray(16)

    // The symmetry actually used by applyInverseSnapSymmetry -- frozen from cameraSnapSymmetry
    // at the *start* of each stick hold (see updateCell4Selection) and held fixed until the
    // stick is released, deliberately not tracking cameraSnapSymmetry live. Without this, a
    // rotation-button press mid-hold (which re-snaps the camera for its own reasons, e.g. to
    // correct drift from incidental touchscreen contact while also holding a controller) could
    // change cameraSnapSymmetry between two motion events of the *same* continuous hold, making
    // an unmoved stick silently resolve to a different cell -- confirmed via a real-device
    // logcat capture: (0.576, 0.733) resolved to R, then moments later (0.576, 0.702) -- an
    // almost identical position, no release in between -- resolved to I.
    private val holdSymmetry = FloatArray(16)
    private val snapSymmetryVecScratch = FloatArray(3)
    private val snapSymmetryOutScratch = FloatArray(3)

    private val stickerModelMatrix = FloatArray(16)
    private val worldModelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Current (settled) per-piece transforms, refreshed after every twist/scramble/reset.
    private var currentTransforms: FloatArray = FloatArray(HypercubeGeometry.HOME_POSITIONS.size * 20)

    // In-flight twist animation state.
    private var animating = false
    private var animStartNanos = 0L
    private var animPlaneA = 0
    private var animPlaneB = 0
    private var animAngleDeg = 0f
    private var animBefore: FloatArray? = null
    private var animAfter: FloatArray? = null
    private var animAffected: BooleanArray? = null

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
        uniform float uHighlight;
        uniform float uForceBlack;
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
            c = mix(c, vec3(1.0), uHighlight * 0.35);
            c = mix(c, vec3(0.0), uForceBlack);
            fragColor = vec4(c, 1.0);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.05f, 0.05f, 0.07f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // LEQUAL (not the default LESS) so the wireframe overlay -- drawn with the exact same
        // vertex positions as the triangle fill it's outlining -- doesn't lose the depth test
        // to the fragments it's coincident with.
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        program = buildProgram(vertexShaderSrc, fragmentShaderSrc)
        uMvpLoc = GLES30.glGetUniformLocation(program, "uMVP")
        uNormalMatrixLoc = GLES30.glGetUniformLocation(program, "uNormalMatrix")
        uHighlightLoc = GLES30.glGetUniformLocation(program, "uHighlight")
        uForceBlackLoc = GLES30.glGetUniformLocation(program, "uForceBlack")

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

        val wireIbo = IntArray(1)
        GLES30.glGenBuffers(1, wireIbo, 0)
        wireIndexBufferId = wireIbo[0]
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, wireIndexBufferId)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            wireIndexBuffer.capacity() * 2,
            wireIndexBuffer,
            GLES30.GL_STATIC_DRAW,
        )

        val vboIds = IntArray(Cell4.entries.size)
        GLES30.glGenBuffers(vboIds.size, vboIds, 0)
        stickerVboIds = vboIds
        Cell4.entries.forEach { cell ->
            val vertices = HypercubeGeometry.buildStickerVertices(HypercubeGeometry.CELL_COLORS[cell.ordinal])
            val buffer: FloatBuffer = ByteBuffer
                .allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(vertices); position(0) }

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[cell.ordinal])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, buffer, GLES30.GL_STATIC_DRAW)
        }

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
        Matrix.setIdentityM(cameraSnapSymmetry, 0)
        Matrix.setIdentityM(holdSymmetry, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        // A narrower FOV (was 40f) than CubeRenderer's, paired with a proportionally longer
        // camera distance below, flattens the perspective -- less size difference between the
        // near and far walls (e.g. F/R vs. their opposite B/L) -- closer to an isometric look
        // without going fully orthographic.
        Matrix.perspectiveM(projMatrix, 0, 24f, aspect, 0.1f, 150f)
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
     * native identity) ends up in the I slot -- for the gamepad's left trigger. A no-op if the
     * selection is already on the W axis (I or O), since there's no spatial axis left to pair
     * with W for this: the two cells on the W axis can only swap via a *180*-degree turn, not a
     * single quarter turn, and this only ever does quarter turns like every other rotation here.
     *
     * The rotation direction (`reverse`) is derived, not looked up: [requestCameraRotate90]'s
     * `(axis, W)` quarter turn sends the room's own +axis direction to +W when [reverse] is
     * false, and to -W when true (see [setPlaneRotation4]) -- I is -W, so the selected slot's
     * sign alone picks the direction that lands there.
     */
    fun requestMoveSelectedCellToI() {
        if (selectedRoomAxis == AXIS_W) return
        requestCameraRotate90(selectedRoomAxis, AXIS_W, reverse = selectedRoomSign > 0)
    }

    /** Multiplies the camera distance by [factor] (>1 zooms in, <1 zooms out), clamped so the
     * room can't be zoomed inside-out or pushed arbitrarily far away -- mirrors
     * [CubeRenderer.zoomBy] exactly, just with a range scaled up for this room's larger extent. */
    fun zoomBy(factor: Float) {
        distance = (distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    /**
     * Starts animating [viewOrientation3] toward whichever of the cube's 24 rotational
     * symmetries (see [CARDINAL_TARGETS]) is *closest* to the current orientation, easing into
     * it over [SNAP_ANIM_DURATION_NANOS] (see [onDrawFrame]'s handling of [snapAnimating]) for a
     * quick but visible rotation rather than a jarring jump -- the same target-finding as
     * [CubeRenderer.snapToNearestCardinalOrientation] ("maximize the elementwise dot product of
     * the 3x3 rotation parts"). Called (see [updateCell4Selection] and [MainActivity]'s
     * rotation-button wiring) whenever the puzzle is about to be interacted with via the
     * gamepad, so the view never stays at an arbitrary, ugly continuous drag angle -- e.g. drag
     * the room until native R's wall is closest to where D's wall usually sits, and the snap
     * settles it precisely there rather than snapping all the way back to the start.
     *
     * Also records *which* of the 24 symmetries was chosen, into [cameraSnapSymmetry] -- unlike
     * 3D mode, which can get away with an unconstrained 24-way snap because its buttons
     * re-resolve which native face is at each screen position every press
     * ([CubeRenderer.requestScreenRelativeTwist]), this puzzle's left-stick wedges are fixed to
     * room slots (see [updateCell4Selection]'s doc) and would silently point at the wrong wall
     * once the snap has picked a symmetry other than identity -- e.g. after the R-near-D drag
     * above, "down" needs to mean "whatever's visually down", which is now R, not D.
     * [applyInverseSnapSymmetry] uses [cameraSnapSymmetry] to correct for exactly that.
     */
    fun snapViewToNearestCardinalOrientation() {
        var bestTarget = CARDINAL_TARGETS[0]
        var bestSymmetry = CARDINAL_ROTATIONS[0]
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in CARDINAL_TARGETS.indices) {
            val candidate = CARDINAL_TARGETS[i]
            var score = 0f
            for (idx in ROTATION_PART_INDICES) {
                score += candidate[idx] * viewOrientation3[idx]
            }
            if (score > bestScore) {
                bestScore = score
                bestTarget = candidate
                bestSymmetry = CARDINAL_ROTATIONS[i]
            }
        }
        System.arraycopy(viewOrientation3, 0, snapAnimFrom, 0, 16)
        System.arraycopy(bestTarget, 0, snapAnimTo, 0, 16)
        System.arraycopy(bestSymmetry, 0, cameraSnapSymmetry, 0, 16)
        snapAnimStartNanos = System.nanoTime()
        snapAnimating = true
    }

    /**
     * Left-stick cell selection for 4D mode (see `todo-controller-input.md`), called via
     * `queueEvent` on every left-stick motion update -- [x]/[y] are the deadzoned stick axes as
     * reported by [GamepadInputHandler]. An 8-way compass (evenly split into 45-degree wedges by
     * the stick's raw angle alone) picks one of the 6 walls or 2 special (I/O) slots. Each wedge
     * has a *default* room slot (e.g. down-right defaults to the room's own +X slot) calibrated
     * for the default camera tilt, but for the 6 wall wedges that default is then corrected by
     * [applyInverseSnapSymmetry] for whichever of the 24 cardinal symmetries the view is
     * currently snapped to -- so "down-right" always means whichever wall is *currently*
     * down-right on screen, not just the wall that's down-right when the camera hasn't been
     * touched. I/O are excluded from that correction since they're not walls: I always renders
     * at the room's center and O is never rendered at all (see class doc), so there's no "visual
     * position" for a camera symmetry to relabel. [nativeCellInRoomSlot] then resolves whichever
     * slot was landed on to its current native occupant via [cubeOrientation4] -- a completely
     * separate concern from the camera-symmetry correction above (one tracks which native cell
     * is in a room slot; the other tracks which room slot is at a screen position), and both can
     * be in effect at once.
     *
     * As soon as the stick crosses [SIGNIFICANT_STICK_MAGNITUDE] from centered, this also snaps
     * the view (see [snapViewToNearestCardinalOrientation]) exactly once per press-and-hold
     * (tracked via [stickWasSignificant]), and freezes [holdSymmetry] from [cameraSnapSymmetry]
     * at that same moment -- see [holdSymmetry]'s doc for why the wedge-correcting symmetry
     * must be captured once per hold rather than read live on every motion event.
     *
     * Below [SIGNIFICANT_STICK_MAGNITUDE], a nonzero-but-small deflection is ignored entirely
     * rather than updating the selected slot -- a real analog stick (especially wireless)
     * rarely settles at exactly (0,0) once released, and without this, that residual noise
     * would silently reassign the selected cell out from under the user between presses (e.g.
     * selecting R, then having a twist button unexpectedly act on a totally different cell the
     * stick never intentionally pointed at).
     */
    fun updateCell4Selection(x: Float, y: Float) {
        if (x == 0f && y == 0f) {
            stickWasSignificant = false
            stickHeld = false
            return
        }
        val isSignificant = hypot(x, y) > SIGNIFICANT_STICK_MAGNITUDE
        if (!isSignificant) return
        if (!stickWasSignificant) {
            snapViewToNearestCardinalOrientation()
            System.arraycopy(cameraSnapSymmetry, 0, holdSymmetry, 0, 16)
        }
        stickWasSignificant = true
        stickHeld = true

        // AXIS_Y is negative when pushed up, so negate it to get a standard math angle (0 deg
        // = right, 90 deg = up, increasing counterclockwise).
        val deg = (Math.toDegrees(atan2(-y.toDouble(), x.toDouble())) + 360.0) % 360.0
        when {
            deg < 22.5 || deg >= 337.5 -> { selectedRoomAxis = AXIS_W; selectedRoomSign = -1 } // right: I's slot
            deg < 202.5 && deg >= 157.5 -> { selectedRoomAxis = AXIS_W; selectedRoomSign = 1 } // left: O's slot
            else -> {
                val (defaultAxis, defaultSign) = when {
                    deg < 67.5 -> AXIS_Z to -1 // up-right: B's default slot
                    deg < 112.5 -> AXIS_Y to 1 // up: U's default slot
                    deg < 157.5 -> AXIS_X to -1 // up-left: L's default slot
                    deg < 247.5 -> AXIS_Z to 1 // down-left: F's default slot
                    deg < 292.5 -> AXIS_Y to -1 // down: D's default slot
                    else -> AXIS_X to 1 // down-right: R's default slot
                }
                applyInverseSnapSymmetry(defaultAxis, defaultSign)
            }
        }
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
     * Sets [selectedRoomAxis]/[selectedRoomSign] to whichever room slot is *currently* where
     * ([defaultAxis], [defaultSign]) would be under the default (un-snapped) camera tilt --
     * i.e. corrects a wedge's default target for [holdSymmetry], the symmetry frozen at the
     * start of the current stick hold (*not* the live [cameraSnapSymmetry] -- see
     * [holdSymmetry]'s doc for why that distinction matters). See [inverseSnapSymmetryTarget]
     * for the underlying math, shared with mode 2's [navigateMode2Selection].
     */
    private fun applyInverseSnapSymmetry(defaultAxis: Int, defaultSign: Int) {
        val (axis, sign) = inverseSnapSymmetryTarget(holdSymmetry, defaultAxis, defaultSign)
        selectedRoomAxis = axis
        selectedRoomSign = sign
    }

    /**
     * Which room axis/sign ([defaultAxis], [defaultSign]) -- a direction under the *default*,
     * un-rotated camera tilt -- currently maps to, given [symmetry] (always one of the 24
     * cardinal rotations). Since such a symmetry only ever permutes/sign-flips the 3 spatial
     * axes among themselves (never anything fractional), this is exact, not an approximation:
     * apply its *inverse* (its transpose, since it's orthogonal) to the default axis vector.
     */
    private fun inverseSnapSymmetryTarget(symmetry: FloatArray, defaultAxis: Int, defaultSign: Int): Pair<Int, Int> {
        snapSymmetryVecScratch[0] = 0f; snapSymmetryVecScratch[1] = 0f; snapSymmetryVecScratch[2] = 0f
        snapSymmetryVecScratch[defaultAxis] = defaultSign.toFloat()
        for (row in 0 until 3) {
            var sum = 0f
            for (col in 0 until 3) sum += symmetry[row * 4 + col] * snapSymmetryVecScratch[col]
            snapSymmetryOutScratch[row] = sum
        }
        for (axis in 0 until 3) {
            if (abs(snapSymmetryOutScratch[axis]) > 0.5f) {
                return axis to (if (snapSymmetryOutScratch[axis] > 0) 1 else -1)
            }
        }
        error("symmetry should always map every axis to exactly one other axis")
    }

    /**
     * Applies [cell]/[fixAxis2]/[prime] to native puzzle state immediately, then animates the
     * affected cell's pieces from their pre-twist transforms to the new ones. Ignored if
     * another twist is still animating, or if [fixAxis2] equals [cell]'s own axis (invalid).
     */
    fun requestTwist(cell: Cell4, fixAxis2: Axis4, prime: Boolean) {
        if (!applyTwistInternal(cell, fixAxis2, prime)) return
        onTwistApplied?.invoke(cell, fixAxis2, prime)
    }

    /** Re-applies [cell]/[fixAxis2] with [prime] inverted, without notifying [onTwistApplied] --
     * for undo, where the caller is already responsible for popping its own history entry. */
    fun undoTwist(cell: Cell4, fixAxis2: Axis4, prime: Boolean) {
        applyTwistInternal(cell, fixAxis2, !prime)
    }

    private fun applyTwistInternal(cell: Cell4, fixAxis2: Axis4, prime: Boolean): Boolean {
        if (animating || roomAnimating || fixAxis2 == cell.axis) return false

        val before = currentTransforms
        NativeLib.cube4Twist(cell.nativeIndex, fixAxis2.nativeIndex, prime)
        val after = NativeLib.cube4GetTransforms()

        val cellAxisIdx = cell.axis.nativeIndex
        animAffected = BooleanArray(HypercubeGeometry.HOME_POSITIONS.size) { i ->
            val base = i * 20
            before[base + cellAxisIdx].roundToInt() == cell.sign
        }
        val rotating = (0 until 4).filter { it != cellAxisIdx && it != fixAxis2.nativeIndex }
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

    /** Instantly re-randomizes the puzzle (no animation), refreshes solved state, and returns
     * the moves actually applied -- MainActivity records these into its own twist history (see
     * [NativeLib.cube4Scramble]'s doc) so an exported MC4D log can mark where the scramble ends,
     * matching real MagicCube4D's own "m|" convention. */
    fun requestScramble(moveCount: Int): List<Triple<Cell4, Axis4, Boolean>> {
        if (animating) return emptyList()
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
        NativeLib.cube4Reset()
        currentTransforms = NativeLib.cube4GetTransforms()
        onStateChanged?.invoke(NativeLib.cube4IsSolved())
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // Resolved once per frame (not once per sticker below) since highlightedCell is a
        // computed property now -- see its doc for why it needs to be live rather than cached.
        val frameHighlightedCell = highlightedCell

        if (snapAnimating) {
            val snapT = ((System.nanoTime() - snapAnimStartNanos).toFloat() / SNAP_ANIM_DURATION_NANOS).coerceIn(0f, 1f)
            if (snapT >= 1f) {
                System.arraycopy(snapAnimTo, 0, viewOrientation3, 0, 16)
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
            } else {
                setPlaneRotation4(roomAnimDeltaRot4, roomAnimPlaneA, roomAnimPlaneB, roomAnimAngleDeg * roomAnimT)
                mat4MatMul(effectiveCubeOrientation4, roomAnimDeltaRot4, roomAnimBefore)
            }
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
            animT = ((System.nanoTime() - animStartNanos).toFloat() / ANIM_DURATION_NANOS).coerceIn(0f, 1f)
            animDone = animT >= 1f
        }

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)

        val stride = HypercubeGeometry.FLOATS_PER_VERTEX * 4
        val after = animAfter
        for (i in HypercubeGeometry.HOME_POSITIONS.indices) {
            val home = HypercubeGeometry.HOME_POSITIONS[i]
            val stickerCount = (if (home.x != 0) 1 else 0) + (if (home.y != 0) 1 else 0) +
                (if (home.z != 0) 1 else 0) + (if (home.w != 0) 1 else 0)
            if (hideCorners && stickerCount == 4) continue
            if (hideEdges && stickerCount == 3) continue

            val base = i * 20
            val src = when {
                !animating -> currentTransforms
                animAffected!![i] -> animBefore!!
                else -> after!!
            }

            for (k in 0 until 4) pos4[k] = src[base + k]
            for (k in 0 until 16) pieceOrient4[k] = src[base + 4 + k]

            if (animating && animAffected!![i]) {
                setPlaneRotation4(animRot4, animPlaneA, animPlaneB, animAngleDeg * animT)
                mat4VecMul(animatedPos4, animRot4, pos4)
                mat4MatMul(animatedOrient4, animRot4, pieceOrient4)
                System.arraycopy(animatedPos4, 0, pos4, 0, 4)
                System.arraycopy(animatedOrient4, 0, pieceOrient4, 0, 16)
            }

            mat4VecMul(cameraPos4, effectiveCubeOrientation4, pos4)

            val homeCoords = intArrayOf(home.x, home.y, home.z, home.w)
            for (axisIdx in 0 until 4) {
                val homeCoord = homeCoords[axisIdx]
                if (homeCoord == 0) continue

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
                if (slotAxis == AXIS_W && slotSign > 0) continue // O slot: never rendered

                if (slotAxis == AXIS_W) {
                    // I slot: floats at the room's center: local axes are simply X, Y, Z.
                    screenPos[0] = cameraPos4[0] * SPACING
                    screenPos[1] = cameraPos4[1] * SPACING
                    screenPos[2] = cameraPos4[2] * SPACING
                } else {
                    // A "wall" slot: offset along its own axis by ROOM_HALF, extended further
                    // by this piece's camera-space W coordinate (its depth within that wall);
                    // the other 2 axes place it within the wall's own 3x3 in-plane grid.
                    screenPos[0] = cameraPos4[0]
                    screenPos[1] = cameraPos4[1]
                    screenPos[2] = cameraPos4[2]
                    screenPos[slotAxis] = slotSign * ROOM_HALF + slotSign * cameraPos4[3] * SPACING
                    for (k in 0 until 3) if (k != slotAxis) screenPos[k] *= SPACING
                }

                buildStickerModelMatrix(stickerModelMatrix, screenPos[0], screenPos[1], screenPos[2])
                Matrix.multiplyMM(worldModelMatrix, 0, viewOrientation3, 0, stickerModelMatrix, 0)
                Matrix.multiplyMM(mvpMatrix, 0, viewProjMatrix, 0, worldModelMatrix, 0)
                GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)

                // colorCell is this sticker's permanent identity (like a real sticker, it never
                // repaints itself -- see cellFor's doc), so it picks which color VBO to draw.
                // currentCell is whichever wall it's *presently* sitting on (from the slot this
                // sticker just got resolved into above), which is what selection/highlighting
                // needs to match against -- otherwise selecting "R" would highlight whatever
                // stickers originally started on R, not whatever's actually on R right now.
                val colorCell = cellFor(axisIdx, homeCoord)
                val currentCell = cellFor(slotAxis, slotSign)
                val isHighlighted = currentCell == frameHighlightedCell
                GLES30.glUniform1f(uHighlightLoc, if (isHighlighted) 1f else 0f)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, stickerVboIds[colorCell.ordinal])
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
                GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, HypercubeGeometry.INDICES.size, GLES30.GL_UNSIGNED_SHORT, 0)

                if (isHighlighted) {
                    // Black wireframe outline for the selected cell (see handleCell4StickInput
                    // in MainActivity) -- a solid-color highlight blend is too subtle to read
                    // against these unlit, flat-shaded stickers, so this traces actual edges
                    // instead. Same vertex buffer/layout, just a different index buffer + mode.
                    GLES30.glUniform1f(uForceBlackLoc, 1f)
                    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, wireIndexBufferId)
                    GLES30.glDrawElements(
                        GLES30.GL_LINES,
                        HypercubeGeometry.WIREFRAME_INDICES.size,
                        GLES30.GL_UNSIGNED_SHORT,
                        0,
                    )
                    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
                    GLES30.glUniform1f(uForceBlackLoc, 0f)
                }
            }
        }

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)

        if (animDone) {
            currentTransforms = after!!
            animating = false
            animBefore = null
            animAfter = null
            animAffected = null
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

    /** Fills [out] (column-major GL layout) with a sticker cube at ([x],[y],[z]) in room-local
     * space; the shared [viewOrientation3] rotation is applied on top of this in [onDrawFrame],
     * so no per-sticker rotation is needed here. */
    private fun buildStickerModelMatrix(out: FloatArray, x: Float, y: Float, z: Float) {
        out[0] = 1f; out[1] = 0f; out[2] = 0f; out[3] = 0f
        out[4] = 0f; out[5] = 1f; out[6] = 0f; out[7] = 0f
        out[8] = 0f; out[9] = 0f; out[10] = 1f; out[11] = 0f
        out[12] = x; out[13] = y; out[14] = z; out[15] = 1f
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
        private const val STICK_DEG_PER_FRAME = 1.2f
        private const val ANIM_DURATION_NANOS = 220_000_000L // 220ms

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

        /** Distance from the room's center to each of the 6 wall blocks' center -- spaced out
         * further than a tight 3x3x3 grid would need, closer to MagicCube4D's proportions. */
        private const val ROOM_HALF = 6.0f

        private const val MIN_DISTANCE = 14f
        private const val MAX_DISTANCE = 77f

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
         * [snapViewToNearestCardinalOrientation] to find the closest axis-aligned view, and (via
         * [cameraSnapSymmetry]) by [applyInverseSnapSymmetry] to correct left-stick wedges for
         * whichever symmetry got picked. */
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
