package dev.twisted4d.app

import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Detects connected gamepads, logs button/axis events, maps the left stick to camera
 * rotation, and maps face/shoulder buttons to cube twists.
 *
 * Default mapping (placeholder until the in-app remapping screen exists, per the spec's
 * milestone 6): Y=U, A=D, X=L, B=R, L1=F, R1=B, with L2 held as the "prime" (inverse)
 * modifier. D-pad and sticks stay reserved for camera control per the spec.
 */
class GamepadInputHandler(
    private val renderer: CubeRenderer,
    private val onTwistRequested: (Face, Boolean) -> Unit,
) : InputManager.InputDeviceListener {

    @Volatile private var invertHeld = false

    /** Input device listener callbacks only fire on future connect/disconnect, so call this
     * once at startup to log any gamepad that was already connected before the app launched. */
    fun logAlreadyConnectedDevices() {
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (isGamepadSource(device.sources)) {
                Log.i(TAG, "Gamepad already connected: ${device.name} (id=$id)")
            }
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (isGamepadSource(device.sources)) {
            Log.i(TAG, "Gamepad connected: ${device.name} (id=$deviceId)")
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        Log.i(TAG, "Input device disconnected: id=$deviceId")
    }

    override fun onInputDeviceChanged(deviceId: Int) = Unit

    /** Returns true if this was a joystick event this handler consumed. */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false

        val x = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
        val y = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y))
        Log.d(TAG, "Left stick axes x=$x y=$y (device=${event.device?.name})")

        renderer.stickX = x
        renderer.stickY = y
        return true
    }

    /** Logs gamepad button presses and triggers the mapped twist, if any; never consumes the
     * event so system buttons (e.g. Back) keep working. */
    fun handleKeyEvent(event: KeyEvent) {
        if (!isGamepadSource(event.source)) return

        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            invertHeld = event.action == KeyEvent.ACTION_DOWN
        }

        if (event.repeatCount > 0) return // don't spam the log/twists while a button is held

        val label = KeyEvent.keyCodeToString(event.keyCode)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> Log.i(TAG, "Button down: $label (device=${event.device?.name})")
            KeyEvent.ACTION_UP -> Log.i(TAG, "Button up: $label (device=${event.device?.name})")
        }

        if (event.action != KeyEvent.ACTION_DOWN) return
        val face = FACE_BUTTON_MAP[event.keyCode] ?: return
        Log.i(TAG, "Twist requested: ${face.label}${if (invertHeld) "'" else ""} (gamepad)")
        onTwistRequested(face, invertHeld)
    }

    private fun applyDeadzone(v: Float): Float = if (abs(v) < DEADZONE) 0f else v

    companion object {
        private const val TAG = "Twisted4DGamepad"
        private const val DEADZONE = 0.15f

        fun isGamepadSource(sources: Int): Boolean =
            (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        private val FACE_BUTTON_MAP = mapOf(
            KeyEvent.KEYCODE_BUTTON_Y to Face.U,
            KeyEvent.KEYCODE_BUTTON_A to Face.D,
            KeyEvent.KEYCODE_BUTTON_X to Face.L,
            KeyEvent.KEYCODE_BUTTON_B to Face.R,
            KeyEvent.KEYCODE_BUTTON_L1 to Face.F,
            KeyEvent.KEYCODE_BUTTON_R1 to Face.B,
        )
    }
}
