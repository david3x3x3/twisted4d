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

/** The 8 left-hand physical buttons 4D mode's alternate "step navigation" input mode uses (see
 * [on4DNavigate] and `todo-controller-input.md`) -- unlike [RotationButton], these don't twist
 * anything themselves; [MainActivity] interprets them differently depending on which gamepad
 * input mode is currently active (mode 1: only [THUMB_L] does anything, moving the
 * stick-selected cell to I; mode 2: the dpad steps the highlight toward L/R/U/D, [BUMPER_L]/
 * [TRIGGER_L] step it toward F/B, and [SELECT] moves the highlighted cell to I). */
enum class NavigationButton { LEFT, RIGHT, UP, DOWN, BUMPER_L, TRIGGER_L, SELECT, THUMB_L }

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
 * L2 (left trigger) is 3D mode's "prime" modifier ([invertHeld]) but otherwise unused there; in
 * 4D mode, L2 and the rest of the left-hand buttons (dpad, L1, select) fire [on4DNavigate] --
 * see [NavigationButton]'s doc for what they mean, which depends on the active input mode.
 *
 * Left stick = ordinary camera rotation in 3D mode, cell selection in 4D mode *when 4D's input
 * mode 1 is active* (per `todo-controller-input.md`) -- ignored by [MainActivity] in mode 2,
 * where the dpad/L1/L2 steps a persistent selection instead (see [NavigationButton]). Right
 * stick = 4D-specific rotation, i.e. camera orbit, in 4D mode regardless of input mode (ignored
 * in 3D mode).
 *
 * [onDpadStick] is a d-pad-driven alternative to the left stick for controllers without one --
 * MainActivity wires it into the exact same selection call [onLeftStick] does, always active
 * alongside the stick (not a separate toggle/mode) since the two don't conflict: a stick-less
 * controller simply never fires [onLeftStick]. See [reportDpadStick]'s doc for how a stick-like
 * (x, y) is synthesized from the d-pad's held state.
 */
