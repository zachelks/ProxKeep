package io.github.zachelks.proxkeep.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zachelks.proxkeep.ProxKeepApplication
import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.VersionInfo
import io.github.zachelks.proxkeep.data.ServerProfile
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.Session
import io.github.zachelks.proxkeep.data.StoredServers
import io.github.zachelks.proxkeep.ui.addserver.AddServerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** What is known about the selected server's cluster. */
sealed interface ClusterState {
    data object Loading : ClusterState

    /**
     * @property refreshError set when the latest refresh failed and
     *   [resources] is therefore older than it looks
     */
    data class Loaded(
        val version: VersionInfo?,
        val resources: List<ClusterResource>,
        val refreshError: UiMessage? = null,
    ) : ClusterState

    data class Failed(val message: UiMessage) : ClusterState
}

class MainViewModel(private val repository: ServerRepository) : ViewModel() {

    val session: StateFlow<Session> = repository.session
    val servers: StateFlow<StoredServers> = repository.servers
    val loaded: StateFlow<Boolean> = repository.loaded

    private val _cluster = MutableStateFlow<ClusterState>(ClusterState.Loading)
    val cluster: StateFlow<ClusterState> = _cluster.asStateFlow()

    /** The add-server or sign-in flow in progress, or null when none is open. */
    var addServer by mutableStateOf<AddServerState?>(null)
        private set

    private val refreshMutex = Mutex()
    private var cachedVersion: Pair<String, VersionInfo>? = null

    init {
        viewModelScope.launch {
            // Data from one server must never be shown under another.
            repository.session
                .map { (it as? Session.Active)?.profile?.id }
                .distinctUntilChanged()
                .collect { _cluster.value = ClusterState.Loading }
        }
    }

    /** Fetches the cluster's resources once. The screen calls this on a timer while visible. */
    suspend fun refresh() {
        refreshMutex.withLock {
            val active = repository.session.value as? Session.Active ?: return
            val profileId = active.profile.id
            try {
                val resources = active.client.clusterResources()
                val version = cachedVersion?.takeIf { it.first == profileId }?.second
                    ?: try {
                        active.client.version().also { cachedVersion = profileId to it }
                    } catch (e: IOException) {
                        null
                    }
                if (isStillActive(profileId)) {
                    _cluster.value = ClusterState.Loaded(version, resources)
                }
            } catch (e: PveApiException) {
                if (e.isAuthenticationFailure) {
                    repository.sessionExpired(profileId)
                } else if (isStillActive(profileId)) {
                    showFailure(e)
                }
            } catch (e: IOException) {
                if (isStillActive(profileId)) showFailure(e)
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { refresh() }
    }

    private fun isStillActive(profileId: String): Boolean =
        (repository.session.value as? Session.Active)?.profile?.id == profileId

    private fun showFailure(error: IOException) {
        val message = error.toUiMessage()
        val current = _cluster.value
        _cluster.value = if (current is ClusterState.Loaded) {
            current.copy(refreshError = message)
        } else {
            ClusterState.Failed(message)
        }
    }

    fun startAddServer() {
        addServer = AddServerState(repository, viewModelScope, existing = null)
    }

    /** Opens the sign-in flow for a saved server and contacts it straight away. */
    fun startSignIn(profile: ServerProfile) {
        addServer = AddServerState(repository, viewModelScope, existing = profile).also { it.submitAddress() }
    }

    fun closeAddServer() {
        addServer?.cancel()
        addServer = null
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

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ProxKeepApplication
                MainViewModel(application.container.repository)
            }
        }
    }
}
