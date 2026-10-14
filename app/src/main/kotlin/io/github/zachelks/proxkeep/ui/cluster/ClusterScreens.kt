package io.github.zachelks.proxkeep.ui.cluster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.Permissions
import io.github.zachelks.proxkeep.core.api.model.Privilege
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.VersionInfo
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.ServerProfile
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.components.UsageBar
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.format.formatUptime
import io.github.zachelks.proxkeep.ui.format.usageFraction
import io.github.zachelks.proxkeep.ui.text
import io.github.zachelks.proxkeep.ui.toUiMessage

private val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

@Composable
private fun clusterViewModel(): ClusterViewModel {
    val container = appContainer()
    return viewModel { ClusterViewModel(container.cluster, container.access, container.consoles, container.refresh) }
}

/**
 * Shows [content] once the cluster has loaded, and the shared loading and
 * failure states until then.
 */
@Composable
fun ClusterContent(
    state: ClusterState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (ClusterState.Loaded) -> Unit,
) {
    when (state) {
        ClusterState.Loading -> CenteredProgress(modifier)
        is ClusterState.Failed -> MessageWithAction(
            message = state.error.toUiMessage().text(),
            modifier = modifier,
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRetry,
        )
        is ClusterState.Loaded -> content(state)
    }
}

/** Shown above a list when its data could not be refreshed. */
@Composable
fun StaleBanner(state: ClusterState.Loaded) {
    val error = state.refreshError?.toUiMessage() ?: return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.refresh_failed) + "\n" + error.text(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

// --- Overview ----------------------------------------------------------------

@Composable
fun OverviewRoute(profile: ServerProfile, onOpenRoute: (Route) -> Unit = {}) {
    val viewModel = clusterViewModel()
    val cluster by viewModel.cluster.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()
    ClusterContent(state = cluster, onRetry = viewModel::retry) { loaded ->
        OverviewScreen(profile = profile, state = loaded, version = access?.version, onOpenNode = { onOpenRoute(Route.Node(it)) })
    }
}

@Composable
fun OverviewScreen(
    profile: ServerProfile,
    state: ClusterState.Loaded,
    version: VersionInfo?,
    modifier: Modifier = Modifier,
    onOpenNode: (node: String) -> Unit = {},
) {
    val nodes = state.resources.filter { it.kind == ResourceKind.Node }.sortedBy { it.node }
    val guests = state.resources.filter { it.isGuest && !it.isTemplate }
    val running = guests.count { it.status == "running" }
    val online = nodes.count { it.status == "online" }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.refreshError != null) {
            item(key = "stale") { StaleBanner(state) }
        }
        item(key = "summary") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = profile.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = listOfNotNull(
                            profile.address.display,
                            version?.let { stringResource(R.string.version_label, it.version) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.summary_nodes, nodes.size, online, nodes.size) + " · " +
                            pluralStringResource(R.plurals.summary_guests, guests.size, running, guests.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (nodes.isEmpty()) {
            item(key = "no-nodes") {
                Text(
                    text = stringResource(R.string.no_nodes_visible),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(nodes, key = { it.id }) { node -> NodeCard(node, onClick = { node.node?.let(onOpenNode) }) }
    }
}

@Composable
private fun NodeCard(node: ClusterResource, onClick: () -> Unit) {
    val status = node.statusText()
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = node.node ?: node.id, style = MaterialTheme.typography.titleMedium)
                StatusLabel(text = status.text, tone = status.tone)
            }
            node.cpu?.let { cpu ->
                val cores = node.maxcpu?.toInt() ?: 0
                UsageBar(
                    label = stringResource(R.string.usage_cpu),
                    fraction = cpu,
                    detail = pluralStringResource(R.plurals.usage_cpu_cores, cores, cores),
                )
            }
            usageFraction(node.mem, node.maxmem)?.let { fraction ->
                UsageBar(
                    label = stringResource(R.string.usage_memory),
                    fraction = fraction,
                    detail = usedOfTotal(node.mem, node.maxmem),
                )
            }
            usageFraction(node.disk, node.maxdisk)?.let { fraction ->
                UsageBar(
                    label = stringResource(R.string.usage_root_disk),
                    fraction = fraction,
                    detail = usedOfTotal(node.disk, node.maxdisk),
                )
            }
            node.uptime?.takeIf { it > 0 }?.let { uptime ->
                Text(
                    text = stringResource(R.string.uptime_label, formatUptime(uptime)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun usedOfTotal(used: Long?, total: Long?): String =
    stringResource(R.string.used_of_total, formatBytes(used ?: 0), formatBytes(total ?: 0))

// --- Infrastructure ----------------------------------------------------------

/** @param onConsoleOpened called after a node shell has been opened, to show it */
@Composable
fun InfrastructureRoute(onConsoleOpened: () -> Unit, onOpenRoute: (Route) -> Unit = {}) {
    val viewModel = clusterViewModel()
    val cluster by viewModel.cluster.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()
    ClusterContent(state = cluster, onRetry = viewModel::retry) { loaded ->
        InfrastructureScreen(
            state = loaded,
            canOpenShell = { node ->
                access?.allows(listOf(Permissions.nodePath(node)), Privilege.SYS_CONSOLE) ?: true
            },
            onOpenShell = { node ->
                viewModel.openNodeShell(node)
                onConsoleOpened()
            },
            onOpenNode = { node -> onOpenRoute(Route.Node(node)) },
        )
    }
}

@Composable
fun InfrastructureScreen(
    state: ClusterState.Loaded,
    canOpenShell: (node: String) -> Boolean,
    onOpenShell: (node: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenNode: (node: String) -> Unit = {},
) {
    val nodes = state.resources.filter { it.kind == ResourceKind.Node }.sortedBy { it.node }
    val storages = state.resources
        .filter { it.kind == ResourceKind.Storage }
        .sortedWith(compareBy({ it.storage }, { it.node }))

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.refreshError != null) {
            item(key = "stale") { StaleBanner(state) }
        }

        item(key = "nodes-title") { SectionTitle(stringResource(R.string.section_nodes)) }
        items(nodes, key = { it.id }) { node ->
            val status = node.statusText()
            OutlinedCard(onClick = { node.node?.let(onOpenNode) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(node.node ?: node.id) },
                    supportingContent = {
                        node.uptime?.takeIf { it > 0 }?.let {
                            Text(stringResource(R.string.uptime_label, formatUptime(it)))
                        }
                    },
                    trailingContent = { StatusLabel(text = status.text, tone = status.tone) },
                )
                val name = node.node
                if (name != null && node.status == "online" && canOpenShell(name)) {
                    TextButton(
                        onClick = { onOpenShell(name) },
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    ) {
                        Text(stringResource(R.string.action_open_shell))
                    }
                }
            }
        }

        item(key = "storage-title") { SectionTitle(stringResource(R.string.section_storage)) }
        if (storages.isEmpty()) {
            item(key = "no-storage") {
                Text(
                    text = stringResource(R.string.no_storage_visible),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(storages, key = { it.id }) { storage -> StorageCard(storage) }
    }
}

@Composable
private fun StorageCard(storage: ClusterResource) {
    val status = storage.statusText()
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(text = storage.storage ?: storage.id, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = listOfNotNull(storage.node, storage.plugintype).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusLabel(text = status.text, tone = status.tone)
            }
            usageFraction(storage.disk, storage.maxdisk)?.let { fraction ->
                UsageBar(
                    label = stringResource(R.string.usage_space),
                    fraction = fraction,
                    detail = usedOfTotal(storage.disk, storage.maxdisk),
                )
            }
        }
    }
}
