package io.github.zachelks.proxkeep.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.model.TaskStatus
import io.github.zachelks.proxkeep.core.api.model.TaskSummary
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

/** What is known about the cluster's recent and running tasks. */
sealed interface TasksState {
    data object Loading : TasksState

    data class Loaded(
        val profileId: String,
        val tasks: List<TaskSummary>,
        val refreshError: UiMessage? = null,
    ) : TasksState

    data class Failed(val message: UiMessage) : TasksState
}

/**
 * One task's status and the log lines read so far.
 *
 * @property finished true once the task has stopped and its whole log has been read
 */
data class TaskLog(
    val upid: String,
    val status: TaskStatus? = null,
    val lines: List<String> = emptyList(),
    val error: UiMessage? = null,
    val finished: Boolean = false,
)

/** The Tasks destination: recent and running tasks, and the log of the selected one. */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    private val servers: ServerRepository,
    private val refreshSignal: RefreshSignal,
) : ViewModel() {

    val tasks: StateFlow<TasksState> = servers.activeProfileId
        .flatMapLatest { profileId -> if (profileId == null) flowOf(TasksState.Loading) else followTasks(profileId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TasksState.Loading)

    private val selected = MutableStateFlow<TaskSummary?>(null)

    /** The selected task's log. It follows the task while it runs. */
    val log: StateFlow<TaskLog?> = selected
        .flatMapLatest { task -> if (task == null) flowOf(null) else followLog(task.node, task.upid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Tells the view model which task is on screen, or null when none is. */
    fun select(task: TaskSummary?) {
        // Only a different task restarts the log; the same task's row changes as it runs.
        if (selected.value?.upid != task?.upid) selected.value = task
    }

    fun retry() {
        refreshSignal.request()
    }

    private fun followTasks(profileId: String): Flow<TasksState> = flow {
        var current: TasksState = (tasks.value as? TasksState.Loaded)?.takeIf { it.profileId == profileId }
            ?: TasksState.Loading
        emit(current)
        refreshSignal.ticks().collect {
            current = try {
                TasksState.Loaded(profileId, servers.request { it.clusterTasks() })
            } catch (e: IOException) {
                val previous = current
                if (previous is TasksState.Loaded) previous.copy(refreshError = e.toUiMessage()) else TasksState.Failed(e.toUiMessage())
            }
            emit(current)
        }
    }

    private fun followLog(node: String, upid: String): Flow<TaskLog> = flow {
        // Coming back to the same task carries on from the lines already read.
        var current = log.value?.takeIf { it.upid == upid } ?: TaskLog(upid)
        emit(current)
        while (!current.finished) {
            var pageWasFull = false
            current = try {
                val (status, newLines) = servers.request { client ->
                    client.taskStatus(node, upid) to client.taskLog(node, upid, start = current.lines.size, limit = LOG_PAGE_SIZE)
                }
                val fresh = newLines.map { it.t }
                val isPlaceholder = current.lines.isEmpty() && fresh.size == 1 && fresh.first() == EMPTY_LOG_PLACEHOLDER
                pageWasFull = newLines.size >= LOG_PAGE_SIZE
                current.copy(
                    status = status,
                    lines = if (isPlaceholder) current.lines else current.lines + fresh,
                    error = null,
                    finished = !pageWasFull && !status.isRunning,
                )
            } catch (e: PveApiException) {
                // The server refused; asking again will not help.
                current.copy(error = e.toUiMessage(), finished = true)
            } catch (e: IOException) {
                current.copy(error = e.toUiMessage())
            }
            emit(current)
            // More of a long log is waiting: fetch it without a pause.
            if (!current.finished && !pageWasFull) delay(LOG_POLL_MS)
        }
    }

    private companion object {
        /** The most log lines read in one call. */
        const val LOG_PAGE_SIZE = 500

        const val LOG_POLL_MS = 1_500L
        const val STOP_TIMEOUT_MS = 5_000L

        /** The server's stand-in for a log with nothing in it yet. */
        const val EMPTY_LOG_PLACEHOLDER = "no content"
    }
}
