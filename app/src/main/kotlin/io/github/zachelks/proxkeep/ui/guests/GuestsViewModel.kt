package io.github.zachelks.proxkeep.ui.guests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.console.ConsoleManager
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.GuestConfig
import io.github.zachelks.proxkeep.core.api.model.GuestOsInfo
import io.github.zachelks.proxkeep.core.api.model.GuestStatus
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.RrdPoint
import io.github.zachelks.proxkeep.core.api.model.RrdTimeframe
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.GuestActions
import io.github.zachelks.proxkeep.data.GuestLifecycle
import io.github.zachelks.proxkeep.data.LifecycleOperation
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.desktop.DesktopManager
import io.github.zachelks.proxkeep.data.ServerAccess
import io.github.zachelks.proxkeep.data.ServerAccessRepository
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

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

/**
 * A guest's usage over time.
 *
 * @property points null until the first answer
 * @property error set when the latest attempt to read the history failed
 */
data class UsageHistory(
    val resourceId: String,
    val timeframe: RrdTimeframe,
    val points: List<RrdPoint>? = null,
    val error: UiMessage? = null,
)

/** Where a guest's detail is read from. A change, such as a migration to another node, restarts the reading. */
private data class GuestLocation(val profileId: String, val node: String, val kind: ResourceKind, val vmid: Int)

/** The guest on screen and, while it is listed, where it is. */
private data class Selection(val resourceId: String, val location: GuestLocation?)

/** The Guests destination: the guest list, the open guest's detail, and its actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuestsViewModel(
    private val servers: ServerRepository,
    clusterRepository: ClusterRepository,
    accessRepository: ServerAccessRepository,
    private val actions: GuestActions,
    private val consoles: ConsoleManager,
    private val refreshSignal: RefreshSignal,
    private val desktops: DesktopManager? = null,
    lifecycle: GuestLifecycle? = null,
) : ViewModel() {

    val cluster: StateFlow<ClusterState> = clusterRepository.state
    val access: StateFlow<ServerAccess?> = accessRepository.access

    /** Actions in progress on the signed-in server's guests, by resource ID. */
    val busyGuests: StateFlow<Map<String, GuestAction>> =
        combine(actions.busy, servers.activeProfileId) { busy, profileId ->
            busy.filterKeys { it.profileId == profileId }.mapKeys { it.key.resourceId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /** Lifecycle operations in progress on the signed-in server's guests, by resource ID. */
    val lifecycleBusy: StateFlow<Map<String, LifecycleOperation>> =
        combine(lifecycle?.busy ?: flowOf(emptyMap()), servers.activeProfileId) { busy, profileId ->
            busy.filterKeys { it.profileId == profileId }.mapKeys { it.key.resourceId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    private val selectedId = MutableStateFlow<String?>(null)

    private val selection: Flow<Selection?> = selectedId
        .flatMapLatest { resourceId ->
            if (resourceId == null) {
                flowOf(null)
            } else {
                cluster.map { state -> Selection(resourceId, (state as? ClusterState.Loaded)?.locate(resourceId)) }
            }
        }
        .distinctUntilChanged()

    /** The detail of the selected guest, read again on the same schedule as the list. */
    val detail: StateFlow<GuestDetail?> = selection
        .flatMapLatest { selected ->
            when {
                selected == null -> flowOf(null)
                selected.location == null -> flowOf(GuestDetail(selected.resourceId))
                else -> readDetail(selected.resourceId, selected.location)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _timeframe = MutableStateFlow(RrdTimeframe.Hour)

    /** How far back the usage charts reach. */
    val timeframe: StateFlow<RrdTimeframe> = _timeframe.asStateFlow()

    /** The selected guest's usage history over [timeframe]. */
    val history: StateFlow<UsageHistory?> = combine(selection, _timeframe) { selected, timeframe -> selected to timeframe }
        .flatMapLatest { (selected, timeframe) ->
            val location = selected?.location
            if (location == null) flowOf(null) else readHistory(selected.resourceId, location, timeframe)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Tells the view model which guest is on screen, or null when none is. */
    fun select(resourceId: String?) {
        selectedId.value = resourceId
    }

    fun retry() {
        refreshSignal.request()
    }

    fun selectTimeframe(timeframe: RrdTimeframe) {
        _timeframe.value = timeframe
    }

    fun runAction(resource: ClusterResource, action: GuestAction) {
        val profileId = (cluster.value as? ClusterState.Loaded)?.profileId ?: return
        actions.run(profileId, resource, action)
    }

    fun openConsole(resource: ClusterResource) {
        val node = resource.node ?: return
        val vmid = resource.vmid ?: return
        consoles.open(ConsoleTarget.Guest(node, resource.kind, vmid), title = resource.name ?: vmid.toString())
    }

    /** Opens a virtual machine's display. */
    fun openDesktop(resource: ClusterResource) {
        val node = resource.node ?: return
        val vmid = resource.vmid ?: return
        if (resource.kind != ResourceKind.Qemu) return
        desktops?.open(node, vmid, title = resource.name ?: vmid.toString())
    }

    private fun ClusterState.Loaded.locate(resourceId: String): GuestLocation? {
        val resource = resources.firstOrNull { it.id == resourceId } ?: return null
        return GuestLocation(profileId, resource.node ?: return null, resource.kind, resource.vmid ?: return null)
    }

    private fun readDetail(resourceId: String, at: GuestLocation): Flow<GuestDetail> = flow {
        var current = detail.value?.takeIf { it.resourceId == resourceId } ?: GuestDetail(resourceId)
        emit(current)
        refreshSignal.ticks().collect {
            current = try {
                servers.request { client ->
                    val status = client.guestStatus(at.node, at.kind, at.vmid)
                    val config = client.guestConfig(at.node, at.kind, at.vmid)
                    // The operating system does not change while a guest runs, so one answer is kept.
                    val osInfo = current.osInfo ?: if (at.kind == ResourceKind.Qemu && status.isRunning && config.agentEnabled) {
                        try {
                            client.guestOsInfo(at.node, at.vmid)
                        } catch (e: IOException) {
                            // No agent answering is normal; the row is simply left out.
                            null
                        }
                    } else {
                        null
                    }
                    GuestDetail(resourceId, status, config, osInfo)
                }
            } catch (e: IOException) {
                current.copy(error = e.toUiMessage())
            }
            emit(current)
        }
    }

    private fun readHistory(resourceId: String, at: GuestLocation, timeframe: RrdTimeframe): Flow<UsageHistory> = flow {
        var current = history.value?.takeIf { it.resourceId == resourceId && it.timeframe == timeframe }
            ?: UsageHistory(resourceId, timeframe)
        emit(current)
        // The finest history has a sample a minute, so reading it more often shows nothing new.
        refreshSignal.ticks(HISTORY_REFRESH_MS).collect {
            current = try {
                UsageHistory(resourceId, timeframe, servers.request { it.guestRrd(at.node, at.kind, at.vmid, timeframe) })
            } catch (e: IOException) {
                current.copy(error = e.toUiMessage())
            }
            emit(current)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val HISTORY_REFRESH_MS = 60_000L
    }
}
