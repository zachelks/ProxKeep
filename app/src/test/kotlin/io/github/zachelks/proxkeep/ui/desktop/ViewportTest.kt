package io.github.zachelks.proxkeep.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewportTest {

    @Test
    fun aWideDesktopFitsTheWidthAndIsCentered() {
        val fit = Viewport.fit(1920, 1080, viewWidth = 960f, viewHeight = 2000f)

        assertEquals(0.5f, fit.scale)
        assertEquals(0f, fit.offsetX)
        assertEquals((2000f - 540f) / 2, fit.offsetY)
    }

    @Test
    fun pointsConvertBothWays() {
        val view = Viewport(scale = 2f, offsetX = 10f, offsetY = -20f)

        assertEquals(45f, view.toDesktopX(100f))
        assertEquals(60f, view.toDesktopY(100f))
        assertEquals(100f, view.toScreenX(45f))
    }

    @Test
    fun zoomingKeepsThePointUnderTheFingers() {
        val view = Viewport(scale = 1f, offsetX = 0f, offsetY = 0f)
        val before = view.toDesktopX(300f)

        val zoomed = view.zoomed(2f, focusX = 300f, focusY = 200f, minScale = 0.5f, maxScale = 4f)

        assertEquals(2f, zoomed.scale)
        assertEquals(before, zoomed.toDesktopX(300f))
    }

    @Test
    fun zoomStaysWithinItsLimits() {
        assertEquals(4f, Viewport(scale = 3f).zoomed(10f, 0f, 0f, 0.5f, 4f).scale)
        assertEquals(0.5f, Viewport(scale = 1f).zoomed(0.1f, 0f, 0f, 0.5f, 4f).scale)
    }

    @Test
    fun theDesktopNeverLeavesAGapWhenLargerThanTheView() {
        val clamped = Viewport(scale = 1f, offsetX = 50f, offsetY = -5000f).clamped(1000, 1000, viewWidth = 500f, viewHeight = 500f)

        assertEquals(0f, clamped.offsetX)
        assertEquals(-500f, clamped.offsetY)
    }

    @Test
    fun aSmallerDesktopIsCentered() {
        val clamped = Viewport(scale = 1f, offsetX = 999f, offsetY = 0f).clamped(100, 100, viewWidth = 500f, viewHeight = 300f)

        assertEquals(200f, clamped.offsetX)
        assertEquals(100f, clamped.offsetY)
    }

    @Test
    fun theViewFollowsThePointerToTheEdge() {
        val view = Viewport(scale = 1f)

        val revealed = view.revealing(desktopX = 520f, desktopY = 10f, viewWidth = 500f, viewHeight = 500f, margin = 20f)

        assertEquals(500f - 20f - 520f, revealed.offsetX)
        assertEquals(10f, revealed.offsetY)
    }

    @Test
    fun actualSizeShowsOnePixelPerPixel() {
        val fit = Viewport.fit(1920, 1080, viewWidth = 960f, viewHeight = 540f)

        val actual = Viewport.actualSize(fit, viewWidth = 960f, viewHeight = 540f)

        assertEquals(1f, actual.scale)
        assertEquals(fit.toDesktopX(480f), actual.toDesktopX(480f))
    }
}
