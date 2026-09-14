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

    // Raw, unswapped face-button state -- which physical button labeled Y/A/X/B is held, not
    // which *position* (top/left/right/bottom) that maps to (see GamepadInputHandler.
    // handleKeyEvent's held-flag-mirroring doc for why: swapping at write time let a mid-press
    // Nintendo Layout toggle desync a button's own down/up). Readers wanting a fixed on-screen
    // position resolve PerControllerSettings.current()?.nintendoLayout themselves at read time --
    // see MainActivity's virtualClusterRight wiring and GamepadOverlayView.onDraw.
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

    // Load-bearing for gameplay (not just the debug overlay) since 2026-08-02: THUMB_L/BUTTON_C
    // held with nothing selected is the "twist the opposite default cell" modifier -- see
    // MainActivity.handleRotationButton's moveModifierHeldAtPress.
    @Volatile var buttonCHeld = false

    // Nintendo ABXY moved to PerControllerSettings.Entry.nintendoLayout 2026-07-28 -- it's
    // per-controller now, not a single app-wide flag, so it no longer lives here. GamepadOverlayView
    // reads PerControllerSettings.current()?.nintendoLayout directly.
}
