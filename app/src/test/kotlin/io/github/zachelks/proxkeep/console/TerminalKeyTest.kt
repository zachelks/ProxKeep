package io.github.zachelks.proxkeep.console

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalKeyTest {

    @Test
    fun aRowSurvivesBeingSaved() {
        val row = listOf(TerminalKey.F1, TerminalKey.CTRL, TerminalKey.BACKSLASH, TerminalKey.DOUBLE_QUOTE)

        assertEquals(row, TerminalKey.parse(TerminalKey.encode(row)))
        assertEquals(emptyList(), TerminalKey.parse(TerminalKey.encode(emptyList())))
    }

    @Test
    fun unknownAndRepeatedKeysAreDropped() {
        assertEquals(listOf(TerminalKey.ESC, TerminalKey.TAB), TerminalKey.parse("ESC, F99,TAB,ESC"))
    }

    @Test
    fun characterKeysTypeTheirLabel() {
        assertEquals(KeyAction.Character('\\'.code), TerminalKey.BACKSLASH.action)
        assertEquals(KeyAction.Character('"'.code), TerminalKey.DOUBLE_QUOTE.action)
        TerminalKey.entries.filter { it.action is KeyAction.Character }.forEach { key ->
            assertEquals(1, key.label.codePointCount(0, key.label.length), key.name)
        }
    }

    @Test
    fun movementKeysRepeatAndOthersDoNot() {
        assertTrue(TerminalKey.LEFT.repeats)
        assertTrue(TerminalKey.PGDN.repeats)
        assertTrue(TerminalKey.DEL.repeats)
        assertFalse(TerminalKey.ESC.repeats)
        assertFalse(TerminalKey.CTRL.repeats)
        assertEquals(KeyAction.Code(KeyEvent.KEYCODE_F12), TerminalKey.F12.action)
    }

    @Test
    fun theDefaultRowIsTheOriginalOne() {
        assertEquals(
            "CTRL ALT ESC TAB ← ↓ ↑ → HOME END PGUP PGDN - / | ~",
            TerminalKey.DEFAULT_ROW.joinToString(" ") { it.label },
        )
    }
}
