package io.github.zachelks.proxkeep.ui.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.awaitIfTask
import io.github.zachelks.proxkeep.console.ConsoleManager
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import io.github.zachelks.proxkeep.core.api.model.NodeCertificate
import io.github.zachelks.proxkeep.core.api.model.NodeDns
import io.github.zachelks.proxkeep.core.api.model.NodeNetwork
import io.github.zachelks.proxkeep.core.api.model.NodeService
import io.github.zachelks.proxkeep.core.api.model.NodeStatus
import io.github.zachelks.proxkeep.core.api.model.NodeTime
import io.github.zachelks.proxkeep.core.api.model.PackageUpdate
import io.github.zachelks.proxkeep.core.api.model.PhysicalDisk
import io.github.zachelks.proxkeep.core.api.model.RrdPoint
import io.github.zachelks.proxkeep.core.api.model.RrdTimeframe
import io.github.zachelks.proxkeep.core.api.model.ServiceAction
import io.github.zachelks.proxkeep.core.api.model.SmartData
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.guests.UsageHistory
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

/** A node's DNS and time settings and its certificates, which change rarely and are read together. */
data class NodeSystem(val dns: NodeDns, val time: NodeTime, val certificates: List<NodeCertificate>)

/** What is happening on the node that the person started from this screen. */
enum class NodeOperation { RefreshingPackages, ServiceAction, RemovingInterface, ApplyingNetwork, RevertingNetwork, SavingDns, SavingTime, Power }

