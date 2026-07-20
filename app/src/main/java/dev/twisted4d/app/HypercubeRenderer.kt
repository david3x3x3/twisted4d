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
class HypercubeRenderer : GLSurfaceView.Renderer {

    @Volatile private var distance = 13.0f

    // Ordinary 3D-feeling rotation input (touch-drag / left stick) -> XZ/YZ planes.
    @Volatile var stickX: Float = 0f
    @Volatile var stickY: Float = 0f
    private val dragLock = Any()
    private var pendingDragYawDeg = 0f
    private var pendingDragPitchDeg = 0f

    /** Called (on the GL thread) right after a twist/scramble/reset with the new solved state. */
    @Volatile var onStateChanged: ((Boolean) -> Unit)? = null

    /** Which [Cell4] to render brightened, e.g. while the gamepad left stick that selects it
     * for the next twist is actively deflected (see `todo-controller-input.md`); null shows no
     * highlight. Safe to set from the UI thread. */
    @Volatile var highlightedCell: Cell4? = null

    /** Which [Cell4] the gamepad's left stick currently has selected for the next twist -- see
     * [updateCell4Selection]. Only ever written on the GL thread, but safe to read from
     * anywhere ([MainActivity]'s rotation-button handling reads it). */
    @Volatile var selectedCell4: Cell4 = Cell4.U

    // GL-thread-only edge-detection state for updateCell4Selection's snap-on-deflect behavior.
    private var stickWasSignificant = false
    private val roomDirScratch4 = FloatArray(4)

    private var program = 0
    private var uMvpLoc = 0
    private var uHighlightLoc = 0
    private var uForceBlackLoc = 0

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
        layout(location = 1) in vec3 aColor;
        uniform mat4 uMVP;
        out vec3 vColor;
        void main() {
            gl_Position = uMVP * vec4(aPosition, 1.0);
            vColor = aColor;
        }
    """.trimIndent()

    private val fragmentShaderSrc = """
        #version 300 es
        precision mediump float;
        in vec3 vColor;
        uniform float uHighlight;
        uniform float uForceBlack;
        out vec4 fragColor;
        void main() {
            vec3 c = mix(vColor, vec3(1.0), uHighlight * 0.35);
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

        NativeLib.cube4Reset()
        currentTransforms = NativeLib.cube4GetTransforms()

        setIdentity4(cubeOrientation4)
        Matrix.setIdentityM(viewOrientation3, 0)
        applyScreenRelativeRotation(INITIAL_YAW_DEG, INITIAL_PITCH_DEG) // a pleasant default 3/4 view
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 40f, aspect, 0.1f, 100f)
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
     * matrix, keeping the per-sticker slot resolution in [onDrawFrame] exact.
     */
    fun requestCameraRotate90(axisA: Int, axisB: Int, reverse: Boolean) {
        setPlaneRotation4(deltaRot4A, axisA, axisB, if (reverse) -90f else 90f)
        mat4MatMul(newOrientation4, deltaRot4A, cubeOrientation4)
        System.arraycopy(newOrientation4, 0, cubeOrientation4, 0, 16)
    }

