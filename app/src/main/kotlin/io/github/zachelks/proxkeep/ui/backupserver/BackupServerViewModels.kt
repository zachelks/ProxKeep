package io.github.zachelks.proxkeep.ui.backupserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.awaitTask
import io.github.zachelks.proxkeep.core.api.pbs.BackupGroup
import io.github.zachelks.proxkeep.core.api.pbs.BackupNamespace
import io.github.zachelks.proxkeep.core.api.pbs.BackupServerStatus
import io.github.zachelks.proxkeep.core.api.pbs.BackupSnapshot
import io.github.zachelks.proxkeep.core.api.pbs.DatastoreUsage
import io.github.zachelks.proxkeep.core.api.pbs.PBS_NODE
import io.github.zachelks.proxkeep.core.api.pbs.PruneOptions
import io.github.zachelks.proxkeep.core.api.pbs.PruneResult
import io.github.zachelks.proxkeep.core.api.pbs.backupGroups
import io.github.zachelks.proxkeep.core.api.pbs.backupServerStatus
import io.github.zachelks.proxkeep.core.api.pbs.backupSnapshots
import io.github.zachelks.proxkeep.core.api.pbs.datastoreUsage
import io.github.zachelks.proxkeep.core.api.pbs.deleteGroup
import io.github.zachelks.proxkeep.core.api.pbs.deleteSnapshot
import io.github.zachelks.proxkeep.core.api.pbs.namespaces
import io.github.zachelks.proxkeep.core.api.pbs.pruneDatastore
import io.github.zachelks.proxkeep.core.api.pbs.pruneGroup
import io.github.zachelks.proxkeep.core.api.pbs.setProtected
import io.github.zachelks.proxkeep.core.api.pbs.startGarbageCollection
import io.github.zachelks.proxkeep.core.api.pbs.verify
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

private const val STOP_TIMEOUT_MS = 5_000L
private const val MESSAGE_BUFFER = 4
private const val POLL_MS = 1_000L

/**
 * What every Backup Server screen shares: reading on the shared schedule,
 * and running a change, following its task and reporting on [messages].
 */
abstract class BackupServerViewModel(
    protected val servers: ServerRepository,
    protected val refreshSignal: RefreshSignal,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)

    /** True while a change runs; screens offer no other change meanwhile. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    fun retry() = refreshSignal.request()

    /** Reads again at once and every time the shared schedule ticks. */
    protected fun <T> follow(read: suspend (PveClient) -> T): Flow<Loadable<T>> = flow {
        var current: Loadable<T> = Loadable.Loading
        emit(current)
        refreshSignal.ticks().collect {
            current = try {
                Loadable.Loaded(servers.request(read))
            } catch (e: IOException) {
                val previous = current
                if (previous is Loadable.Loaded) previous.copy(refreshError = e.toUiMessage()) else Loadable.Failed(e.toUiMessage())
            }
            emit(current)
        }
    }

    protected fun <T> Flow<Loadable<T>>.share(): StateFlow<Loadable<T>> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Loadable.Loading)

    /**
     * Runs a change. When [start] returns a task's UPID, waits for the task
     * and reports [done] only if it succeeded. Without [wait], [done] is
     * reported once the task has started, for tasks that run long, such as
     * garbage collection, which the person follows in Tasks.
     */
    protected fun change(done: Int, wait: Boolean = true, start: suspend (PveClient) -> String?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val upid = servers.request(start)
                if (wait && upid != null) {
                    val status = servers.request { it.awaitTask(PBS_NODE, upid, pollIntervalMs = POLL_MS) }
                    if (!status.succeeded) {
                        _messages.emit(UiMessage(R.string.pbs_task_failed, status.exitstatus))
                        return@launch
                    }
                }
                _messages.emit(UiMessage(done))
                refreshSignal.request()
            } catch (e: IOException) {
                _messages.emit(e.toUiMessage())
            } finally {
                _busy.value = false
            }
        }
    }

    protected suspend fun report(message: UiMessage) {
        _messages.emit(message)
    }
}

/** The server's own state and every datastore's space, for the Backup Server's first screen. */
class DatastoresViewModel(servers: ServerRepository, refreshSignal: RefreshSignal) : BackupServerViewModel(servers, refreshSignal) {
    val status: StateFlow<Loadable<BackupServerStatus>> = follow { it.backupServerStatus() }.share()
    val datastores: StateFlow<Loadable<List<DatastoreUsage>>> = follow { client -> client.datastoreUsage().sortedBy { it.store } }.share()
}

