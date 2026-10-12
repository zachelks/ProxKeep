package io.github.zachelks.proxkeep.ui.cluster

import androidx.lifecycle.ViewModel
import io.github.zachelks.proxkeep.console.ConsoleManager
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerAccess
import io.github.zachelks.proxkeep.data.ServerAccessRepository
import kotlinx.coroutines.flow.StateFlow

/** Overview and Infrastructure: the cluster's nodes and storage. */
class ClusterViewModel(
    clusterRepository: ClusterRepository,
    accessRepository: ServerAccessRepository,
    private val consoles: ConsoleManager,
    private val refreshSignal: RefreshSignal,
) : ViewModel() {

    val cluster: StateFlow<ClusterState> = clusterRepository.state
    val access: StateFlow<ServerAccess?> = accessRepository.access

    fun retry() {
        refreshSignal.request()
    }

    fun openNodeShell(node: String) {
        consoles.open(ConsoleTarget.NodeShell(node), title = node)
    }
}
