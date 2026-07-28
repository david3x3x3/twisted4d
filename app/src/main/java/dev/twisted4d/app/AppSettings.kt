package dev.twisted4d.app

/** Settings that don't have a more specific natural home elsewhere -- compare
 * [GamepadVisualState.nintendoLayout] and [GamepadInputHandler.swapZDirectionLeft]/
 * [GamepadInputHandler.swapZDirectionRight], which live next to the logic they actually affect,
 * and are also read/written by the Settings screen alongside these. See menu_system_plan memory
 * for the full settings-row spec this is gradually implementing.
 */
object AppSettings {
    @Volatile var exportFormatIsMC4D = true
    @Volatile var confirmBeforeScrambleReset = false
}
