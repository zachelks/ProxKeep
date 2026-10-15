package io.github.zachelks.proxkeep.ui.desktop

import kotlin.math.abs
import kotlin.math.hypot

/** How touches drive the remote pointer. */
enum class PointerMode {
    /** The pointer goes where the finger touches. */
    Touch,

    /** The screen is a trackpad that moves an on-screen pointer. */
    Trackpad,
}

/** A remote mouse button and its bit in the VNC button mask. */
enum class MouseButton(val mask: Int) { Left(1), Middle(2), Right(4) }

/** What a gesture asks for. Positions and distances are in device pixels. */
sealed interface PointerAction {
    /** Moves the pointer to the remote point under this screen point. */
    data class MoveTo(val x: Float, val y: Float) : PointerAction

    /** Moves the pointer by this much, as a trackpad does. */
    data class MoveBy(val dx: Float, val dy: Float) : PointerAction

    /** Presses and releases a button where the pointer is. */
    data class Click(val button: MouseButton) : PointerAction

    data class Press(val button: MouseButton) : PointerAction

    data class Release(val button: MouseButton) : PointerAction

    /** Turns the wheel by [steps]: positive scrolls down, negative up. */
    data class Scroll(val steps: Int) : PointerAction

    /** Scales the view around a screen point. */
    data class Zoom(val factor: Float, val focusX: Float, val focusY: Float) : PointerAction

    /** Moves the view. */
    data class Pan(val dx: Float, val dy: Float) : PointerAction
}

/**
 * Turns touches into pointer actions, following the interaction model in
 * the spec, after Microsoft's remote desktop app:
 *
 * - tap: left click
 * - long press or two-finger tap: right click
 * - double tap and drag: drag with the left button held
 * - one-finger drag: moves the pointer (with the finger, or like a trackpad)
 * - two-finger drag: scroll
 * - pinch: zoom, with the view following the fingers
 *
 * It holds no timers: the caller passes event times, and calls [timeout] so
 * a long press is noticed while the finger rests.
 */