/**
 * One node's management screen: status and history, updates, services,
 * network, DNS and time, certificates, disks and the system log. Each part
 * loads only while its tab is on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeViewModel(
    private val servers: ServerRepository,
    private val refreshSignal: RefreshSignal,
    private val consoles: ConsoleManager,
    val node: String,
) : ViewModel() {

    val status: StateFlow<Loadable<NodeStatus>> = follow { it.nodeStatus(node) }.share()

    private val _timeframe = MutableStateFlow(RrdTimeframe.Hour)
    val timeframe: StateFlow<RrdTimeframe> = _timeframe.asStateFlow()

    val history: StateFlow<UsageHistory?> = _timeframe
        .flatMapLatest { timeframe -> readHistory(timeframe) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val updates: StateFlow<Loadable<List<PackageUpdate>>> = follow(UPDATES_REFRESH_MS) { it.packageUpdates(node) }.share()

    val services: StateFlow<Loadable<List<NodeService>>> = follow { it.services(node) }.share()

    val networks: StateFlow<Loadable<List<NodeNetwork>>> = follow(SLOW_REFRESH_MS) { it.nodeNetworks(node) }.share()

    val system: StateFlow<Loadable<NodeSystem>> = follow(SLOW_REFRESH_MS) { client ->
        NodeSystem(client.nodeDns(node), client.nodeTime(node), client.certificates(node).sortedBy { it.filename })
    }.share()

    val disks: StateFlow<Loadable<List<PhysicalDisk>>> = follow(SLOW_REFRESH_MS) { it.physicalDisks(node) }.share()

    val journal: StateFlow<Loadable<List<String>>> = follow { it.journal(node, JOURNAL_LINES) }.share()

    private val _smart = MutableStateFlow<Pair<String, Loadable<SmartData>>?>(null)

    /** The SMART report of the disk last asked for with [loadSmart], by its device path. */
    val smart: StateFlow<Pair<String, Loadable<SmartData>>?> = _smart.asStateFlow()

    private val _busy = MutableStateFlow<NodeOperation?>(null)
    val busy: StateFlow<NodeOperation?> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    fun selectTimeframe(timeframe: RrdTimeframe) {
        _timeframe.value = timeframe
    }

    fun retry() = refreshSignal.request()

    fun loadSmart(disk: String) {
        _smart.value = disk to Loadable.Loading
        viewModelScope.launch {
            val result = try {
                Loadable.Loaded(servers.request { it.smart(node, disk) })
            } catch (e: IOException) {
                Loadable.Failed(e.toUiMessage())
            }
            if (_smart.value?.first == disk) _smart.value = disk to result
        }
    }

    fun closeSmart() {
        _smart.value = null
    }

    fun refreshPackages() = run(NodeOperation.RefreshingPackages, R.string.node_packages_refreshed) { it.refreshPackageLists(node) }

    fun serviceAction(service: String, action: ServiceAction) =
        run(NodeOperation.ServiceAction, R.string.node_service_done) { it.serviceAction(node, service, action) }

    fun applyNetwork() = run(NodeOperation.ApplyingNetwork, R.string.node_network_applied) { it.applyNetwork(node) }

    fun revertNetwork() = run(NodeOperation.RevertingNetwork, R.string.node_network_reverted) { client ->
        client.revertNetwork(node)
        null
    }

    /** Removes a network interface. Like every network change, it takes effect when the configuration is applied. */
    fun removeInterface(iface: String) = run(NodeOperation.RemovingInterface, R.string.node_interface_removed) { client ->
        client.write("DELETE", "/nodes/$node/network/$iface", emptyMap())
    }

    fun saveDns(search: String, servers: List<String>) = run(NodeOperation.SavingDns, R.string.node_dns_saved) { client ->
        client.setNodeDns(node, search.trim(), servers.map { it.trim() }.filter { it.isNotEmpty() })
        null
    }

    fun saveTimezone(timezone: String) = run(NodeOperation.SavingTime, R.string.node_time_saved) { client ->
        client.setTimezone(node, timezone.trim())
        null
    }

    /** Reboots or shuts down the node. The server answers before it goes down, so only the start is reported. */
    fun power(reboot: Boolean) = run(NodeOperation.Power, if (reboot) R.string.node_rebooting else R.string.node_shutting_down, follow = false) {
        it.nodePower(node, reboot)
    }

    /** Opens a console that upgrades the node's packages, as the web interface does. */
    fun openUpgrade(title: String) {
        consoles.open(ConsoleTarget.NodeUpgrade(node), title = title)
    }

    fun openShell() {
        consoles.open(ConsoleTarget.NodeShell(node), title = node)
    }

    /** Runs a change, follows its task unless [follow] is false, and says how it went. */
    private fun run(operation: NodeOperation, done: Int, follow: Boolean = true, start: suspend (PveClient) -> String?) {
        if (_busy.value != null) return
        _busy.value = operation
        viewModelScope.launch {
            try {
                val reply = servers.request(start)
                val status = if (follow) servers.request { it.awaitIfTask(reply, pollIntervalMs = POLL_MS) } else null
                if (status != null && !status.succeeded) {
                    _messages.emit(UiMessage(R.string.node_operation_failed, status.exitstatus))
                    return@launch
                }
                _messages.emit(UiMessage(done))
                refreshSignal.request()
            } catch (e: IOException) {
                _messages.emit(e.toUiMessage())
            } finally {
                _busy.value = null
            }
        }
    }

    /** Reads again on the shared schedule, no more often than every [minimumMs]. */
    private fun <T> follow(minimumMs: Long = 0, read: suspend (PveClient) -> T): Flow<Loadable<T>> = flow {
        var current: Loadable<T> = Loadable.Loading
        emit(current)
        val ticks = if (minimumMs > 0) refreshSignal.ticks(minimumMs) else refreshSignal.ticks()
        ticks.collect {
            current = try {
                Loadable.Loaded(servers.request(read))
            } catch (e: IOException) {
                val previous = current
                if (previous is Loadable.Loaded) previous.copy(refreshError = e.toUiMessage()) else Loadable.Failed(e.toUiMessage())
            }
            emit(current)
        }
    }

    private fun <T> Flow<Loadable<T>>.share(): StateFlow<Loadable<T>> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Loadable.Loading)

    private fun readHistory(timeframe: RrdTimeframe): Flow<UsageHistory> = flow {
        var current = UsageHistory(HISTORY_ID, timeframe)
        emit(current)
        refreshSignal.ticks(HISTORY_REFRESH_MS).collect {
            current = try {
                UsageHistory(HISTORY_ID, timeframe, servers.request { it.nodeRrd(node, timeframe) }.map(::asGuestPoint))
            } catch (e: IOException) {
                current.copy(error = e.toUiMessage())
            }
            emit(current)
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MESSAGE_BUFFER = 8
        private const val POLL_MS = 1_000L
        private const val HISTORY_REFRESH_MS = 60_000L
        private const val UPDATES_REFRESH_MS = 60_000L
        private const val SLOW_REFRESH_MS = 30_000L
        private const val HISTORY_ID = "node"
        const val JOURNAL_LINES = 500

        /** A node's history names memory `memused` and `memtotal`; the charts read a guest's `mem` and `maxmem`. */
        fun asGuestPoint(point: RrdPoint): RrdPoint {
            val values = point.values.toMutableMap()
            point.values["memused"]?.let { values["mem"] = it }
            point.values["memtotal"]?.let { values["maxmem"] = it }
            return point.copy(values = values)
        }

        /** Whether a certificate expires within [days] days of [nowSeconds], or has expired. */
        fun expiresSoon(certificate: NodeCertificate, nowSeconds: Long, days: Int = 30): Boolean =
            certificate.notafter?.let { it - nowSeconds < days * 86_400L } == true
    }
}
