package dev.twisted4d.app

/**
 * Live, read-anytime snapshot of gamepad input, updated by [GamepadInputHandler] as events
 * arrive and read by [GamepadOverlayView] on its own repaint schedule -- deliberately a plain
 * `@Volatile`-field object rather than a callback/event stream, since the overlay only ever
 * wants "what's true right now," not a history of individual presses.
 */
object GamepadVisualState {
    @Volatile var leftStickX = 0f
    @Volatile var leftStickY = 0f
    @Volatile var rightStickX = 0f
    @Volatile var rightStickY = 0f

    @Volatile var yHeld = false
    @Volatile var aHeld = false
    @Volatile var xHeld = false
    @Volatile var bHeld = false
    @Volatile var l1Held = false
    @Volatile var r1Held = false
    @Volatile var l2Held = false
    @Volatile var r2Held = false

    @Volatile var dpadLeftHeld = false
    @Volatile var dpadRightHeld = false
    @Volatile var dpadUpHeld = false
    @Volatile var dpadDownHeld = false
    @Volatile var selectHeld = false
}
