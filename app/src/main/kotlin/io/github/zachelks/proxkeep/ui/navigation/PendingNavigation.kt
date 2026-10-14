package io.github.zachelks.proxkeep.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A screen that something outside the UI asked to open, such as a tap on
 * the console notification. It waits here until the navigation is on
 * screen, which is not the case while the app is locked.
 */
class PendingNavigation {
    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()

    fun request(route: Route) {
        _route.value = route
    }

    /** Returns the waiting route, if any, and forgets it. */
    fun take(): Route? = _route.value.also { _route.value = null }
}
