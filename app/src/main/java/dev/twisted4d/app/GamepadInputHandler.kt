package dev.twisted4d.app

import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * One of the 6 physical buttons the 4D rotation scheme uses (see [GamepadInputHandler]'s class
 * doc and `todo-controller-input.md`): [literalAxis] is the axis this button's pair is *named*
 * for (Left/Right="Y-axis", Up/Down="X-axis", bumper/trigger="Z-axis"), and [primaryPrime] is
 * the `prime` flag to twist with when the resolved `fixAxis2` ends up being [literalAxis]
 * itself -- i.e. these two constants alone reproduce the doc's three concrete examples (e.g.
 * RIGHT sends +X toward -Z) via [HypercubeRenderer.requestTwist]'s existing animation math,
 * with no per-button special-casing needed. When [literalAxis] collides with the selected
 * cell's own axis (an invalid twist), callers resolve `fixAxis2` to [Axis4.W] instead -- which,
 * because the *other two* spatial axes end up rotating in that case, happens to reproduce the
 * exact same physical rotation anyway (see [MainActivity]'s gamepad wiring).
 */
enum class RotationButton(val literalAxis: Axis4, val primaryPrime: Boolean) {
    RIGHT(Axis4.Y, true),
    LEFT(Axis4.Y, false),
    UP(Axis4.X, false),
    DOWN(Axis4.X, true),
    TRIGGER_R(Axis4.Z, false),
    BUMPER_R(Axis4.Z, true),
}

/**
 * Detects connected gamepads, logs button/axis events, and reports left-stick/right-stick/
 * button input via callbacks -- deliberately renderer-agnostic so the same handler drives
 * either [CubeRenderer] (3D mode) or [HypercubeRenderer] (4D mode); [MainActivity] decides what
 * the callbacks actually do.
 *
 * Two separate button callbacks exist because 3D and 4D modes use the physical buttons for
 * unrelated purposes (see `todo-controller-input.md`): [onFaceButton] is 3D mode's scheme --
 * a plain button index, 0-5 matching U/D/L/R/F/B in both [Face] and [Cell4]'s enum order,
 * mapped Y=U, A=D, X=L, B=R, L1=F, R1=B, with L2 held as the "prime" (inverse) modifier.
 * [on4DRotationButton] is 4D mode's scheme -- Y/A/X/B are UP/DOWN/LEFT/RIGHT and R1/R2 are
 * BUMPER_R/TRIGGER_R (see [RotationButton]), twisting whichever cell the left stick currently
 * has selected; direction is which button was pressed, not a held modifier. Both fire for any
 * relevant physical press regardless of which callback the active mode actually wires up.
 *
 * Left stick = ordinary camera rotation in 3D mode, cell selection in 4D mode (per
 * `todo-controller-input.md`); right stick = 4D-specific rotation, i.e. camera orbit, in 4D
 * mode (ignored in 3D mode). D-pad stays reserved for camera control per the spec.
 */
class GamepadInputHandler(
    private val onLeftStick: (x: Float, y: Float) -> Unit,
    private val onRightStick: (x: Float, y: Float) -> Unit,
    private val onFaceButton: (index: Int, invert: Boolean) -> Unit,
    private val on4DRotationButton: (RotationButton) -> Unit = {},
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

        val lx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
        val ly = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y))
        Log.d(TAG, "Left stick axes x=$lx y=$ly (device=${event.device?.name})")
        onLeftStick(lx, ly)

        val rx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z))
        val ry = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ))
        Log.d(TAG, "Right stick axes x=$rx y=$ry (device=${event.device?.name})")
        onRightStick(rx, ry)

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

        FACE_BUTTON_INDEX_MAP[event.keyCode]?.let { index ->
            Log.i(TAG, "Twist requested: index=$index invert=$invertHeld (gamepad)")
            onFaceButton(index, invertHeld)
        }
        ROTATION_BUTTON_MAP[event.keyCode]?.let { button ->
            Log.i(TAG, "4D rotation button: $button (gamepad)")
            on4DRotationButton(button)
        }
    }

    private fun applyDeadzone(v: Float): Float = if (abs(v) < DEADZONE) 0f else v

    companion object {
        private const val TAG = "Twisted4DGamepad"
        private const val DEADZONE = 0.15f

        fun isGamepadSource(sources: Int): Boolean =
            (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        /** Index matches U/D/L/R/F/B's position (0-5) in both [Face] and [Cell4]'s enum order.
         * 3D mode only -- see [onFaceButton]. */
        private val FACE_BUTTON_INDEX_MAP = mapOf(
            KeyEvent.KEYCODE_BUTTON_Y to 0,
            KeyEvent.KEYCODE_BUTTON_A to 1,
            KeyEvent.KEYCODE_BUTTON_X to 2,
            KeyEvent.KEYCODE_BUTTON_B to 3,
            KeyEvent.KEYCODE_BUTTON_L1 to 4,
            KeyEvent.KEYCODE_BUTTON_R1 to 5,
        )

        /** Physical layout matches [FACE_BUTTON_INDEX_MAP]'s Y/A/X/B (top/bottom/left/right on
         * an Xbox-style pad); R1/R2 are the right bumper/trigger. 4D mode only -- see
         * [on4DRotationButton]. */
        private val ROTATION_BUTTON_MAP = mapOf(
            KeyEvent.KEYCODE_BUTTON_Y to RotationButton.UP,
            KeyEvent.KEYCODE_BUTTON_A to RotationButton.DOWN,
            KeyEvent.KEYCODE_BUTTON_X to RotationButton.LEFT,
            KeyEvent.KEYCODE_BUTTON_B to RotationButton.RIGHT,
            KeyEvent.KEYCODE_BUTTON_R1 to RotationButton.BUMPER_R,
            KeyEvent.KEYCODE_BUTTON_R2 to RotationButton.TRIGGER_R,
        )
    }
}
