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
 * Renders a 3x3x3 cube from the native puzzle-core cubie transforms. Camera is a
 * touch-drag/gamepad-stick orbit (yaw/pitch/distance). Twists are applied to native state
 * instantly (so [NativeLib.cubeIsSolved] is correct right away) but rendered as a smooth
 * 90-degree rotation of just the affected layer, interpolated from a before/after transform
 * snapshot -- see [requestTwist].
 *
 * All public methods here are meant to be called via `GLSurfaceView.queueEvent` (i.e. on the
 * GL thread), except [yawDeg]/[pitchDeg]/[stickX]/[stickY] which are intentionally `@Volatile`
 * for cross-thread camera input.
 */
class CubeRenderer : GLSurfaceView.Renderer {

    // Orbit camera state, updated from the UI thread by MainActivity's touch handling.
    @Volatile var yawDeg: Float = 35f
    @Volatile var pitchDeg: Float = 25f
    private val distance = 6.0f

    // Left-stick deflection (-1..1), updated from the UI thread by GamepadInputHandler and
    // applied continuously here every frame -- unlike touch drags, a held stick keeps
    // rotating the camera even if no new motion event arrives while it's steady.
    @Volatile var stickX: Float = 0f
    @Volatile var stickY: Float = 0f

    /** Called (on the GL thread) right after a twist/scramble/reset with the new solved state. */
    @Volatile var onStateChanged: ((Boolean) -> Unit)? = null

    private var program = 0
    private var uMvpLoc = 0

    private val indexBuffer: ShortBuffer = ByteBuffer
        .allocateDirect(CubeGeometry.INDICES.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply { put(CubeGeometry.INDICES); position(0) }

    private var indexBufferId = 0
    private lateinit var cubieVertexBufferIds: IntArray

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val rotMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Current (settled) per-cubie transforms, refreshed after every twist/scramble/reset.
    private var currentTransforms: FloatArray = FloatArray(CubeGeometry.HOME_POSITIONS.size * 12)

    // In-flight twist animation state.
    private var animating = false
    private var animStartNanos = 0L
    private var animFace: Face? = null
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

        val vboIds = IntArray(CubeGeometry.HOME_POSITIONS.size)
        GLES30.glGenBuffers(vboIds.size, vboIds, 0)
        cubieVertexBufferIds = vboIds
        CubeGeometry.HOME_POSITIONS.forEachIndexed { i, home ->
            val vertices = CubeGeometry.buildCubieVertices(home)
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

        NativeLib.cubeReset()
        currentTransforms = NativeLib.cubeGetTransforms()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, 0.1f, 100f)
    }

    /**
     * Applies [face]/[prime] to native puzzle state immediately, then animates the affected
     * layer's cubies from their pre-twist transforms to the new ones over [ANIM_DURATION_NANOS].
     * Ignored if another twist is still animating.
     */
    fun requestTwist(face: Face, prime: Boolean) {
        if (animating) return

        val before = currentTransforms
        NativeLib.cubeTwist(face.nativeIndex, prime)
        val after = NativeLib.cubeGetTransforms()

        animAffected = BooleanArray(CubeGeometry.HOME_POSITIONS.size) { i ->
            val base = i * 12
            face.selects(
                before[base].roundToInt(),
                before[base + 1].roundToInt(),
                before[base + 2].roundToInt(),
            )
        }
        animBefore = before
        animAfter = after
        animFace = face
        animAngleDeg = if (prime) -face.clockwiseDeg else face.clockwiseDeg
        animStartNanos = System.nanoTime()
        animating = true

        onStateChanged?.invoke(NativeLib.cubeIsSolved())
    }

    /** Instantly re-randomizes the cube (no animation) and refreshes solved state. */
    fun requestScramble(moveCount: Int) {
        if (animating) return
        NativeLib.cubeScramble(moveCount)
        currentTransforms = NativeLib.cubeGetTransforms()
        onStateChanged?.invoke(NativeLib.cubeIsSolved())
    }

    /** Instantly resets to solved (no animation) and refreshes solved state. */
    fun requestReset() {
        if (animating) return
        NativeLib.cubeReset()
        currentTransforms = NativeLib.cubeGetTransforms()
        onStateChanged?.invoke(NativeLib.cubeIsSolved())
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(program)

        if (stickX != 0f || stickY != 0f) {
            yawDeg -= stickX * STICK_DEG_PER_FRAME
            pitchDeg = (pitchDeg + stickY * STICK_DEG_PER_FRAME).coerceIn(-PITCH_LIMIT_DEG, PITCH_LIMIT_DEG)
        }

        val yawRad = Math.toRadians(yawDeg.toDouble())
        val pitchRad = Math.toRadians(pitchDeg.toDouble())
        val eyeX = (distance * cos(pitchRad) * sin(yawRad)).toFloat()
        val eyeY = (distance * sin(pitchRad)).toFloat()
        val eyeZ = (distance * cos(pitchRad) * cos(yawRad)).toFloat()
        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f)
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

        val stride = CubeGeometry.FLOATS_PER_VERTEX * 4
        val after = animAfter
        for (i in CubeGeometry.HOME_POSITIONS.indices) {
            if (animating && animAffected!![i]) {
                buildModelMatrix(modelMatrix, animBefore!!, i)
                Matrix.setRotateM(rotMatrix, 0, animAngleDeg * animT, animFace!!.axisX, animFace!!.axisY, animFace!!.axisZ)
                Matrix.multiplyMM(mvpMatrix, 0, rotMatrix, 0, modelMatrix, 0)
                System.arraycopy(mvpMatrix, 0, modelMatrix, 0, 16)
            } else {
                buildModelMatrix(modelMatrix, if (animating) after!! else currentTransforms, i)
            }
            Matrix.multiplyMM(mvpMatrix, 0, viewProjMatrix, 0, modelMatrix, 0)
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cubieVertexBufferIds[i])
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, CubeGeometry.INDICES.size, GLES30.GL_UNSIGNED_SHORT, 0)
        }

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)

        if (animDone) {
            currentTransforms = after!!
            animating = false
            animBefore = null
            animAfter = null
            animAffected = null
            animFace = null
        }
    }

    /** Fills [out] (column-major, OpenGL layout) from the position + 3x3 rotation at cubie [i]. */
    private fun buildModelMatrix(out: FloatArray, transforms: FloatArray, i: Int) {
        val base = i * 12
        val px = transforms[base]; val py = transforms[base + 1]; val pz = transforms[base + 2]
        val m00 = transforms[base + 3]; val m01 = transforms[base + 4]; val m02 = transforms[base + 5]
        val m10 = transforms[base + 6]; val m11 = transforms[base + 7]; val m12 = transforms[base + 8]
        val m20 = transforms[base + 9]; val m21 = transforms[base + 10]; val m22 = transforms[base + 11]

        out[0] = m00; out[1] = m10; out[2] = m20; out[3] = 0f
        out[4] = m01; out[5] = m11; out[6] = m21; out[7] = 0f
        out[8] = m02; out[9] = m12; out[10] = m22; out[11] = 0f
        out[12] = px; out[13] = py; out[14] = pz; out[15] = 1f
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
        private const val PITCH_LIMIT_DEG = 85f
        private const val ANIM_DURATION_NANOS = 220_000_000L // 220ms
    }
}
