package io.github.zachelks.proxkeep.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.zachelks.proxkeep.R
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * One line on a [UsageChart].
 *
 * @property values one per sample time, null where nothing was recorded
 */
data class ChartSeries(val label: String, val values: List<Double?>, val color: Color)

/**
 * A line chart of one measure over time, with at most two series on one
 * scale. Touching the chart shows the values at that moment.
 *
 * @param times sample times in seconds since the epoch, oldest first
 * @param ceiling the top of the scale, when the measure has a natural one such as 100%
 * @param binaryScale round the top of the scale in steps of 1024, for sizes in bytes
 */
@Composable
fun UsageChart(
    title: String,
    times: List<Long>,
    series: List<ChartSeries>,
    formatValue: (Double) -> String,
    formatTime: (Long) -> String,
    modifier: Modifier = Modifier,
    ceiling: Double? = null,
    binaryScale: Boolean = false,
) {
    var selected by remember(times) { mutableStateOf<Int?>(null) }
    val top = ceiling?.takeIf { it > 0 } ?: niceCeiling(series.flatMap { it.values }.filterNotNull().maxOrNull() ?: 0.0, binaryScale)
    val seriesTemplate = stringResource(R.string.chart_series_description)
    val description = stringResource(R.string.chart_description, title) + " " + series.joinToString(" ") { line ->
        val latest = line.values.lastOrNull { it != null }
        val peak = line.values.filterNotNull().maxOrNull()
        seriesTemplate.format(line.label, latest?.let(formatValue) ?: "–", peak?.let(formatValue) ?: "–")
    }

    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val index = selected
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = if (index != null) formatTime(times[index]) else stringResource(R.string.chart_latest),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            series.forEach { line ->
                val value = if (index != null) line.values.getOrNull(index) else line.values.lastOrNull { it != null }
                LegendEntry(
                    color = line.color,
                    text = if (series.size > 1) "${line.label} ${value?.let(formatValue) ?: "–"}" else value?.let(formatValue) ?: "–",
                )
            }
        }

        val grid = MaterialTheme.colorScheme.outlineVariant
        val crosshair = MaterialTheme.colorScheme.onSurface
        val surface = MaterialTheme.colorScheme.surface
        val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        val measurer = rememberTextMeasurer()
        val topLabel = formatValue(top)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(times) {
                    detectTapGestures { offset ->
                        val tapped = nearestIndex(times, offset.x, size.width.toFloat())
                        selected = if (selected == tapped) null else tapped
                    }
                }
                .pointerInput(times) {
                    detectHorizontalDragGestures(
                        onDragEnd = { selected = null },
                        onDragCancel = { selected = null },
                    ) { change, _ -> selected = nearestIndex(times, change.position.x, size.width.toFloat()) }
                },
        ) {
            if (times.size < 2) return@Canvas
            val hairline = 1.dp.toPx()
            for (y in listOf(hairline / 2, size.height / 2, size.height - hairline / 2)) {
                drawLine(grid, Offset(0f, y), Offset(size.width, y), hairline)
            }
            drawText(measurer, topLabel, Offset(0f, 2.dp.toPx()), style = labelStyle)

            val start = times.first()
            val span = (times.last() - start).coerceAtLeast(1).toFloat()
            fun x(i: Int) = (times[i] - start) / span * size.width
            fun y(value: Double) = (size.height * (1 - (value / top).coerceIn(0.0, 1.0))).toFloat()

            series.forEach { line ->
                val segments = segments(line.values)
                if (series.size == 1) {
                    segments.forEach { range -> drawArea(range, ::x, { y(line.values[it]!!) }, line.color.copy(alpha = 0.15f)) }
                }
                segments.forEach { range ->
                    val path = Path()
                    range.forEachIndexed { n, i -> if (n == 0) path.moveTo(x(i), y(line.values[i]!!)) else path.lineTo(x(i), y(line.values[i]!!)) }
                    drawPath(path, line.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    if (range.first == range.last) drawCircle(line.color, 2.dp.toPx(), Offset(x(range.first), y(line.values[range.first]!!)))
                }
            }

            val index = selected ?: return@Canvas
            drawLine(crosshair.copy(alpha = 0.5f), Offset(x(index), 0f), Offset(x(index), size.height), hairline)
            series.forEach { line ->
                val value = line.values.getOrNull(index) ?: return@forEach
                val point = Offset(x(index), y(value))
                drawCircle(surface, 6.dp.toPx(), point)
                drawCircle(line.color, 4.dp.toPx(), point)
            }
        }
        if (times.size >= 2) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(times.first()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(times.last()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LegendEntry(color: Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(width = 12.dp, height = 8.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), cap = StrokeCap.Round)
        }
        Box { Text(text = text, style = MaterialTheme.typography.labelMedium) }
    }
}

private fun DrawScope.drawArea(range: IntRange, x: (Int) -> Float, y: (Int) -> Float, color: Color) {
    if (range.first == range.last) return
    val path = Path().apply {
        moveTo(x(range.first), size.height)
        range.forEach { lineTo(x(it), y(it)) }
        lineTo(x(range.last), size.height)
        close()
    }
    drawPath(path, color)
}

/** The runs of consecutive recorded values, so a gap in the data is a gap in the line. */
internal fun segments(values: List<Double?>): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    var start = -1
    values.forEachIndexed { i, value ->
        if (value != null && start < 0) start = i
        if (value == null && start >= 0) {
            runs += start until i
            start = -1
        }
    }
    if (start >= 0) runs += start..values.lastIndex
    return runs
}

/** The sample nearest to [x] on a chart [width] wide. */
internal fun nearestIndex(times: List<Long>, x: Float, width: Float): Int? {
    if (times.isEmpty() || width <= 0f) return null
    val start = times.first()
    val span = (times.last() - start).coerceAtLeast(1)
    val target = start + (x / width).coerceIn(0f, 1f) * span
    return times.indices.minByOrNull { abs(times[it] - target) }
}

/**
 * A round number at or above [value] to top a scale with: 1, 2 or 5 times a
 * power of ten. With [binary], the number is round once shown in KiB, MiB and
 * so on, the way sizes are written.
 */
internal fun niceCeiling(value: Double, binary: Boolean = false): Double {
    if (value <= 0.0 || value.isNaN()) return 1.0
    if (binary && value >= 1024.0) {
        var unit = 1.0
        while (value / unit >= 1024.0) unit *= 1024.0
        return niceCeiling(value / unit) * unit
    }
    val magnitude = 10.0.pow(floor(log10(value)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).first { it * magnitude >= value * (1 - 1e-9) }
    return step * magnitude
}
