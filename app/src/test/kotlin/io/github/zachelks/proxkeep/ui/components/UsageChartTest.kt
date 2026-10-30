package io.github.zachelks.proxkeep.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageChartTest {

    @Test
    fun gapsInTheDataSplitTheLine() {
        assertEquals(listOf(0..1, 3..3, 5..6), segments(listOf(1.0, 2.0, null, 3.0, null, 4.0, 5.0)))
        assertEquals(emptyList(), segments(listOf(null, null)))
        assertEquals(listOf(1..2), segments(listOf(null, 1.0, 1.0)))
    }

    @Test
    fun touchPicksTheNearestSample() {
        val times = listOf(0L, 60L, 120L, 180L)

        assertEquals(0, nearestIndex(times, x = 0f, width = 300f))
        assertEquals(1, nearestIndex(times, x = 110f, width = 300f))
        assertEquals(3, nearestIndex(times, x = 400f, width = 300f))
        assertNull(nearestIndex(emptyList(), x = 10f, width = 300f))
    }

    @Test
    fun scalesEndOnARoundNumber() {
        assertEquals(1.0, niceCeiling(0.0))
        assertEquals(1.0, niceCeiling(1.0))
        assertEquals(2.0, niceCeiling(1.2))
        assertEquals(500.0, niceCeiling(301.0))
        assertEquals(10_000.0, niceCeiling(5_001.0))
        assertEquals(0.05, niceCeiling(0.031), 1e-12)
    }

    @Test
    fun byteScalesEndOnARoundNumberOfKibOrMib() {
        assertEquals(500.0, niceCeiling(301.0, binary = true))
        assertEquals(100.0 * 1024, niceCeiling(100_000.0, binary = true))
        assertEquals(5.0 * 1024 * 1024, niceCeiling(4.5 * 1024 * 1024, binary = true))
    }
}
