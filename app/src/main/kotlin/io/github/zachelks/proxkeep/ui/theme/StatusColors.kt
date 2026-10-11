package io.github.zachelks.proxkeep.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * How a status should read at a glance. The color is fixed, not taken from
 * the device's dynamic palette, so "running" looks the same on every device.
 * Every use pairs the color with a text label.
 */
enum class StatusTone { Good, Neutral, Warning, Bad }

private val GoodLight = Color(0xFF1B7F3B)
private val GoodDark = Color(0xFF7BD88F)
private val WarningLight = Color(0xFF9A6700)
private val WarningDark = Color(0xFFE3B341)

@Composable
fun StatusTone.color(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        StatusTone.Good -> if (dark) GoodDark else GoodLight
        StatusTone.Warning -> if (dark) WarningDark else WarningLight
        StatusTone.Bad -> MaterialTheme.colorScheme.error
        StatusTone.Neutral -> MaterialTheme.colorScheme.outline
    }
}
