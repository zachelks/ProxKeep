package io.github.zachelks.proxkeep.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.ServerProfile
import io.github.zachelks.proxkeep.data.Session
import io.github.zachelks.proxkeep.ui.addserver.AddServerFlow
import io.github.zachelks.proxkeep.ui.cluster.InfrastructureScreen
import io.github.zachelks.proxkeep.ui.cluster.OverviewScreen
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MainTopBar
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.guests.GuestsPane
import io.github.zachelks.proxkeep.ui.guests.message
import io.github.zachelks.proxkeep.ui.settings.SettingsScreen
import io.github.zachelks.proxkeep.ui.tasks.TasksPane
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
    if (viewModel.settingsOpen) {
        SettingsScreen(onClose = viewModel::closeSettings)
        return
    }

    val session by viewModel.session.collectAsStateWithLifecycle()
    val cluster by viewModel.cluster.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()

    val active = session as? Session.Active
    RefreshWhileVisible(activeProfileId = active?.profile?.id, onRefresh = viewModel::refresh)

    val snackbars = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel, resources) {
        viewModel.events.collect { event -> snackbars.showSnackbar(event.message(resources)) }
    }

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
        Box(modifier = Modifier.fillMaxSize()) {
            val loadedCluster = cluster as? ClusterState.Loaded
            when {
                // These two destinations manage their own list and detail panes.
                active != null && loadedCluster != null && destination == TopLevelDestination.Guests ->
                    GuestsPane(viewModel = viewModel, cluster = loadedCluster)
                active != null && destination == TopLevelDestination.Tasks ->
                    TasksPane(viewModel = viewModel)
                else -> Scaffold(
                    topBar = { MainTopBar(title = stringResource(destination.label), viewModel = viewModel) },
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        SinglePaneContent(
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
            SnackbarHost(hostState = snackbars, modifier = Modifier.align(Alignment.BottomCenter))
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

/**
 * Content for the destinations that are a single screen, and the states
 * every destination shares: still starting, no server, signed out, loading
 * and failed.
 */
@Composable
private fun SinglePaneContent(
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
                TopLevelDestination.Infrastructure -> InfrastructureScreen(cluster)
                // Shown by their own panes in AppShell.
                TopLevelDestination.Guests, TopLevelDestination.Tasks -> Unit
            }
        }
    }
}
