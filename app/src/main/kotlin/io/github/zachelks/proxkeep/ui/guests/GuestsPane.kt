@file:OptIn(ExperimentalMaterial3AdaptiveApi::class)

package io.github.zachelks.proxkeep.ui.guests

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.Permissions
import io.github.zachelks.proxkeep.core.api.model.Privilege
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.ServerAccess
import io.github.zachelks.proxkeep.ui.TopBar
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.cluster.ClusterContent
import io.github.zachelks.proxkeep.ui.cluster.StaleBanner
import io.github.zachelks.proxkeep.ui.cluster.statusText
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import kotlinx.coroutines.launch

/**
 * The Guests destination: a list of guests and the detail of the selected
 * one. Phones show one at a time; wider windows show them side by side.
 *
 * @param onConsoleOpened called after a guest's console has been opened, to show it
 */
@Composable
fun GuestsPane(topBar: TopBar, onConsoleOpened: () -> Unit) {
    val container = appContainer()
    val viewModel = viewModel {
        GuestsViewModel(
            container.repository,
            container.cluster,
            container.access,
            container.guestActions,
            container.consoles,
            container.refresh,
        )
    }
    val cluster by viewModel.cluster.collectAsStateWithLifecycle()
    val loaded = cluster as? ClusterState.Loaded
    if (loaded == null) {
        Scaffold(topBar = { topBar(stringResource(R.string.destination_guests)) }) { innerPadding ->
            ClusterContent(state = cluster, onRetry = viewModel::retry, modifier = Modifier.padding(innerPadding)) {}
        }
        return
    }

    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val busyGuests by viewModel.busyGuests.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()

    val selectedId = navigator.currentDestination?.contentKey
    LaunchedEffect(selectedId) { viewModel.select(selectedId) }

    val listIsHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                GuestList(
                    topBar = topBar,
                    cluster = loaded,
                    selectedId = selectedId,
                    busyGuests = busyGuests,
                    onSelect = { id ->
                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) }
                    },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                if (selectedId == null) {
                    MessageWithAction(message = stringResource(R.string.guest_select_prompt))
                } else {
                    val resource = loaded.resources.firstOrNull { it.id == selectedId }
                    // Keyed by guest so an open menu or confirmation never carries over to another guest.
                    key(selectedId) {
                        GuestDetailPane(
                            resource = resource,
                            detail = detail?.takeIf { it.resourceId == selectedId },
                            busyAction = busyGuests[selectedId],
                            canPowerManage = resource != null && access.allowsOn(resource, Privilege.VM_POWER_MGMT),
                            canOpenConsole = resource != null && access.allowsOn(resource, Privilege.VM_CONSOLE),
                            showBack = listIsHidden,
                            onBack = { scope.launch { navigator.navigateBack() } },
                            onAction = { action -> resource?.let { viewModel.runAction(it, action) } },
                            onOpenConsole = {
                                resource?.let {
                                    viewModel.openConsole(it)
                                    onConsoleOpened()
                                }
                            },
                        )
                    }
                }
            }
        },
    )
}

/** Whether to offer something that needs [privilege] on [guest]. */
private fun ServerAccess?.allowsOn(guest: ClusterResource, privilege: String): Boolean {
    val vmid = guest.vmid ?: return true
    return this?.allows(Permissions.guestPaths(vmid, guest.pool), privilege) ?: true
}

@Composable
private fun GuestList(
    topBar: TopBar,
    cluster: ClusterState.Loaded,
    selectedId: String?,
    busyGuests: Map<String, GuestAction>,
    onSelect: (String) -> Unit,
) {
    val guests = cluster.resources.filter { it.isGuest }.sortedBy { it.vmid ?: Int.MAX_VALUE }

    Scaffold(
        topBar = { topBar(stringResource(R.string.destination_guests)) },
    ) { innerPadding ->
        if (guests.isEmpty()) {
            MessageWithAction(
                message = stringResource(R.string.no_guests),
                modifier = Modifier.padding(innerPadding),
            )
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (cluster.refreshError != null) {
                item(key = "stale") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { StaleBanner(cluster) }
                }
            }
            items(guests, key = { it.id }) { guest ->
                GuestRow(
                    guest = guest,
                    selected = guest.id == selectedId,
                    busy = busyGuests.containsKey(guest.id),
                    onClick = { onSelect(guest.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun GuestRow(guest: ClusterResource, selected: Boolean, busy: Boolean, onClick: () -> Unit) {
    val status = guest.statusText()
    val kind = stringResource(if (guest.kind == ResourceKind.Lxc) R.string.kind_container else R.string.kind_vm)
    val details = listOfNotNull(guest.vmid?.toString(), kind, guest.node).joinToString(" · ")
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
        trailingContent = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                StatusLabel(text = status.text, tone = status.tone)
            }
        },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
