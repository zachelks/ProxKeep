package io.github.zachelks.proxkeep.desktop

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeysymsTest {

    @Test
    fun specialKeysHaveTheirOwnNames() {
        assertEquals(Keysyms.RETURN, Keysyms.forKey(KeyEvent.KEYCODE_ENTER, '\n'.code))
        assertEquals(Keysyms.BACKSPACE, Keysyms.forKey(KeyEvent.KEYCODE_DEL, 0))
        assertEquals(Keysyms.LEFT, Keysyms.forKey(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertEquals(0xffc9, Keysyms.forKey(KeyEvent.KEYCODE_F12, 0))
        assertEquals(Keysyms.CONTROL_L, Keysyms.forKey(KeyEvent.KEYCODE_CTRL_LEFT, 0))
    }

    @Test
    fun otherKeysAreNamedByWhatTheyType() {
        assertEquals('A'.code, Keysyms.forKey(KeyEvent.KEYCODE_A, 'A'.code))
        assertEquals('ü'.code, Keysyms.forKey(KeyEvent.KEYCODE_U, 'ü'.code))
        assertNull(Keysyms.forKey(KeyEvent.KEYCODE_UNKNOWN, 0))
    }

    @Test
    fun charactersBeyondLatin1UseTheUnicodeRange() {
        assertEquals(0x010020ac, Keysyms.forCodePoint('€'.code))
        assertEquals(0x0100044f, Keysyms.forCodePoint('я'.code))
    }

    @Test
    fun controlCharactersTypeTheirKeys() {
        assertEquals(Keysyms.RETURN, Keysyms.forCodePoint('\n'.code))
        assertEquals(Keysyms.TAB, Keysyms.forCodePoint('\t'.code))
    }
}
