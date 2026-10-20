package io.github.zachelks.proxkeep.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.ui.addserver.AddServerFlow
import io.github.zachelks.proxkeep.ui.addserver.AddServerViewModel
import io.github.zachelks.proxkeep.ui.backupjobs.BackupJobsScreen
import io.github.zachelks.proxkeep.ui.backupserver.BackupGroupScreen
import io.github.zachelks.proxkeep.ui.backupserver.DatastoreScreen
import io.github.zachelks.proxkeep.ui.config.GuestConfigScreen
import io.github.zachelks.proxkeep.ui.console.TerminalScreen
import io.github.zachelks.proxkeep.ui.create.CreateGuestScreen
import io.github.zachelks.proxkeep.ui.datacenter.CephScreen
import io.github.zachelks.proxkeep.ui.datacenter.CollectionScreen
import io.github.zachelks.proxkeep.ui.datacenter.DatacenterScreen
import io.github.zachelks.proxkeep.ui.datacenter.GuestFirewallScreen
import io.github.zachelks.proxkeep.ui.datacenter.TwoFactorScreen
import io.github.zachelks.proxkeep.ui.desktop.DesktopScreen
import io.github.zachelks.proxkeep.ui.lifecycle.BackupsScreen
import io.github.zachelks.proxkeep.ui.lifecycle.SnapshotsScreen
import io.github.zachelks.proxkeep.ui.navigation.Navigator
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.navigation.rememberBackStack
import io.github.zachelks.proxkeep.ui.nodes.NodeScreen
import io.github.zachelks.proxkeep.ui.schema.SchemaEditRequest
import io.github.zachelks.proxkeep.ui.schema.SchemaEditScreen
import io.github.zachelks.proxkeep.ui.settings.ExtraKeysScreen
import io.github.zachelks.proxkeep.ui.settings.SettingsScreen
import io.github.zachelks.proxkeep.ui.storage.StorageScreen

/**
 * The app's screens and the way between them. Each screen on the back stack
 * gets its own view models, which are cleared when it is closed.
 */
@Composable
fun AppNavigation() {
    val backStack = rememberBackStack()
    val navigator = remember(backStack) { Navigator(backStack) }
    val pending = appContainer().pendingNavigation
    val requested by pending.route.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        pending.take()?.let(navigator::open)
    }
    // A destination is shown on the main screen, so whatever is over it closes.
    val destination by pending.destination.collectAsStateWithLifecycle()
    val search by pending.search.collectAsStateWithLifecycle()
    LaunchedEffect(destination, search) {
        if (destination != null || search) navigator.backToMain()
    }

    NavDisplay(
        backStack = backStack,
        onBack = { navigator.back() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider<Route> {
            entry<Route.Main> { MainScreen(navigator) }
            entry<Route.Settings> {
                SettingsScreen(
                    onClose = { navigator.close(Route.Settings) },
                    onOpenExtraKeys = { navigator.open(Route.ExtraKeys) },
                )
            }
            entry<Route.ExtraKeys> { ExtraKeysScreen(onClose = { navigator.close(Route.ExtraKeys) }) }
            entry<Route.SignIn> { route -> SignInEntry(route, navigator) }
            entry<Route.Terminal> { TerminalEntry(navigator) }
            entry<Route.Desktop> { DesktopEntry(navigator) }
            entry<Route.Snapshots> { route -> SnapshotsScreen(route.resourceId, onClose = { navigator.close(route) }) }
            entry<Route.Backups> { route -> BackupsScreen(route.resourceId, onClose = { navigator.close(route) }) }
            entry<Route.CreateVm> { CreateGuestScreen(ResourceKind.Qemu, onClose = { navigator.close(Route.CreateVm) }) }
            entry<Route.CreateContainer> { CreateGuestScreen(ResourceKind.Lxc, onClose = { navigator.close(Route.CreateContainer) }) }
            entry<Route.Node> { route ->
                NodeScreen(
                    node = route.node,
                    onClose = { navigator.close(route) },
                    onConsoleOpened = { navigator.open(Route.Terminal) },
                    onOpenRoute = navigator::open,
                )
            }
            entry<Route.Datacenter> { DatacenterScreen(onClose = { navigator.close(Route.Datacenter) }, onOpenRoute = navigator::open) }
            entry<Route.TwoFactor> { TwoFactorScreen(onClose = { navigator.close(Route.TwoFactor) }) }
            entry<Route.Ceph> { CephScreen(onClose = { navigator.close(Route.Ceph) }, onOpenRoute = navigator::open) }
            entry<Route.Datastore> { route -> DatastoreScreen(route.store, onClose = { navigator.close(route) }, onOpenRoute = navigator::open) }
            entry<Route.BackupGroup> { route ->
                BackupGroupScreen(route.store, route.ns, route.type, route.id, onClose = { navigator.close(route) })
            }
            entry<Route.Collection> { route ->
                CollectionScreen(route.specId, route.args, route.title, onClose = { navigator.close(route) }, onOpenRoute = navigator::open)
            }
            entry<Route.BackupJobs> { BackupJobsScreen(onClose = { navigator.close(Route.BackupJobs) }, onOpenRoute = navigator::open) }
            entry<Route.Storage> { route ->
                StorageScreen(route.node, route.storage, onClose = { navigator.close(route) }, onOpenRoute = navigator::open)
            }
            entry<Route.SchemaEdit> { route ->
                SchemaEditScreen(
                    title = route.title,
                    request = SchemaEditRequest(route.path, route.schemaPath, route.method, route.exclude.toSet(), route.presets, route.read, route.digest),
                    onClose = { navigator.close(route) },
                )
            }
            entry<Route.GuestConfig> { route -> GuestConfigScreen(route.resourceId, onClose = { navigator.close(route) }) }
            entry<Route.GuestFirewall> { route -> GuestFirewallScreen(route.resourceId, onClose = { navigator.close(route) }, onOpenRoute = navigator::open) }
        },
    )
}

