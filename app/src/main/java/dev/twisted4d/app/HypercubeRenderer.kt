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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders a 3^4 hypercube (80 pieces) from the native puzzle-core piece transforms.
 *
 * The puzzle's orientation lives in a single accumulated 4x4 rotation matrix, [cubeOrientation4]
 * (row-major, distinct from the column-major GL convention used only for the final per-piece
 * model matrix). Two independent screen-relative rotations feed it every frame: the "ordinary"
 * 3D-feeling one (touch-drag/left-stick, rotating the XZ/YZ planes -- same feel as [CubeRenderer])
 * and the 4D-specific one (a dedicated on-screen drag area/right-stick, rotating the XW/ZW planes
 * -- the actual "4D camera" control). Both are left-multiplied on, so -- exactly as in
 * [CubeRenderer] -- there's no gimbal-lock pole in either pair of planes.
 *
 * Each piece renders as a simple 3D cube (see [HypercubeGeometry]); its 4D position is rotated
 * by [cubeOrientation4] and perspective-projected to 3D by scaling based on its resulting W
 * coordinate ([W_PROJECTION_DIST]) -- pieces nearer in the 4th dimension render bigger/closer,
 * the same illusion an ordinary 3D perspective camera gives for depth along Z.
 */
class HypercubeRenderer : GLSurfaceView.Renderer {

    @Volatile private var distance = 7.5f

    // "Ordinary" 3D-feeling rotation input (touch-drag / left stick) -> XZ/YZ planes.
    @Volatile var stickX: Float = 0f
    @Volatile var stickY: Float = 0f
    private val dragLock = Any()
    private var pendingDragYawDeg = 0f
    private var pendingDragPitchDeg = 0f

    // 4D-specific rotation input (dedicated drag area / right stick) -> XW/ZW planes.
    @Volatile var stick4DX: Float = 0f
    @Volatile var stick4DY: Float = 0f
    private val drag4DLock = Any()
    private var pendingDrag4DXDeg = 0f
    private var pendingDrag4DYDeg = 0f

    /** Called (on the GL thread) right after a twist/scramble/reset with the new solved state. */
    @Volatile var onStateChanged: ((Boolean) -> Unit)? = null

    private var program = 0
    private var uMvpLoc = 0

