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
import kotlin.math.roundToInt

/**
 * Renders a 3x3x3 cube from the native puzzle-core cubie transforms. Twists are applied to
 * native state instantly (so [NativeLib.cubeIsSolved] is correct right away) but rendered as
 * a smooth 90-degree rotation of just the affected layer, interpolated from a before/after
 * transform snapshot -- see [requestTwist].
 *
 * The camera's eye/center/up never change, only its distance from the origin ([zoomBy], via
 * pinch-to-zoom); instead the whole puzzle's orientation is a single accumulated rotation
 * matrix, [cubeOrientation]. Touch drags and the gamepad stick both feed
 * [addDragDelta]/[stickX]/[stickY], which are applied as small rotations about the *screen's
 * current* horizontal/vertical axes and left-multiplied onto [cubeOrientation] (see
 * [applyScreenRelativeRotation]). Because the axes used are always the fixed screen/camera axes
 * rather than a re-derived yaw/pitch pair, this has no gimbal-lock pole -- holding a direction
 * keeps spinning the puzzle indefinitely, and a given drag direction always moves whatever's
 * currently facing the camera in that same screen direction, regardless of prior orientation.
 *
 * Twist buttons/gamepad face buttons go through [requestScreenRelativeTwist] rather than
 * [requestTwist] directly: since the puzzle can be rotated to any angle, "R" needs to mean
 * "twist whatever's at screen-right *right now*", not always the native R face.
 *
 * All public methods here are meant to be called via `GLSurfaceView.queueEvent` (i.e. on the
 * GL thread), except [stickX]/[stickY]/[addDragDelta]/[zoomBy] which are safe to call from the
 * UI thread.
 */
class CubeRenderer : GLSurfaceView.Renderer {

    // Camera distance from the origin; pinch-to-zoom adjusts this from the UI thread.
    @Volatile private var distance = 6.0f

    // Left-stick deflection (-1..1), updated from the UI thread by GamepadInputHandler and
    // applied continuously here every frame -- unlike touch drags, a held stick keeps
    // rotating the cube even if no new motion event arrives while it's steady.
    @Volatile var stickX: Float = 0f
    @Volatile var stickY: Float = 0f

    // Touch-drag deltas accumulate here (UI thread) and are drained once per frame (GL thread).
    private val dragLock = Any()
    private var pendingDragYawDeg = 0f
    private var pendingDragPitchDeg = 0f

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

    // Accumulated whole-puzzle orientation (see class doc). Rebuilt only by drag/stick input.
    private val cubeOrientation = FloatArray(16)
    private val deltaRotX = FloatArray(16)
    private val deltaRotY = FloatArray(16)
    private val deltaCombined = FloatArray(16)
    private val newOrientation = FloatArray(16)

    private val modelMatrix = FloatArray(16)
    private val worldModel = FloatArray(16)
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

