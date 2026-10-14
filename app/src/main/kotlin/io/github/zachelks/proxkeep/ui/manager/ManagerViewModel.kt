package io.github.zachelks.proxkeep.ui.manager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.Upid
import io.github.zachelks.proxkeep.core.api.awaitTask
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.pdm.PdmResource
import io.github.zachelks.proxkeep.core.api.pdm.Remote
import io.github.zachelks.proxkeep.core.api.pdm.RemoteResources
import io.github.zachelks.proxkeep.core.api.pdm.remoteGuestAction
import io.github.zachelks.proxkeep.core.api.pdm.remoteResources
import io.github.zachelks.proxkeep.core.api.pdm.remotes
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Which resources the list shows. */
enum class ResourceFilter(val label: Int) {
    All(R.string.pdm_filter_all),
    Guests(R.string.pdm_filter_guests),
    Nodes(R.string.pdm_filter_nodes),
    Storage(R.string.pdm_filter_storage),
}

/**
 * One remote with its resources and type. [type] is null when the remote
 * list could not be read.
 */
data class RemoteView(val remote: String, val type: String?, val error: String?, val resources: List<PdmResource>) {
    val guests: List<PdmResource>
        get() = resources.filter { it.isGuest && !it.template }

    val nodes: List<PdmResource>
        get() = resources.filter { it.isNode }
}

/** A resource with the remote it belongs to. */
data class RemoteResource(val remote: String, val resource: PdmResource)

/**
 * The resources that match [query] and [filter], across every remote. The
 * query matches a name, node, guest ID or remote, ignoring case.
 */
fun filterResources(remotes: List<RemoteView>, query: String, filter: ResourceFilter): List<RemoteResource> {
    val text = query.trim()
    return remotes.flatMap { remote -> remote.resources.map { RemoteResource(remote.remote, it) } }
        .filter { (_, resource) ->
            when (filter) {
                ResourceFilter.All -> true
                ResourceFilter.Guests -> resource.isGuest
                ResourceFilter.Nodes -> resource.isNode
                ResourceFilter.Storage -> resource.type == "pve-storage" || resource.type == "pbs-datastore"
            }
        }
        .filter { (remote, resource) ->
            text.isEmpty() || listOfNotNull(resource.name, resource.node, resource.storage, resource.vmid?.toString(), remote)
                .any { it.contains(text, ignoreCase = true) }
        }
        .sortedWith(compareBy({ it.remote }, { typeOrder(it.resource.type) }, { it.resource.vmid ?: 0 }, { it.resource.label }))
}

private fun typeOrder(type: String): Int = when (type) {
    "pve-node", "pbs-node" -> 0
    "pve-qemu", "pve-lxc" -> 1
    else -> 2
}

/**
 * A Datacenter Manager's remotes and their resources, read on the shared
 * schedule while a screen shows them, and power actions on remote guests.
 */
class ManagerViewModel(
    private val servers: ServerRepository,
    private val refreshSignal: RefreshSignal,
) : ViewModel() {

    val remotes: StateFlow<Loadable<List<RemoteView>>> = flow {
        var current: Loadable<List<RemoteView>> = Loadable.Loading
        emit(current)
        refreshSignal.ticks().collect {
            current = try {
                val (configured, resources) = servers.request { client ->
                    // The types are only labels, so the resources stand without them.
                    val list = try {
                        client.remotes()
                    } catch (e: IOException) {
                        null
                    }
                    list to client.remoteResources()
                }
                Loadable.Loaded(merge(configured, resources))
            } catch (e: IOException) {
                val previous = current
                if (previous is Loadable.Loaded) previous.copy(refreshError = e.toUiMessage()) else Loadable.Failed(e.toUiMessage())
            }
            emit(current)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Loadable.Loading)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(ResourceFilter.All)
    val filter: StateFlow<ResourceFilter> = _filter.asStateFlow()

    fun setQuery(text: String) {
        _query.value = text
    }

    fun setFilter(filter: ResourceFilter) {
        _filter.value = filter
    }

    /** The resources the list shows, or null until they have loaded. */
    val shown: StateFlow<List<RemoteResource>?> = combine(remotes, _query, _filter) { state, query, filter ->
        (state as? Loadable.Loaded)?.value?.let { filterResources(it, query, filter) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** The IDs of guests with an action running. */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    fun retry() = refreshSignal.request()

    /** Runs a power action on a remote guest and reports when its task ends. */
    fun run(remote: String, guest: PdmResource, action: GuestAction) {
        val kind = guest.guestKind ?: return
        val vmid = guest.vmid ?: return
        if (guest.id in _busy.value) return
        _busy.update { it + guest.id }
        viewModelScope.launch {
            try {
                val task = servers.request { it.remoteGuestAction(remote, kind, vmid, action) }?.let(Upid::parse)
                if (task != null) {
                    val status = servers.request { it.awaitTask(task.node, task.text, pollIntervalMs = POLL_MS) }
                    if (!status.succeeded) {
                        _messages.emit(UiMessage(R.string.pdm_action_failed, status.exitstatus))
                        return@launch
                    }
                }
                _messages.emit(UiMessage(R.string.pdm_action_done))
                refreshSignal.request()
            } catch (e: IOException) {
                _messages.emit(e.toUiMessage())
            } finally {
                _busy.update { it - guest.id }
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MESSAGE_BUFFER = 4
        private const val POLL_MS = 2_000L

        /** Each remote the manager reports, with its type when the remote list says it; configured remotes it has not reported yet come last. */
        fun merge(configured: List<Remote>?, resources: List<RemoteResources>): List<RemoteView> {
            val types = configured?.associate { it.id to it.type }.orEmpty()
            val reported = resources.map { RemoteView(it.remote, types[it.remote], it.error, it.resources) }
            val missing = configured.orEmpty().filter { remote -> resources.none { it.remote == remote.id } }
                .map { RemoteView(it.id, it.type, error = null, resources = emptyList()) }
            return (reported + missing).sortedBy { it.remote }
        }
    }
}
