package dev.twisted4d.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Regression tests for [ButtonConfigs]/[parseButtonActionToken] -- the hand-rolled button-config
 * format (two levels: group, slot) and its hypercubing.xyz-style notation token grammar (see
 * ButtonConfigs' class doc), which is easy to get subtly wrong (which letter is the fixed axis vs.
 * the twisted cell, ridge vs. edge shape detection, prime handling) without any compiler help. */
class ButtonConfigTest {

    // --- parseButtonActionToken: ridge shape (2 letters) ----------------------------------------

    @Test
    fun `2-letter token with no apostrophe parses as a non-prime ridge twist`() {
        val action = parseButtonActionToken("RO")
        assertEquals(ButtonAction.Ridge(Cell4.R, Cell4.O, prime = false), action)
    }

    @Test
    fun `2-letter token with a trailing apostrophe parses as a prime ridge twist`() {
        val action = parseButtonActionToken("RO'")
        assertEquals(ButtonAction.Ridge(Cell4.R, Cell4.O, prime = true), action)
    }

    @Test
    fun `ridge token rejects a fixAxis2 letter that isn't an axis representative`() {
        // "L" is a valid cell letter, but not one of R-U-F-O -- not what
        // Notation.communityNotation ever prints as a ridge's second letter, so not accepted as
        // input either.
        assertThrows(IllegalArgumentException::class.java) { parseButtonActionToken("RL") }
    }

    @Test
    fun `ridge token rejects a fixAxis2 that shares the twisted cell's own axis`() {
        // O's axis is W; using O as its own fixAxis2 collides with itself.
        assertThrows(IllegalArgumentException::class.java) { parseButtonActionToken("OO") }
    }

    // --- parseButtonActionToken: edge shape (3 letters) -----------------------------------------

    @Test
    fun `3-letter token parses as an edge twist`() {
        val action = parseButtonActionToken("IUF")
        assertEquals(ButtonAction.Edge(Cell4.I, Cell4.U, Cell4.F), action)
    }

    @Test
    fun `edge token rejects a trailing apostrophe`() {
        assertThrows(IllegalArgumentException::class.java) { parseButtonActionToken("IUF'") }
    }

    @Test
    fun `edge token rejects an axis used twice`() {
        // U and D share an axis (Y) -- not 3 distinct axes.
        assertThrows(IllegalArgumentException::class.java) { parseButtonActionToken("IUD") }
    }

    // --- parseButtonActionToken: unsupported/invalid shapes ---------------------------------------

    @Test
    fun `4-letter token (a corner) is rejected with a clear not-supported message`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { parseButtonActionToken("IUFR") }
        assertTrue(ex.message!!.contains("supported yet"))
    }

    @Test
    fun `unknown cell letter is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { parseButtonActionToken("RQ") }
    }

    // --- parseConfigText: group/slot grammar ------------------------------------------------------

    @Test
    fun `parses a minimal plain-only config`() {
        val config = ButtonConfigs.parseConfigText(
            """
            plain:
              A: RO'
              Y: RO
            """.trimIndent(),
        )
        assertEquals(ButtonAction.Ridge(Cell4.R, Cell4.O, prime = true), config.action(ButtonModifier.PLAIN, ConfigButton.A))
        assertEquals(ButtonAction.Ridge(Cell4.R, Cell4.O, prime = false), config.action(ButtonModifier.PLAIN, ConfigButton.Y))
        assertNull(config.action(ButtonModifier.BUTTON_C, ConfigButton.A))
    }

    @Test
    fun `parses both groups and comments-slash-blank-lines are ignored`() {
        val config = ButtonConfigs.parseConfigText(
            """
            # a comment
            plain:
              X: UO # inline comment

            buttonC:
              X: IUF
            """.trimIndent(),
        )
        assertEquals(ButtonAction.Ridge(Cell4.U, Cell4.O, prime = false), config.action(ButtonModifier.PLAIN, ConfigButton.X))
        assertEquals(ButtonAction.Edge(Cell4.I, Cell4.U, Cell4.F), config.action(ButtonModifier.BUTTON_C, ConfigButton.X))
    }

    @Test
    fun `slot before any group header is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ButtonConfigs.parseConfigText("A: RO'") }
    }

    @Test
    fun `unknown group name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ButtonConfigs.parseConfigText(
                """
                weird:
                  A: RO'
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `unknown button name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ButtonConfigs.parseConfigText(
                """
                plain:
                  Z: RO'
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `empty text is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ButtonConfigs.parseConfigText("") }
    }

    // --- BUILTIN_BUTTON_CONFIG_TEXT parses into all 12 slots, matching the old hardcoded defaults --

    @Test
    fun `built-in config has all 6 plain slots and all 6 buttonC slots filled`() {
        val config = ButtonConfigs.BUILTIN_BUTTON_CONFIG
        for (button in ConfigButton.entries) {
            assertTrue(config.action(ButtonModifier.PLAIN, button) != null, "plain:$button missing")
            assertTrue(config.action(ButtonModifier.BUTTON_C, button) != null, "buttonC:$button missing")
        }
    }

    @Test
    fun `built-in buttonC X and B reproduce the old fixed I-edge-twist shortcuts`() {
        val config = ButtonConfigs.BUILTIN_BUTTON_CONFIG
        // Old requestI180TwistUFDB: I twisted around native Y,+1 (U) / Z,+1 (F).
        assertEquals(ButtonAction.Edge(Cell4.I, Cell4.U, Cell4.F), config.action(ButtonModifier.BUTTON_C, ConfigButton.X))
        // Old requestI180TwistURDL: I twisted around native X,+1 (R) / Y,+1 (U).
        assertEquals(ButtonAction.Edge(Cell4.I, Cell4.R, Cell4.U), config.action(ButtonModifier.BUTTON_C, ConfigButton.B))
    }
}
