package io.github.zachelks.proxkeep.ui.desktop

import io.github.zachelks.proxkeep.ui.desktop.PointerAction.Click
import io.github.zachelks.proxkeep.ui.desktop.PointerAction.MoveBy
import io.github.zachelks.proxkeep.ui.desktop.PointerAction.MoveTo
import io.github.zachelks.proxkeep.ui.desktop.PointerAction.Pan
import io.github.zachelks.proxkeep.ui.desktop.PointerAction.Press
import io.github.zachelks.proxkeep.ui.desktop.PointerAction.Release
import io.github.zachelks.proxkeep.ui.desktop.PointerAction.Scroll
import io.github.zachelks.proxkeep.ui.desktop.PointerAction.Zoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GestureInterpreterTest {

    private val touch = GestureInterpreter(PointerMode.Touch, touchSlop = 10f)
    private val trackpad = GestureInterpreter(PointerMode.Trackpad, touchSlop = 10f)

    @Test
    fun aTapClicksWhereTheFingerTouches() {
        assertEquals(listOf(MoveTo(100f, 50f)), touch.down(1, 100f, 50f, time = 0))
        assertEquals(listOf(Click(MouseButton.Left)), touch.up(1, 102f, 51f, time = 80))
    }

    @Test
    fun aTrackpadTapClicksWhereThePointerIs() {
        assertEquals(emptyList(), trackpad.down(1, 100f, 50f, time = 0))
        assertEquals(listOf(Click(MouseButton.Left)), trackpad.up(1, 100f, 50f, time = 80))
    }

    @Test
    fun aLongPressRightClicksOnce() {
        touch.down(1, 100f, 50f, time = 0)

        assertEquals(emptyList(), touch.timeout(time = 300))
        assertEquals(listOf(Click(MouseButton.Right)), touch.timeout(time = 520))
        assertEquals(emptyList(), touch.timeout(time = 900))
        assertEquals(emptyList(), touch.up(1, 100f, 50f, time = 1000))
    }

    @Test
    fun aTwoFingerTapRightClicks() {
        touch.down(1, 100f, 100f, time = 0)
        touch.down(2, 200f, 100f, time = 20)

        assertEquals(listOf(Click(MouseButton.Right)), touch.up(2, 200f, 100f, time = 120))
        assertEquals(emptyList(), touch.up(1, 100f, 100f, time = 130))
    }

    @Test
    fun aOneFingerDragMovesThePointer() {
        touch.down(1, 100f, 100f, time = 0)

        assertEquals(emptyList(), touch.move(1, 105f, 100f, time = 10), "within the slop")
        assertEquals(listOf(MoveTo(130f, 100f)), touch.move(1, 130f, 100f, time = 20))
        assertEquals(emptyList(), touch.up(1, 130f, 100f, time = 30), "a drag is not a tap")
    }

    @Test
    fun aTrackpadDragMovesThePointerByTheFingersTravel() {
        trackpad.down(1, 100f, 100f, time = 0)

        assertEquals(listOf(MoveBy(30f, -5f)), trackpad.move(1, 130f, 95f, time = 10))
        assertEquals(listOf(MoveBy(10f, 0f)), trackpad.move(1, 140f, 95f, time = 20))
    }

    @Test
    fun aDoubleTapAndDragHoldsTheLeftButton() {
        touch.down(1, 100f, 100f, time = 0)
        touch.up(1, 100f, 100f, time = 60)
        touch.down(1, 102f, 101f, time = 200)

        val start = touch.move(1, 150f, 100f, time = 250)
        val end = touch.up(1, 150f, 100f, time = 300)

        assertEquals(listOf(Press(MouseButton.Left), MoveTo(150f, 100f)), start)
        assertEquals(listOf(Release(MouseButton.Left)), end)
    }

    @Test
    fun aDoubleTapWithoutDraggingIsADoubleClick() {
        touch.down(1, 100f, 100f, time = 0)
        val first = touch.up(1, 100f, 100f, time = 60)
        touch.down(1, 100f, 100f, time = 200)
        val second = touch.up(1, 100f, 100f, time = 260)

        assertEquals(listOf(Click(MouseButton.Left)), first)
        assertEquals(listOf(Click(MouseButton.Left)), second)
    }

    @Test
    fun slowTapsAreSeparateClicks() {
        touch.down(1, 100f, 100f, time = 0)
        touch.up(1, 100f, 100f, time = 60)
        touch.down(1, 100f, 100f, time = 1000)

        assertEquals(listOf(MoveTo(150f, 100f)), touch.move(1, 150f, 100f, time = 1050), "no drag without a double tap")
    }

    @Test
    fun twoFingersMovingTogetherScroll() {
        touch.down(1, 100f, 100f, time = 0)
        touch.down(2, 200f, 100f, time = 0)

        val actions = (1..10).flatMap { step ->
            touch.move(1, 100f, 100f + step * 10, time = step * 10L) + touch.move(2, 200f, 100f + step * 10, time = step * 10L)
        }

        val steps = actions.filterIsInstance<Scroll>().sumOf { it.steps }
        assertTrue(steps < 0, "fingers moving down scroll up, as on a trackpad")
        assertTrue(actions.none { it is Zoom })
    }

    @Test
    fun aPinchZoomsAroundTheFingersAndPans() {
        touch.down(1, 100f, 100f, time = 0)
        touch.down(2, 200f, 100f, time = 0)

        touch.move(1, 50f, 100f, time = 10)
        val actions = touch.move(2, 250f, 100f, time = 10)

        val zoom = assertIs<Zoom>(actions.first())
        assertTrue(zoom.factor > 1f)
        assertEquals(150f, zoom.focusX)
        assertIs<Pan>(actions[1])
    }

    @Test
    fun aSecondFingerDuringADragReleasesTheButton() {
        touch.down(1, 100f, 100f, time = 0)
        touch.up(1, 100f, 100f, time = 60)
        touch.down(1, 100f, 100f, time = 200)
        touch.move(1, 150f, 100f, time = 250)

        assertEquals(listOf(Release(MouseButton.Left)), touch.down(2, 300f, 300f, time = 260))
    }

    @Test
    fun cancellingADragReleasesTheButton() {
        touch.down(1, 100f, 100f, time = 0)
        touch.up(1, 100f, 100f, time = 60)
        touch.down(1, 100f, 100f, time = 200)
        touch.move(1, 150f, 100f, time = 250)

        assertEquals(listOf(Release(MouseButton.Left)), touch.cancel())
    }
}