    private val indexBuffer: ShortBuffer = ByteBuffer
        .allocateDirect(HypercubeGeometry.INDICES.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply { put(HypercubeGeometry.INDICES); position(0) }

    private var indexBufferId = 0
    private lateinit var pieceVertexBufferIds: IntArray

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjMatrix = FloatArray(16)

    // Row-major 4x4 matrices for pure 4D math (distinct from GL's column-major convention,
    // used only for the final 3D model matrix -- see class doc).
    private val cubeOrientation4 = FloatArray(16)
    private val deltaRot4A = FloatArray(16)
    private val deltaRot4B = FloatArray(16)
    private val deltaCombined4 = FloatArray(16)
    private val newOrientation4 = FloatArray(16)
    private val pieceOrient4 = FloatArray(16)
    private val worldOrient4 = FloatArray(16)
    private val animRot4 = FloatArray(16)
    private val pos4 = FloatArray(4)
    private val worldPos4 = FloatArray(4)

    private val modelMatrix = FloatArray(16)
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
        out vec4 fragColor;
        void main() {
            fragColor = vec4(vColor, 1.0);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.05f, 0.05f, 0.07f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        program = buildProgram(vertexShaderSrc, fragmentShaderSrc)
        uMvpLoc = GLES30.glGetUniformLocation(program, "uMVP")

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

        val vboIds = IntArray(HypercubeGeometry.HOME_POSITIONS.size)
        GLES30.glGenBuffers(vboIds.size, vboIds, 0)
        pieceVertexBufferIds = vboIds
        HypercubeGeometry.HOME_POSITIONS.forEachIndexed { i, home ->
            val vertices = HypercubeGeometry.buildPieceVertices(home)
            val buffer: FloatBuffer = ByteBuffer
                .allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(vertices); position(0) }

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[i])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                vertices.size * 4,
                buffer,
                GLES30.GL_STATIC_DRAW,
            )
        }

        NativeLib.cube4Reset()
        currentTransforms = NativeLib.cube4GetTransforms()

        setIdentity4(cubeOrientation4)
        applyOrdinaryRotation(INITIAL_YAW_DEG, INITIAL_PITCH_DEG) // a pleasant default 3/4 view
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, 0.1f, 100f)
    }

    /** Accumulates an "ordinary" 3D-feeling touch-drag delta (degrees), applied next frame. */
    fun addDragDelta(dYawDeg: Float, dPitchDeg: Float) {
        synchronized(dragLock) {
            pendingDragYawDeg += dYawDeg
            pendingDragPitchDeg += dPitchDeg
        }
    }

    /** Accumulates a 4D-specific rotation delta (degrees) from the dedicated drag area. */
    fun addDrag4DDelta(dXDeg: Float, dYDeg: Float) {
        synchronized(drag4DLock) {
            pendingDrag4DXDeg += dXDeg
            pendingDrag4DYDeg += dYDeg
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

    private fun drainDrag4DDelta(): Pair<Float, Float> {
        synchronized(drag4DLock) {
            val delta = pendingDrag4DXDeg to pendingDrag4DYDeg
            pendingDrag4DXDeg = 0f
            pendingDrag4DYDeg = 0f
            return delta
        }
    }

    /** Rotates [cubeOrientation4] in the XZ (yaw) / YZ (pitch) planes -- the familiar 3D feel. */
    private fun applyOrdinaryRotation(dYawDeg: Float, dPitchDeg: Float) {
        setPlaneRotation4(deltaRot4A, AXIS_Z, AXIS_Y, dPitchDeg)
        setPlaneRotation4(deltaRot4B, AXIS_X, AXIS_Z, dYawDeg)
        mat4MatMul(deltaCombined4, deltaRot4B, deltaRot4A)
        mat4MatMul(newOrientation4, deltaCombined4, cubeOrientation4)
        System.arraycopy(newOrientation4, 0, cubeOrientation4, 0, 16)
    }

    /** Rotates [cubeOrientation4] in the XW / ZW planes -- the actual "4D camera" control. */
    private fun apply4DRotation(dXDeg: Float, dYDeg: Float) {
        setPlaneRotation4(deltaRot4A, AXIS_Z, AXIS_W, dYDeg)
        setPlaneRotation4(deltaRot4B, AXIS_X, AXIS_W, dXDeg)
        mat4MatMul(deltaCombined4, deltaRot4B, deltaRot4A)
        mat4MatMul(newOrientation4, deltaCombined4, cubeOrientation4)
        System.arraycopy(newOrientation4, 0, cubeOrientation4, 0, 16)
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
        if (dYaw != 0f || dPitch != 0f) applyOrdinaryRotation(dYaw, dPitch)

        val (drag4X, drag4Y) = drainDrag4DDelta()
        var d4X = drag4X
        var d4Y = drag4Y
        if (stick4DX != 0f || stick4DY != 0f) {
            d4X += stick4DX * STICK_DEG_PER_FRAME
            d4Y += stick4DY * STICK_DEG_PER_FRAME
        }
        if (d4X != 0f || d4Y != 0f) apply4DRotation(d4X, d4Y)

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
                mat4VecMul(worldPos4, animRot4, pos4)
                mat4MatMul(worldOrient4, animRot4, pieceOrient4)
                System.arraycopy(worldPos4, 0, pos4, 0, 4)
                System.arraycopy(worldOrient4, 0, pieceOrient4, 0, 16)
            }

            mat4VecMul(worldPos4, cubeOrientation4, pos4)
            mat4MatMul(worldOrient4, cubeOrientation4, pieceOrient4)

            val w = worldPos4[3]
            val scale = W_PROJECTION_DIST / (W_PROJECTION_DIST - w)
            buildModelMatrix(modelMatrix, worldPos4, worldOrient4, scale)

            Matrix.multiplyMM(mvpMatrix, 0, viewProjMatrix, 0, modelMatrix, 0)
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pieceVertexBufferIds[i])
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, HypercubeGeometry.INDICES.size, GLES30.GL_UNSIGNED_SHORT, 0)
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

    /**
     * Fills [out] (column-major GL layout) from a rotated 4D position/orientation: [pos]'s
     * XYZ (scaled by [scale] for the W-perspective effect) becomes the translation, and
     * [orient]'s top-left 3x3 (also scaled by [scale], since scaling then rotating commutes
     * for a uniform scale) becomes the rotation -- the W row/column of orient is dropped, since
     * pieces render as ordinary 3D cubes.
     */
    private fun buildModelMatrix(out: FloatArray, pos: FloatArray, orient: FloatArray, scale: Float) {
        // orient is row-major 4x4; take the top-left 3x3 (indices row*4+col for row,col in 0..3).
        out[0] = orient[0] * scale; out[1] = orient[4] * scale; out[2] = orient[8] * scale; out[3] = 0f
        out[4] = orient[1] * scale; out[5] = orient[5] * scale; out[6] = orient[9] * scale; out[7] = 0f
        out[8] = orient[2] * scale; out[9] = orient[6] * scale; out[10] = orient[10] * scale; out[11] = 0f
        out[12] = pos[0] * scale; out[13] = pos[1] * scale; out[14] = pos[2] * scale; out[15] = 1f
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

        /** How dramatic the 4th-dimension perspective effect is; smaller = more dramatic. */
        private const val W_PROJECTION_DIST = 3.0f

        private const val AXIS_X = 0
        private const val AXIS_Y = 1
        private const val AXIS_Z = 2
        private const val AXIS_W = 3

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
