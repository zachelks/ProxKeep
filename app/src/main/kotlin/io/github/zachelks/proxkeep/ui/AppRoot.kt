package io.github.zachelks.proxkeep.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.AppContainer
import io.github.zachelks.proxkeep.ProxKeepApplication
import io.github.zachelks.proxkeep.ui.lock.LockScreen

/** The process-wide objects, for the few composables that need them directly. */
@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as ProxKeepApplication).container

/**
 * The top of the UI. While the app is locked nothing else is composed, so no
 * server data is on screen or being fetched behind the lock.
 */
@Composable
fun AppRoot() {
    val container = appContainer()
    val locked by container.appLock.locked.collectAsStateWithLifecycle()
    if (locked) {
        LockScreen(appLock = container.appLock, authenticator = container.authenticator)
    } else {
        AppNavigation()
    }
}
