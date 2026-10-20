package io.github.zachelks.proxkeep.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import kotlinx.serialization.json.Json

/** Opens and closes full screens by changing the back stack. */
class Navigator(private val backStack: MutableList<Route>) {

    /** Opens [route] over the current screen, unless it is already on top. */
    fun open(route: Route) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    /** Leaves the screen on top. The main screen is never left this way. */
    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** Closes every screen over the main screen. */
    fun backToMain() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /**
     * Closes [route] wherever it is in the stack. Safe to call more than
     * once, so a screen can close itself from more than one path.
     */
    fun close(route: Route) {
        val index = backStack.lastIndexOf(route)
        if (index > 0) backStack.removeAt(index)
    }
}

/** The back stack, kept across rotation and the app's process being stopped in the background. */
@Composable
fun rememberBackStack(): SnapshotStateList<Route> =
    rememberSaveable(saver = BackStackSaver) { listOf<Route>(Route.Main).toMutableStateList() }

private val BackStackSaver = listSaver<SnapshotStateList<Route>, String>(
    save = { stack -> stack.map { Json.encodeToString(Route.serializer(), it) } },
    restore = { saved -> saved.map { Json.decodeFromString(Route.serializer(), it) }.toMutableStateList() },
)
