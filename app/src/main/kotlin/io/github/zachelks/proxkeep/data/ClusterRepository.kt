package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

/** What is known about the signed-in server's cluster. */
sealed interface ClusterState {
    data object Loading : ClusterState

    /**
     * @property profileId the server the resources belong to
     * @property refreshError set when the latest refresh failed and
     *   [resources] is therefore older than it looks
     */
    data class Loaded(
        val profileId: String,
        val resources: List<ClusterResource>,
        val refreshError: IOException? = null,
    ) : ClusterState

    data class Failed(val error: IOException) : ClusterState
}

/**
 * The cluster's nodes, guests and storage, from the one call that lists them
 * all. Several screens share it, and it is only read while one of them is
 * watching.
 */
class ClusterRepository(
    private val servers: ServerRepository,
    private val refresh: RefreshSignal,
    scope: CoroutineScope,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ClusterState> = servers.activeProfileId
        .flatMapLatest { profileId -> if (profileId == null) flowOf(ClusterState.Loading) else follow(profileId) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ClusterState.Loading)

    private fun follow(profileId: String): Flow<ClusterState> = flow {
        // Coming back to the same server keeps what is on screen while it reloads.
        // Data from one server is never shown under another.
        var current: ClusterState = (state.value as? ClusterState.Loaded)?.takeIf { it.profileId == profileId }
            ?: ClusterState.Loading
        emit(current)
        refresh.ticks().collect {
            current = try {
                ClusterState.Loaded(profileId, servers.request { it.clusterResources() })
            } catch (e: IOException) {
                val previous = current
                if (previous is ClusterState.Loaded) previous.copy(refreshError = e) else ClusterState.Failed(e)
            }
            emit(current)
        }
    }

    private companion object {
        /** Keeps loading briefly after the last screen stops watching, so rotating the device does not reload. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
