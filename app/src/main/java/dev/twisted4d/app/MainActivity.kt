package dev.twisted4d.app

import android.content.Context
import android.hardware.input.InputManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: CubeRenderer
    private lateinit var gamepadInput: GamepadInputHandler
    private lateinit var inputManager: InputManager
    private lateinit var statusText: TextView
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "puzzle-core version: ${NativeLib.coreVersion()}")

        renderer = CubeRenderer()
        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    renderer.zoomBy(detector.scaleFactor)
                    return true
                }
            },
        )
        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            setOnTouchListener { _, event -> handleCameraDrag(event) }
        }

        gamepadInput = GamepadInputHandler(renderer) { face, prime ->
            glSurfaceView.queueEvent { renderer.requestTwist(face, prime) }
        }
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(gamepadInput, null)
        gamepadInput.logAlreadyConnectedDevices()

        statusText = TextView(this).apply {
            text = SOLVED_LABEL
            textSize = 18f
            setPadding(24, 16, 24, 16)
        }

        renderer.onStateChanged = { solved ->
            runOnUiThread { statusText.text = if (solved) SOLVED_LABEL else "" }
        }

        val twistRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            Face.entries.forEach { face -> addView(twistButton(face)) }
        }

        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                Button(this@MainActivity).apply {
                    text = "Scramble"
                    setOnClickListener { glSurfaceView.queueEvent { renderer.requestScramble(SCRAMBLE_MOVE_COUNT) } }
                },
            )
            addView(
                Button(this@MainActivity).apply {
                    text = "Reset"
                    setOnClickListener { glSurfaceView.queueEvent { renderer.requestReset() } }
                },
            )
        }

        val root = FrameLayout(this).apply {
            addView(glSurfaceView)
            addView(
                statusText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply { topMargin = 24 },
            )
            addView(
                twistRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = 48 },
            )
            addView(
                utilityRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply { topMargin = 24; rightMargin = 24 },
            )
        }
        setContentView(root)
    }

    /** Tap = clockwise twist, long-press = prime (counter-clockwise). */
    private fun twistButton(face: Face): Button = Button(this).apply {
        text = face.label
        setOnClickListener { glSurfaceView.queueEvent { renderer.requestTwist(face, false) } }
        setOnLongClickListener {
            glSurfaceView.queueEvent { renderer.requestTwist(face, true) }
            true
        }
    }

    private fun handleCameraDrag(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            // A second finger landing/lifting shifts which pointer event.x/y tracks, so treat
            // it like a fresh touch-down to avoid a jump when the drag resumes afterward.
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y

                // Ignore drag while pinch-zooming with a second finger down.
                if (!scaleGestureDetector.isInProgress) {
                    // Screen-relative: dragging right/up should move whatever's currently
                    // facing the camera to the right/up on screen -- see CubeRenderer's doc.
                    renderer.addDragDelta(dx * DRAG_SENSITIVITY, dy * DRAG_SENSITIVITY)
                    glSurfaceView.requestRender()
                }
            }
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadInput.handleMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        gamepadInput.handleKeyEvent(event)

        // Some gamepads (e.g. the Retroid Pocket's controller) alias face buttons like B with
        // a synthetic BACK keycode for launcher-navigation compatibility. Without this, pressing
        // B to twist R also exits the app via the default Back behavior on the root activity.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && GamepadInputHandler.isGamepadSource(event.source)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    override fun onDestroy() {
        inputManager.unregisterInputDeviceListener(gamepadInput)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Twisted4D"
        private const val DRAG_SENSITIVITY = 0.4f
        private const val SCRAMBLE_MOVE_COUNT = 25
        private const val SOLVED_LABEL = "SOLVED"
    }
}
