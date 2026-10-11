package io.github.zachelks.proxkeep.ui.format

import java.util.Locale
import kotlin.math.roundToInt

private val BYTE_UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
private const val BYTES_PER_STEP = 1024.0

/** Formats a size the way Proxmox does, in binary units: `1536` becomes `1.5 KiB`. */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < BYTES_PER_STEP) return "${bytes.coerceAtLeast(0)} B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= BYTES_PER_STEP && unit < BYTE_UNITS.lastIndex) {
        value /= BYTES_PER_STEP
        unit++
    }
    return String.format(locale, "%.1f %s", value, BYTE_UNITS[unit])
}

/** Formats a fraction from 0 to 1 as a whole percentage. */
fun formatPercent(fraction: Double): String {
    val safe = if (fraction.isNaN()) 0.0 else fraction.coerceIn(0.0, 1.0)
    return "${(safe * 100).roundToInt()}%"
}

/** Returns [used] divided by [total], or null when the total is unknown or zero. */
fun usageFraction(used: Long?, total: Long?): Double? {
    if (used == null || total == null || total <= 0) return null
    return (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
}

/** The two largest units of a duration: `93784` seconds becomes `1d 2h`. */
fun formatUptime(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val days = total / 86_400
    val hours = total % 86_400 / 3_600
    val minutes = total % 3_600 / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${total}s"
    }
}
