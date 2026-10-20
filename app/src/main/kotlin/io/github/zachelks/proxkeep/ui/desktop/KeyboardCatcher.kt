package io.github.zachelks.proxkeep.ui.desktop

import android.content.Context
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.zachelks.proxkeep.desktop.Keysyms

/**
 * An invisible view that takes keyboard input for the remote desktop: the
 * on-screen keyboard's text, and every key of a hardware keyboard, which
 * goes to the guest as pressed and released.
 */
class KeyboardCatcher(context: Context) : View(context) {

    /** Set by the screen. */
    var controller: DesktopController? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Like a terminal: no suggestions or corrections, one character at a time.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                controller?.type(text)
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                // Composition is not shown remotely; the finished text arrives through commitText.
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(MAX_DELETE)) { controller?.tap(Keysyms.BACKSPACE) }
                repeat(afterLength.coerceAtMost(MAX_DELETE)) { controller?.tap(Keysyms.DELETE) }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = send(keyCode, event, down = true) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = send(keyCode, event, down = false) || super.onKeyUp(keyCode, event)

    private fun send(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        // Back stays the system's, so the person can always leave the desktop.
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        val controller = controller ?: return false
        // Ctrl and Alt reach the guest as their own keys, so the character is read without them.
        val character = event.getUnicodeChar(event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON))
        val keysym = Keysyms.forKey(keyCode, character) ?: return false
        val fromOnScreenKeyboard = (event.source and InputDevice.SOURCE_KEYBOARD) == 0 ||
            event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD
        if (fromOnScreenKeyboard) {
            // Keys the on-screen keyboard sends as events, such as Enter and Backspace.
            if (down) controller.tap(keysym)
        } else {
            controller.key(keysym, down)
        }
        return true
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    private companion object {
        const val MAX_DELETE = 64
    }
}
