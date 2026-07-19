package dev.twisted4d.app

import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Milestone 3: detect connected gamepads, log button/axis events, and map the left stick
 * to camera rotation as a first test. Button-to-twist mapping is a later milestone --
 * [handleKeyEvent] only logs for now and never consumes the event.
 */
class GamepadInputHandler(private val renderer: CubeRenderer) : InputManager.InputDeviceListener {

    private fun isGamepadSource(sources: Int): Boolean =
        (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

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

    /** Logs gamepad button presses; never consumes so system buttons (e.g. Back) still work. */
    fun handleKeyEvent(event: KeyEvent) {
        if (!isGamepadSource(event.source)) return
        if (event.repeatCount > 0) return // don't spam the log while a button is held

        val label = KeyEvent.keyCodeToString(event.keyCode)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> Log.i(TAG, "Button down: $label (device=${event.device?.name})")
            KeyEvent.ACTION_UP -> Log.i(TAG, "Button up: $label (device=${event.device?.name})")
        }
    }

    private fun applyDeadzone(v: Float): Float = if (abs(v) < DEADZONE) 0f else v

    companion object {
        private const val TAG = "Twisted4DGamepad"
        private const val DEADZONE = 0.15f
    }
}
