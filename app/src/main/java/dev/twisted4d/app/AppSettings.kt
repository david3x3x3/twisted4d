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

    /** Raw text of the currently-active piece-filter set (see [PieceFilters]' class doc for the
     * format) -- the canonical source of truth MainActivity's Filters submenu parses into
     * [PieceFilter]s, so clipboard export is always byte-for-byte what was last imported/edited
     * rather than a reformatted round-trip. */
    @Volatile var pieceFiltersText: String = PieceFilters.BUILTIN_FILTERS_TEXT

    /** Raw text of the currently-active button config (see [ButtonConfigs]' class doc for the
     * format) -- same "persist the raw text, not a re-serialized structure" reasoning as
     * [pieceFiltersText]. */
    @Volatile var buttonConfigText: String = ButtonConfigs.BUILTIN_BUTTON_CONFIG_TEXT
}
