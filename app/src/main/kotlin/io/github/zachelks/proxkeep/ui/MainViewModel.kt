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
import io.github.zachelks.proxkeep.core.api.awaitTask
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.GuestConfig
import io.github.zachelks.proxkeep.core.api.model.GuestOsInfo
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.GuestStatus
import io.github.zachelks.proxkeep.core.api.model.TaskLogLine
import io.github.zachelks.proxkeep.core.api.model.TaskStatus
import io.github.zachelks.proxkeep.core.api.model.TaskSummary
import io.github.zachelks.proxkeep.core.api.model.VersionInfo
import io.github.zachelks.proxkeep.data.ServerProfile
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.Session
import io.github.zachelks.proxkeep.data.StoredServers
import io.github.zachelks.proxkeep.ui.addserver.AddServerState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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

/**
 * Detail of the guest that is open on screen.
 *
 * @property resourceId the guest's ID in the cluster resource list, for example `qemu/100`
 * @property osInfo what the guest agent reports, when one is running in the guest
 * @property error set when the latest attempt to read the detail failed
 */
data class GuestDetail(
    val resourceId: String,
    val status: GuestStatus? = null,
    val config: GuestConfig? = null,
    val osInfo: GuestOsInfo? = null,
    val error: UiMessage? = null,
)

/** The outcome of a power action, for a brief message on screen. */
data class ActionEvent(
    val action: GuestAction,
    val guestName: String,
    val succeeded: Boolean,
    /** The server's explanation when the action failed. */
    val detail: String? = null,
)

/** What is known about the cluster's recent and running tasks. */
sealed interface TasksState {
    data object Loading : TasksState

    data class Loaded(
        val tasks: List<TaskSummary>,
        val refreshError: UiMessage? = null,
    ) : TasksState

    data class Failed(val message: UiMessage) : TasksState
}

/** A task's current status and the log lines read so far. */
data class TaskProgress(
    val status: TaskStatus,
    val newLines: List<TaskLogLine>,
)

class MainViewModel(private val repository: ServerRepository) : ViewModel() {

    val session: StateFlow<Session> = repository.session
    val servers: StateFlow<StoredServers> = repository.servers
    val loaded: StateFlow<Boolean> = repository.loaded

    private val _cluster = MutableStateFlow<ClusterState>(ClusterState.Loading)
    val cluster: StateFlow<ClusterState> = _cluster.asStateFlow()

    /** The add-server or sign-in flow in progress, or null when none is open. */
    var addServer by mutableStateOf<AddServerState?>(null)
        private set

    private val _guestDetail = MutableStateFlow<GuestDetail?>(null)
    val guestDetail: StateFlow<GuestDetail?> = _guestDetail.asStateFlow()

    /** Guests with a power action in progress, by resource ID. */
    private val _busyGuests = MutableStateFlow<Map<String, GuestAction>>(emptyMap())
    val busyGuests: StateFlow<Map<String, GuestAction>> = _busyGuests.asStateFlow()

    private val _events = MutableSharedFlow<ActionEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<ActionEvent> = _events.asSharedFlow()

    private val _tasks = MutableStateFlow<TasksState>(TasksState.Loading)
    val tasks: StateFlow<TasksState> = _tasks.asStateFlow()
    private var tasksVisible = false

    /** Whether the Settings screen is open. */
    var settingsOpen by mutableStateOf(false)
        private set

    fun openSettings() {
        settingsOpen = true
    }

    fun closeSettings() {
        settingsOpen = false
    }

    private val refreshMutex = Mutex()
    private var cachedVersion: Pair<String, VersionInfo>? = null

