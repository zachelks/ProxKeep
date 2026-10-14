package io.github.zachelks.proxkeep.ui.console

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleViewClientTest {

    private val reported = mutableListOf<Int>()
    private val client = ConsoleViewClient(
        modifiers = ModifierKeys(),
        textSizeSp = 14,
        textSizeRange = 7..36,
        toPixels = { it * 3 },
        onTextSizeChanged = { reported += it },
    )

    @Test
    fun pinchingChangesTheSizeOneStepAndReportsIt() {
        client.onScale(1.2f)
        client.onScale(0.8f)
        client.onScale(0.8f)

        assertEquals(listOf(15, 14, 13), reported)
        assertEquals(39, client.textSizePixels)
    }

    @Test
    fun smallPinchesAreIgnored() {
        assertEquals(1.05f, client.onScale(1.05f))
        assertEquals(emptyList(), reported)
    }

    @Test
    fun theSizeStaysInRangeAndOutsideChangesAreNotReported() {
        client.applyTextSize(40)
        client.onScale(1.5f)

        assertEquals(36, client.textSizeSp)
        assertEquals(emptyList(), reported)
    }
}
