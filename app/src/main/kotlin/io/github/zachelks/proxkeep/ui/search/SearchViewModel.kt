package io.github.zachelks.proxkeep.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.RefreshSignal
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What the search shows.
 *
 * @property hits null until the cluster has loaded
 */
data class SearchResults(
    val query: String = "",
    val hits: List<ClusterResource>? = null,
    val cluster: ClusterState = ClusterState.Loading,
)

/** One search across the signed-in server's guests, nodes and storage. */
class SearchViewModel(
    clusterRepository: ClusterRepository,
    private val refreshSignal: RefreshSignal,
) : ViewModel() {

    /** What the person has typed. Compose state, so the text field never lags behind typing. */
    var query by mutableStateOf("")

    val results: StateFlow<SearchResults> =
        combine(clusterRepository.state, snapshotFlow { query }) { cluster, text ->
            val resources = (cluster as? ClusterState.Loaded)?.resources
            SearchResults(text, resources?.let { searchResources(it, text) }, cluster)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchResults())

    fun retry() {
        refreshSignal.request()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
