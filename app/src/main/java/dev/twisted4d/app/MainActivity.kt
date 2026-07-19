package dev.twisted4d.app

import android.content.Context
import android.hardware.input.InputManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: CubeRenderer
    private lateinit var gamepadInput: GamepadInputHandler
    private lateinit var inputManager: InputManager

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "puzzle-core version: ${NativeLib.coreVersion()}")

        renderer = CubeRenderer()
        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            setOnTouchListener { _, event -> handleCameraDrag(event) }
        }

        gamepadInput = GamepadInputHandler(renderer)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(gamepadInput, null)
        gamepadInput.logAlreadyConnectedDevices()

        val twistButton = Button(this).apply {
            text = "Twist U"
            setOnClickListener {
                glSurfaceView.queueEvent { NativeLib.cubeTwistU() }
            }
        }

        val root = FrameLayout(this).apply {
            addView(glSurfaceView)
            addView(
                twistButton,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = 48 },
            )
        }
        setContentView(root)
    }

    private fun handleCameraDrag(event: MotionEvent): Boolean {
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

                renderer.yawDeg -= dx * DRAG_SENSITIVITY
                renderer.pitchDeg = (renderer.pitchDeg + dy * DRAG_SENSITIVITY)
                    .coerceIn(-PITCH_LIMIT_DEG, PITCH_LIMIT_DEG)
                glSurfaceView.requestRender()
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
        private const val PITCH_LIMIT_DEG = 85f
    }
}
