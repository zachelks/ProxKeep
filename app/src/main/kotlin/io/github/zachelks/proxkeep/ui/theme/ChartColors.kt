package io.github.zachelks.proxkeep.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Fixed series colors, so a chart reads the same on every device. The pair
// was checked for color-vision deficiency separation, with its own step for
// each theme.
private val SeriesLight = listOf(Color(0xFF2A78D6), Color(0xFFEB6834))
private val SeriesDark = listOf(Color(0xFF3987E5), Color(0xFFD95926))

/** The color of a chart's first or second series. Charts never show more than two. */
@Composable
fun chartSeriesColor(slot: Int): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return (if (dark) SeriesDark else SeriesLight)[slot.coerceIn(0, 1)]
}