class GamepadInputHandler(
    private val onLeftStick: (x: Float, y: Float) -> Unit,
    private val onRightStick: (x: Float, y: Float) -> Unit,
    private val onFaceButton: (index: Int, invert: Boolean) -> Unit,
    private val on4DRotationButton: (RotationButton) -> Unit = {},
    private val on4DNavigate: (NavigationButton) -> Unit = {},
    private val onDpadStick: (x: Float, y: Float) -> Unit = { _, _ -> },
) : InputManager.InputDeviceListener {

    @Volatile private var invertHeld = false

    // Last-seen d-pad hat axis values, for edge-detecting a "press" out of AXIS_HAT_X/Y -- see
    // handleMotionEvent's doc for why this exists alongside NAVIGATION_BUTTON_MAP's key-based
    // handling. UI-thread-only (handleMotionEvent is only ever called from
    // dispatchGenericMotionEvent), so no need for @Volatile here.
    private var lastHatX = 0f
    private var lastHatY = 0f

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

        GamepadVisualState.leftStickX = lx
        GamepadVisualState.leftStickY = ly
        GamepadVisualState.rightStickX = rx
        GamepadVisualState.rightStickY = ry

        handleHatAxes(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))

        return true
    }

    /** Some controllers/compatibility modes (confirmed with a real device: an 8BitDo BSP-D3 in
     * DualShock 4 mode) report the d-pad as a joystick hat switch (AXIS_HAT_X/Y, -1/0/+1 on each
     * axis) via ordinary MotionEvents rather than discrete KEYCODE_DPAD_* KeyEvents -- so
     * NAVIGATION_BUTTON_MAP's key-based handling alone missed the d-pad entirely on that
     * hardware. This handles the hat-axis path too, edge-detected (fires [on4DNavigate] once per
     * transition into a direction, not continuously while held) to match the key-based path's
     * once-per-ACTION_DOWN behavior -- a real hat only ever reports -1/0/+1, so a 0.5 threshold
     * cleanly separates "pressed" from "centered" with no risk of false triggers from noise. */
    private fun handleHatAxes(hatX: Float, hatY: Float) {
        if (hatX <= -0.5f && lastHatX > -0.5f) on4DNavigate(NavigationButton.LEFT)
        if (hatX >= 0.5f && lastHatX < 0.5f) on4DNavigate(NavigationButton.RIGHT)
        GamepadVisualState.dpadLeftHeld = hatX <= -0.5f
        GamepadVisualState.dpadRightHeld = hatX >= 0.5f
        lastHatX = hatX

        if (hatY <= -0.5f && lastHatY > -0.5f) on4DNavigate(NavigationButton.UP)
        if (hatY >= 0.5f && lastHatY < 0.5f) on4DNavigate(NavigationButton.DOWN)
        GamepadVisualState.dpadUpHeld = hatY <= -0.5f
        GamepadVisualState.dpadDownHeld = hatY >= 0.5f
        lastHatY = hatY

        reportDpadStick()
    }

    /** Logs gamepad button presses and triggers the mapped twist, if any; never consumes the
     * event so system buttons (e.g. Back) keep working. */
    fun handleKeyEvent(event: KeyEvent) {
        // event.device?.sources (the whole device's capabilities), not event.source (just this
        // one event's) -- a gamepad's own D-pad button events individually classify as
        // SOURCE_DPAD, a *different* bit than SOURCE_GAMEPAD, so checking only event.source made
        // every D-pad press fail this check and fall through to dispatchKeyEvent's default
        // view-focus-navigation handling instead of ever reaching NAVIGATION_BUTTON_MAP below --
        // confirmed via real-device testing (mode 2's D-pad navigation silently did nothing).
        // The device's overall sources reliably include SOURCE_GAMEPAD regardless of which
        // specific button produced this event.
        if (!isGamepadSource(event.device?.sources ?: event.source)) return

        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            invertHeld = event.action == KeyEvent.ACTION_DOWN
        }

        // Tracks true "currently held" state (both down and up) for GamepadOverlayView's debug
        // display -- unlike the callbacks below, which only fire once per press and don't care
        // about release, so they can't drive a light-stays-on-while-held indicator by themselves.
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            val held = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_Y -> GamepadVisualState.yHeld = held
                KeyEvent.KEYCODE_BUTTON_A -> GamepadVisualState.aHeld = held
                KeyEvent.KEYCODE_BUTTON_X -> GamepadVisualState.xHeld = held
                KeyEvent.KEYCODE_BUTTON_B -> GamepadVisualState.bHeld = held
                KeyEvent.KEYCODE_BUTTON_L1 -> GamepadVisualState.l1Held = held
                KeyEvent.KEYCODE_BUTTON_R1 -> GamepadVisualState.r1Held = held
                KeyEvent.KEYCODE_BUTTON_L2 -> GamepadVisualState.l2Held = held
                KeyEvent.KEYCODE_BUTTON_R2 -> GamepadVisualState.r2Held = held
                KeyEvent.KEYCODE_DPAD_LEFT -> GamepadVisualState.dpadLeftHeld = held
                KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadVisualState.dpadRightHeld = held
                KeyEvent.KEYCODE_DPAD_UP -> GamepadVisualState.dpadUpHeld = held
                KeyEvent.KEYCODE_DPAD_DOWN -> GamepadVisualState.dpadDownHeld = held
                KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadVisualState.selectHeld = held
                KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadVisualState.thumbLHeld = held
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                -> reportDpadStick()
            }
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
        NAVIGATION_BUTTON_MAP[event.keyCode]?.let { button ->
            Log.i(TAG, "4D navigation button: $button (gamepad)")
            on4DNavigate(button)
        }
    }

    /** Synthesizes a stick-like (x, y) from the d-pad's *current* held state -- Left/Right and
     * Up/Down held at once combine into a diagonal, exactly like a real d-pad's physical diagonal
     * press, so this reports the same continuous shape [onLeftStick] does (magnitude up to
     * sqrt(2) on a diagonal, well past any deadzone, so no normalization needed) and the two are
     * interchangeable to callers. Same up=negative-y sign convention as the real AXIS_Y stick
     * (see [handleMotionEvent]): [GamepadVisualState.dpadUpHeld] alone must yield the same sign
     * pushing the stick up would, since [MainActivity] feeds both into
     * [HypercubeRenderer.updateCell4Selection] unchanged. */
    private fun reportDpadStick() {
        val x = (if (GamepadVisualState.dpadRightHeld) 1f else 0f) - (if (GamepadVisualState.dpadLeftHeld) 1f else 0f)
        val y = (if (GamepadVisualState.dpadDownHeld) 1f else 0f) - (if (GamepadVisualState.dpadUpHeld) 1f else 0f)
        onDpadStick(x, y)
    }

    private fun applyDeadzone(v: Float): Float = if (abs(v) < DEADZONE) 0f else v

    companion object {
        private const val TAG = "Twisted4DGamepad"
        private const val DEADZONE = 0.15f

        fun isGamepadSource(sources: Int): Boolean =
            (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD

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

        /** The 8 left-hand buttons 4D mode's [on4DNavigate] fires for -- see [NavigationButton]. */
        private val NAVIGATION_BUTTON_MAP = mapOf(
            KeyEvent.KEYCODE_DPAD_LEFT to NavigationButton.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to NavigationButton.RIGHT,
            KeyEvent.KEYCODE_DPAD_UP to NavigationButton.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to NavigationButton.DOWN,
            KeyEvent.KEYCODE_BUTTON_L1 to NavigationButton.BUMPER_L,
            KeyEvent.KEYCODE_BUTTON_L2 to NavigationButton.TRIGGER_L,
            KeyEvent.KEYCODE_BUTTON_SELECT to NavigationButton.SELECT,
            KeyEvent.KEYCODE_BUTTON_THUMBL to NavigationButton.THUMB_L,
        )
    }
}
