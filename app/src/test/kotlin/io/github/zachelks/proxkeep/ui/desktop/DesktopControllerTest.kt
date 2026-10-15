package io.github.zachelks.proxkeep.ui.desktop

import io.github.zachelks.proxkeep.desktop.DesktopInput
import io.github.zachelks.proxkeep.desktop.Keysyms
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopControllerTest {

    private val sent = mutableListOf<String>()
    private val input = object : DesktopInput {
        override fun sendKey(keysym: Int, down: Boolean) {
            sent += "key ${Integer.toHexString(keysym)} ${if (down) "down" else "up"}"
        }

        override fun sendPointer(x: Int, y: Int, buttons: Int) {
            sent += "pointer $x,$y $buttons"
        }
    }
    private val controller = DesktopController(input)

    @BeforeTest
    fun setUp() {
        // A 1920×1080 desktop on a 960×540 view: half size, filling it.
        controller.layout(1920, 1080, viewWidth = 960f, viewHeight = 540f)
    }

    @Test
    fun theWholeScreenFitsAtFirstWithThePointerInTheMiddle() {
        assertEquals(0.5f, controller.viewport.scale)
        assertTrue(controller.fitting)
        assertEquals(960f, controller.pointerX)
    }

    @Test
    fun aTapClicksAtTheRemotePointUnderTheFinger() {
        controller.apply(listOf(PointerAction.MoveTo(100f, 50f), PointerAction.Click(MouseButton.Left)))

        assertEquals(listOf("pointer 200,100 0", "pointer 200,100 1", "pointer 200,100 0"), sent)
    }

    @Test
    fun aTrackpadMovesThePointerRelativeToWhereItIs() {
        controller.apply(PointerAction.MoveBy(10f, 0f))

        assertEquals(960f + 10f / 0.5f * 1.5f, controller.pointerX)
    }

    @Test
    fun thePointerStaysOnTheRemoteScreen() {
        controller.apply(PointerAction.MoveTo(-500f, 5000f))

        assertEquals("pointer 0,1079 0", sent.single())
    }

    @Test
    fun aDragHoldsTheButtonUntilReleased() {
        controller.apply(PointerAction.Press(MouseButton.Left))
        controller.apply(PointerAction.MoveTo(10f, 10f))
        controller.apply(PointerAction.Release(MouseButton.Left))

        assertEquals(listOf("pointer 960,540 1", "pointer 20,20 1", "pointer 20,20 0"), sent)
    }

    @Test
    fun scrollingTurnsTheWheelOneStepAtATime() {
        controller.scroll(-2)
        controller.scroll(1)

        assertEquals(
            listOf("pointer 960,540 8", "pointer 960,540 0", "pointer 960,540 8", "pointer 960,540 0", "pointer 960,540 16", "pointer 960,540 0"),
            sent,
        )
    }

    @Test
    fun zoomingLeavesFitAndNeverShrinksBelowIt() {
        controller.apply(PointerAction.Zoom(0.5f, 480f, 270f))
        assertEquals(0.5f, controller.viewport.scale)

        controller.apply(PointerAction.Zoom(2f, 480f, 270f))
        assertEquals(1f, controller.viewport.scale)
        assertFalse(controller.fitting)
    }

    @Test
    fun fitAndActualSizeToggle() {
        controller.toggleFit()
        assertEquals(1f, controller.viewport.scale)
        assertFalse(controller.fitting)

        controller.toggleFit()
        assertEquals(0.5f, controller.viewport.scale)
        assertTrue(controller.fitting)
    }

    @Test
    fun aMouseMovesThePointerAndPassesItsButtonsOn() {
        controller.mouse(50f, 25f, buttonMask = 4)

        assertEquals(listOf("pointer 100,50 4"), sent)
    }

    @Test
    fun stickyModifiersWrapTheNextKeyAndRelease() {
        controller.ctrl = true
        controller.alt = true

        controller.tap('t'.code)
        controller.tap('x'.code)

        assertEquals(
            listOf(
                "key ffe3 down", "key ffe9 down", "key 74 down", "key 74 up", "key ffe9 up", "key ffe3 up",
                "key 78 down", "key 78 up",
            ),
            sent,
        )
        assertFalse(controller.ctrl)
    }

    @Test
    fun typedTextBecomesKeys() {
        controller.type("a€\n")

        assertEquals(listOf("key 61 down", "key 61 up", "key 10020ac down", "key 10020ac up", "key ff0d down", "key ff0d up"), sent)
    }

    @Test
    fun ctrlAltDeleteIsPressedAndReleasedInOrder() {
        controller.ctrlAltDelete()

        assertEquals(
            listOf("key ffe3 down", "key ffe9 down", "key ffff down", "key ffff up", "key ffe9 up", "key ffe3 up"),
            sent,
        )
    }

    @Test
    fun hardwareKeysAreSentAsTheyHappen() {
        controller.key(Keysyms.SHIFT_L, down = true)
        controller.key('A'.code, down = true)

        assertEquals(listOf("key ffe1 down", "key 41 down"), sent)
    }
}
