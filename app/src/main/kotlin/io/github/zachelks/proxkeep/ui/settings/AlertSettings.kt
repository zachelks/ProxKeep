package io.github.zachelks.proxkeep.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.AlertInterval
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CheckRow
import io.github.zachelks.proxkeep.ui.components.RadioChoices

/** Background checks for failed tasks and offline nodes, and how often they run. */
@Composable
fun AlertSettings() {
    val settings = appContainer().settings
    val context = LocalContext.current
    val enabled by settings.alertsEnabled.collectAsStateWithLifecycle()
    val kinds by settings.alertKinds.collectAsStateWithLifecycle()
    val interval by settings.alertInterval.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) settings.setAlertsEnabled(true)
    }
    val labels = AlertInterval.entries.associateWith { stringResource(it.label) }

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_alerts)) },
        supportingContent = { Text(stringResource(R.string.settings_alerts_summary)) },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = { on ->
                val needsPermission = on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsPermission) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else settings.setAlertsEnabled(on)
            })
        },
    )
    if (enabled) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            CheckRow(stringResource(R.string.settings_alerts_tasks), kinds.failedTasks) { settings.setAlertKinds(kinds.copy(failedTasks = it)) }
            CheckRow(stringResource(R.string.settings_alerts_nodes), kinds.offlineNodes) { settings.setAlertKinds(kinds.copy(offlineNodes = it)) }
            Text(
                stringResource(R.string.settings_alerts_every),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            RadioChoices(AlertInterval.entries, interval, label = { labels.getValue(it) }) { settings.setAlertInterval(it) }
            Text(
                stringResource(R.string.settings_alerts_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

private val AlertInterval.label: Int
    get() = when (this) {
        AlertInterval.FifteenMinutes -> R.string.alert_interval_15
        AlertInterval.ThirtyMinutes -> R.string.alert_interval_30
        AlertInterval.OneHour -> R.string.alert_interval_60
        AlertInterval.ThreeHours -> R.string.alert_interval_180
    }
