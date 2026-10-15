package io.github.zachelks.proxkeep.ui.guests

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.RrdPoint
import io.github.zachelks.proxkeep.core.api.model.RrdTimeframe
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.ChartSeries
import io.github.zachelks.proxkeep.ui.components.UsageChart
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.format.formatPercent
import io.github.zachelks.proxkeep.ui.text
import io.github.zachelks.proxkeep.ui.theme.chartSeriesColor

/** CPU, memory, network and disk activity over a chosen period. */
@Composable
fun UsageHistoryCard(
    history: UsageHistory?,
    timeframe: RrdTimeframe,
    onTimeframe: (RrdTimeframe) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.section_usage_history), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RrdTimeframe.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = entry == timeframe,
                        onClick = { onTimeframe(entry) },
                        shape = SegmentedButtonDefaults.itemShape(index, RrdTimeframe.entries.size),
                        icon = {},
                    ) {
                        Text(stringResource(entry.label), maxLines = 1)
                    }
                }
            }
            val points = history?.takeIf { it.timeframe == timeframe }?.points
            history?.error?.let { error ->
                Text(error.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            when {
                points == null -> if (history?.error == null) CenteredProgress(Modifier.padding(16.dp))
                points.none { it.values.keys.any { key -> key in CHARTED } } -> Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> UsageCharts(points, timeframe)
            }
        }
    }
}

private val CHARTED = setOf("cpu", "mem", "netin", "netout", "diskread", "diskwrite")

@Composable
private fun UsageCharts(points: List<RrdPoint>, timeframe: RrdTimeframe) {
    val context = LocalContext.current
    val times = points.map { it.time }
    val formatTime = { seconds: Long -> DateUtils.formatDateTime(context, seconds * 1000L, timeframe.dateFlags) }
    val perSecond = stringResource(R.string.rate_per_second)
    val rate = { value: Double -> perSecond.format(formatBytes(value.toLong())) }
    val first = chartSeriesColor(0)
    val second = chartSeriesColor(1)

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (points.any { it["cpu"] != null }) {
            UsageChart(
                title = stringResource(R.string.usage_cpu),
                times = times,
                series = listOf(ChartSeries(stringResource(R.string.usage_cpu), points.map { it["cpu"] }, first)),
                formatValue = ::formatPercent,
                formatTime = formatTime,
                ceiling = 1.0,
            )
        }
        if (points.any { it["mem"] != null }) {
            UsageChart(
                title = stringResource(R.string.usage_memory),
                times = times,
                series = listOf(ChartSeries(stringResource(R.string.usage_memory), points.map { it["mem"] }, first)),
                formatValue = { formatBytes(it.toLong()) },
                formatTime = formatTime,
                ceiling = points.lastOrNull { it["maxmem"] != null }?.get("maxmem"),
            )
        }
        if (points.any { it["netin"] != null || it["netout"] != null }) {
            UsageChart(
                title = stringResource(R.string.chart_network),
                times = times,
                series = listOf(
                    ChartSeries(stringResource(R.string.series_in), points.map { it["netin"] }, first),
                    ChartSeries(stringResource(R.string.series_out), points.map { it["netout"] }, second),
                ),
                formatValue = rate,
                formatTime = formatTime,
                binaryScale = true,
            )
        }
        if (points.any { it["diskread"] != null || it["diskwrite"] != null }) {
            UsageChart(
                title = stringResource(R.string.chart_disk_io),
                times = times,
                series = listOf(
                    ChartSeries(stringResource(R.string.series_read), points.map { it["diskread"] }, first),
                    ChartSeries(stringResource(R.string.series_write), points.map { it["diskwrite"] }, second),
                ),
                formatValue = rate,
                formatTime = formatTime,
                binaryScale = true,
            )
        }
    }
}

private val RrdTimeframe.label: Int
    get() = when (this) {
        RrdTimeframe.Hour -> R.string.timeframe_hour
        RrdTimeframe.Day -> R.string.timeframe_day
        RrdTimeframe.Week -> R.string.timeframe_week
        RrdTimeframe.Month -> R.string.timeframe_month
        RrdTimeframe.Year -> R.string.timeframe_year
    }

/** How sample times are written: the time of day for short periods, the date for long ones. */
private val RrdTimeframe.dateFlags: Int
    get() = when (this) {
        RrdTimeframe.Hour, RrdTimeframe.Day -> DateUtils.FORMAT_SHOW_TIME
        RrdTimeframe.Week -> DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_WEEKDAY
        RrdTimeframe.Month -> DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH
        RrdTimeframe.Year -> DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    }
