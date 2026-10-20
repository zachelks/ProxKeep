package io.github.zachelks.proxkeep.ui.backupjobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.model.BackupJob
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/** The datacenter's scheduled backup jobs: run one now, switch one on or off, or delete one. */
class BackupJobsViewModel(
    private val servers: ServerRepository,
    clusterRepository: ClusterRepository,
    private val refreshSignal: RefreshSignal,
) : ViewModel() {

    private val clusterState = clusterRepository.state

    val jobs: StateFlow<Loadable<List<BackupJob>>> = follow { it.backupJobs() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Loadable.Loading)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    fun retry() = refreshSignal.request()

    /**
     * Starts the job now on every node it covers. The backups run as tasks
     * on their nodes and show in the task list; the job's own schedule is
     * not changed.
     */
    fun runNow(job: BackupJob) {
        val resources = (clusterState.value as? ClusterState.Loaded)?.resources ?: return
        val plan = runPlan(job, resources)
        if (plan.isEmpty()) {
            _messages.tryEmit(UiMessage(R.string.backup_job_nothing_to_run))
            return
        }
        change(R.string.backup_job_started) { client ->
            plan.forEach { (node, parameters) -> client.runBackup(node, parameters) }
        }
    }

    fun setEnabled(job: BackupJob, enabled: Boolean) = change(if (enabled) R.string.backup_job_enabled else R.string.backup_job_disabled) { client ->
        client.write("PUT", "/cluster/backup/${job.id}", mapOf("enabled" to if (enabled) "1" else "0"))
    }

    fun delete(job: BackupJob) = change(R.string.backup_job_deleted) { client ->
        client.write("DELETE", "/cluster/backup/${job.id}", emptyMap())
    }

    private fun change(done: Int, block: suspend (PveClient) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                servers.request { block(it) }
                _messages.emit(UiMessage(done))
                refreshSignal.request()
            } catch (e: IOException) {
                _messages.emit(e.toUiMessage())
            } finally {
                _busy.value = false
            }
        }
    }

    private fun <T> follow(read: suspend (PveClient) -> T): Flow<Loadable<T>> = flow {
        var current: Loadable<T> = Loadable.Loading
        emit(current)
        refreshSignal.ticks(SLOW_REFRESH_MS).collect {
            current = try {
                Loadable.Loaded(servers.request(read))
            } catch (e: IOException) {
                val previous = current
                if (previous is Loadable.Loaded) previous.copy(refreshError = e.toUiMessage()) else Loadable.Failed(e.toUiMessage())
            }
            emit(current)
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MESSAGE_BUFFER = 8
        private const val SLOW_REFRESH_MS = 30_000L

        /**
         * Where running [job] now starts a backup, and with what. A backup
         * runs on the node its guests are on, so a job that names guests
         * runs on each of their nodes with only that node's guests. A job
         * for all guests or a pool runs on every online node, or on its
         * own node when it names one.
         */
        fun runPlan(job: BackupJob, resources: List<ClusterResource>): Map<String, Map<String, String>> {
            val online = resources.filter { it.kind == ResourceKind.Node && it.status == "online" }.mapNotNull { it.node }.sorted()
            val nodes = job.node?.let { node -> listOf(node).filter { it in online } } ?: online
            val parameters = job.runParameters()
            if (job.guests.isEmpty()) return nodes.associateWith { parameters }
            val guestNodes = resources.filter { (it.kind == ResourceKind.Qemu || it.kind == ResourceKind.Lxc) && it.vmid in job.guests }
                .groupBy({ it.node }, { it.vmid!! })
            return nodes.mapNotNull { node ->
                guestNodes[node]?.takeIf { it.isNotEmpty() }?.let { ids -> node to parameters + ("vmid" to ids.sorted().joinToString(",")) }
            }.toMap()
        }
    }
}
