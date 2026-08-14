package dev.twisted4d.app

/** One of the 6 face/shoulder buttons whose twist is configurable via [ButtonConfigs] -- the
 * physical identity (Nintendo-layout-normalized the same way [GamepadInputHandler.
 * layoutSwappedKeyCode] already normalizes real controller input, so a slot always means the
 * same physical position regardless of the connected pad's own labeling). */
enum class ConfigButton { A, B, X, Y, R1, R2 }

/** Which of the two configurable groups a press falls into -- see [ButtonConfigs]' class doc.
 * [PLAIN] is an unmodified press; [BUTTON_C] is held together with Button C (the THUMB_L/Button-C
 * "move modifier" -- see MainActivity.handleRotationButton's moveModifierHeldAtPress). */
enum class ButtonModifier { PLAIN, BUTTON_C }

/** One configured slot's action, already parsed from its hypercubing.xyz-style notation token
 * (see [parseButtonActionToken]) but not yet resolved to a native twist -- both variants are
 * expressed in *room-relative* letters (whatever's currently rendered under that label), resolved
 * to native cell/axis identifiers only at press time by [HypercubeRenderer.requestButtonAction],
 * the same room-relative-then-native pattern [HypercubeRenderer.requestRktITwist] and
 * [HypercubeRenderer.requestI180TwistUFDB]/[HypercubeRenderer.requestI180TwistURDL] already use.
 * Deliberately doesn't carry a corner (3-axis-diagonal) variant yet -- see
 * [parseButtonActionToken]'s doc for why that's not supported. */
sealed class ButtonAction {
    /** e.g. `"RO'"` -- twist [roomCell] with [roomFixAxis2Cell]'s axis held fixed, [prime] for
     * counterclockwise (community-notation apostrophe). */
    data class Ridge(val roomCell: Cell4, val roomFixAxis2Cell: Cell4, val prime: Boolean) : ButtonAction()

    /** e.g. `"IUF"` -- a genuine 180-degree edge twist of [roomCell] around the diagonal through
     * [axis1Cell]/[axis2Cell]'s two signed axes. No prime (a 180-degree edge twist is its own
     * inverse -- see [TwistRecord.Edge]'s doc). */
    data class Edge(val roomCell: Cell4, val axis1Cell: Cell4, val axis2Cell: Cell4) : ButtonAction()
}

/** Parses one hypercubing.xyz-style notation token (as typed in a button-config file, e.g. `"RO'"`
 * or `"IUF"`) into a [ButtonAction] -- the inverse of [Notation.communityNotation], but reading
 * *room-relative* letters directly rather than a [TwistRecord]'s already-resolved fields, since a
 * button-config slot is defined before any particular press ever happens.
 *
 * Two shapes recognized, by letter count after stripping a trailing `'`:
 * - 2 letters (e.g. `"RO"`, `"RO'"`): a [ButtonAction.Ridge] -- first letter is the twisted cell,
 *   second is the fixed axis's representative cell (must be one of [Notation.axisRepresentativeCell]'s
 *   outputs -- R/U/F/O -- matching exactly what [Notation.communityNotation] itself ever prints for
 *   a ridge twist's second letter, so any string this app would show is also a string it accepts
 *   back in).
 * - 3 letters (e.g. `"IUF"`): a [ButtonAction.Edge] -- first letter the twisted cell, the other two
 *   the diagonal's two signed axis cells (exact sign matters -- see [Notation.signedAxisCell]'s
 *   doc -- so e.g. `"IUF"` and `"IUB"` are genuinely different diagonals). No trailing `'` allowed
 *   (edge twists have no direction).
 *
 * A 4-letter token (a corner, i.e. a genuine 3-axis-diagonal twist) is rejected with a clear
 * "not supported yet" message rather than silently misparsed -- corner twists don't exist in this
 * app yet at all (no rotation math, no MC4D corner-grip derivation -- see the
 * discuss_theories_before_acting memory for the scoping conversation that deferred this). */
fun parseButtonActionToken(rawToken: String): ButtonAction {
    val token = rawToken.trim()
    val prime = token.endsWith("'")
    val letters = if (prime) token.dropLast(1) else token
    fun cellFor(letter: Char): Cell4 = Cell4.entries.firstOrNull { it.label.single() == letter.uppercaseChar() }
        ?: throw IllegalArgumentException("unknown cell letter '$letter' in \"$token\"")

    return when (letters.length) {
        2 -> {
            val cell = cellFor(letters[0])
            val fixAxis2Cell = cellFor(letters[1])
            require(fixAxis2Cell == Notation.axisRepresentativeCell(fixAxis2Cell.axis)) {
                "\"$token\": second letter must be an axis representative (R, U, F, or O), not \"${fixAxis2Cell.label}\""
            }
            require(fixAxis2Cell.axis != cell.axis) {
                "\"$token\": the fixed axis can't be the twisted cell's own axis"
            }
            ButtonAction.Ridge(cell, fixAxis2Cell, prime)
        }
        3 -> {
            require(!prime) { "\"$token\": edge twists don't take a trailing apostrophe -- they're their own inverse" }
            val cell = cellFor(letters[0])
            val axis1Cell = cellFor(letters[1])
            val axis2Cell = cellFor(letters[2])
            require(axis1Cell.axis != cell.axis && axis2Cell.axis != cell.axis && axis1Cell.axis != axis2Cell.axis) {
                "\"$token\": an edge twist needs 3 distinct axes (the cell's own, plus two more)"
            }
            ButtonAction.Edge(cell, axis1Cell, axis2Cell)
        }
        4 -> throw IllegalArgumentException(
            "\"$token\": corner (3-axis diagonal) twists aren't supported yet",
        )
        else -> throw IllegalArgumentException("unrecognized twist notation \"$token\"")
    }
}

