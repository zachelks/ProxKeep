package io.github.zachelks.proxkeep.ui.addserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.data.ServerRepository

/**
 * Holds the add-server or sign-in flow for as long as its screen is open.
 *
 * @param profileId the saved server to sign in to, or null to add a new one
 */
class AddServerViewModel(repository: ServerRepository, profileId: String?) : ViewModel() {

    private val existing = profileId?.let { id -> repository.servers.value.profiles.firstOrNull { it.id == id } }

    val state = AddServerState(
        repository,
        viewModelScope,
        existing,
        existingHeaders = existing?.let(repository::savedHeaders).orEmpty(),
        existingPassword = existing?.let(repository::savedPassword),
    )

    init {
        // Signing in to a saved server starts by contacting it straight away.
        if (existing != null) state.submitAddress()
    }

    override fun onCleared() {
        state.cancel()
    }
}
