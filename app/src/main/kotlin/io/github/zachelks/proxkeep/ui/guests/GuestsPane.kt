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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.ui.ClusterState
import io.github.zachelks.proxkeep.ui.MainViewModel
import io.github.zachelks.proxkeep.ui.cluster.StaleBanner
import io.github.zachelks.proxkeep.ui.cluster.statusText
import io.github.zachelks.proxkeep.ui.components.MainTopBar
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import kotlinx.coroutines.launch

/**
 * The Guests destination: a list of guests and the detail of the selected
 * one. Phones show one at a time; wider windows show them side by side.
 */
@Composable
fun GuestsPane(viewModel: MainViewModel, cluster: ClusterState.Loaded) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val detail by viewModel.guestDetail.collectAsStateWithLifecycle()
    val busyGuests by viewModel.busyGuests.collectAsStateWithLifecycle()

    val selectedId = navigator.currentDestination?.contentKey
    LaunchedEffect(selectedId) { viewModel.openGuest(selectedId) }
    DisposableEffect(Unit) {
        onDispose { viewModel.openGuest(null) }
    }

    val listIsHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                GuestList(
                    viewModel = viewModel,
                    cluster = cluster,
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
                    val resource = cluster.resources.firstOrNull { it.id == selectedId }
                    GuestDetailPane(
                        resource = resource,
                        detail = detail?.takeIf { it.resourceId == selectedId },
                        busyAction = busyGuests[selectedId],
                        showBack = listIsHidden,
                        onBack = { scope.launch { navigator.navigateBack() } },
                        onAction = { action -> resource?.let { viewModel.runGuestAction(it, action) } },
                    )
                }
            }
        },
    )
}

@Composable
private fun GuestList(
    viewModel: MainViewModel,
    cluster: ClusterState.Loaded,
    selectedId: String?,
    busyGuests: Map<String, GuestAction>,
    onSelect: (String) -> Unit,
) {
    val guests = cluster.resources.filter { it.isGuest }.sortedBy { it.vmid ?: Int.MAX_VALUE }

    Scaffold(
        topBar = { MainTopBar(title = stringResource(R.string.destination_guests), viewModel = viewModel) },
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