class GestureInterpreter(
    var mode: PointerMode,
    private val touchSlop: Float,
    private val scrollStep: Float = touchSlop * 3,
) {
    private enum class Phase { Idle, OneFinger, Dragging, TwoFingers, Zooming, Scrolling, Done }

    private var phase = Phase.Idle
    private val pointers = LinkedHashMap<Long, Pair<Float, Float>>()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var dragArmed = false

    private var lastTapTime = Long.MIN_VALUE / 2
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var startSpan = 0f
    private var lastSpan = 0f
    private var startCentroid = 0f to 0f
    private var lastCentroid = 0f to 0f
    private var scrollRemainder = 0f
    private var twoFingerStart = 0L

    fun down(id: Long, x: Float, y: Float, time: Long): List<PointerAction> {
        pointers[id] = x to y
        return when (pointers.size) {
            1 -> {
                phase = Phase.OneFinger
                downX = x
                downY = y
                lastX = x
                lastY = y
                downTime = time
                moved = false
                dragArmed = time - lastTapTime <= DOUBLE_TAP_MS && near(x, y, lastTapX, lastTapY, touchSlop * DOUBLE_TAP_SLOP)
                if (mode == PointerMode.Touch) listOf(PointerAction.MoveTo(x, y)) else emptyList()
            }
            2 -> {
                val actions = if (phase == Phase.Dragging) listOf(PointerAction.Release(MouseButton.Left)) else emptyList()
                phase = Phase.TwoFingers
                twoFingerStart = time
                startSpan = span()
                lastSpan = startSpan
                startCentroid = centroid()
                lastCentroid = startCentroid
                scrollRemainder = 0f
                actions
            }
            else -> emptyList()
        }
    }

    fun move(id: Long, x: Float, y: Float, time: Long): List<PointerAction> {
        if (id !in pointers) return emptyList()
        pointers[id] = x to y
        return when (phase) {
            Phase.OneFinger, Phase.Dragging -> moveOne(x, y)
            Phase.TwoFingers, Phase.Zooming, Phase.Scrolling -> moveTwo()
            else -> emptyList()
        }
    }

    private fun moveOne(x: Float, y: Float): List<PointerAction> {
        if (!moved && !near(x, y, downX, downY, touchSlop)) moved = true
        if (!moved) return emptyList()
        val actions = mutableListOf<PointerAction>()
        if (dragArmed && phase == Phase.OneFinger) {
            phase = Phase.Dragging
            actions += PointerAction.Press(MouseButton.Left)
        }
        actions += when (mode) {
            PointerMode.Touch -> PointerAction.MoveTo(x, y)
            PointerMode.Trackpad -> PointerAction.MoveBy(x - lastX, y - lastY)
        }
        lastX = x
        lastY = y
        return actions
    }

    private fun moveTwo(): List<PointerAction> {
        if (pointers.size < 2) return emptyList()
        val span = span()
        val centroid = centroid()
        if (phase == Phase.TwoFingers) {
            val spanChange = if (startSpan > 0f) abs(span / startSpan - 1f) else 0f
            val travel = hypot(centroid.first - startCentroid.first, centroid.second - startCentroid.second)
            phase = when {
                spanChange > ZOOM_THRESHOLD -> Phase.Zooming
                travel > touchSlop -> Phase.Scrolling
                else -> return emptyList()
            }
        }
        val actions = mutableListOf<PointerAction>()
        when (phase) {
            Phase.Zooming -> {
                if (lastSpan > 0f) actions += PointerAction.Zoom(span / lastSpan, centroid.first, centroid.second)
                actions += PointerAction.Pan(centroid.first - lastCentroid.first, centroid.second - lastCentroid.second)
            }
            Phase.Scrolling -> {
                // Fingers moving down reveal what is above, as on a trackpad: the wheel turns up.
                scrollRemainder += centroid.second - lastCentroid.second
                val steps = (scrollRemainder / scrollStep).toInt()
                if (steps != 0) {
                    scrollRemainder -= steps * scrollStep
                    actions += PointerAction.Scroll(-steps)
                }
            }
            else -> Unit
        }
        lastSpan = span
        lastCentroid = centroid
        return actions
    }

    fun up(id: Long, x: Float, y: Float, time: Long): List<PointerAction> {
        if (pointers.remove(id) == null) return emptyList()
        val actions = mutableListOf<PointerAction>()
        when (phase) {
            Phase.OneFinger -> if (!moved && time - downTime < LONG_PRESS_MS) {
                actions += PointerAction.Click(MouseButton.Left)
                // A tap that was the second of a double tap does not start a third.
                lastTapTime = if (dragArmed) Long.MIN_VALUE / 2 else time
                lastTapX = x
                lastTapY = y
            }
            Phase.Dragging -> actions += PointerAction.Release(MouseButton.Left)
            Phase.TwoFingers -> if (time - twoFingerStart < LONG_PRESS_MS) actions += PointerAction.Click(MouseButton.Right)
            else -> Unit
        }
        // The other finger of a two-finger gesture lifting later does nothing more.
        phase = if (pointers.isEmpty()) Phase.Idle else Phase.Done
        return actions
    }

    /** Call while fingers rest, with the current time, to notice a long press. */
    fun timeout(time: Long): List<PointerAction> {
        if (phase != Phase.OneFinger || moved || dragArmed || time - downTime < LONG_PRESS_MS) return emptyList()
        phase = Phase.Done
        return listOf(PointerAction.Click(MouseButton.Right))
    }

    /** Ends whatever was going on, for example when the gesture is taken over by the system. */
    fun cancel(): List<PointerAction> {
        val actions = if (phase == Phase.Dragging) listOf(PointerAction.Release(MouseButton.Left)) else emptyList()
        pointers.clear()
        phase = Phase.Idle
        return actions
    }

    private fun span(): Float {
        val (a, b) = pointers.values.take(2)
        return hypot(a.first - b.first, a.second - b.second)
    }

    private fun centroid(): Pair<Float, Float> {
        val (a, b) = pointers.values.take(2)
        return (a.first + b.first) / 2 to (a.second + b.second) / 2
    }

    private fun near(x: Float, y: Float, x0: Float, y0: Float, distance: Float) = hypot(x - x0, y - y0) <= distance

    companion object {
        const val LONG_PRESS_MS = 500L
        const val DOUBLE_TAP_MS = 300L
        private const val DOUBLE_TAP_SLOP = 4f
        private const val ZOOM_THRESHOLD = 0.08f
    }
}
