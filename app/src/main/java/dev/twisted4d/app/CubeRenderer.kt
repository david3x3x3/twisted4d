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
import kotlin.math.sin

/**
 * Renders a static 3x3x3 cube from the native puzzle-core cubie transforms. Camera is a
 * touch-drag orbit (yaw/pitch/distance); twists are applied instantly (no interpolation --
 * smooth twist animation is a later milestone).
 */
class CubeRenderer : GLSurfaceView.Renderer {

    // Orbit camera state, updated from the UI thread by MainActivity's touch handling.
    @Volatile var yawDeg: Float = 35f
    @Volatile var pitchDeg: Float = 25f
    private val distance = 6.0f

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
    private val mvpMatrix = FloatArray(16)

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
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(program)

        val yawRad = Math.toRadians(yawDeg.toDouble())
        val pitchRad = Math.toRadians(pitchDeg.toDouble())
        val eyeX = (distance * cos(pitchRad) * sin(yawRad)).toFloat()
        val eyeY = (distance * sin(pitchRad)).toFloat()
        val eyeZ = (distance * cos(pitchRad) * cos(yawRad)).toFloat()
        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjMatrix, 0, projMatrix, 0, viewMatrix, 0)

        val transforms = NativeLib.cubeGetTransforms()

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)

        val stride = CubeGeometry.FLOATS_PER_VERTEX * 4
        for (i in CubeGeometry.HOME_POSITIONS.indices) {
            val base = i * 12
            buildModelMatrix(
                px = transforms[base], py = transforms[base + 1], pz = transforms[base + 2],
                m00 = transforms[base + 3], m01 = transforms[base + 4], m02 = transforms[base + 5],
                m10 = transforms[base + 6], m11 = transforms[base + 7], m12 = transforms[base + 8],
                m20 = transforms[base + 9], m21 = transforms[base + 10], m22 = transforms[base + 11],
            )
            Matrix.multiplyMM(mvpMatrix, 0, viewProjMatrix, 0, modelMatrix, 0)
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cubieVertexBufferIds[i])
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, CubeGeometry.INDICES.size, GLES30.GL_UNSIGNED_SHORT, 0)
        }

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    /** Fills [modelMatrix] (column-major, OpenGL layout) from a position + 3x3 rotation. */
    private fun buildModelMatrix(
        px: Float, py: Float, pz: Float,
        m00: Float, m01: Float, m02: Float,
        m10: Float, m11: Float, m12: Float,
        m20: Float, m21: Float, m22: Float,
    ) {
        modelMatrix[0] = m00; modelMatrix[1] = m10; modelMatrix[2] = m20; modelMatrix[3] = 0f
        modelMatrix[4] = m01; modelMatrix[5] = m11; modelMatrix[6] = m21; modelMatrix[7] = 0f
        modelMatrix[8] = m02; modelMatrix[9] = m12; modelMatrix[10] = m22; modelMatrix[11] = 0f
        modelMatrix[12] = px; modelMatrix[13] = py; modelMatrix[14] = pz; modelMatrix[15] = 1f
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
}
