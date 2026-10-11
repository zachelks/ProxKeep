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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.data.ServerProfile
import io.github.zachelks.proxkeep.ui.ClusterState
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.components.UsageBar
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.format.formatUptime
import io.github.zachelks.proxkeep.ui.format.usageFraction
import io.github.zachelks.proxkeep.ui.text

private val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/** Shown above a list when its data could not be refreshed. */
@Composable
private fun StaleBanner(state: ClusterState.Loaded) {
    val error = state.refreshError ?: return
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
fun OverviewScreen(profile: ServerProfile, state: ClusterState.Loaded, modifier: Modifier = Modifier) {
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
                            state.version?.let { stringResource(R.string.version_label, it.version) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.summary_nodes, online, nodes.size) + " · " +
                            stringResource(R.string.summary_guests, running, guests.size),
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
        items(nodes, key = { it.id }) { node -> NodeCard(node) }
    }
}

@Composable
private fun NodeCard(node: ClusterResource) {
    val status = node.statusText()
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                    detail = stringResource(R.string.usage_cpu_cores, cores),
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

// --- Guests ------------------------------------------------------------------

@Composable
fun GuestsScreen(state: ClusterState.Loaded, modifier: Modifier = Modifier) {
    val guests = state.resources.filter { it.isGuest }.sortedBy { it.vmid ?: Int.MAX_VALUE }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (state.refreshError != null) {
            item(key = "stale") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { StaleBanner(state) }
            }
        }
        if (guests.isEmpty()) {
            item(key = "empty") {
                MessageWithAction(message = stringResource(R.string.no_guests))
            }
        }
        items(guests, key = { it.id }) { guest ->
            GuestRow(guest)
            HorizontalDivider()
        }
    }
}

@Composable
private fun GuestRow(guest: ClusterResource) {
    val status = guest.statusText()
    val kind = stringResource(if (guest.kind == ResourceKind.Lxc) R.string.kind_container else R.string.kind_vm)
    val details = listOfNotNull(
        guest.vmid?.toString(),
        kind,
        guest.node,
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(guest.name ?: guest.id) },
        supportingContent = {
            Column {
                Text(details)
                if (guest.tagList.isNotEmpty()) {
                    Text(
                        text = guest.tagList.joinToString("  ") { "#$it" },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        trailingContent = { StatusLabel(text = status.text, tone = status.tone) },
    )
}

// --- Infrastructure ----------------------------------------------------------

@Composable
fun InfrastructureScreen(state: ClusterState.Loaded, modifier: Modifier = Modifier) {
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
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(node.node ?: node.id) },
                    supportingContent = {
                        node.uptime?.takeIf { it > 0 }?.let {
                            Text(stringResource(R.string.uptime_label, formatUptime(it)))
                        }
                    },
                    trailingContent = { StatusLabel(text = status.text, tone = status.tone) },
                )
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
