package io.github.zachelks.proxkeep.console

import android.view.KeyEvent

/** What pressing a key of the extra-keys row does. */
sealed interface KeyAction {
    /** Ctrl or Alt: applies to the next key pressed, here or on the keyboard. */
    enum class Modifier : KeyAction { Ctrl, Alt }

    /** A key the terminal knows by key code, such as an arrow or F1. */
    data class Code(val keyCode: Int) : KeyAction

    /** A key that types a character. */
    data class Character(val codePoint: Int) : KeyAction
}

/**
 * Every key the extra-keys row can show. The row is the person's choice
 * and order; [DEFAULT_ROW] is where it starts.
 *
 * @property repeats whether holding the key repeats it, as on a keyboard
 */
enum class TerminalKey(val label: String, val action: KeyAction, val repeats: Boolean = false) {
    CTRL("CTRL", KeyAction.Modifier.Ctrl),
    ALT("ALT", KeyAction.Modifier.Alt),
    ESC("ESC", KeyAction.Code(KeyEvent.KEYCODE_ESCAPE)),
    TAB("TAB", KeyAction.Code(KeyEvent.KEYCODE_TAB)),
    LEFT("←", KeyAction.Code(KeyEvent.KEYCODE_DPAD_LEFT), repeats = true),
    DOWN("↓", KeyAction.Code(KeyEvent.KEYCODE_DPAD_DOWN), repeats = true),
    UP("↑", KeyAction.Code(KeyEvent.KEYCODE_DPAD_UP), repeats = true),
    RIGHT("→", KeyAction.Code(KeyEvent.KEYCODE_DPAD_RIGHT), repeats = true),
    HOME("HOME", KeyAction.Code(KeyEvent.KEYCODE_MOVE_HOME)),
    END("END", KeyAction.Code(KeyEvent.KEYCODE_MOVE_END)),
    PGUP("PGUP", KeyAction.Code(KeyEvent.KEYCODE_PAGE_UP), repeats = true),
    PGDN("PGDN", KeyAction.Code(KeyEvent.KEYCODE_PAGE_DOWN), repeats = true),
    INS("INS", KeyAction.Code(KeyEvent.KEYCODE_INSERT)),
    DEL("DEL", KeyAction.Code(KeyEvent.KEYCODE_FORWARD_DEL), repeats = true),
    F1("F1", KeyAction.Code(KeyEvent.KEYCODE_F1)),
    F2("F2", KeyAction.Code(KeyEvent.KEYCODE_F2)),
    F3("F3", KeyAction.Code(KeyEvent.KEYCODE_F3)),
    F4("F4", KeyAction.Code(KeyEvent.KEYCODE_F4)),
    F5("F5", KeyAction.Code(KeyEvent.KEYCODE_F5)),
    F6("F6", KeyAction.Code(KeyEvent.KEYCODE_F6)),
    F7("F7", KeyAction.Code(KeyEvent.KEYCODE_F7)),
    F8("F8", KeyAction.Code(KeyEvent.KEYCODE_F8)),
    F9("F9", KeyAction.Code(KeyEvent.KEYCODE_F9)),
    F10("F10", KeyAction.Code(KeyEvent.KEYCODE_F10)),
    F11("F11", KeyAction.Code(KeyEvent.KEYCODE_F11)),
    F12("F12", KeyAction.Code(KeyEvent.KEYCODE_F12)),
    DASH("-"),
    SLASH("/"),
    PIPE("|"),
    TILDE("~"),
    BACKSLASH("\\"),
    UNDERSCORE("_"),
    COLON(":"),
    SEMICOLON(";"),
    QUOTE("'"),
    DOUBLE_QUOTE("\""),
    BACKTICK("`"),
    STAR("*"),
    AMPERSAND("&"),
    DOLLAR("$"),
    HASH("#"),
    EQUALS("="),
    LESS("<"),
    GREATER(">"),
    LEFT_BRACKET("["),
    RIGHT_BRACKET("]"),
    LEFT_BRACE("{"),
    RIGHT_BRACE("}"),
    ;

    /** A key that types [label]. */
    constructor(label: String) : this(label, KeyAction.Character(label.codePointAt(0)))

    companion object {
        val DEFAULT_ROW: List<TerminalKey> = listOf(
            CTRL, ALT, ESC, TAB, LEFT, DOWN, UP, RIGHT, HOME, END, PGUP, PGDN, DASH, SLASH, PIPE, TILDE,
        )

        /** Reads a row saved by [encode]. Keys this version does not know are skipped. */
        fun parse(text: String): List<TerminalKey> = text.split(',')
            .mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }
            .distinct()

        fun encode(row: List<TerminalKey>): String = row.joinToString(",") { it.name }
    }
}