/** A full button configuration: one [ButtonAction] per ([ButtonModifier], [ConfigButton]) slot --
 * 12 in total, though [ButtonConfigs.parseConfigText] doesn't require every slot to be present
 * (an unconfigured slot just does nothing when pressed with no cell selected). */
data class ButtonConfig(private val slots: Map<Pair<ButtonModifier, ConfigButton>, ButtonAction>) {
    fun action(modifier: ButtonModifier, button: ConfigButton): ButtonAction? = slots[modifier to button]
}

private val GROUP_HEADER_LINE = Regex("""^(\S.*):\s*$""")
private val SLOT_LINE = Regex("""^\s*(\S+)\s*:\s*(\S+)\s*$""")
private val GROUP_NAMES: Map<String, ButtonModifier> = mapOf("plain" to ButtonModifier.PLAIN, "buttonC" to ButtonModifier.BUTTON_C)

/**
 * Configurable button-twist mapping and its hand-editable text format -- mirrors [PieceFilters]'
 * own pattern (a small hand-rolled, not-real-YAML format; raw text persisted as the canonical
 * source of truth rather than a re-serialized structure, so clipboard export is always
 * byte-for-byte what was last imported/edited).
 *
 * Two levels deep -- group, then slot -- shaped like:
 * ```
 * # comment lines start with '#', blank lines ignored
 * plain:
 *   A: RO'
 *   B: UO'
 *   X: UO
 *   Y: RO
 *   R1: FO'
 *   R2: FO
 * buttonC:
 *   A: LO
 *   B: IRU
 *   X: IUF
 *   Y: LO'
 *   R1: BO
 *   R2: BO'
 * ```
 * An unindented `plain:`/`buttonC:` line starts a group; an indented `BUTTON: token` line assigns
 * that button's action within it, in [parseButtonActionToken]'s notation. Applies only when
 * nothing is currently selected via the stick (see MainActivity.handleRotationButton) -- with an
 * active selection, all 6 buttons still act on it directly, unaffected by this config, exactly as
 * before.
 */
object ButtonConfigs {

    /** Throws [IllegalArgumentException] with a line-numbered message on malformed input --
     * callers importing user-supplied text (MainActivity's clipboard import) should catch this and
     * leave the existing config untouched rather than letting a bad paste corrupt it. */
    fun parseConfigText(text: String): ButtonConfig {
        val slots = mutableMapOf<Pair<ButtonModifier, ConfigButton>, ButtonAction>()
        var currentModifier: ButtonModifier? = null

        text.lines().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.substringBefore('#').trimEnd()
            if (line.isBlank()) return@forEachIndexed

            // A slot line always has a token after its colon; a group header never does (see
            // GROUP_HEADER_LINE/SLOT_LINE) -- the two shapes are mutually exclusive by construction,
            // so trying SLOT_LINE first (no indentation-based disambiguation needed, unlike
            // PieceFilters' 3-level format) is enough.
            val slotLine = SLOT_LINE.matchEntire(line)
            if (slotLine != null) {
                require(currentModifier != null) {
                    "line $lineNumber: button slot before any group header: $rawLine"
                }
                val buttonName = slotLine.groupValues[1]
                val button = ConfigButton.entries.firstOrNull { it.name == buttonName }
                    ?: throw IllegalArgumentException(
                        "line $lineNumber: unknown button \"$buttonName\" (expected one of ${ConfigButton.entries.joinToString()}): $rawLine",
                    )
                val action = try {
                    parseButtonActionToken(slotLine.groupValues[2])
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("line $lineNumber: ${e.message}", e)
                }
                slots[currentModifier!! to button] = action
                return@forEachIndexed
            }

            val groupHeader = GROUP_HEADER_LINE.matchEntire(line)
            require(groupHeader != null) {
                "line $lineNumber: expected a group header ('plain:' or 'buttonC:') or a 'BUTTON: token' line, got: $rawLine"
            }
            val groupName = groupHeader.groupValues[1]
            currentModifier = GROUP_NAMES[groupName]
                ?: throw IllegalArgumentException(
                    "line $lineNumber: unknown group \"$groupName\" (expected \"plain\" or \"buttonC\"): $rawLine",
                )
        }
        require(slots.isNotEmpty()) { "no button slots found" }
        return ButtonConfig(slots)
    }

    /** The button config shipped at launch, reproducing exactly what was previously hardcoded in
     * MainActivity.handleRotationButton (derived by hand from [RotationButton]'s literalAxis/
     * primaryPrime, [Notation.rotationInvertedForCell], and [Notation.PRIME_FLIP_TWISTS] -- not
     * yet independently re-confirmed on a real device against the pre-config behavior, so worth a
     * side-by-side check the first time this ships -- see verify_visually memory).
     *
     * Lives in `app/src/main/resources/button_config.txt`, a plain text file David can open and
     * edit directly -- same JVM-classpath-resource reasoning as [PieceFilters.BUILTIN_FILTERS_TEXT]
     * (readable from plain JVM unit tests with no Android Context). */
    val BUILTIN_BUTTON_CONFIG_TEXT: String = ButtonConfigs::class.java.getResourceAsStream("/button_config.txt")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("button_config.txt is missing from the classpath -- expected at app/src/main/resources/button_config.txt")

    val BUILTIN_BUTTON_CONFIG: ButtonConfig = parseConfigText(BUILTIN_BUTTON_CONFIG_TEXT)
}
