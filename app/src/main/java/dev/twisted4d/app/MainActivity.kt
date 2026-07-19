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

    private lateinit var rootLayout: FrameLayout
    private lateinit var inputManager: InputManager
    private lateinit var gamepadInput: GamepadInputHandler

    private var glSurfaceView: GLSurfaceView? = null
    private var is4DMode = false

    // Which of the 3 valid axes is used as fixAxis2 for the next 4D cell twist.
    private var selectedAxis4 = Axis4.W

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "puzzle-core version: ${NativeLib.coreVersion()}")

        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        rootLayout = FrameLayout(this)
        setContentView(rootLayout)
        rebuildUi()
    }

    /** Tears down and rebuilds the whole screen for the current mode. GLSurfaceView only
     * accepts one `setRenderer` call ever, so switching modes means a fresh instance. */
    private fun rebuildUi() {
        rootLayout.removeAllViews()
        if (::gamepadInput.isInitialized) {
            inputManager.unregisterInputDeviceListener(gamepadInput)
        }

        if (is4DMode) build4DScreen() else build3DScreen()
    }

    // --- 3D mode ---------------------------------------------------------------------------

    private fun build3DScreen() {
        val renderer = CubeRenderer()
        val surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
        }
        glSurfaceView = surfaceView

        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    renderer.zoomBy(detector.scaleFactor)
                    return true
                }
            },
        )
        surfaceView.setOnTouchListener { _, event -> handle3DDrag(surfaceView, renderer, event) }

        gamepadInput = GamepadInputHandler(
            onLeftStick = { x, y -> renderer.stickX = x; renderer.stickY = y },
            onRightStick = { _, _ -> },
            onFaceButton = { index, invert ->
                surfaceView.queueEvent { renderer.requestScreenRelativeTwist(Face.entries[index], invert) }
            },
        )
        inputManager.registerInputDeviceListener(gamepadInput, null)
        gamepadInput.logAlreadyConnectedDevices()

        val statusText = statusTextView()
        renderer.onStateChanged = { solved ->
            runOnUiThread { statusText.text = if (solved) SOLVED_LABEL else "" }
        }

        val twistRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            Face.entries.forEach { face ->
                addView(
                    Button(this@MainActivity).apply {
                        text = face.label
                        setOnClickListener { surfaceView.queueEvent { renderer.requestScreenRelativeTwist(face, false) } }
                        setOnLongClickListener {
                            surfaceView.queueEvent { renderer.requestScreenRelativeTwist(face, true) }
                            true
                        }
                    },
                )
            }
        }

        val utilityRow = utilityRow(
            onScramble = { surfaceView.queueEvent { renderer.requestScramble(SCRAMBLE_MOVE_COUNT_3D) } },
            onReset = { surfaceView.queueEvent { renderer.requestReset() } },
        )

        rootLayout.addView(surfaceView)
        rootLayout.addView(statusText, topCenterParams())
        rootLayout.addView(twistRow, bottomCenterParams(bottomMargin = 48))
        rootLayout.addView(modeToggleButton(), topStartParams())
        rootLayout.addView(utilityRow, topEndParams())
    }

    private fun handle3DDrag(surfaceView: GLSurfaceView, renderer: CubeRenderer, event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                if (!scaleGestureDetector.isInProgress) {
                    renderer.addDragDelta(dx * DRAG_SENSITIVITY, dy * DRAG_SENSITIVITY)
                    surfaceView.requestRender()
                }
            }
        }
        return true
    }

    // --- 4D mode -----------------------------------------------------------------------------

    private fun build4DScreen() {
        val renderer = HypercubeRenderer()
        val surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
        }
        glSurfaceView = surfaceView
        surfaceView.setOnTouchListener { _, event -> handle4DDrag(surfaceView, renderer, event) }

        gamepadInput = GamepadInputHandler(
            onLeftStick = { x, y -> renderer.stickX = x; renderer.stickY = y },
            onRightStick = { x, y -> renderer.stick4DX = x; renderer.stick4DY = y },
            onFaceButton = { index, invert ->
                surfaceView.queueEvent { renderer.requestTwist(Cell4.entries[index], selectedAxis4, invert) }
            },
        )
        inputManager.registerInputDeviceListener(gamepadInput, null)
        gamepadInput.logAlreadyConnectedDevices()

        val statusText = statusTextView()
        renderer.onStateChanged = { solved ->
            runOnUiThread { statusText.text = if (solved) SOLVED_LABEL else "" }
        }

        val cellRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            Cell4.entries.forEach { cell ->
                addView(
                    Button(this@MainActivity).apply {
                        text = cell.label
                        setOnClickListener {
                            surfaceView.queueEvent { renderer.requestTwist(cell, selectedAxis4, false) }
                        }
                        setOnLongClickListener {
                            surfaceView.queueEvent { renderer.requestTwist(cell, selectedAxis4, true) }
                            true
                        }
                    },
                )
            }
        }

        lateinit var axisButtons: Map<Axis4, Button>
        val axisRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            axisButtons = Axis4.entries.associateWith { axis ->
                Button(this@MainActivity).apply {
                    text = "axis:${axis.label}"
                    setOnClickListener {
                        selectedAxis4 = axis
                        axisButtons.forEach { (a, b) -> b.alpha = if (a == axis) 1f else 0.5f }
                    }
                }.also { addView(it) }
            }
            axisButtons.forEach { (a, b) -> b.alpha = if (a == selectedAxis4) 1f else 0.5f }
        }

        // A dedicated drag area for the 4D-specific camera rotation (XW/ZW planes), separate
        // from the main view's touch-drag which only controls the ordinary 3D-feeling planes.
        val fourDDragArea = TextView(this).apply {
            text = "4D drag"
            setBackgroundColor(0x552196F3)
            setPadding(24, 24, 24, 24)
            setOnTouchListener { _, event -> handle4DOnlyDrag(surfaceView, renderer, event) }
        }

        val utilityRow = utilityRow(
            onScramble = { surfaceView.queueEvent { renderer.requestScramble(SCRAMBLE_MOVE_COUNT_4D) } },
            onReset = { surfaceView.queueEvent { renderer.requestReset() } },
        )

        rootLayout.addView(surfaceView)
        rootLayout.addView(statusText, topCenterParams())
        rootLayout.addView(
            fourDDragArea,
            FrameLayout.LayoutParams(300, 300, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 24 },
        )
        rootLayout.addView(axisRow, bottomCenterParams(bottomMargin = 220))
        rootLayout.addView(cellRow, bottomCenterParams(bottomMargin = 48))
        rootLayout.addView(modeToggleButton(), topStartParams())
        rootLayout.addView(utilityRow, topEndParams())
    }

    private var last4DTouchX = 0f
    private var last4DTouchY = 0f

    /** Drag inside the dedicated 4D-drag area only rotates the 4D-specific planes. */
    private fun handle4DOnlyDrag(surfaceView: GLSurfaceView, renderer: HypercubeRenderer, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                last4DTouchX = event.x
                last4DTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - last4DTouchX
                val dy = event.y - last4DTouchY
                last4DTouchX = event.x
                last4DTouchY = event.y
                renderer.addDrag4DDelta(dx * DRAG_SENSITIVITY, dy * DRAG_SENSITIVITY)
                surfaceView.requestRender()
            }
        }
        return true
    }

    /** Drag on the main view controls the ordinary 3D-feeling rotation, same as [handle3DDrag]. */
    private fun handle4DDrag(surfaceView: GLSurfaceView, renderer: HypercubeRenderer, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                renderer.addDragDelta(dx * DRAG_SENSITIVITY, dy * DRAG_SENSITIVITY)
                surfaceView.requestRender()
            }
        }
        return true
    }

    // --- shared UI helpers -------------------------------------------------------------------

    private fun statusTextView(): TextView = TextView(this).apply {
        text = SOLVED_LABEL
        textSize = 18f
        setPadding(24, 16, 24, 16)
    }

    private fun modeToggleButton(): Button = Button(this).apply {
        text = if (is4DMode) "Go 3D" else "Go 4D"
        setOnClickListener {
            is4DMode = !is4DMode
            rebuildUi()
        }
    }

    private fun utilityRow(onScramble: () -> Unit, onReset: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply { text = "Scramble"; setOnClickListener { onScramble() } })
            addView(Button(this@MainActivity).apply { text = "Reset"; setOnClickListener { onReset() } })
        }

    private fun topCenterParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
    ).apply { topMargin = 24 }

    private fun topStartParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.START,
    ).apply { topMargin = 24; leftMargin = 24 }

    private fun topEndParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.END,
    ).apply { topMargin = 24; rightMargin = 24 }

    private fun bottomCenterParams(bottomMargin: Int) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
    ).apply { this.bottomMargin = bottomMargin }

    // --- lifecycle / input dispatch ----------------------------------------------------------

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
        glSurfaceView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
    }

    override fun onDestroy() {
        if (::gamepadInput.isInitialized) {
            inputManager.unregisterInputDeviceListener(gamepadInput)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Twisted4D"
        private const val DRAG_SENSITIVITY = 0.4f
        private const val SCRAMBLE_MOVE_COUNT_3D = 25
        private const val SCRAMBLE_MOVE_COUNT_4D = 25
        private const val SOLVED_LABEL = "SOLVED"
    }
}
