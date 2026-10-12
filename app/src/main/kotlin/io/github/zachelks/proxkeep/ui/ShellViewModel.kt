package io.github.zachelks.proxkeep.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.data.ActionEvent
import io.github.zachelks.proxkeep.data.GuestActions
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.Session
import io.github.zachelks.proxkeep.data.StoredServers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** The frame around the top-level destinations: the server menu, refreshing and action messages. */
class ShellViewModel(
    private val repository: ServerRepository,
    guestActions: GuestActions,
    private val refreshSignal: RefreshSignal,
) : ViewModel() {

    val session: StateFlow<Session> = repository.session
    val servers: StateFlow<StoredServers> = repository.servers
    val loaded: StateFlow<Boolean> = repository.loaded

    /** Outcomes of power actions, wherever they were started. */
    val events: SharedFlow<ActionEvent> = guestActions.events

    /** Reloads everything on screen now. */
    fun refresh() {
        refreshSignal.request()
    }

    fun activate(profileId: String) {
        launchStorageChange { repository.activate(profileId) }
    }

    fun remove(profileId: String) {
        launchStorageChange { repository.remove(profileId) }
    }

    fun signOut() {
        viewModelScope.launch { repository.signOut() }
    }

    private fun launchStorageChange(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: IOException) {
                // The change was not saved; the visible state is unchanged.
            }
        }
    }
}
