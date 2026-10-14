package io.github.zachelks.proxkeep.desktop

import android.view.KeyEvent

/**
 * X11 keysyms, the key names VNC sends. Printable characters map to
 * themselves in Latin-1 and to `0x01000000 + code point` beyond it.
 */
object Keysyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val DELETE = 0xffff
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val INSERT = 0xff63
    const val MENU = 0xff67
    const val PRINT = 0xff61
    const val PAUSE = 0xff13
    const val SCROLL_LOCK = 0xff14
    const val NUM_LOCK = 0xff7f
    const val CAPS_LOCK = 0xffe5
    const val SHIFT_L = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CONTROL_L = 0xffe3
    const val CONTROL_R = 0xffe4
    const val ALT_L = 0xffe9
    const val ALT_R = 0xffea
    const val SUPER_L = 0xffeb
    const val SUPER_R = 0xffec
    const val F1 = 0xffbe

    /** F1 to F12. */
    fun function(number: Int): Int {
        require(number in 1..12) { "F$number does not exist" }
        return F1 + number - 1
    }

    private val byKeyCode: Map<Int, Int> = buildMap {
        put(KeyEvent.KEYCODE_DEL, BACKSPACE)
        put(KeyEvent.KEYCODE_FORWARD_DEL, DELETE)
        put(KeyEvent.KEYCODE_TAB, TAB)
        put(KeyEvent.KEYCODE_ENTER, RETURN)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, RETURN)
        put(KeyEvent.KEYCODE_ESCAPE, ESCAPE)
        put(KeyEvent.KEYCODE_MOVE_HOME, HOME)
        put(KeyEvent.KEYCODE_MOVE_END, END)
        put(KeyEvent.KEYCODE_DPAD_LEFT, LEFT)
        put(KeyEvent.KEYCODE_DPAD_UP, UP)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, RIGHT)
        put(KeyEvent.KEYCODE_DPAD_DOWN, DOWN)
        put(KeyEvent.KEYCODE_PAGE_UP, PAGE_UP)
        put(KeyEvent.KEYCODE_PAGE_DOWN, PAGE_DOWN)
        put(KeyEvent.KEYCODE_INSERT, INSERT)
        put(KeyEvent.KEYCODE_MENU, MENU)
        put(KeyEvent.KEYCODE_SYSRQ, PRINT)
        put(KeyEvent.KEYCODE_BREAK, PAUSE)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, SCROLL_LOCK)
        put(KeyEvent.KEYCODE_NUM_LOCK, NUM_LOCK)
        put(KeyEvent.KEYCODE_CAPS_LOCK, CAPS_LOCK)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT_L)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, SHIFT_R)
        put(KeyEvent.KEYCODE_CTRL_LEFT, CONTROL_L)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, CONTROL_R)
        put(KeyEvent.KEYCODE_ALT_LEFT, ALT_L)
        put(KeyEvent.KEYCODE_ALT_RIGHT, ALT_R)
        put(KeyEvent.KEYCODE_META_LEFT, SUPER_L)
        put(KeyEvent.KEYCODE_META_RIGHT, SUPER_R)
        for (n in 1..12) put(KeyEvent.KEYCODE_F1 + n - 1, function(n))
    }

    /**
     * The keysym for a key event: special keys by key code, everything else
     * by the character it types, which already includes Shift. Returns null
     * for keys VNC has no name for.
     *
     * @param unicodeChar what [KeyEvent.getUnicodeChar] reports for the event
     */
    fun forKey(keyCode: Int, unicodeChar: Int): Int? =
        byKeyCode[keyCode] ?: unicodeChar.takeIf { it > 0 }?.let(::forCodePoint)

    /** The keysym that types [codePoint]. */
    fun forCodePoint(codePoint: Int): Int = when {
        codePoint == '\n'.code || codePoint == '\r'.code -> RETURN
        codePoint == '\t'.code -> TAB
        codePoint == '\b'.code -> BACKSPACE
        codePoint in 0x20..0x7e || codePoint in 0xa0..0xff -> codePoint
        else -> 0x01000000 or codePoint
    }
}