@Composable
private fun SignInEntry(route: Route.SignIn, navigator: Navigator) {
    val container = appContainer()
    val viewModel = viewModel { AddServerViewModel(container.repository, route.profileId) }
    AddServerFlow(state = viewModel.state, onClose = { navigator.close(route) })
}

@Composable
private fun TerminalEntry(navigator: Navigator) {
    val container = appContainer()
    val consoles = container.consoles
    val sessions by consoles.sessions.collectAsStateWithLifecycle()
    val extraKeys by container.settings.extraKeys.collectAsStateWithLifecycle()
    val textSize by container.settings.terminalTextSize.collectAsStateWithLifecycle()
    val font by container.settings.terminalFont.collectAsStateWithLifecycle()
    val palette by container.settings.terminalPalette.collectAsStateWithLifecycle()
    val console by consoles.current.collectAsStateWithLifecycle()
    val open = console
    if (open == null) {
        // Every console has ended, for example by switching server.
        LaunchedEffect(Unit) { navigator.close(Route.Terminal) }
        return
    }
    TerminalScreen(
        sessions = sessions,
        console = open,
        onSelect = consoles::select,
        onEnd = consoles::close,
        onReconnect = consoles::reconnect,
        onLeave = { navigator.close(Route.Terminal) },
        extraKeys = extraKeys,
        textSizeSp = textSize,
        font = font,
        palette = palette,
        onTextSizeChange = container.settings::setTerminalTextSize,
    )
}

@Composable
private fun DesktopEntry(navigator: Navigator) {
    val desktops = appContainer().desktops
    val desktop by desktops.current.collectAsStateWithLifecycle()
    val open = desktop
    if (open == null) {
        // The desktop was closed elsewhere, for example by switching server.
        LaunchedEffect(Unit) { navigator.close(Route.Desktop) }
        return
    }
    // Keyed so a reconnect, which is a new session, starts with a fresh view.
    key(open.id) {
        DesktopScreen(
            session = open,
            onLeave = { navigator.close(Route.Desktop) },
            onDisconnect = {
                desktops.close()
                navigator.close(Route.Desktop)
            },
            onReconnect = desktops::reconnect,
        )
    }
}
