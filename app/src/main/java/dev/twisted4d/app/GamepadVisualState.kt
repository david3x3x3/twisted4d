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
    @Volatile var thumbLHeld = false

    /** Set by the 4D screen's "Nintendo ABXY" toggle. Nintendo's face buttons swap A/B and X/Y
     * relative to Xbox's layout (A bottom/B right vs. A right/B bottom, same swap for X/Y) --
     * some controllers/modes report button presses using Nintendo's physical positions, which
     * breaks [GamepadInputHandler]'s Xbox-position-based button maps. Read from two places: by
     * [GamepadInputHandler.layoutSwappedKeyCode] to normalize incoming key codes before any
     * lookup, and by [GamepadOverlayView] to print the label that's actually on the controller at
     * each fixed screen position (the *lit* position is already correct either way, since it's
     * driven by the already-normalized held-flags below -- only the printed letter needs to
     * change). Lives here rather than on [GamepadInputHandler] since the overlay has no reference
     * to that class, only to this shared object. */
    @Volatile var nintendoLayout = false
}
