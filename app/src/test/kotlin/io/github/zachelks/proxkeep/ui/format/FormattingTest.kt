package io.github.zachelks.proxkeep.ui.format

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormattingTest {

    @Test
    fun bytesUseBinaryUnits() {
        assertEquals("0 B", formatBytes(0, Locale.US))
        assertEquals("1023 B", formatBytes(1023, Locale.US))
        assertEquals("1.0 KiB", formatBytes(1024, Locale.US))
        assertEquals("1.5 KiB", formatBytes(1536, Locale.US))
        assertEquals("4.0 GiB", formatBytes(4L * 1024 * 1024 * 1024, Locale.US))
        assertEquals("15.6 GiB", formatBytes(16_777_216_000L, Locale.US))
        assertEquals("0 B", formatBytes(-5, Locale.US))
    }

    @Test
    fun bytesFollowTheLocaleDecimalMark() {
        assertEquals("1,5 KiB", formatBytes(1536, Locale.GERMANY))
    }

    @Test
    fun percentIsClampedAndRounded() {
        assertEquals("0%", formatPercent(0.0))
        assertEquals("12%", formatPercent(0.123))
        assertEquals("100%", formatPercent(1.7))
        assertEquals("0%", formatPercent(-0.2))
        assertEquals("0%", formatPercent(Double.NaN))
    }

    @Test
    fun usageNeedsAKnownTotal() {
        assertEquals(0.25, usageFraction(1, 4))
        assertEquals(1.0, usageFraction(9, 4))
        assertNull(usageFraction(1, 0))
        assertNull(usageFraction(null, 4))
        assertNull(usageFraction(1, null))
    }

    @Test
    fun uptimeShowsTheTwoLargestUnits() {
        assertEquals("1d 2h", formatUptime(93_784))
        assertEquals("2h 5m", formatUptime(7_500))
        assertEquals("5m", formatUptime(330))
        assertEquals("42s", formatUptime(42))
        assertEquals("0s", formatUptime(-1))
    }
}
