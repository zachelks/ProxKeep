@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.datacenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.CephStatus
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.components.UsageBar
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.format.usageFraction
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.text
import io.github.zachelks.proxkeep.ui.theme.StatusTone
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

/** Ceph's health, read on the shared schedule while the screen is open. */
class CephViewModel(private val servers: ServerRepository, private val refreshSignal: RefreshSignal) : ViewModel() {
    val status: StateFlow<Loadable<CephStatus>> = flow {
        var current: Loadable<CephStatus> = Loadable.Loading
        emit(current)
        refreshSignal.ticks().collect {
            current = try {
                Loadable.Loaded(servers.request { it.cephStatus() })
            } catch (e: IOException) {
                val previous = current
                if (previous is Loadable.Loaded) previous.copy(refreshError = e.toUiMessage()) else Loadable.Failed(e.toUiMessage())
            }
            emit(current)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Loadable.Loading)

    fun retry() = refreshSignal.request()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Ceph's health, monitors, OSDs, placement groups and capacity, and a way to its pools. */
@Composable
fun CephScreen(onClose: () -> Unit, onOpenRoute: (Route) -> Unit) {
    val container = appContainer()
    val viewModel = viewModel { CephViewModel(container.repository, container.refresh) }
    val status by viewModel.status.collectAsStateWithLifecycle()
    val cluster by container.cluster.state.collectAsStateWithLifecycle()
    val node = (cluster as? ClusterState.Loaded)?.resources
        ?.filter { it.kind == ResourceKind.Node && it.status == "online" }?.mapNotNull { it.node }?.minOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dc_ceph_status)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::retry) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = status) {
            Loadable.Loading -> CenteredProgress(modifier = Modifier.padding(innerPadding))
            is Loadable.Failed -> MessageWithAction(state.message.text(), Modifier.padding(innerPadding), stringResource(R.string.action_retry), viewModel::retry)
            is Loadable.Loaded -> CephContent(
                state.value,
                modifier = Modifier.padding(innerPadding),
                onOpenPools = node?.let { { onOpenRoute(Route.Collection("ceph-pools", mapOf("node" to it))) } },
            )
        }
    }
}

@Composable
private fun CephContent(ceph: CephStatus, modifier: Modifier, onOpenPools: (() -> Unit)?) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val (label, tone) = when (ceph.health) {
                    "HEALTH_OK" -> R.string.ceph_health_ok to StatusTone.Good
                    "HEALTH_WARN" -> R.string.ceph_health_warn to StatusTone.Warning
                    "HEALTH_ERR" -> R.string.ceph_health_err to StatusTone.Bad
                    else -> R.string.ceph_health_unknown to StatusTone.Neutral
                }
                StatusLabel(stringResource(label), tone)
                ceph.checks.forEach { (name, message) ->
                    Text("$name: $message", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ceph.monitors?.let {
                    Text(pluralStringResource(R.plurals.ceph_monitors, it, ceph.quorum.size, it), style = MaterialTheme.typography.bodyLarge)
                }
                ceph.osds?.let { total ->
                    Text(stringResource(R.string.ceph_osds, ceph.osdsUp ?: 0, ceph.osdsIn ?: 0, total), style = MaterialTheme.typography.bodyLarge)
                }
                if (ceph.placementGroups.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.ceph_placement_groups), style = MaterialTheme.typography.titleSmall)
                    ceph.placementGroups.forEach { (state, count) -> Text("$count × $state", style = MaterialTheme.typography.bodyMedium) }
                }
                usageFraction(ceph.bytesUsed, ceph.bytesTotal)?.let { fraction ->
                    HorizontalDivider()
                    UsageBar(
                        label = stringResource(R.string.usage_space),
                        fraction = fraction,
                        detail = stringResource(R.string.used_of_total, formatBytes(ceph.bytesUsed ?: 0), formatBytes(ceph.bytesTotal ?: 0)),
                    )
                }
            }
        }
        if (onOpenPools != null) {
            TextButton(onClick = onOpenPools) { Text(stringResource(R.string.dc_ceph_pools)) }
        }
    }
}
