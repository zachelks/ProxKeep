package io.github.zachelks.proxkeep.ui.backupserver

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.pbs.BackupServerStatus
import io.github.zachelks.proxkeep.core.api.pbs.DatastoreUsage
import io.github.zachelks.proxkeep.ui.TopBar
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.components.UsageBar
import io.github.zachelks.proxkeep.ui.datacenter.SectionList
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.format.formatUptime
import io.github.zachelks.proxkeep.ui.format.usageFraction
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.tasks.TasksPane
import io.github.zachelks.proxkeep.ui.text
import io.github.zachelks.proxkeep.ui.theme.StatusTone

/** The top-level destinations of a Backup Server. */
enum class BackupServerDestination(val label: Int, val icon: Int) {
    Datastores(R.string.pbs_destination_datastores, R.drawable.ic_destination_datastores),
    Tasks(R.string.destination_tasks, R.drawable.ic_destination_tasks),
    Administration(R.string.pbs_destination_administration, R.drawable.ic_destination_admin),
}

/**
 * The main screen while a Backup Server is signed in: its datastores, its
 * tasks and its administration.
 */
@Composable
fun BackupServerHome(topBar: TopBar, snackbars: SnackbarHostState, onOpenRoute: (Route) -> Unit) {
    var destination by rememberSaveable { mutableStateOf(BackupServerDestination.Datastores) }
    BackHandler(enabled = destination != BackupServerDestination.Datastores) {
        destination = BackupServerDestination.Datastores
    }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            BackupServerDestination.entries.forEach { entry ->
                item(
                    selected = entry == destination,
                    onClick = { destination = entry },
                    icon = { Icon(painterResource(entry.icon), contentDescription = null) },
                    label = { Text(stringResource(entry.label)) },
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (destination) {
                BackupServerDestination.Datastores -> Scaffold(topBar = { topBar(stringResource(destination.label)) }) { innerPadding ->
                    DatastoresContent(Modifier.padding(innerPadding), onOpenRoute)
                }
                BackupServerDestination.Tasks -> TasksPane(topBar = topBar)
                BackupServerDestination.Administration -> Scaffold(topBar = { topBar(stringResource(destination.label)) }) { innerPadding ->
                    SectionList(BACKUP_SERVER_GROUPS, onOpenRoute, snackbars, Modifier.padding(innerPadding))
                }
            }
            SnackbarHost(hostState = snackbars, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun DatastoresContent(modifier: Modifier, onOpenRoute: (Route) -> Unit) {
    val container = appContainer()
    val viewModel = viewModel { DatastoresViewModel(container.repository, container.refresh) }
    val status by viewModel.status.collectAsStateWithLifecycle()
    val datastores by viewModel.datastores.collectAsStateWithLifecycle()

    when (val state = datastores) {
        Loadable.Loading -> CenteredProgress(modifier)
        is Loadable.Failed -> MessageWithAction(state.message.text(), modifier, stringResource(R.string.action_retry), viewModel::retry)
        is Loadable.Loaded -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            (status as? Loadable.Loaded)?.value?.let { server ->
                item(key = "server") { ServerCard(server) }
            }
            if (state.value.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.pbs_no_datastores), style = MaterialTheme.typography.bodyLarge) }
            }
            items(state.value, key = { it.store }) { usage ->
                DatastoreCard(usage, onClick = { onOpenRoute(Route.Datastore(usage.store)) })
            }
        }
    }
}

@Composable
private fun ServerCard(server: BackupServerStatus) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pbs_server), style = MaterialTheme.typography.titleMedium)
            server.cpu?.let { cpu ->
                UsageBar(stringResource(R.string.usage_cpu), cpu.coerceIn(0.0, 1.0), stringResource(R.string.pbs_cpu_detail))
            }
            server.memory?.let { memory ->
                usageFraction(memory.used, memory.total)?.let { fraction ->
                    UsageBar(stringResource(R.string.usage_memory), fraction, stringResource(R.string.used_of_total, formatBytes(memory.used ?: 0), formatBytes(memory.total ?: 0)))
                }
            }
            server.root?.let { root ->
                usageFraction(root.used, root.total)?.let { fraction ->
                    UsageBar(stringResource(R.string.usage_root_disk), fraction, stringResource(R.string.used_of_total, formatBytes(root.used ?: 0), formatBytes(root.total ?: 0)))
                }
            }
            server.uptime?.let { Text(stringResource(R.string.pbs_uptime, formatUptime(it)), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** A datastore's space, its last garbage collection and when it will be full. */
@Composable
fun DatastoreCard(usage: DatastoreUsage, onClick: (() -> Unit)?) {
    val context = LocalContext.current
    OutlinedCard(modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(usage.store, style = MaterialTheme.typography.titleMedium)
            val error = usage.error
            if (error != null) {
                StatusLabel(stringResource(R.string.pbs_datastore_unavailable), StatusTone.Bad)
                Text(error, style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            usageFraction(usage.used, usage.total)?.let { fraction ->
                UsageBar(
                    label = stringResource(R.string.usage_space),
                    fraction = fraction,
                    detail = stringResource(R.string.used_of_total, formatBytes(usage.used ?: 0), formatBytes(usage.total ?: 0)),
                )
            }
            usage.estimatedFullDate?.takeIf { it > 0 }?.let { seconds ->
                Text(
                    stringResource(R.string.pbs_estimated_full, DateUtils.formatDateTime(context, seconds * 1000L, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            usage.gcStatus?.deduplicationFactor?.let { factor ->
                Text(stringResource(R.string.pbs_deduplication, factor), style = MaterialTheme.typography.bodyMedium)
            }
            usage.gcStatus?.pendingBytes?.takeIf { it > 0 }?.let { pending ->
                Text(stringResource(R.string.pbs_gc_pending, formatBytes(pending)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
