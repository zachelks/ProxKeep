package io.github.zachelks.proxkeep.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.zachelks.proxkeep.ui.addserver.AddServerFlow
import io.github.zachelks.proxkeep.ui.addserver.AddServerViewModel
import io.github.zachelks.proxkeep.ui.console.TerminalScreen
import io.github.zachelks.proxkeep.ui.navigation.Navigator
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.navigation.rememberBackStack
import io.github.zachelks.proxkeep.ui.settings.ExtraKeysScreen
import io.github.zachelks.proxkeep.ui.settings.SettingsScreen

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
        onTextSizeChange = container.settings::setTerminalTextSize,
    )
}