    /**
     * Snaps [viewOrientation3] to whichever of the 24 symmetries of [INITIAL_VIEW_ORIENTATION]
     * (see [CARDINAL_TARGETS]) requires the smallest rotation from the current one -- mirrors
     * [CubeRenderer.snapToNearestCardinalOrientation] exactly (same "maximize the elementwise
     * dot product of the 3x3 rotation parts" trick). Called (see [updateCell4Selection] and
     * [MainActivity]'s rotation-button wiring) whenever the puzzle is about to be interacted
     * with via the gamepad, so the view never stays at an arbitrary, ugly continuous drag angle
     * -- purely a visual realignment; unlike in 3D mode, it has no bearing on which cell a given
     * stick direction selects, since that's resolved via [cubeOrientation4] alone (see
     * [nativeCellInRoomSlot]), not against the view's current on-screen angle.
     */
    fun snapViewToNearestCardinalOrientation() {
        var best = CARDINAL_TARGETS[0]
        var bestScore = Float.NEGATIVE_INFINITY
        for (candidate in CARDINAL_TARGETS) {
            var score = 0f
            for (idx in ROTATION_PART_INDICES) {
                score += candidate[idx] * viewOrientation3[idx]
            }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        System.arraycopy(best, 0, viewOrientation3, 0, 16)
    }

    /**
     * Left-stick cell selection for 4D mode (see `todo-controller-input.md`), called via
     * `queueEvent` on every left-stick motion update -- [x]/[y] are the deadzoned stick axes as
     * reported by [GamepadInputHandler]. A fixed 8-way compass (evenly split into 45-degree
     * wedges by the stick's raw angle alone -- deliberately *not* approximated against any
     * on-screen/projected angle) picks one of the 8 room slots -- e.g. down-right always means
     * "whichever cell currently occupies the room's own +X slot" -- and [nativeCellInRoomSlot]
     * resolves that slot to its current native occupant via [cubeOrientation4]. Since
     * [cubeOrientation4] only ever changes via [requestCameraRotate90] (not by dragging the
     * view around), so does which cell a given stick direction selects.
     *
     * As soon as the stick crosses [SIGNIFICANT_STICK_MAGNITUDE] from centered, this also snaps
     * the view (see [snapViewToNearestCardinalOrientation]) exactly once per press-and-hold
     * (tracked via [stickWasSignificant]) -- a purely visual realignment (see that function's
     * doc); it has no bearing on which cell gets selected here.
     */
    fun updateCell4Selection(x: Float, y: Float) {
        if (x == 0f && y == 0f) {
            stickWasSignificant = false
            highlightedCell = null
            return
        }
        val isSignificant = hypot(x, y) > SIGNIFICANT_STICK_MAGNITUDE
        if (isSignificant && !stickWasSignificant) snapViewToNearestCardinalOrientation()
        stickWasSignificant = isSignificant

        // AXIS_Y is negative when pushed up, so negate it to get a standard math angle (0 deg
        // = right, 90 deg = up, increasing counterclockwise).
        val deg = (Math.toDegrees(atan2(-y.toDouble(), x.toDouble())) + 360.0) % 360.0
        selectedCell4 = when {
            deg < 22.5 || deg >= 337.5 -> nativeCellInRoomSlot(AXIS_W, -1) // right: I's slot
            deg < 67.5 -> nativeCellInRoomSlot(AXIS_Z, -1) // up-right: B's slot
            deg < 112.5 -> nativeCellInRoomSlot(AXIS_Y, 1) // up: U's slot
            deg < 157.5 -> nativeCellInRoomSlot(AXIS_X, -1) // up-left: L's slot
            deg < 202.5 -> nativeCellInRoomSlot(AXIS_W, 1) // left: O's slot
            deg < 247.5 -> nativeCellInRoomSlot(AXIS_Z, 1) // down-left: F's slot
            deg < 292.5 -> nativeCellInRoomSlot(AXIS_Y, -1) // down: D's slot
            else -> nativeCellInRoomSlot(AXIS_X, 1) // down-right: R's slot
        }
        highlightedCell = selectedCell4
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
     * affected cell's pieces from their pre-twist transforms to the new ones. Ignored if
     * another twist is still animating, or if [fixAxis2] equals [cell]'s own axis (invalid).
     */
    fun requestTwist(cell: Cell4, fixAxis2: Axis4, prime: Boolean) {
        if (animating || fixAxis2 == cell.axis) return

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
    }

    /** Instantly re-randomizes the puzzle (no animation) and refreshes solved state. */
    fun requestScramble(moveCount: Int) {
        if (animating) return
        NativeLib.cube4Scramble(moveCount)
        currentTransforms = NativeLib.cube4GetTransforms()
        onStateChanged?.invoke(NativeLib.cube4IsSolved())
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

        var animT = 0f
        var animDone = false
        if (animating) {
            animT = ((System.nanoTime() - animStartNanos).toFloat() / ANIM_DURATION_NANOS).coerceIn(0f, 1f)
            animDone = animT >= 1f
        }

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)

        val stride = HypercubeGeometry.FLOATS_PER_VERTEX * 4
        val after = animAfter
        for (i in HypercubeGeometry.HOME_POSITIONS.indices) {
            val home = HypercubeGeometry.HOME_POSITIONS[i]
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
                val isHighlighted = currentCell == highlightedCell
                GLES30.glUniform1f(uHighlightLoc, if (isHighlighted) 1f else 0f)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, stickerVboIds[colorCell.ordinal])
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
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
        private const val INITIAL_YAW_DEG = -35f
        private const val INITIAL_PITCH_DEG = 25f

        /** Spacing between adjacent stickers within one cell's 3x3x3 block. */
        private const val SPACING = 1.0f

        /** Distance from the room's center to each of the 6 wall blocks' center. */
        private const val ROOM_HALF = 3.5f

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
         * [snapViewToNearestCardinalOrientation]). */
        private val ROTATION_PART_INDICES = intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)

        /** The app's default startup tilt for [viewOrientation3], matching the
         * applyScreenRelativeRotation call in onSurfaceCreated -- the fixed reference frame for
         * [snapViewToNearestCardinalOrientation], mirroring
         * [CubeRenderer.INITIAL_ORIENTATION]. */
        private val INITIAL_VIEW_ORIENTATION: FloatArray = run {
            val rotX = FloatArray(16)
            val rotY = FloatArray(16)
            val combined = FloatArray(16)
            Matrix.setRotateM(rotX, 0, INITIAL_PITCH_DEG, 1f, 0f, 0f)
            Matrix.setRotateM(rotY, 0, INITIAL_YAW_DEG, 0f, 1f, 0f)
            Matrix.multiplyMM(combined, 0, rotY, 0, rotX, 0)
            combined
        }

        /** The 24 orientation-preserving symmetries of a cube -- every signed permutation
         * matrix (one +-1 entry per row/column) with determinant +1 -- as 4x4 GL matrices.
         * Identical construction to [CubeRenderer.CARDINAL_ROTATIONS]; used by
         * [snapViewToNearestCardinalOrientation] to find the closest axis-aligned view. */
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
    }
}
