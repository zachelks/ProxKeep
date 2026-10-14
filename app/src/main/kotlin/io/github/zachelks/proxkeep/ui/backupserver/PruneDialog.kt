package io.github.zachelks.proxkeep.ui.backupserver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.pbs.PruneOptions
import io.github.zachelks.proxkeep.ui.components.WholeNumberField

/** The largest count a keep option accepts. */
private const val MAX_KEEP = 9_999L

/** The periods a prune keeps snapshots for, in the order the form shows them. */
enum class KeepPeriod(val label: Int) {
    Last(R.string.pbs_keep_last),
    Hourly(R.string.pbs_keep_hourly),
    Daily(R.string.pbs_keep_daily),
    Weekly(R.string.pbs_keep_weekly),
    Monthly(R.string.pbs_keep_monthly),
    Yearly(R.string.pbs_keep_yearly),
}

/**
 * The options in [texts], one per [KeepPeriod]. An empty text leaves the
 * period out. Returns null when a text is not a count, or every one is empty,
 * since the server would then keep everything.
 */
fun pruneOptionsOf(texts: Map<KeepPeriod, String>): PruneOptions? {
    val counts = KeepPeriod.entries.associateWith { period ->
        val text = texts[period].orEmpty()
        if (text.isEmpty()) null else text.toLongOrNull()?.takeIf { it in 1..MAX_KEEP }?.toInt() ?: return null
    }
    val options = PruneOptions(
        last = counts[KeepPeriod.Last],
        hourly = counts[KeepPeriod.Hourly],
        daily = counts[KeepPeriod.Daily],
        weekly = counts[KeepPeriod.Weekly],
        monthly = counts[KeepPeriod.Monthly],
        yearly = counts[KeepPeriod.Yearly],
    )
    return options.takeUnless { it.isEmpty }
}

/**
 * Asks how many snapshots to keep in each period.
 *
 * @param confirmLabel what pressing on does: preview for one group, prune for a whole namespace
 */
@Composable
fun PruneDialog(title: String, body: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (PruneOptions) -> Unit) {
    var texts by rememberSaveable { mutableStateOf(mapOf(KeepPeriod.Last to "", KeepPeriod.Daily to "7", KeepPeriod.Weekly to "4", KeepPeriod.Monthly to "6")) }
    val options = pruneOptionsOf(texts)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                KeepPeriod.entries.forEach { period ->
                    WholeNumberField(
                        label = stringResource(period.label),
                        value = texts[period].orEmpty(),
                        onValueChange = { texts = texts + (period to it) },
                        min = 1,
                        max = MAX_KEEP,
                        error = stringResource(R.string.pbs_keep_invalid, MAX_KEEP),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { options?.let(onConfirm) }, enabled = options != null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