/** One datastore: its space, namespaces and backup groups, and its maintenance. */
@OptIn(ExperimentalCoroutinesApi::class)
class DatastoreViewModel(
    servers: ServerRepository,
    refreshSignal: RefreshSignal,
    val store: String,
) : BackupServerViewModel(servers, refreshSignal) {

    val usage: StateFlow<Loadable<DatastoreUsage?>> = follow { client -> client.datastoreUsage().firstOrNull { it.store == store } }.share()

    /** The namespaces to pick from. A server without namespaces has only the root. */
    val namespaces: StateFlow<List<BackupNamespace>> = flow {
        emit(listOf(BackupNamespace()))
        try {
            val found = servers.request { it.namespaces(store) }
            if (found.isNotEmpty()) emit(found.sortedBy { it.ns })
        } catch (ignored: IOException) {
            // Only the root namespace is offered.
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), listOf(BackupNamespace()))

    private val _namespace = MutableStateFlow("")
    val namespace: StateFlow<String> = _namespace.asStateFlow()

    fun selectNamespace(ns: String) {
        _namespace.value = ns
    }

    val groups: StateFlow<Loadable<List<BackupGroup>>> = _namespace
        .flatMapLatest { ns -> follow { client -> client.backupGroups(store, ns).sortedWith(compareBy({ it.type }, { it.id.toLongOrNull() ?: Long.MAX_VALUE }, { it.id })) } }
        .share()

    /** Garbage collection runs long, so the person follows it in Tasks. */
    fun collectGarbage() = change(R.string.pbs_gc_started, wait = false) { it.startGarbageCollection(store) }

    fun verifyAll(skipVerified: Boolean) = change(R.string.pbs_verify_started, wait = false) {
        it.verify(store, _namespace.value, skipVerified = skipVerified)
    }

    fun pruneAll(options: PruneOptions) {
        if (options.isEmpty) return
        change(R.string.pbs_prune_started, wait = false) { it.pruneDatastore(store, _namespace.value, options) }
    }

    fun deleteGroup(group: BackupGroup) = change(R.string.pbs_group_deleted) { client ->
        client.deleteGroup(store, _namespace.value, group)
        null
    }
}

/**
 * One backup group's snapshots, with verifying, protecting, deleting and
 * pruning them. A prune is previewed with a dry run before it is applied.
 */
class BackupGroupViewModel(
    servers: ServerRepository,
    refreshSignal: RefreshSignal,
    val store: String,
    val ns: String,
    val group: BackupGroup,
) : BackupServerViewModel(servers, refreshSignal) {

    val snapshots: StateFlow<Loadable<List<BackupSnapshot>>> = follow { client ->
        client.backupSnapshots(store, ns, group).sortedByDescending { it.time }
    }.share()

    /** A dry run's result while the person decides whether to prune. */
    data class PrunePreview(val options: PruneOptions, val results: List<PruneResult>) {
        val removed: Int
            get() = results.count { !it.keep }
    }

    private val _preview = MutableStateFlow<PrunePreview?>(null)
    val preview: StateFlow<PrunePreview?> = _preview.asStateFlow()

    fun verify(snapshot: BackupSnapshot) = change(R.string.pbs_verified) { it.verify(store, ns, snapshot) }

    fun setProtected(snapshot: BackupSnapshot, protected: Boolean) =
        change(if (protected) R.string.pbs_protected else R.string.pbs_unprotected) { client ->
            client.setProtected(store, ns, snapshot, protected)
            null
        }

    fun delete(snapshot: BackupSnapshot) = change(R.string.pbs_snapshot_deleted) { client ->
        client.deleteSnapshot(store, ns, snapshot)
        null
    }

    /** Asks the server which snapshots [options] would remove, without removing any. */
    fun previewPrune(options: PruneOptions) {
        if (options.isEmpty) return
        viewModelScope.launch {
            try {
                _preview.value = PrunePreview(options, servers.request { it.pruneGroup(store, ns, group, options, dryRun = true) })
            } catch (e: IOException) {
                report(e.toUiMessage())
            }
        }
    }

    fun cancelPrune() {
        _preview.value = null
    }

    fun confirmPrune() {
        val preview = _preview.value ?: return
        _preview.value = null
        change(R.string.pbs_pruned) { client ->
            client.pruneGroup(store, ns, group, preview.options, dryRun = false)
            null
        }
    }
}
