package io.github.zachelks.proxkeep.ui.manager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.pdm.PdmResource
import io.github.zachelks.proxkeep.ui.TopBar
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.backupserver.ShowMessages
import io.github.zachelks.proxkeep.ui.cluster.statusTextOf
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.datacenter.SectionList
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.tasks.TasksPane
import io.github.zachelks.proxkeep.ui.text
import io.github.zachelks.proxkeep.ui.theme.StatusTone

/** The top-level destinations of a Datacenter Manager. */
enum class ManagerDestination(val label: Int, val icon: Int) {
    Remotes(R.string.pdm_remotes, R.drawable.ic_destination_remotes),
    Resources(R.string.pdm_destination_resources, R.drawable.ic_destination_guests),
    Tasks(R.string.destination_tasks, R.drawable.ic_destination_tasks),
    Administration(R.string.pbs_destination_administration, R.drawable.ic_destination_admin),
}

/**
 * The main screen while a Datacenter Manager is signed in: its remotes,
 * their resources across clusters, tasks and administration.
 */
@Composable
fun ManagerHome(topBar: TopBar, snackbars: SnackbarHostState, onOpenRoute: (Route) -> Unit) {
    val container = appContainer()
    val viewModel = viewModel { ManagerViewModel(container.repository, container.refresh) }
    ShowMessages(viewModel.messages, snackbars)
    var destination by rememberSaveable { mutableStateOf(ManagerDestination.Remotes) }
    BackHandler(enabled = destination != ManagerDestination.Remotes) {
        destination = ManagerDestination.Remotes
    }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            ManagerDestination.entries.forEach { entry ->
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
                ManagerDestination.Remotes -> Scaffold(topBar = { topBar(stringResource(destination.label)) }) { innerPadding ->
                    RemotesContent(viewModel, Modifier.padding(innerPadding), onOpenRemote = { remote ->
                        viewModel.setFilter(ResourceFilter.All)
                        viewModel.setQuery(remote)
                        destination = ManagerDestination.Resources
                    })
                }
                ManagerDestination.Resources -> Scaffold(topBar = { topBar(stringResource(destination.label)) }) { innerPadding ->
                    ResourcesContent(viewModel, Modifier.padding(innerPadding), onOpenRoute)
                }
                ManagerDestination.Tasks -> TasksPane(topBar = topBar)
                ManagerDestination.Administration -> Scaffold(topBar = { topBar(stringResource(destination.label)) }) { innerPadding ->
                    SectionList(MANAGER_GROUPS, onOpenRoute, snackbars, Modifier.padding(innerPadding))
                }
            }
            SnackbarHost(hostState = snackbars, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun RemotesContent(viewModel: ManagerViewModel, modifier: Modifier, onOpenRemote: (String) -> Unit) {
    val remotes by viewModel.remotes.collectAsStateWithLifecycle()
    when (val state = remotes) {
        Loadable.Loading -> CenteredProgress(modifier)
        is Loadable.Failed -> MessageWithAction(state.message.text(), modifier, stringResource(R.string.action_retry), viewModel::retry)
        is Loadable.Loaded -> {
            if (state.value.isEmpty()) {
                MessageWithAction(stringResource(R.string.pdm_no_remotes), modifier)
                return
            }
            LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "summary") { SummaryCard(state.value) }
                items(state.value, key = { it.remote }) { remote -> RemoteCard(remote, onClick = { onOpenRemote(remote.remote) }) }
            }
        }
    }
}

@Composable
private fun SummaryCard(remotes: List<RemoteView>) {
    val nodes = remotes.flatMap { it.nodes }
    val guests = remotes.flatMap { it.guests }
    val unreachable = remotes.count { it.error != null }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pluralStringResource(R.plurals.pdm_remote_count, remotes.size, remotes.size), style = MaterialTheme.typography.titleMedium)
            Text(pluralStringResource(R.plurals.pdm_nodes_online, nodes.size, nodes.count { it.isOnline }, nodes.size), style = MaterialTheme.typography.bodyMedium)
            Text(pluralStringResource(R.plurals.pdm_guests_running, guests.size, guests.count { it.status == "running" }, guests.size), style = MaterialTheme.typography.bodyMedium)
            if (unreachable > 0) {
                StatusLabel(pluralStringResource(R.plurals.pdm_remotes_unreachable, unreachable, unreachable), StatusTone.Bad)
            }
        }
    }
}