        Matrix.setIdentityM(cubeOrientation, 0)
        applyScreenRelativeRotation(INITIAL_YAW_DEG, INITIAL_PITCH_DEG) // a pleasant default 3/4 view
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, 0.1f, 100f)
    }

    /**
     * Multiplies the camera distance by [factor] (>1 zooms in, <1 zooms out), clamped so the
     * puzzle can't be zoomed inside the near plane or shrunk to a speck.
     */
    fun zoomBy(factor: Float) {
        distance = (distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    /** Accumulates a touch-drag delta (in degrees) to be applied on the next drawn frame. */
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
     * Rotates [cubeOrientation] by [dYawDeg]/[dPitchDeg] about the *screen's current* vertical/
     * horizontal axes (i.e. always world Y / world X, since the camera itself never rotates),
     * composed on the left so the puzzle keeps responding the same way to "push right"/"push
     * up" no matter how it's currently oriented -- see the class doc.
     */
    private fun applyScreenRelativeRotation(dYawDeg: Float, dPitchDeg: Float) {
        Matrix.setRotateM(deltaRotX, 0, dPitchDeg, 1f, 0f, 0f)
        Matrix.setRotateM(deltaRotY, 0, dYawDeg, 0f, 1f, 0f)
        Matrix.multiplyMM(deltaCombined, 0, deltaRotY, 0, deltaRotX, 0)
        Matrix.multiplyMM(newOrientation, 0, deltaCombined, 0, cubeOrientation, 0)
        System.arraycopy(newOrientation, 0, cubeOrientation, 0, 16)
    }

    /**
     * Button-driven twist: [buttonFace]'s meaning is a fixed *screen direction*, namely wherever
     * that face appeared in the app's initial 3/4-view tilt (its [Face.outwardNormal] as seen
     * through [INITIAL_ORIENTATION]) -- not a fixed native face. Since the puzzle can be freely
     * rotated, this first snaps the view to whichever of the 24 cube symmetries of that initial
     * tilt is closest to the current view (see [snapToNearestCardinalOrientation]) -- preserving
     * the nice corner-on look instead of flattening to a single dead-on face -- then twists
     * whichever native face has ended up at the requested screen direction, which may not be
     * [buttonFace] itself.
     */
    fun requestScreenRelativeTwist(buttonFace: Face, prime: Boolean) {
        if (animating) return
        snapToNearestCardinalOrientation()

        val (nx0, ny0, nz0) = buttonFace.outwardNormal()
        val tx = nx0 * INITIAL_ORIENTATION[0] + ny0 * INITIAL_ORIENTATION[4] + nz0 * INITIAL_ORIENTATION[8]
        val ty = nx0 * INITIAL_ORIENTATION[1] + ny0 * INITIAL_ORIENTATION[5] + nz0 * INITIAL_ORIENTATION[9]
        val tz = nx0 * INITIAL_ORIENTATION[2] + ny0 * INITIAL_ORIENTATION[6] + nz0 * INITIAL_ORIENTATION[10]

        var resolvedFace = buttonFace
        var bestDistSq = Float.POSITIVE_INFINITY
        for (candidate in Face.entries) {
            val (nx, ny, nz) = candidate.outwardNormal()
            val wx = nx * cubeOrientation[0] + ny * cubeOrientation[4] + nz * cubeOrientation[8]
            val wy = nx * cubeOrientation[1] + ny * cubeOrientation[5] + nz * cubeOrientation[9]
            val wz = nx * cubeOrientation[2] + ny * cubeOrientation[6] + nz * cubeOrientation[10]
            val dx = wx - tx; val dy = wy - ty; val dz = wz - tz
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                resolvedFace = candidate
            }
        }
        requestTwist(resolvedFace, prime)
    }

    /**
     * Snaps [cubeOrientation] to whichever of the 24 symmetries of [INITIAL_ORIENTATION] (see
     * [CARDINAL_TARGETS]) requires the smallest rotation from the current one -- i.e. the one
     * maximizing trace(C^T * cubeOrientation), which for two rotation matrices is proportional
     * to cos(angle between them). This is a plain elementwise dot product of the two 3x3 parts,
     * not a matrix multiply, since trace(C^T * R) = sum of elementwise products of C and R.
     */
    private fun snapToNearestCardinalOrientation() {
        var best = CARDINAL_TARGETS[0]
        var bestScore = Float.NEGATIVE_INFINITY
        for (candidate in CARDINAL_TARGETS) {
            var score = 0f
            for (idx in ROTATION_PART_INDICES) {
                score += candidate[idx] * cubeOrientation[idx]
            }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        System.arraycopy(best, 0, cubeOrientation, 0, 16)
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

        val (dragYaw, dragPitch) = drainDragDelta()
        var dYaw = dragYaw
        var dPitch = dragPitch
        if (stickX != 0f || stickY != 0f) {
            dYaw += stickX * STICK_DEG_PER_FRAME
            dPitch += stickY * STICK_DEG_PER_FRAME
        }
        if (dYaw != 0f || dPitch != 0f) {
            applyScreenRelativeRotation(dYaw, dPitch)
        }

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
            Matrix.multiplyMM(worldModel, 0, cubeOrientation, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, viewProjMatrix, 0, worldModel, 0)
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
        private const val ANIM_DURATION_NANOS = 220_000_000L // 220ms
        private const val MIN_DISTANCE = 2.5f
        private const val MAX_DISTANCE = 15f
        private const val INITIAL_YAW_DEG = -35f
        private const val INITIAL_PITCH_DEG = 25f

        /** Column-major indices of the 3x3 rotation part within a 4x4 GL matrix (see [buildModelMatrix]). */
        private val ROTATION_PART_INDICES = intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)

        /** The app's default startup tilt, matching the [applyScreenRelativeRotation] call in
         * [onSurfaceCreated] -- the fixed reference frame for [requestScreenRelativeTwist]. */
        private val INITIAL_ORIENTATION: FloatArray = run {
            val rotX = FloatArray(16)
            val rotY = FloatArray(16)
            val combined = FloatArray(16)
            Matrix.setRotateM(rotX, 0, INITIAL_PITCH_DEG, 1f, 0f, 0f)
            Matrix.setRotateM(rotY, 0, INITIAL_YAW_DEG, 0f, 1f, 0f)
            Matrix.multiplyMM(combined, 0, rotY, 0, rotX, 0)
            combined
        }

        /**
         * The 24 orientation-preserving symmetries of a cube -- every signed permutation matrix
         * (one +-1 entry per row/column) with determinant +1 -- as 4x4 GL matrices. Used by
         * [snapToNearestCardinalOrientation] to find the closest axis-aligned view.
         */
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

        /**
         * Each [CARDINAL_ROTATIONS] symmetry re-expressed relative to [INITIAL_ORIENTATION]
         * (i.e. INITIAL_ORIENTATION * C), so snapping preserves the app's nice corner-on tilt
         * instead of flattening to a single face viewed dead-on.
         */
        private val CARDINAL_TARGETS: List<FloatArray> = CARDINAL_ROTATIONS.map { c ->
            val out = FloatArray(16)
            Matrix.multiplyMM(out, 0, INITIAL_ORIENTATION, 0, c, 0)
            out
        }
    }
}