    init {
        viewModelScope.launch {
            // Data from one server must never be shown under another.
            repository.session
                .map { (it as? Session.Active)?.profile?.id }
                .distinctUntilChanged()
                .collect {
                    _cluster.value = ClusterState.Loading
                    _tasks.value = TasksState.Loading
                    _guestDetail.value = null
                    _busyGuests.value = emptyMap()
                }
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
                refreshGuestDetail()
                if (tasksVisible) refreshTasks()
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

    // --- Guest detail and power actions ----------------------------------

    /** Tells the view model which guest is on screen, or null when none is. */
    fun openGuest(resourceId: String?) {
        if (_guestDetail.value?.resourceId == resourceId) return
        _guestDetail.value = resourceId?.let { GuestDetail(it) }
        if (resourceId != null) viewModelScope.launch { refreshGuestDetail() }
    }

    private fun findResource(resourceId: String): ClusterResource? =
        (_cluster.value as? ClusterState.Loaded)?.resources?.firstOrNull { it.id == resourceId }

    private suspend fun refreshGuestDetail() {
        val open = _guestDetail.value ?: return
        val active = repository.session.value as? Session.Active ?: return
        val resource = findResource(open.resourceId) ?: return
        val node = resource.node ?: return
        val vmid = resource.vmid ?: return
        try {
            val status = active.client.guestStatus(node, resource.kind, vmid)
            val config = active.client.guestConfig(node, resource.kind, vmid)
            // The operating system does not change while a guest runs, so one answer is kept.
            val osInfo = open.osInfo ?: if (resource.kind == ResourceKind.Qemu && status.isRunning && config.agentEnabled) {
                try {
                    active.client.guestOsInfo(node, vmid)
                } catch (e: IOException) {
                    // No agent answering is normal; the row is simply left out.
                    null
                }
            } else {
                null
            }
            _guestDetail.update { current ->
                if (current?.resourceId == open.resourceId) {
                    GuestDetail(open.resourceId, status, config, osInfo ?: current.osInfo)
                } else {
                    current
                }
            }
        } catch (e: PveApiException) {
            if (e.isAuthenticationFailure) {
                repository.sessionExpired(active.profile.id)
            } else {
                showGuestDetailError(open.resourceId, e)
            }
        } catch (e: IOException) {
            showGuestDetailError(open.resourceId, e)
        }
    }

    private fun showGuestDetailError(resourceId: String, error: IOException) {
        _guestDetail.update { current ->
            if (current != null && current.resourceId == resourceId) current.copy(error = error.toUiMessage()) else current
        }
    }

    /**
     * Starts [action] on a guest and follows the resulting task to its end.
     * The outcome arrives on [events]. A guest runs one action at a time.
     */
    fun runGuestAction(resource: ClusterResource, action: GuestAction) {
        val active = repository.session.value as? Session.Active ?: return
        val node = resource.node ?: return
        val vmid = resource.vmid ?: return
        if (_busyGuests.value.containsKey(resource.id)) return
        val guestName = resource.name ?: vmid.toString()

        _busyGuests.update { it + (resource.id to action) }
        viewModelScope.launch {
            try {
                val upid = active.client.guestAction(node, resource.kind, vmid, action)
                val result = active.client.awaitTask(node, upid)
                _events.emit(ActionEvent(action, guestName, result.succeeded, result.exitstatus.takeUnless { result.succeeded }))
            } catch (e: PveApiException) {
                if (e.isAuthenticationFailure) {
                    repository.sessionExpired(active.profile.id)
                } else {
                    _events.emit(ActionEvent(action, guestName, succeeded = false, detail = e.statusMessage))
                }
            } catch (e: IOException) {
                _events.emit(ActionEvent(action, guestName, succeeded = false, detail = e.message))
            } finally {
                _busyGuests.update { it - resource.id }
            }
            refresh()
        }
    }

    // --- Tasks -----------------------------------------------------------

    /** The task list is only fetched while it is on screen. */
    fun setTasksVisible(visible: Boolean) {
        tasksVisible = visible
        if (visible) viewModelScope.launch { refreshTasks() }
    }

    private suspend fun refreshTasks() {
        val active = repository.session.value as? Session.Active ?: return
        val profileId = active.profile.id
        try {
            val tasks = active.client.clusterTasks()
            if (isStillActive(profileId)) _tasks.value = TasksState.Loaded(tasks)
        } catch (e: PveApiException) {
            if (e.isAuthenticationFailure) {
                repository.sessionExpired(profileId)
            } else if (isStillActive(profileId)) {
                showTasksFailure(e)
            }
        } catch (e: IOException) {
            if (isStillActive(profileId)) showTasksFailure(e)
        }
    }

    private fun showTasksFailure(error: IOException) {
        val message = error.toUiMessage()
        val current = _tasks.value
        _tasks.value = if (current is TasksState.Loaded) current.copy(refreshError = message) else TasksState.Failed(message)
    }

    /**
     * Reads a task's status and any log lines after the first [linesSoFar].
     *
     * @throws IOException when the task cannot be read
     */
    suspend fun readTask(node: String, upid: String, linesSoFar: Int): TaskProgress {
        val active = repository.session.value as? Session.Active
            ?: throw IOException("Not signed in")
        try {
            val status = active.client.taskStatus(node, upid)
            val lines = active.client.taskLog(node, upid, start = linesSoFar, limit = LOG_PAGE_SIZE)
            return TaskProgress(status, lines)
        } catch (e: PveApiException) {
            if (e.isAuthenticationFailure) repository.sessionExpired(active.profile.id)
            throw e
        }
    }

    // --- Servers ---------------------------------------------------------

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
        private const val EVENT_BUFFER = 16

        /** The most log lines [readTask] returns in one call. */
        const val LOG_PAGE_SIZE = 500

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ProxKeepApplication
                MainViewModel(application.container.repository)
            }
        }
    }
}
