package io.github.zachelks.proxkeep.ui

import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.Session
import io.github.zachelks.proxkeep.ui.cluster.InfrastructureRoute
import io.github.zachelks.proxkeep.ui.cluster.OverviewRoute
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MainTopBar
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.guests.GuestsPane
import io.github.zachelks.proxkeep.ui.guests.message
import io.github.zachelks.proxkeep.ui.lifecycle.message
import io.github.zachelks.proxkeep.ui.storage.message
import io.github.zachelks.proxkeep.ui.navigation.Navigator
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.search.SearchScreen
import io.github.zachelks.proxkeep.ui.search.SearchViewModel
import io.github.zachelks.proxkeep.ui.tasks.TasksPane

/** The app bar of a top-level destination, given its title. */
typealias TopBar = @Composable (title: String) -> Unit

/**
 * The four top-level destinations. [NavigationSuiteScaffold] shows a bottom
 * bar on phones and a navigation rail on wider windows. Each destination
 * loads its own data while it is on screen.
 */
@Composable
fun MainScreen(navigator: Navigator) {
    val container = appContainer()
    val viewModel = viewModel { ShellViewModel(container.repository, container.guestActions, container.refresh) }
    var destination by rememberSaveable { mutableStateOf(TopLevelDestination.Overview) }
    val requestedDestination by container.pendingNavigation.destination.collectAsStateWithLifecycle()
    LaunchedEffect(requestedDestination) {
        container.pendingNavigation.takeDestination()?.let { destination = it }
    }
    var searching by rememberSaveable { mutableStateOf(false) }

    // A guest picked in search, until the Guests pane has opened it.
    var guestToOpen by rememberSaveable { mutableStateOf<String?>(null) }

    // Back from another destination returns to Overview before leaving the app.
    BackHandler(enabled = destination != TopLevelDestination.Overview) {
        destination = TopLevelDestination.Overview
    }
    BackHandler(enabled = searching) { searching = false }

    val session by viewModel.session.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val consoles by container.consoles.sessions.collectAsStateWithLifecycle()
    val desktop by container.desktops.current.collectAsStateWithLifecycle()

    val snackbars = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel, resources) {
        viewModel.events.collect { event -> snackbars.showSnackbar(event.message(resources)) }
    }
    LaunchedEffect(container.guestLifecycle, resources) {
        container.guestLifecycle.events.collect { event -> snackbars.showSnackbar(event.message(resources)) }
    }
    LaunchedEffect(container.transfers, resources) {
        container.transfers.events.collect { event -> snackbars.showSnackbar(event.message(resources)) }
    }

    val topBar: TopBar = { title ->
        MainTopBar(
            title = title,
            viewModel = viewModel,
            openConsoles = consoles.size,
            onOpenConsoles = { navigator.open(Route.Terminal) },
            desktopOpen = desktop != null,
            onOpenDesktop = { navigator.open(Route.Desktop) },
            onSearch = { searching = true },
            onAddServer = { navigator.open(Route.SignIn()) },
            onSettings = { navigator.open(Route.Settings) },
        )
    }
    val openTerminal = { navigator.open(Route.Terminal) }

    // The search covers the destinations while it is open.
    Box(modifier = Modifier.fillMaxSize()) {
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
                val active = session as? Session.Active
                when {
                    // These two destinations manage their own list and detail panes.
                    active != null && destination == TopLevelDestination.Guests ->
                        GuestsPane(
                            topBar = topBar,
                            onConsoleOpened = openTerminal,
                            onDesktopOpened = { navigator.open(Route.Desktop) },
                            onOpenRoute = navigator::open,
                            guestToOpen = guestToOpen,
                            onGuestOpened = { guestToOpen = null },
                        )
                    active != null && destination == TopLevelDestination.Tasks ->
                        TasksPane(topBar = topBar)
                    else -> Scaffold(topBar = { topBar(stringResource(destination.label)) }) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            when {
                                !loaded || session is Session.SigningIn -> CenteredProgress()
                                session == Session.None -> MessageWithAction(
                                    message = stringResource(destination.emptyMessage),
                                    actionLabel = stringResource(R.string.action_add_server),
                                    onAction = { navigator.open(Route.SignIn()) },
                                )
                                session is Session.SignedOut -> {
                                    val profile = (session as Session.SignedOut).profile
                                    MessageWithAction(
                                        message = stringResource(R.string.signed_out_message, profile.name),
                                        actionLabel = stringResource(R.string.action_sign_in),
                                        onAction = { navigator.open(Route.SignIn(profile.id)) },
                                    )
                                }
                                active != null && destination == TopLevelDestination.Overview ->
                                    OverviewRoute(profile = active.profile, onOpenRoute = navigator::open)
                                active != null && destination == TopLevelDestination.Infrastructure ->
                                    InfrastructureRoute(onConsoleOpened = openTerminal, onOpenRoute = navigator::open)
                            }
                        }
                    }
                }
                SnackbarHost(hostState = snackbars, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }

        if (searching && session is Session.Active) {
            val search = viewModel { SearchViewModel(container.cluster, container.refresh) }
            SearchScreen(
                viewModel = search,
                onClose = { searching = false },
                onOpen = { resource ->
                    searching = false
                    if (resource.isGuest) {
                        guestToOpen = resource.id
                        destination = TopLevelDestination.Guests
                    } else {
                        destination = TopLevelDestination.Infrastructure
                    }
                },
            )
        }
    }
}
