package io.github.zachelks.proxkeep.ui.console

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * The Ctrl and Alt keys of the extra-keys row. Each is "sticky": pressed
 * once, it applies to the next key and then releases itself.
 */
class ModifierKeys {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    /** Returns whether Ctrl is held and releases it. */
    fun takeCtrl(): Boolean = ctrl.also { ctrl = false }

    /** Returns whether Alt is held and releases it. */
    fun takeAlt(): Boolean = alt.also { alt = false }
}

/**
 * Answers the terminal view's questions about how it should behave and
 * reacts to its gestures.
 *
 * @param textSize the starting text size in pixels
 * @param textSizeRange the smallest and largest text size pinching may reach
 */
class ConsoleViewClient(
    private val modifiers: ModifierKeys,
    textSize: Int,
    private val textSizeRange: IntRange,
) : TerminalViewClient {

    /** Set once the view exists. */
    var view: TerminalView? = null

    var textSize: Int = textSize.coerceIn(textSizeRange)
        private set

    /** Pinching changes the text size one step at a time. */
    override fun onScale(scale: Float): Float {
        if (scale < SCALE_SHRINK || scale > SCALE_GROW) {
            val step = if (scale > 1f) TEXT_SIZE_STEP else -TEXT_SIZE_STEP
            val next = (textSize + step).coerceIn(textSizeRange)
            if (next != textSize) {
                textSize = next
                view?.setTextSize(next)
            }
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        showKeyboard()
    }

    fun showKeyboard() {
        val target = view ?: return
        target.requestFocus()
        target.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(target, 0)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    // Makes on-screen keyboards deliver one character at a time, without
    // suggestions or autocorrect, which is what a terminal needs.
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    // False lets the view start its own text selection.
    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = modifiers.takeCtrl()

    override fun readAltKey(): Boolean = modifiers.takeAlt()

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String?, message: String?) = Unit

    override fun logWarn(tag: String?, message: String?) = Unit

    override fun logInfo(tag: String?, message: String?) = Unit

    override fun logDebug(tag: String?, message: String?) = Unit

    override fun logVerbose(tag: String?, message: String?) = Unit

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit

    override fun logStackTrace(tag: String?, e: Exception?) = Unit

    private companion object {
        const val SCALE_SHRINK = 0.9f
        const val SCALE_GROW = 1.1f
        const val TEXT_SIZE_STEP = 2
    }
}
