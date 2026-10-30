package io.github.zachelks.proxkeep.ui.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zachelks.proxkeep.desktop.DesktopInput
import io.github.zachelks.proxkeep.desktop.Keysyms

/**
 * The viewer's state and the rules that turn gestures, mouse events and
 * typing into what the guest receives: where the pointer is, which buttons
 * are held, how the screen is zoomed, and which sticky modifiers apply to
 * the next key.
 */
class DesktopController(private val input: DesktopInput) {

    var mode by mutableStateOf(PointerMode.Touch)

    var viewport by mutableStateOf(Viewport())
        private set

    /** True while the view follows the window size to show the whole screen. */
    var fitting by mutableStateOf(true)
        private set

    /** The pointer, in remote screen pixels. */
    var pointerX by mutableFloatStateOf(0f)
        private set
    var pointerY by mutableFloatStateOf(0f)
        private set

    // Sticky modifiers from the extra-keys bar: each applies to the next key, then releases.
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var win by mutableStateOf(false)

    private var buttons = 0
    private var desktopWidth = 0
    private var desktopHeight = 0
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** Tells the controller the sizes of the remote screen and of the view showing it. */
    fun layout(desktopWidth: Int, desktopHeight: Int, viewWidth: Float, viewHeight: Float) {
        val changed = desktopWidth != this.desktopWidth || desktopHeight != this.desktopHeight ||
            viewWidth != this.viewWidth || viewHeight != this.viewHeight
        if (!changed) return
        val firstLayout = this.desktopWidth == 0
        this.desktopWidth = desktopWidth
        this.desktopHeight = desktopHeight
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        viewport = if (fitting) fitViewport() else viewport.clamped(desktopWidth, desktopHeight, viewWidth, viewHeight)
        if (firstLayout) {
            pointerX = desktopWidth / 2f
            pointerY = desktopHeight / 2f
        }
    }

    /** Switches between the whole screen and one remote pixel per device pixel. */
    fun toggleFit() {
        if (fitting) {
            fitting = false
            viewport = Viewport.actualSize(viewport, viewWidth, viewHeight).clamped(desktopWidth, desktopHeight, viewWidth, viewHeight)
        } else {
            fitting = true
            viewport = fitViewport()
        }
    }

    fun apply(actions: List<PointerAction>) = actions.forEach(::apply)

    fun apply(action: PointerAction) {
        when (action) {
            is PointerAction.MoveTo -> moveTo(viewport.toDesktopX(action.x), viewport.toDesktopY(action.y))
            is PointerAction.MoveBy -> {
                moveTo(pointerX + action.dx / viewport.scale * TRACKPAD_SPEED, pointerY + action.dy / viewport.scale * TRACKPAD_SPEED)
                // In trackpad mode the view follows the pointer, so it never leaves the screen.
                if (!fitting) {
                    viewport = viewport.revealing(pointerX, pointerY, viewWidth, viewHeight, viewWidth / REVEAL_MARGIN_FRACTION)
                        .clamped(desktopWidth, desktopHeight, viewWidth, viewHeight)
                }
            }
            is PointerAction.Click -> {
                sendButtons(buttons or action.button.mask)
                sendButtons(buttons)
            }
            is PointerAction.Press -> {
                buttons = buttons or action.button.mask
                sendButtons(buttons)
            }
            is PointerAction.Release -> {
                buttons = buttons and action.button.mask.inv()
                sendButtons(buttons)
            }
            is PointerAction.Scroll -> scroll(action.steps)
            is PointerAction.Zoom -> {
                fitting = false
                val fit = fitViewport().scale
                viewport = viewport.zoomed(action.factor, action.focusX, action.focusY, fit, maxOf(MAX_ZOOM, fit))
                    .clamped(desktopWidth, desktopHeight, viewWidth, viewHeight)
            }
            is PointerAction.Pan -> viewport = viewport.panned(action.dx, action.dy).clamped(desktopWidth, desktopHeight, viewWidth, viewHeight)
        }
    }

    /** A mouse or touchpad: the pointer follows it exactly and its buttons are passed on as they are. */
    fun mouse(screenX: Float, screenY: Float, buttonMask: Int) {
        moveTo(viewport.toDesktopX(screenX), viewport.toDesktopY(screenY), send = false)
        buttons = buttonMask
        sendButtons(buttons)
    }

    /** Turns the wheel: positive steps scroll down. */
    fun scroll(steps: Int) {
        val wheel = if (steps > 0) WHEEL_DOWN else WHEEL_UP
        repeat(kotlin.math.abs(steps)) {
            sendButtons(buttons or wheel)
            sendButtons(buttons)
        }
    }

    /** Presses or releases a key as the hardware keyboard reports it. Sticky modifiers do not apply. */
    fun key(keysym: Int, down: Boolean) = input.sendKey(keysym, down)

    /** Presses and releases a key, with the sticky modifiers held around it, which then release. */
    fun tap(keysym: Int) {
        val held = buildList {
            if (ctrl) add(Keysyms.CONTROL_L)
            if (alt) add(Keysyms.ALT_L)
            if (win) add(Keysyms.SUPER_L)
        }
        ctrl = false
        alt = false
        win = false
        held.forEach { input.sendKey(it, true) }
        input.sendKey(keysym, true)
        input.sendKey(keysym, false)
        held.asReversed().forEach { input.sendKey(it, false) }
    }

    /** Types text from the on-screen keyboard. */
    fun type(text: CharSequence) {
        text.codePoints().forEach { tap(Keysyms.forCodePoint(it)) }
    }

    /** The key combination guests use for their security screen or a restart. */
    fun ctrlAltDelete() {
        listOf(Keysyms.CONTROL_L, Keysyms.ALT_L, Keysyms.DELETE).forEach { input.sendKey(it, true) }
        listOf(Keysyms.DELETE, Keysyms.ALT_L, Keysyms.CONTROL_L).forEach { input.sendKey(it, false) }
    }

    private fun moveTo(x: Float, y: Float, send: Boolean = true) {
        pointerX = x.coerceIn(0f, (desktopWidth - 1).coerceAtLeast(0).toFloat())
        pointerY = y.coerceIn(0f, (desktopHeight - 1).coerceAtLeast(0).toFloat())
        if (send) sendButtons(buttons)
    }

    private fun sendButtons(mask: Int) = input.sendPointer(pointerX.toInt(), pointerY.toInt(), mask)

    private fun fitViewport() = Viewport.fit(desktopWidth, desktopHeight, viewWidth, viewHeight)

    private companion object {
        const val WHEEL_UP = 8
        const val WHEEL_DOWN = 16
        const val MAX_ZOOM = 4f
        const val TRACKPAD_SPEED = 1.5f
        const val REVEAL_MARGIN_FRACTION = 10f
    }
}
