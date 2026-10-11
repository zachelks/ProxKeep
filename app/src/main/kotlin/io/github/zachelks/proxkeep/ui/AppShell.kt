@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.data.ServerProfile
import io.github.zachelks.proxkeep.data.Session
import io.github.zachelks.proxkeep.data.StoredServers
import io.github.zachelks.proxkeep.ui.addserver.AddServerFlow
import io.github.zachelks.proxkeep.ui.cluster.GuestsScreen
import io.github.zachelks.proxkeep.ui.cluster.InfrastructureScreen
import io.github.zachelks.proxkeep.ui.cluster.OverviewScreen
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import kotlinx.coroutines.delay

private const val REFRESH_INTERVAL_MS = 10_000L

/**
 * Top-level frame of the app. [NavigationSuiteScaffold] shows a bottom bar on
 * phones and a navigation rail on wider windows.
 */
@Composable
fun AppShell(viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)) {
    var destination by rememberSaveable { mutableStateOf(TopLevelDestination.Overview) }

    val flow = viewModel.addServer
    if (flow != null) {
        AddServerFlow(state = flow, onClose = viewModel::closeAddServer)
        return
    }

    val session by viewModel.session.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val cluster by viewModel.cluster.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()

    RefreshWhileVisible(activeProfileId = (session as? Session.Active)?.profile?.id, onRefresh = viewModel::refresh)

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { entry ->
                item(
                    selected = entry == destination,
                    onClick = { destination = entry },
                    icon = { Icon(painter = painterResource(entry.icon), contentDescription = null) },
                    label = { Text(stringResource(entry.label)) },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(destination.label)) },
                    actions = {
                        if (session is Session.Active) {
                            IconButton(onClick = viewModel::refreshNow) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = stringResource(R.string.action_refresh),
                                )
                            }
                        }
                        ServerMenu(
                            session = session,
                            servers = servers,
                            onAddServer = viewModel::startAddServer,
                            onSwitch = viewModel::activate,
                            onSignOut = viewModel::signOut,
                            onRemove = viewModel::remove,
                        )
                    },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                DestinationContent(
                    destination = destination,
                    loaded = loaded,
                    session = session,
                    cluster = cluster,
                    onAddServer = viewModel::startAddServer,
                    onSignIn = viewModel::startSignIn,
                    onRetry = viewModel::refreshNow,
                )
            }
        }
    }
}

/** Refreshes on a timer, but only while the app is on screen and signed in. */
@Composable
private fun RefreshWhileVisible(activeProfileId: String?, onRefresh: suspend () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(activeProfileId, lifecycleOwner) {
        if (activeProfileId == null) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                onRefresh()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }
}

@Composable
private fun DestinationContent(
    destination: TopLevelDestination,
    loaded: Boolean,
    session: Session,
    cluster: ClusterState,
    onAddServer: () -> Unit,
    onSignIn: (ServerProfile) -> Unit,
    onRetry: () -> Unit,
) {
    if (!loaded) {
        CenteredProgress()
        return
    }
    when (session) {
        Session.None -> MessageWithAction(
            message = stringResource(destination.emptyMessage),
            actionLabel = stringResource(R.string.action_add_server),
            onAction = onAddServer,
        )
        is Session.SignedOut -> MessageWithAction(
            message = stringResource(R.string.signed_out_message, session.profile.name),
            actionLabel = stringResource(R.string.action_sign_in),
            onAction = { onSignIn(session.profile) },
        )
        is Session.Active -> when (cluster) {
            ClusterState.Loading -> CenteredProgress()
            is ClusterState.Failed -> MessageWithAction(
                message = cluster.message.text(),
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRetry,
            )
            is ClusterState.Loaded -> when (destination) {
                TopLevelDestination.Overview -> OverviewScreen(session.profile, cluster)
                TopLevelDestination.Guests -> GuestsScreen(cluster)
                TopLevelDestination.Infrastructure -> InfrastructureScreen(cluster)
                TopLevelDestination.Tasks -> MessageWithAction(message = stringResource(R.string.tasks_not_yet))
            }
        }
    }
}

/** Overflow menu for switching, adding and removing servers. */
@Composable
private fun ServerMenu(
    session: Session,
    servers: StoredServers,
    onAddServer: () -> Unit,
    onSwitch: (String) -> Unit,
    onSignOut: () -> Unit,
    onRemove: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<ServerProfile?>(null) }

    val current: ServerProfile? = when (session) {
        is Session.Active -> session.profile
        is Session.SignedOut -> session.profile
        Session.None -> null
    }
    val canSignOut = session is Session.Active && session.client.credentials is TicketCredentials

    // The Box anchors the menu to the button that opens it.
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.action_more),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val others = servers.profiles.filter { it.id != current?.id }
            others.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_switch_to, profile.name)) },
                    onClick = {
                        expanded = false
                        onSwitch(profile.id)
                    },
                )
            }
            if (others.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_server)) },
                onClick = {
                    expanded = false
                    onAddServer()
                },
            )
            if (canSignOut) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_sign_out)) },
                    onClick = {
                        expanded = false
                        onSignOut()
                    },
                )
            }
            if (current != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove_server, current.name)) },
                    onClick = {
                        expanded = false
                        confirmRemove = current
                    },
                )
            }
        }
    }

    confirmRemove?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.remove_server_title, profile.name)) },
            text = { Text(stringResource(R.string.remove_server_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = null
                        onRemove(profile.id)
                    },
                ) {
                    Text(stringResource(R.string.action_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