/** What kind of product a remote is, in words. */
fun remoteTypeLabel(type: String?): Int = when (type) {
    "pve" -> R.string.product_pve
    "pbs" -> R.string.product_pbs
    else -> R.string.pdm_remote_unknown_type
}

@Composable
private fun RemoteCard(remote: RemoteView, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(remote.remote, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(remoteTypeLabel(remote.type)), style = MaterialTheme.typography.bodySmall)
            val error = remote.error
            if (error != null) {
                StatusLabel(stringResource(R.string.pdm_remote_unreachable), StatusTone.Bad)
                Text(error, style = MaterialTheme.typography.bodySmall)
            }
            val nodes = remote.nodes
            if (nodes.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.pdm_nodes_online, nodes.size, nodes.count { it.isOnline }, nodes.size), style = MaterialTheme.typography.bodyMedium)
            }
            val guests = remote.guests
            if (guests.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.pdm_guests_running, guests.size, guests.count { it.status == "running" }, guests.size), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ResourcesContent(viewModel: ManagerViewModel, modifier: Modifier, onOpenRoute: (Route) -> Unit) {
    val remotes by viewModel.remotes.collectAsStateWithLifecycle()
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<RemoteResource?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            label = { Text(stringResource(R.string.pdm_search)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ResourceFilter.entries.forEach { entry ->
                FilterChip(selected = entry == filter, onClick = { viewModel.setFilter(entry) }, label = { Text(stringResource(entry.label)) })
            }
        }
        when (val state = remotes) {
            Loadable.Loading -> CenteredProgress()
            is Loadable.Failed -> MessageWithAction(state.message.text(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::retry)
            is Loadable.Loaded -> {
                val list = shown.orEmpty()
                if (list.isEmpty()) {
                    MessageWithAction(stringResource(R.string.pdm_no_matches))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(list, key = { "${it.remote}:${it.resource.id}" }) { item ->
                            ResourceRow(item, onClick = if (item.resource.isGuest) ({ open = item }) else null)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    open?.let { item ->
        // The dialog follows the guest as it changes, and closes if it goes away.
        val current = shown?.firstOrNull { it.remote == item.remote && it.resource.id == item.resource.id } ?: item
        RemoteGuestDialog(
            remote = current.remote,
            guest = current.resource,
            busy = current.resource.id in busy,
            onAction = { action -> viewModel.run(current.remote, current.resource, action) },
            onOpenRoute = { route ->
                open = null
                onOpenRoute(route)
            },
            onDismiss = { open = null },
        )
    }
}

@Composable
private fun ResourceRow(item: RemoteResource, onClick: (() -> Unit)?) {
    val resource = item.resource
    val status = statusTextOf(resource.status ?: if (resource.isNode && resource.isOnline) "online" else null, resource.template)
    val kind = when (resource.type) {
        "pve-qemu" -> R.string.pdm_kind_vm
        "pve-lxc" -> R.string.pdm_kind_ct
        "pve-node", "pbs-node" -> R.string.pdm_kind_node
        "pve-storage", "pbs-datastore" -> R.string.pdm_kind_storage
        else -> R.string.pdm_kind_other
    }
    val title = listOfNotNull(resource.vmid?.toString(), resource.label).distinct().joinToString(" · ")
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listOfNotNull(stringResource(kind), item.remote, resource.node?.takeIf { !resource.isNode }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                StatusLabel(status.text, status.tone)
            }
        },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/** The words for a guest's kind and ID, such as "VM 100". */
@Composable
fun guestTitle(guest: PdmResource): String =
    stringResource(if (guest.type == "pve-lxc") R.string.pdm_guest_title_ct else R.string.pdm_guest_title_vm, guest.vmid ?: 0, guest.label)
